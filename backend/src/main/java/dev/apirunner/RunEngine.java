package dev.apirunner;

import static dev.apirunner.Model.*;

import jakarta.annotation.PreDestroy;

import org.springframework.stereotype.Service;

import java.time.Instant;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

@Service
public class RunEngine {
    private final Requests requests;
    private final Store store;
    private final Map<String, Run> live = new ConcurrentHashMap<>();
    private final ExecutorService workers = Executors.newVirtualThreadPerTaskExecutor();
    private final ScheduledExecutorService checkpoints =
            Executors.newSingleThreadScheduledExecutor();
    private volatile boolean stopping;

    public RunEngine(Requests requests, Store store) {
        this.requests = requests;
        this.store = store;
        checkpoints.scheduleWithFixedDelay(this::checkpoint, 1, 1, TimeUnit.SECONDS);
    }

    public synchronized Snapshot start(StartRun input) {
        StartRun configuration = Definitions.run(input);
        if (stopping || live.size() >= 2) throw new Busy("At most two runs may execute at once");
        Run run = new Run(configuration);
        store.insertRun(run.snapshot());
        live.put(run.id, run);
        workers.submit(() -> execute(run));
        return run.snapshot();
    }

    public Snapshot snapshot(String id) {
        Run run = live.get(id);
        return run == null ? store.run(id) : run.snapshot();
    }

    public List<History> history() {
        return store.history().stream()
                .map(
                        saved -> {
                            Run run = live.get(saved.id());
                            if (run == null) return saved;
                            Snapshot s = run.snapshot();
                            return new History(
                                    s.id(),
                                    s.name(),
                                    s.state(),
                                    s.startedAt(),
                                    s.attempted(),
                                    s.failed(),
                                    s.p95Ms(),
                                    s.requestsPerSecond());
                        })
                .toList();
    }

    public Snapshot cancel(String id) {
        Run run = live.get(id);
        if (run == null) return store.run(id);
        run.cancel();
        return run.snapshot();
    }

    private void execute(Run run) {
        CountDownLatch finished = new CountDownLatch(run.configuration.concurrency());
        try {
            for (int worker = 0; worker < run.configuration.concurrency(); worker++) {
                final int number = worker + 1;
                workers.submit(
                        () -> {
                            run.threads.add(Thread.currentThread());
                            try {
                                work(run, number);
                            } catch (Exception exception) {
                                run.problem =
                                        "Worker stopped: " + exception.getClass().getSimpleName();
                                run.cancel();
                            } finally {
                                run.threads.remove(Thread.currentThread());
                                finished.countDown();
                            }
                        });
            }
            finished.await();
            run.finish(
                    run.problem.isEmpty()
                            ? (run.cancelled.get() ? "CANCELLED" : "COMPLETED")
                            : "FAILED");
        } catch (InterruptedException exception) {
            run.cancel();
            Thread.currentThread().interrupt();
            run.finish("INTERRUPTED");
        } finally {
            synchronized (run) {
                try {
                    store.checkpoint(run.snapshot());
                } catch (Exception exception) {
                    System.err.println("Final run persistence failed: " + run.id);
                }
                // Remove only after the final snapshot is durable; callers then read the database.
                // A persistence failure stays visible in memory until process exit.
                try {
                    if (store.run(run.id).state().equals(run.state)) live.remove(run.id);
                } catch (Exception ignored) {
                    /* Keep the live result available. */
                }
            }
            try {
                store.prune();
            } catch (Exception exception) {
                System.err.println("Run history pruning failed");
            }
        }
    }

    private void work(Run run, int worker) {
        int iteration = 0;
        while (!run.done()
                && (run.configuration.mode().equals("DURATION")
                        || iteration < run.configuration.iterations())) {
            Map<String, String> variables = new HashMap<>(run.configuration.scenario().variables());
            boolean complete = true;
            for (Step step : run.configuration.scenario().steps()) {
                if (run.done()) {
                    complete = false;
                    break;
                }
                run.statistics.entered();
                Reply reply = requests.send(step, variables, run.remainingMs());
                run.statistics.record(worker, step, reply, System.nanoTime() - run.startedNanos);
                if (!reply.passed()) {
                    complete = false;
                    break;
                }
                variables.putAll(reply.extracted());
            }
            if (complete) run.statistics.iterationCompleted();
            iteration++;
        }
    }

    private void checkpoint() {
        for (Run run : live.values()) {
            synchronized (run) {
                if (!run.state.equals("RUNNING") && !run.state.equals("CANCELLING")) continue;
                try {
                    store.checkpoint(run.snapshot());
                } catch (Exception exception) {
                    System.err.println("Run checkpoint failed: " + run.id);
                }
            }
        }
    }

    @PreDestroy
    void stop() {
        stopping = true;
        live.values().forEach(Run::cancel);
        checkpoints.shutdownNow();
        workers.shutdown();
        try {
            if (!workers.awaitTermination(5, TimeUnit.SECONDS)) workers.shutdownNow();
        } catch (InterruptedException exception) {
            workers.shutdownNow();
            Thread.currentThread().interrupt();
        }
    }

    static class Busy extends RuntimeException {
        Busy(String message) {
            super(message);
        }
    }

    private static final class Run {
        final String id = UUID.randomUUID().toString();
        final Instant startedAt = Instant.now();
        final long startedNanos = System.nanoTime();
        final StartRun configuration;
        final Statistics statistics;
        final AtomicBoolean cancelled = new AtomicBoolean();
        final java.util.Set<Thread> threads = ConcurrentHashMap.newKeySet();
        volatile String state = "RUNNING";
        volatile String problem = "";
        String finishedAt;
        long finishedNanos;

        Run(StartRun configuration) {
            this.configuration = configuration;
            statistics = new Statistics(configuration.scenario());
        }

        int remainingMs() {
            return (int) Math.max(0, TimeUnit.NANOSECONDS.toMillis(deadline() - System.nanoTime()));
        }

        long deadline() {
            int seconds =
                    configuration.mode().equals("DURATION") ? configuration.durationSeconds() : 120;
            return startedNanos + TimeUnit.SECONDS.toNanos(seconds);
        }

        boolean done() {
            return cancelled.get()
                    || Thread.currentThread().isInterrupted()
                    || System.nanoTime() >= deadline();
        }

        synchronized void cancel() {
            if (!state.equals("RUNNING") && !state.equals("CANCELLING")) return;
            cancelled.set(true);
            state = "CANCELLING";
            threads.forEach(Thread::interrupt);
        }

        synchronized void finish(String value) {
            state = value;
            finishedAt = Instant.now().toString();
            finishedNanos = System.nanoTime();
        }

        synchronized Snapshot snapshot() {
            long elapsed = (finishedNanos == 0 ? System.nanoTime() : finishedNanos) - startedNanos;
            String message = problem;
            if (message.isEmpty()
                    && state.equals("COMPLETED")
                    && configuration.mode().equals("COUNT")
                    && (finishedNanos == 0 ? System.nanoTime() : finishedNanos) >= deadline())
                message =
                        "Count run reached the 120-second safety limit; requested iterations may be"
                                + " incomplete.";
            return statistics.snapshot(
                    id, state, startedAt, finishedAt, configuration, elapsed, message);
        }
    }
}

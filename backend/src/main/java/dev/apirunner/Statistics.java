package dev.apirunner;

import static dev.apirunner.Model.*;

import org.HdrHistogram.Histogram;

import java.time.Instant;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;

final class Statistics {
    private final Histogram latency = new Histogram(60_000_000, 3);
    private final Histogram queue = new Histogram(60_000_000, 3);
    private final ArrayDeque<Sample> recent = new ArrayDeque<>();
    private final Map<String, Long> outcomes = new TreeMap<>();
    private final Map<String, StepCounter> steps = new LinkedHashMap<>();
    private final Map<Long, BucketCounter> buckets = new TreeMap<>();
    private long attempted, passed, failed, cancelled, bytes, iterations;
    private int active;

    Statistics(Scenario scenario) {
        for (Step step : scenario.steps()) steps.put(step.name(), new StepCounter());
    }

    synchronized void entered() {
        active++;
    }

    synchronized void iterationCompleted() {
        iterations++;
    }

    synchronized void record(int worker, Step step, Reply reply, long elapsedNanos) {
        active--;
        attempted++;
        bytes += reply.bytes();
        boolean wasCancelled = reply.outcome().equals("CANCELLED");
        if (wasCancelled) cancelled++;
        else if (reply.passed()) passed++;
        else failed++;
        outcomes.merge(reply.outcome(), 1L, Long::sum);
        StepCounter counter = steps.get(step.name());
        counter.requests++;
        if (wasCancelled) counter.cancelled++;
        else {
            long micros = micros(reply.latencyMs());
            latency.recordValue(micros);
            queue.recordValue(micros(reply.queueMs()));
            counter.histogram.recordValue(micros);
            if (reply.passed()) counter.passed++;
            else counter.failed++;
        }
        long second = Math.min(120, elapsedNanos / 1_000_000_000L);
        BucketCounter bucket = buckets.computeIfAbsent(second, ignored -> new BucketCounter());
        bucket.requests++;
        if (!reply.passed() && !wasCancelled) bucket.failed++;
        if (!wasCancelled) {
            bucket.totalMs += reply.latencyMs();
            bucket.measured++;
        }
        String error = reply.errors().isEmpty() ? "" : String.join("; ", reply.errors());
        if (error.length() > 512) error = error.substring(0, 512);
        recent.addLast(
                new Sample(
                        attempted,
                        worker,
                        step.name(),
                        reply.status(),
                        reply.outcome(),
                        reply.latencyMs(),
                        reply.queueMs(),
                        reply.bytes(),
                        error));
        if (recent.size() > 200) recent.removeFirst();
    }

    synchronized Snapshot snapshot(
            String id,
            String state,
            Instant startedAt,
            String finishedAt,
            StartRun configuration,
            long elapsedNanos,
            String message) {
        double seconds = Math.max(0.001, elapsedNanos / 1_000_000_000.0);
        List<StepStats> perStep = new ArrayList<>();
        steps.forEach(
                (name, counter) ->
                        perStep.add(
                                new StepStats(
                                        name,
                                        counter.requests,
                                        counter.passed,
                                        counter.failed,
                                        counter.cancelled,
                                        percentile(counter.histogram, 95))));
        List<Bucket> series = new ArrayList<>();
        buckets.forEach(
                (second, counter) ->
                        series.add(
                                new Bucket(
                                        second,
                                        counter.requests,
                                        counter.failed,
                                        counter.measured == 0
                                                ? 0
                                                : counter.totalMs / counter.measured)));
        return new Snapshot(
                id,
                configuration.scenario().name(),
                state,
                startedAt.toString(),
                finishedAt,
                configuration,
                attempted,
                passed,
                failed,
                cancelled,
                iterations,
                active,
                seconds,
                attempted / seconds,
                percentile(latency, 50),
                percentile(latency, 95),
                percentile(latency, 99),
                percentile(queue, 95),
                bytes,
                Map.copyOf(outcomes),
                List.copyOf(perStep),
                List.copyOf(series),
                List.copyOf(recent),
                message);
    }

    private static long micros(double millis) {
        return Math.min(60_000_000, Math.max(1, Math.round(millis * 1000)));
    }

    private static double percentile(Histogram histogram, double value) {
        return histogram.getTotalCount() == 0 ? 0 : histogram.getValueAtPercentile(value) / 1000.0;
    }

    private static final class StepCounter {
        final Histogram histogram = new Histogram(60_000_000, 3);
        long requests, passed, failed, cancelled;
    }

    private static final class BucketCounter {
        long requests, failed, measured;
        double totalMs;
    }
}

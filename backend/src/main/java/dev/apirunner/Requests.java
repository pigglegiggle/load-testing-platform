package dev.apirunner;

import static dev.apirunner.Model.*;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import jakarta.annotation.PreDestroy;

import org.springframework.stereotype.Component;

import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.Flow;
import java.util.concurrent.Semaphore;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

@Component
public class Requests {
    private final ObjectMapper json;
    private final TargetPolicy targets;
    private final Semaphore slots = new Semaphore(128, true);
    private final HttpClient client =
            HttpClient.newBuilder()
                    .connectTimeout(Duration.ofSeconds(3))
                    .followRedirects(HttpClient.Redirect.NEVER)
                    .version(HttpClient.Version.HTTP_1_1)
                    .build();

    public Requests(ObjectMapper json, TargetPolicy targets) {
        this.json = json;
        this.targets = targets;
    }

    public Reply send(Step step, Map<String, String> variables, int budgetMs) {
        long queued = System.nanoTime();
        long started = queued;
        boolean acquired = false;
        CompletableFuture<HttpResponse<byte[]>> pending = null;
        try {
            var uri = targets.check(Definitions.render(step.url(), variables));
            int timeout = Math.min(step.timeoutMs(), budgetMs);
            if (timeout <= 0) return failure("TIMEOUT", "Run deadline reached", 0, 0);
            var builder = HttpRequest.newBuilder(uri).timeout(Duration.ofMillis(timeout));
            int headerBytes = 0;
            for (var header : step.headers().entrySet()) {
                String value = Definitions.render(header.getValue(), variables);
                headerBytes +=
                        header.getKey().getBytes(StandardCharsets.UTF_8).length
                                + value.getBytes(StandardCharsets.UTF_8).length;
                Definitions.require(headerBytes <= 16384, "Expanded headers exceed 16 KiB");
                builder.header(header.getKey(), value);
            }
            String body = Definitions.render(step.body(), variables);
            Definitions.require(
                    body.getBytes(StandardCharsets.UTF_8).length <= 65536,
                    "Expanded body exceeds 64 KiB");
            builder.method(
                    step.method(),
                    body.isEmpty()
                            ? HttpRequest.BodyPublishers.noBody()
                            : HttpRequest.BodyPublishers.ofString(body));
            // Resolve expectations before sending a request that could change target state.
            List<String> expectations =
                    step.checks().stream()
                            .map(check -> Definitions.render(check.expected(), variables))
                            .toList();
            var request = builder.build();
            acquired = slots.tryAcquire(timeout, TimeUnit.MILLISECONDS);
            started = System.nanoTime();
            if (!acquired)
                return failure(
                        "TIMEOUT",
                        "Timed out waiting for a request slot",
                        0,
                        millis(started - queued));
            long remaining = timeout - TimeUnit.NANOSECONDS.toMillis(started - queued);
            if (remaining <= 0)
                return failure(
                        "TIMEOUT",
                        "Request budget exhausted in queue",
                        0,
                        millis(started - queued));
            pending = client.sendAsync(request, ignored -> new LimitedBody(1_048_576));
            // Bound the entire body transfer, including a server that sends headers and then
            // stalls.
            HttpResponse<byte[]> response = pending.get(remaining, TimeUnit.MILLISECONDS);
            double latency = millis(System.nanoTime() - started);
            String content = new String(response.body(), StandardCharsets.UTF_8);
            List<String> errors = new ArrayList<>();
            if (step.expectedStatus() != 0 && response.statusCode() != step.expectedStatus()) {
                errors.add(
                        "Expected status "
                                + step.expectedStatus()
                                + ", received "
                                + response.statusCode());
            }
            JsonNode document = null;
            if (!step.extract().isEmpty()
                    || step.checks().stream().anyMatch(c -> c.type().equals("JSON_EQUALS"))) {
                try {
                    document = json.readTree(content);
                } catch (Exception exception) {
                    errors.add("Response is not valid JSON");
                }
            }
            for (int i = 0; i < step.checks().size(); i++) {
                Check check = step.checks().get(i);
                String expected = expectations.get(i);
                boolean passed =
                        switch (check.type()) {
                            case "BODY_CONTAINS" -> content.contains(expected);
                            case "HEADER_EQUALS" ->
                                    response.headers()
                                            .firstValue(check.path())
                                            .map(expected::equals)
                                            .orElse(false);
                            case "JSON_EQUALS" ->
                                    document != null
                                            && !document.at(check.path()).isMissingNode()
                                            && value(document.at(check.path())).equals(expected);
                            default -> false;
                        };
                if (!passed) errors.add(check.type() + " failed at " + check.path());
            }
            Map<String, String> extracted = new LinkedHashMap<>();
            for (var entry : step.extract().entrySet()) {
                JsonNode node = document == null ? null : document.at(entry.getValue());
                if (node == null || node.isMissingNode() || node.isNull())
                    errors.add("Missing extraction: " + entry.getValue());
                else {
                    String value = value(node);
                    if (value.length() > 4096)
                        errors.add("Extracted value exceeds 4096 characters");
                    else extracted.put(entry.getKey(), value);
                }
            }
            return new Reply(
                    response.statusCode(),
                    response.headers().map(),
                    content,
                    response.body().length,
                    latency,
                    millis(started - queued),
                    errors.isEmpty(),
                    errors.isEmpty() ? "PASSED" : "ASSERTION_FAILED",
                    List.copyOf(errors),
                    Map.copyOf(extracted));
        } catch (InterruptedException exception) {
            if (pending != null) pending.cancel(true);
            Thread.currentThread().interrupt();
            return failure(
                    "CANCELLED",
                    "Request cancelled",
                    millis(System.nanoTime() - started),
                    millis(started - queued));
        } catch (TimeoutException exception) {
            if (pending != null) pending.cancel(true);
            return failure(
                    "TIMEOUT",
                    "Response deadline exceeded",
                    millis(System.nanoTime() - started),
                    millis(started - queued));
        } catch (IllegalArgumentException exception) {
            return failure(
                    "INVALID_REQUEST",
                    exception.getMessage(),
                    millis(System.nanoTime() - started),
                    millis(started - queued));
        } catch (Exception exception) {
            Throwable cause = exception;
            while (cause.getCause() != null) cause = cause.getCause();
            String outcome =
                    cause instanceof BodyLimit
                            ? "RESPONSE_TOO_LARGE"
                            : cause instanceof java.net.http.HttpTimeoutException
                                    ? "TIMEOUT"
                                    : "NETWORK_ERROR";
            return failure(
                    outcome,
                    cause.getClass().getSimpleName() + ": " + String.valueOf(cause.getMessage()),
                    millis(System.nanoTime() - started),
                    millis(started - queued));
        } finally {
            if (acquired) slots.release();
        }
    }

    private String value(JsonNode node) {
        return node.isValueNode() ? node.asText() : node.toString();
    }

    private static double millis(long nanos) {
        return Math.max(0, nanos / 1_000_000.0);
    }

    private static Reply failure(String outcome, String message, double latency, double queue) {
        return new Reply(
                0, Map.of(), "", 0, latency, queue, false, outcome, List.of(message), Map.of());
    }

    @PreDestroy
    void close() {
        client.shutdownNow();
    }

    private static class BodyLimit extends RuntimeException {
        BodyLimit() {
            super("Response exceeded 1 MiB");
        }
    }

    private static final class LimitedBody implements HttpResponse.BodySubscriber<byte[]> {
        private final HttpResponse.BodySubscriber<byte[]> delegate =
                HttpResponse.BodySubscribers.ofByteArray();
        private final int maximum;
        private Flow.Subscription subscription;
        private long received;
        private boolean finished;

        LimitedBody(int maximum) {
            this.maximum = maximum;
        }

        public CompletionStage<byte[]> getBody() {
            return delegate.getBody();
        }

        public void onSubscribe(Flow.Subscription value) {
            subscription = value;
            delegate.onSubscribe(value);
        }

        public void onNext(List<ByteBuffer> buffers) {
            if (finished) return;
            for (ByteBuffer buffer : buffers) received += buffer.remaining();
            if (received > maximum) {
                finished = true;
                subscription.cancel();
                delegate.onError(new BodyLimit());
            } else delegate.onNext(buffers);
        }

        public void onError(Throwable error) {
            if (!finished) {
                finished = true;
                delegate.onError(error);
            }
        }

        public void onComplete() {
            if (!finished) {
                finished = true;
                delegate.onComplete();
            }
        }
    }
}

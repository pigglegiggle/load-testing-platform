package dev.apirunner;

import java.util.List;
import java.util.Map;

public final class Model {
    private Model() {}

    public record Check(String type, String path, String expected) {}

    public record Step(
            String name,
            String method,
            String url,
            Map<String, String> headers,
            String body,
            int timeoutMs,
            int expectedStatus,
            List<Check> checks,
            Map<String, String> extract) {}

    public record Scenario(String name, Map<String, String> variables, List<Step> steps) {}

    public record SavedScenario(String id, Scenario definition, String updatedAt) {}

    public record StartRun(
            Scenario scenario, String mode, int concurrency, int iterations, int durationSeconds) {}

    public record SendRequest(Step step, Map<String, String> variables) {}

    public record Reply(
            int status,
            Map<String, List<String>> headers,
            String body,
            long bytes,
            double latencyMs,
            double queueMs,
            boolean passed,
            String outcome,
            List<String> errors,
            Map<String, String> extracted) {}

    public record Sample(
            long sequence,
            int worker,
            String step,
            int status,
            String outcome,
            double latencyMs,
            double queueMs,
            long bytes,
            String error) {}

    public record Bucket(long second, long requests, long failed, double meanMs) {}

    public record StepStats(
            String name, long requests, long passed, long failed, long cancelled, double p95Ms) {}

    public record Snapshot(
            String id,
            String name,
            String state,
            String startedAt,
            String finishedAt,
            StartRun configuration,
            long attempted,
            long passed,
            long failed,
            long cancelled,
            long iterationsCompleted,
            int activeRequests,
            double elapsedSeconds,
            double requestsPerSecond,
            double p50Ms,
            double p95Ms,
            double p99Ms,
            double queueP95Ms,
            long responseBytes,
            Map<String, Long> outcomes,
            List<StepStats> steps,
            List<Bucket> series,
            List<Sample> samples,
            String message) {}

    public record History(
            String id,
            String name,
            String state,
            String startedAt,
            long attempted,
            long failed,
            double p95Ms,
            double requestsPerSecond) {}
}

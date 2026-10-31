package dev.apirunner;

import static dev.apirunner.Model.*;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

public final class Definitions {
    private static final Set<String> METHODS =
            Set.of("GET", "POST", "PUT", "PATCH", "DELETE", "HEAD", "OPTIONS");
    private static final Set<String> CHECKS =
            Set.of("JSON_EQUALS", "BODY_CONTAINS", "HEADER_EQUALS");
    private static final Pattern VARIABLE = Pattern.compile("\\$\\{([A-Za-z][A-Za-z0-9_]{0,63})}");

    private Definitions() {}

    public static Scenario scenario(Scenario source) {
        require(source != null, "Scenario is required");
        String name = text(source.name(), 120, "Scenario name");
        Map<String, String> variables = variables(source.variables());
        require(
                source.steps() != null && !source.steps().isEmpty() && source.steps().size() <= 20,
                "A scenario needs 1–20 steps");
        List<Step> steps = source.steps().stream().map(Definitions::step).toList();
        require(
                steps.stream().map(Step::name).distinct().count() == steps.size(),
                "Step names must be unique");
        var names = new java.util.HashSet<>(variables.keySet());
        steps.forEach(step -> names.addAll(step.extract().keySet()));
        require(names.size() <= 40, "At most 40 variable names across the scenario");
        return new Scenario(name, variables, steps);
    }

    public static Step step(Step source) {
        require(source != null, "Step is required");
        String method = text(source.method(), 10, "Method").toUpperCase(Locale.ROOT);
        require(METHODS.contains(method), "Unsupported HTTP method");
        String url = text(source.url(), 4096, "URL");
        Map<String, String> headers = strings(source.headers(), 30, 16384, "Headers");
        headers.forEach(
                (key, value) -> {
                    require(key.matches("[!#$%&'*+.^_`|~0-9A-Za-z-]+"), "Invalid header name");
                    require(
                            !value.contains("\r") && !value.contains("\n"),
                            "Header values cannot contain newlines");
                    require(
                            !Set.of("host", "content-length", "connection", "expect", "upgrade")
                                    .contains(key.toLowerCase(Locale.ROOT)),
                            "Restricted request header: " + key);
                });
        String body = source.body() == null ? "" : source.body();
        require(
                body.getBytes(java.nio.charset.StandardCharsets.UTF_8).length <= 65536,
                "Body exceeds 64 KiB");
        require(
                source.timeoutMs() >= 100 && source.timeoutMs() <= 10000,
                "Timeout must be 100–10000 ms");
        require(
                source.expectedStatus() == 0
                        || source.expectedStatus() >= 100 && source.expectedStatus() <= 599,
                "Expected status must be 0 (any) or 100–599");
        List<Check> checks =
                source.checks() == null ? List.of() : new java.util.ArrayList<>(source.checks());
        require(checks.size() <= 20, "At most 20 assertions per step");
        for (Check check : checks) {
            require(check != null && CHECKS.contains(check.type()), "Unsupported assertion type");
            require(
                    check.path() != null && check.path().length() <= 512,
                    "Assertion path is too long");
            require(
                    check.expected() != null && check.expected().length() <= 4096,
                    "Assertion expected value is too long");
            if (check.type().equals("JSON_EQUALS")) pointer(check.path());
        }
        Map<String, String> extract = strings(source.extract(), 20, 10240, "Extractions");
        extract.forEach(
                (key, value) -> {
                    variableName(key);
                    pointer(value);
                });
        return new Step(
                text(source.name(), 80, "Step name"),
                method,
                url,
                headers,
                body,
                source.timeoutMs(),
                source.expectedStatus(),
                List.copyOf(checks),
                extract);
    }

    public static StartRun run(StartRun source) {
        require(source != null, "Run configuration is required");
        require(
                source.mode() != null && Set.of("COUNT", "DURATION").contains(source.mode()),
                "Mode must be COUNT or DURATION");
        require(
                source.concurrency() >= 1 && source.concurrency() <= 64,
                "Concurrency must be 1–64");
        require(
                source.iterations() >= 1 && source.iterations() <= 500,
                "Iterations must be 1–500 per worker");
        require(
                source.durationSeconds() >= 1 && source.durationSeconds() <= 120,
                "Duration must be 1–120 seconds");
        Scenario scenario = scenario(source.scenario());
        require(
                !source.mode().equals("COUNT")
                        || (long) source.concurrency()
                                        * source.iterations()
                                        * scenario.steps().size()
                                <= 100000,
                "Count workload exceeds 100000 planned requests");
        return new StartRun(
                scenario,
                source.mode(),
                source.concurrency(),
                source.iterations(),
                source.durationSeconds());
    }

    public static Map<String, String> variables(Map<String, String> source) {
        Map<String, String> result = strings(source, 40, 32768, "Variables");
        result.keySet().forEach(Definitions::variableName);
        return result;
    }

    public static String render(String source, Map<String, String> variables) {
        Matcher matcher = VARIABLE.matcher(source);
        StringBuilder output = new StringBuilder();
        while (matcher.find()) {
            String value = variables.get(matcher.group(1));
            require(value != null, "Missing variable: " + matcher.group(1));
            matcher.appendReplacement(output, Matcher.quoteReplacement(value));
        }
        matcher.appendTail(output);
        require(output.length() <= 131072, "Expanded template is too large");
        return output.toString();
    }

    private static Map<String, String> strings(
            Map<String, String> source, int limit, int bytes, String label) {
        if (source == null) return Map.of();
        require(source.size() <= limit, label + " contains too many entries");
        Map<String, String> copy = new LinkedHashMap<>();
        int total = 0;
        for (var entry : source.entrySet()) {
            require(
                    entry.getKey() != null && entry.getValue() != null,
                    label + " cannot contain null");
            total += entry.getKey().length() + entry.getValue().length();
            copy.put(entry.getKey(), entry.getValue());
        }
        require(total <= bytes, label + " exceeds its size limit");
        return Map.copyOf(copy);
    }

    private static void pointer(String value) {
        require(
                value.isEmpty() || value.startsWith("/"),
                "JSON paths use JSON Pointer, e.g. /token");
        try {
            com.fasterxml.jackson.core.JsonPointer.compile(value);
        } catch (IllegalArgumentException exception) {
            throw new IllegalArgumentException("Invalid JSON Pointer");
        }
    }

    private static void variableName(String value) {
        require(value.matches("[A-Za-z][A-Za-z0-9_]{0,63}"), "Invalid variable name: " + value);
    }

    private static String text(String value, int limit, String label) {
        require(
                value != null && !value.isBlank() && value.length() <= limit,
                label + " is required (max " + limit + " characters)");
        return value.trim();
    }

    public static void require(boolean condition, String message) {
        if (!condition) throw new IllegalArgumentException(message);
    }
}

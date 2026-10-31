package dev.apirunner;

import static dev.apirunner.Model.*;

import static org.junit.jupiter.api.Assertions.*;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.sun.net.httpserver.HttpServer;

import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.http.*;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicInteger;

@SpringBootTest(
        webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
        properties = {
            "spring.datasource.url=jdbc:h2:mem:engine;DB_CLOSE_DELAY=-1",
            "runner.demo-enabled=false"
        })
class EngineTest {
    static final HttpServer target;
    static final String origin;
    static final java.util.Set<String> tokens = ConcurrentHashMap.newKeySet();
    static final AtomicInteger calls = new AtomicInteger();
    static final AtomicInteger active = new AtomicInteger();
    static final AtomicInteger maximum = new AtomicInteger();

    static {
        try {
            target = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 64);
            target.setExecutor(Executors.newVirtualThreadPerTaskExecutor());
            target.createContext(
                    "/",
                    exchange -> {
                        int current = active.incrementAndGet();
                        maximum.accumulateAndGet(current, Math::max);
                        calls.incrementAndGet();
                        try (exchange) {
                            byte[] request = exchange.getRequestBody().readAllBytes();
                            String path = exchange.getRequestURI().getPath();
                            if (path.equals("/delay")) Thread.sleep(1000);
                            if (path.equals("/limited")) Thread.sleep(25);
                            if (path.equals("/drop")) return;
                            if (path.equals("/redirect"))
                                exchange.getResponseHeaders()
                                        .set("Location", "http://unconfigured.invalid/secret");
                            int status =
                                    path.equals("/error")
                                            ? 503
                                            : path.equals("/redirect") ? 302 : 200;
                            String content = "{\"ok\":true,\"items\":[{\"name\":\"Notebook\"}]}";
                            if (path.equals("/token")) {
                                String token = UUID.randomUUID().toString();
                                tokens.add(token);
                                content = "{\"token\":\"" + token + "\"}";
                            }
                            if (path.equals("/check")
                                    && !tokens.remove(
                                            exchange.getRequestHeaders().getFirst("Authorization")))
                                status = 401;
                            if (path.equals("/echo"))
                                content = new String(request, StandardCharsets.UTF_8);
                            if (path.equals("/big")) content = "x".repeat(1_048_577);
                            byte[] body = content.getBytes(StandardCharsets.UTF_8);
                            exchange.getResponseHeaders().set("Content-Type", "application/json");
                            exchange.sendResponseHeaders(status, body.length);
                            if (path.equals("/stream")) {
                                exchange.getResponseBody().write(body, 0, 1);
                                exchange.getResponseBody().flush();
                                Thread.sleep(1000);
                                exchange.getResponseBody().write(body, 1, body.length - 1);
                            } else exchange.getResponseBody().write(body);
                        } catch (Exception ignored) {
                        } finally {
                            active.decrementAndGet();
                        }
                    });
            target.start();
            origin = "http://127.0.0.1:" + target.getAddress().getPort();
        } catch (Exception exception) {
            throw new ExceptionInInitializerError(exception);
        }
    }

    @DynamicPropertySource
    static void properties(DynamicPropertyRegistry registry) {
        registry.add("runner.allowed-origins", () -> origin);
    }

    @Autowired Requests requests;
    @Autowired RunEngine engine;
    @Autowired Store store;
    @Autowired ObjectMapper json;
    @Autowired JdbcTemplate jdbc;
    @Autowired TestRestTemplate api;
    @LocalServerPort int port;

    @AfterAll
    static void stop() {
        target.stop(0);
    }

    Step step(String path) {
        return new Step(
                "Request", "GET", origin + path, Map.of(), "", 3000, 200, List.of(), Map.of());
    }

    Scenario scenario(Step... steps) {
        return new Scenario("Test", Map.of(), List.of(steps));
    }

    Snapshot start(Scenario scenario, int concurrency, int iterations) {
        return engine.start(new StartRun(scenario, "COUNT", concurrency, iterations, 10));
    }

    Snapshot finished(String id) throws Exception {
        long deadline = System.nanoTime() + 8_000_000_000L;
        while (System.nanoTime() < deadline) {
            Snapshot result = engine.snapshot(id);
            if (!List.of("RUNNING", "CANCELLING").contains(result.state())) return result;
            Thread.sleep(10);
        }
        throw new AssertionError("Run did not finish");
    }

    void conservation(Snapshot s) {
        assertEquals(s.attempted(), s.passed() + s.failed() + s.cancelled());
        assertEquals(0, s.activeRequests());
        assertEquals(s.attempted(), s.steps().stream().mapToLong(StepStats::requests).sum());
        assertTrue(s.samples().size() <= 200);
    }

    @Test
    void definitionsRejectInvalidInputsAndFreezeCollections() {
        assertThrows(
                IllegalArgumentException.class,
                () -> Definitions.run(new StartRun(scenario(step("/")), null, 1, 1, 1)));
        assertThrows(
                IllegalArgumentException.class,
                () -> Definitions.run(new StartRun(scenario(step("/")), "COUNT", 65, 1, 1)));
        assertThrows(
                IllegalArgumentException.class, () -> Definitions.render("${missing}", Map.of()));
        assertEquals("$5\\x", Definitions.render("${value}", Map.of("value", "$5\\x")));
        var mutable = new java.util.HashMap<>(Map.of("x", "one"));
        Scenario validated =
                Definitions.scenario(new Scenario("Test", mutable, List.of(step("/"))));
        mutable.put("x", "two");
        assertEquals("one", validated.variables().get("x"));
        assertThrows(
                IllegalArgumentException.class,
                () -> Definitions.scenario(scenario(step("/"), step("/"))));
    }

    @Test
    void realRequestChecksAndExtraction() {
        Step source =
                new Step(
                        "Read",
                        "GET",
                        origin + "/",
                        Map.of(),
                        "",
                        1000,
                        200,
                        List.of(
                                new Check("JSON_EQUALS", "/items/0/name", "Notebook"),
                                new Check("BODY_CONTAINS", "", "true"),
                                new Check("HEADER_EQUALS", "Content-Type", "application/json")),
                        Map.of("item", "/items/0/name"));
        Reply reply = requests.send(Definitions.step(source), Map.of(), 1000);
        assertTrue(reply.passed(), reply.errors().toString());
        assertEquals("Notebook", reply.extracted().get("item"));
        assertTrue(reply.bytes() > 0);
        Step missing =
                new Step(
                        "Read",
                        "GET",
                        origin + "/",
                        Map.of(),
                        "",
                        1000,
                        200,
                        List.of(new Check("JSON_EQUALS", "/absent", "")),
                        Map.of("no", "/missing"));
        assertEquals("ASSERTION_FAILED", requests.send(missing, Map.of(), 1000).outcome());
    }

    @Test
    void targetPolicyDoesNotFollowRedirectsOrAllowOtherOrigins() {
        assertEquals(
                "INVALID_REQUEST",
                requests.send(
                                new Step(
                                        "Bad",
                                        "GET",
                                        "http://127.0.0.1:1/",
                                        Map.of(),
                                        "",
                                        500,
                                        0,
                                        List.of(),
                                        Map.of()),
                                Map.of(),
                                500)
                        .outcome());
        Reply redirect = requests.send(step("/redirect"), Map.of(), 1000);
        assertEquals(302, redirect.status());
        assertEquals("ASSERTION_FAILED", redirect.outcome());
    }

    @Test
    void fullBodyDeadlineAndResponseBudgetAreEnforced() {
        long before = System.nanoTime();
        assertEquals("TIMEOUT", requests.send(step("/stream"), Map.of(), 150).outcome());
        assertTrue(
                System.nanoTime() - before < 900_000_000L,
                "Body timeout must not wait for the stalled response");
        assertEquals("RESPONSE_TOO_LARGE", requests.send(step("/big"), Map.of(), 3000).outcome());
        assertEquals("NETWORK_ERROR", requests.send(step("/drop"), Map.of(), 1000).outcome());
    }

    @Test
    void concurrentScenariosKeepVariablesIsolated() throws Exception {
        Step token =
                new Step(
                        "Token",
                        "POST",
                        origin + "/token",
                        Map.of(),
                        "",
                        3000,
                        200,
                        List.of(),
                        Map.of("token", "/token"));
        Step check =
                new Step(
                        "Check",
                        "GET",
                        origin + "/check",
                        Map.of("Authorization", "${token}"),
                        "",
                        3000,
                        200,
                        List.of(),
                        Map.of());
        Snapshot result = finished(start(scenario(token, check), 6, 10).id());
        assertEquals("COMPLETED", result.state());
        assertEquals(120, result.passed());
        assertEquals(60, result.iterationsCompleted());
        conservation(result);
    }

    @Test
    void failedStepsStopTheirIterationWithoutRetrying() throws Exception {
        Step after =
                new Step(
                        "Must not run",
                        "GET",
                        origin + "/",
                        Map.of(),
                        "",
                        1000,
                        200,
                        List.of(),
                        Map.of());
        Snapshot result = finished(start(scenario(step("/error"), after), 2, 4).id());
        assertEquals(8, result.attempted());
        assertEquals(8, result.failed());
        assertEquals(0, result.steps().get(1).requests());
        conservation(result);
    }

    @Test
    void cancellationStopsInFlightWorkAndAdmissionIsBounded() throws Exception {
        Snapshot first = start(scenario(step("/delay")), 2, 100);
        Snapshot second = start(scenario(step("/delay")), 2, 100);
        assertThrows(RunEngine.Busy.class, () -> start(scenario(step("/")), 1, 1));
        long deadline = System.nanoTime() + 1_000_000_000L;
        while (engine.snapshot(first.id()).activeRequests() == 0 && System.nanoTime() < deadline)
            Thread.sleep(5);
        engine.cancel(first.id());
        engine.cancel(second.id());
        Snapshot result = finished(first.id());
        Snapshot other = finished(second.id());
        assertEquals("CANCELLED", result.state());
        assertEquals("CANCELLED", other.state());
        conservation(result);
        conservation(other);
    }

    @Test
    void durationRunsAndRecentSamplesAreBounded() throws Exception {
        Snapshot result =
                finished(
                        engine.start(new StartRun(scenario(step("/limited")), "DURATION", 4, 1, 2))
                                .id());
        assertTrue(result.elapsedSeconds() >= 1.8 && result.elapsedSeconds() < 4);
        assertTrue(result.attempted() > 200);
        assertEquals(200, result.samples().size());
        conservation(result);
    }

    @Test
    void scenarioPersistenceAndRestartRecovery() throws Exception {
        SavedScenario saved = store.saveScenario(null, scenario(step("/")));
        assertTrue(store.scenarios().stream().anyMatch(s -> s.id().equals(saved.id())));
        store.deleteScenario(saved.id());
        String id = UUID.randomUUID().toString();
        StartRun configuration = new StartRun(scenario(step("/")), "COUNT", 1, 1, 1);
        Snapshot running =
                new Statistics(configuration.scenario())
                        .snapshot(
                                id, "RUNNING", java.time.Instant.now(), null, configuration, 1, "");
        store.insertRun(running);
        Store restarted = new Store(jdbc, json);
        assertEquals("INTERRUPTED", restarted.run(id).state());
        assertEquals(0, restarted.run(id).activeRequests());
    }

    @Test
    void httpValidationOriginChecksAndScenarioRoundtrip() {
        var bad = api.postForEntity("/api/runs", Map.of("unexpected", true), String.class);
        assertEquals(400, bad.getStatusCode().value());
        HttpHeaders headers = new HttpHeaders();
        headers.setOrigin("https://untrusted.example");
        var forbidden =
                api.exchange(
                        "/api/config", HttpMethod.GET, new HttpEntity<>(headers), String.class);
        assertEquals(403, forbidden.getStatusCode().value());
        var saved = api.postForEntity("/api/scenarios", scenario(step("/")), SavedScenario.class);
        assertEquals(200, saved.getStatusCode().value());
        api.delete("/api/scenarios/" + saved.getBody().id());
        assertEquals(
                400, api.getForEntity("/api/runs/not-an-id", String.class).getStatusCode().value());
    }

    @Test
    void nullAssertionTypesAreRejectedAsClientErrors() {
        Step invalid =
                new Step(
                        "Invalid assertion",
                        "GET",
                        origin + "/",
                        Map.of(),
                        "",
                        1000,
                        200,
                        List.of(new Check(null, "/ok", "true")),
                        Map.of());
        var response = api.postForEntity("/api/scenarios", scenario(invalid), String.class);
        assertEquals(400, response.getStatusCode().value());
        assertTrue(response.getBody().contains("Unsupported assertion type"));
    }

    @Test
    void malformedHeaderAssertionsDoNotSendRequests() {
        int before = calls.get();
        for (String path : List.of("", "bad header", "X-Test\r\nInjected")) {
            Step invalid =
                    new Step(
                            "Invalid header assertion",
                            "GET",
                            origin + "/",
                            Map.of(),
                            "",
                            1000,
                            200,
                            List.of(new Check("HEADER_EQUALS", path, "")),
                            Map.of());
            var response =
                    api.postForEntity(
                            "/api/requests/send", new SendRequest(invalid, Map.of()), String.class);
            assertEquals(400, response.getStatusCode().value());
            assertTrue(response.getBody().contains("Invalid assertion header name"));
        }
        assertEquals(before, calls.get(), "Rejected definitions must never reach the target");
    }

    @Test
    void unresolvedAssertionTemplatesDoNotSendRequests() {
        Step invalid =
                new Step(
                        "Missing expectation",
                        "POST",
                        origin + "/echo",
                        Map.of(),
                        "operation",
                        1000,
                        200,
                        List.of(new Check("BODY_CONTAINS", "", "${missing}")),
                        Map.of());
        int before = calls.get();
        Reply result = requests.send(Definitions.step(invalid), Map.of(), 1000);
        assertEquals("INVALID_REQUEST", result.outcome());
        assertEquals(
                before,
                calls.get(),
                "An invalid assertion template must be rejected before side effects");
    }

    @Test
    void missingHeadersCannotMatchEmptyExpectations() {
        Step assertion =
                new Step(
                        "Missing header",
                        "GET",
                        origin + "/",
                        Map.of(),
                        "",
                        1000,
                        200,
                        List.of(new Check("HEADER_EQUALS", "X-Absent", "")),
                        Map.of());
        Reply result = requests.send(Definitions.step(assertion), Map.of(), 1000);
        assertEquals("ASSERTION_FAILED", result.outcome());
        assertEquals(200, result.status());
    }

    @Test
    void histogramMatchesKnownSamples() {
        StartRun configuration = new StartRun(scenario(step("/")), "COUNT", 1, 1, 1);
        Statistics stats = new Statistics(configuration.scenario());
        for (int i = 1; i <= 100; i++) {
            stats.entered();
            stats.record(
                    1,
                    step("/"),
                    new Reply(200, Map.of(), "", 10, i, 0, true, "PASSED", List.of(), Map.of()),
                    i * 1_000_000L);
        }
        Snapshot s =
                stats.snapshot(
                        "id",
                        "COMPLETED",
                        java.time.Instant.now(),
                        "now",
                        configuration,
                        1_000_000_000L,
                        "");
        assertEquals(50, s.p50Ms(), .1);
        assertEquals(95, s.p95Ms(), .1);
        assertEquals(99, s.p99Ms(), .1);
        assertEquals(1000, s.responseBytes());
        conservation(s);
    }
}

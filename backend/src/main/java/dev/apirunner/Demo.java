package dev.apirunner;

import static dev.apirunner.Model.*;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;

import jakarta.annotation.PreDestroy;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

@Component
public class Demo {
    private final HttpServer server;
    private final ExecutorService threads = Executors.newVirtualThreadPerTaskExecutor();
    private final java.util.concurrent.Semaphore capacity = new java.util.concurrent.Semaphore(256);
    private final ObjectMapper json;
    private final int port;

    public Demo(
            ObjectMapper json,
            @Value("${runner.demo-port}") int port,
            @Value("${runner.demo-enabled}") boolean enabled)
            throws IOException {
        this.json = json;
        this.port = port;
        if (enabled) {
            server = HttpServer.create(new InetSocketAddress("127.0.0.1", port), 64);
            server.setExecutor(threads);
            server.createContext("/", this::handle);
            server.start();
        } else server = null;
    }

    boolean enabled() {
        return server != null;
    }

    String origin() {
        return "http://127.0.0.1:" + (server == null ? port : server.getAddress().getPort());
    }

    Scenario example() {
        return new Scenario(
                "Demo checkout",
                Map.of("baseUrl", origin()),
                List.of(
                        new Step(
                                "Create session",
                                "POST",
                                "${baseUrl}/session",
                                Map.of("Content-Type", "application/json"),
                                "{\"name\":\"demo\"}",
                                3000,
                                200,
                                List.of(new Check("JSON_EQUALS", "/authenticated", "true")),
                                Map.of("token", "/token")),
                        new Step(
                                "Read items",
                                "GET",
                                "${baseUrl}/items?delayMs=40",
                                Map.of("Authorization", "Bearer ${token}"),
                                "",
                                3000,
                                200,
                                List.of(new Check("JSON_EQUALS", "/items/0/name", "Notebook")),
                                Map.of("itemId", "/items/0/id")),
                        new Step(
                                "Create order",
                                "POST",
                                "${baseUrl}/orders",
                                Map.of(
                                        "Content-Type",
                                        "application/json",
                                        "Authorization",
                                        "Bearer ${token}"),
                                "{\"itemId\":\"${itemId}\",\"quantity\":1}",
                                3000,
                                201,
                                List.of(new Check("JSON_EQUALS", "/accepted", "true")),
                                Map.of())));
    }

    private void handle(HttpExchange exchange) {
        boolean admitted = capacity.tryAcquire();
        try (exchange) {
            if (!admitted) {
                reply(exchange, 503, Map.of("error", "Demo capacity reached"));
                return;
            }
            byte[] body = exchange.getRequestBody().readNBytes(65537);
            if (body.length > 65536) {
                reply(exchange, 413, Map.of("error", "Body too large"));
                return;
            }
            Map<String, String> query = new java.util.HashMap<>();
            String raw = exchange.getRequestURI().getRawQuery();
            if (raw != null)
                for (String pair : raw.split("&")) {
                    String[] fields = pair.split("=", 2);
                    if (fields.length == 2) query.put(fields[0], fields[1]);
                }
            int delay =
                    Math.min(
                            2000,
                            Math.max(0, Integer.parseInt(query.getOrDefault("delayMs", "0"))));
            Thread.sleep(delay);
            String path = exchange.getRequestURI().getPath();
            if (path.equals("/health")) {
                reply(exchange, 200, Map.of("status", "UP"));
                return;
            }
            if (path.equals("/session") && exchange.getRequestMethod().equals("POST")) {
                reply(exchange, 200, Map.of("token", "demo-token", "authenticated", true));
                return;
            }
            if (!"Bearer demo-token"
                    .equals(exchange.getRequestHeaders().getFirst("Authorization"))) {
                reply(exchange, 401, Map.of("error", "Use the token from POST /session"));
                return;
            }
            if (path.equals("/items")) {
                int status = Integer.parseInt(query.getOrDefault("status", "200"));
                if (status < 200 || status > 599) status = 400;
                reply(
                        exchange,
                        status,
                        Map.of("items", List.of(Map.of("id", "item-1", "name", "Notebook"))));
            } else if (path.equals("/orders") && exchange.getRequestMethod().equals("POST")) {
                var payload = json.readTree(body);
                reply(
                        exchange,
                        201,
                        Map.of(
                                "accepted",
                                true,
                                "orderId",
                                "order-demo",
                                "itemId",
                                payload.path("itemId").asText()));
            } else reply(exchange, 404, Map.of("error", "Unknown demo endpoint"));
        } catch (Exception ignored) {
            /* Cancelled clients may close before a reply is written. */
        } finally {
            if (admitted) capacity.release();
        }
    }

    private void reply(HttpExchange exchange, int status, Object payload) throws IOException {
        byte[] body = json.writeValueAsBytes(payload);
        exchange.getResponseHeaders().set("Content-Type", "application/json; charset=utf-8");
        exchange.sendResponseHeaders(status, body.length);
        exchange.getResponseBody().write(body);
    }

    @PreDestroy
    void stop() {
        if (server != null) server.stop(0);
        threads.shutdownNow();
    }
}

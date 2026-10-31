package dev.apirunner;

import static dev.apirunner.Model.*;

import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.Map;
import java.util.concurrent.Semaphore;

@RestController
@RequestMapping("/api")
public class Api {
    private final RunEngine engine;
    private final Store store;
    private final Requests requests;
    private final TargetPolicy targets;
    private final Demo demo;
    private final Semaphore manualSlots = new Semaphore(8);

    public Api(RunEngine engine, Store store, Requests requests, TargetPolicy targets, Demo demo) {
        this.engine = engine;
        this.store = store;
        this.requests = requests;
        this.targets = targets;
        this.demo = demo;
    }

    @GetMapping("/config")
    Object configuration() {
        return Map.of(
                "allowedOrigins",
                targets.origins(),
                "maxConcurrency",
                64,
                "maxActiveRuns",
                2,
                "maxDurationSeconds",
                120,
                "maxResponseBytes",
                1_048_576,
                "sampleLimit",
                200,
                "demoEnabled",
                demo.enabled(),
                "demoOrigin",
                demo.origin());
    }

    @GetMapping("/health")
    Object health() {
        return Map.of("status", "UP");
    }

    @GetMapping("/example")
    Scenario example() {
        return demo.example();
    }

    @GetMapping("/scenarios")
    List<SavedScenario> scenarios() {
        return store.scenarios();
    }

    @PostMapping(value = "/scenarios", consumes = "application/json")
    SavedScenario create(@RequestBody Scenario source) {
        return store.saveScenario(null, Definitions.scenario(source));
    }

    @PutMapping(value = "/scenarios/{id}", consumes = "application/json")
    SavedScenario update(@PathVariable String id, @RequestBody Scenario source) {
        return store.saveScenario(id, Definitions.scenario(source));
    }

    @DeleteMapping("/scenarios/{id}")
    void delete(@PathVariable String id) {
        store.deleteScenario(id);
    }

    @GetMapping("/runs")
    List<History> history() {
        return engine.history();
    }

    @PostMapping(value = "/runs", consumes = "application/json")
    ResponseEntity<Snapshot> start(@RequestBody StartRun source) {
        return ResponseEntity.status(202).body(engine.start(source));
    }

    @GetMapping("/runs/{id}")
    Snapshot run(@PathVariable String id) {
        return engine.snapshot(id);
    }

    @PostMapping("/runs/{id}/cancel")
    Snapshot cancel(@PathVariable String id) {
        return engine.cancel(id);
    }

    @PostMapping(value = "/requests/send", consumes = "application/json")
    Reply send(@RequestBody SendRequest input) {
        if (!manualSlots.tryAcquire())
            throw new RunEngine.Busy("At most eight interactive requests at once");
        try {
            Step step = Definitions.step(input.step());
            return requests.send(step, Definitions.variables(input.variables()), step.timeoutMs());
        } finally {
            manualSlots.release();
        }
    }
}

@RestControllerAdvice
class ApiErrors {
    @ExceptionHandler(IllegalArgumentException.class)
    ResponseEntity<?> invalid(IllegalArgumentException error) {
        return error(HttpStatus.BAD_REQUEST, error.getMessage());
    }

    @ExceptionHandler(HttpMessageNotReadableException.class)
    ResponseEntity<?> malformed() {
        return error(HttpStatus.BAD_REQUEST, "Malformed JSON or unsupported fields");
    }

    @ExceptionHandler(Store.Missing.class)
    ResponseEntity<?> missing(Store.Missing error) {
        return error(HttpStatus.NOT_FOUND, error.getMessage());
    }

    @ExceptionHandler(RunEngine.Busy.class)
    ResponseEntity<?> busy(RunEngine.Busy error) {
        return error(HttpStatus.TOO_MANY_REQUESTS, error.getMessage());
    }

    private ResponseEntity<?> error(HttpStatus status, String message) {
        return ResponseEntity.status(status)
                .body(Map.of("error", message == null ? "Invalid request" : message));
    }
}

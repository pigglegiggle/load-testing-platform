# Measurement definitions

This tool generates a closed-loop workload. Each worker executes one step, waits for its full response and assertions, then starts the next step. Increasing concurrency changes outstanding work; it does not guarantee a fixed requests-per-second arrival rate.

## Counters and latency

- `attempted`: recorded completions, including failed and cancelled attempts. An active request has not entered this count yet.
- `passed`: status, all assertions and all required extractions passed.
- `failed`: non-cancelled failures, including validation, assertion, timeout, response-limit and network outcomes.
- `cancelled`: attempts interrupted by cancellation. No HTTP latency observation is recorded for them.
- `iterationsCompleted`: all steps in an iteration passed. It is not the number of attempted iterations.
- `activeRequests`: workers inside the request path, including preparation and semaphore waiting. It is an active-attempt gauge, not exact socket count.
- `latencyMs`: elapsed monotonic send-to-full-body time. For a failed network/timeout attempt, it records the elapsed failed attempt. Assertion work after receipt is outside this measurement.
- `queueMs`: preparation plus request-slot waiting before send. It is tracked separately and consumes the request deadline.
- `requestsPerSecond`: recorded completions divided by elapsed run wall time. Cancelled completions count in its numerator. It is the run-wide mean, not instantaneous rate.
- `responseBytes`: accepted complete response-body bytes, excluding headers, requests, rejected oversized bodies and bytes from interrupted/failed body transfers. It is not wire throughput.

`attempted = passed + failed + cancelled`. The sum of per-step request counts and outcome counts also equals attempted. A restarted report represents only its last durable checkpoint; in-flight/uncheckpointed operations are not reconstructed.

## Histograms and charts

HdrHistogram receives individual non-cancelled completion latencies, in microseconds, with three significant digits. It also records separate queue and per-step distributions. Values are bounded at 60 seconds; request budgets are at most ten seconds. Published percentiles have histogram bucket quantization and are not calculated by averaging per-step p95 values.

Timeouts, assertion failures and network failures remain part of non-cancelled latency distributions. Inspect outcomes and per-step distributions before treating an aggregate percentile as successful-response latency. A run can have a low p95 because it failed quickly.

The chart groups completions by elapsed second and shows the mean non-cancelled HTTP time in each bucket. Missing seconds do not imply zero latency. Hover points to inspect request and failure counts. A duration boundary can produce bucket 120, giving at most 121 buckets. The last 200 individual samples are retained separately; percentiles still cover the full run.

## Interpretation limits

There is no coordinated-omission correction, fixed-arrival-rate scheduler, distributed generator, warm-up exclusion or automatic statistical regression decision. Comparisons show raw deltas and the configurations; use the same target, scenario, concurrency and measurement setup for a meaningful comparison. The display does not certify that two runs are comparable.

Generator overhead includes Java HTTP connection management, TLS where applicable, assertions/JSON parsing, histogram locks, virtual-thread scheduling, persistence and browser polling. The included target shares the JVM with the generator. Local fixture observations validate semantics, not peak independent generator capacity. Production performance requires a separate target host, controlled resources and repeated measurements appropriate to the workload.

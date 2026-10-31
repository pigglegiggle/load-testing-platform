# Architecture and invariants

## Process and ownership

The packaged Spring Boot process serves Angular assets and `/api` endpoints. Angular never generates the load workload in the browser. `RunEngine` owns admitted runs, a virtual-thread executor and one checkpoint scheduler. Each `Run` owns immutable validated configuration, cancellation state, its worker-thread set and `Statistics`. `Requests` owns a reusable Java HTTP client and a fair 128-slot semaphore. JDBC storage is independent of HTTP response processing.

The optional `Demo` component opens a separate loopback HTTP listener in the same JVM. It exercises real TCP/HTTP but shares the host, heap and scheduler with the runner; it is not an independent performance target.

## Definition validation

`Definitions` validates and copies configuration before admission. A scenario has 1–20 uniquely named steps and at most 40 distinct initial/extracted variable names. Supported methods are GET, POST, PUT, PATCH, DELETE, HEAD and OPTIONS. Restricted transport headers cannot be supplied directly. JSON Pointer paths are validated before use. Templates substitute literal `${name}` values; undefined variables fail explicitly.

A worker creates a fresh hash map from initial variables for every iteration. Extractions are copied into that map only after the step passes. A failed step aborts its iteration, not every worker or the entire run. Steps are an ordered list, not a general DAG, and no variable map is shared between workers.

## Admission and execution

Admission is serialized and rejects a third active run with HTTP 429. There is no waiting-run queue. Each accepted run is inserted into durable storage before its coordinator starts workers. COUNT mode performs up to the selected iterations per worker, subject to a 120-second safety limit. DURATION mode repeats until its configured monotonic deadline. Both are closed-loop workloads: a worker waits for a step to finish before starting the next one.

At most 64 workers belong to one run. The global fair semaphore limits outbound attempts to 128 slots, including interactive sends. At most eight interactive sends enter the request path concurrently. Virtual threads provide lightweight blocking coordination; they do not increase target capacity or CPU parallelism.

A run can be RUNNING, CANCELLING, COMPLETED, CANCELLED, FAILED or INTERRUPTED. COMPLETED describes execution lifecycle; its failed-attempt count can be nonzero. A failed step is a test outcome, while FAILED indicates an unexpected worker failure.

## HTTP lifecycle

1. Render and validate the target URL against the configured exact-origin allowlist.
2. Render headers/body and assertion expectations before sending; missing expectation variables reject the attempt without contacting the target. Enforce expanded size limits. Prepare the request without following redirects.
3. Acquire a global request slot within the request budget. Queue timing includes preparation and slot waiting.
4. Send asynchronously, then wait on the response future for the remaining budget. A custom body subscriber counts incoming byte buffers and rejects bodies above 1 MiB.
5. On timeout or interruption, cancel the pending future. Release the semaphore in `finally`.
6. Evaluate status, assertions and extractions after complete body receipt. Retain full content only for the current attempt/interactive reply; load-test samples contain metadata and truncated error text.

The explicit future deadline covers the full body, including a server that flushes headers and stalls. Java's connection management can perform protocol-level recovery; the application itself does not retry failed steps. Cancellation cannot undo an operation already accepted by the target.

## Cancellation and terminal accounting

Cancellation sets the run flag and interrupts registered worker threads. Workers check cancellation/deadline before every step and iteration. A coordinator waits on a latch until all workers exit before taking the terminal snapshot. Request completion records decrement active-attempt accounting exactly once on the normal request path.

For recorded completions, `attempted = passed + failed + cancelled`. Per-step request totals and outcome totals equal the run's attempted count. Cancellation records have their own counter and do not enter latency histograms. Expected network/timeout/assertion failures become ordinary result records; an unexpected worker exception cancels sibling workers and marks the run FAILED.

## Aggregate state

`Statistics` synchronizes short counter/histogram/sample mutations and snapshot creation. It uses HdrHistogram at three significant digits for full-run HTTP latency, queue time and per-step HTTP latency. Values are recorded in microseconds with a 60-second ceiling; configured request budgets are at most ten seconds. The histogram may introduce small bucket quantization.

Recent completion samples use an `ArrayDeque`, capped at 200. Time-series data is grouped by elapsed completion second, capped at second 120. A snapshot copies these bounded structures. It does not retain every response body or every request sample. Histogram/counter locks, JSON conversion and polling are part of generator overhead.

## Persistence and recovery

H2 file mode is the default. A PostgreSQL JDBC URL selects PostgreSQL without a separate repository implementation. Tables store scenario definitions and run snapshots as JSON text, with IDs/state/timestamps for lookup. A checkpoint runs about every second; terminal state is written before the live run is removed. Old terminal reports are pruned to 200, while list endpoints return the newest 100 reports. Up to 100 scenario definitions may be saved.

The checkpoint and final update synchronize on the run so a delayed checkpoint cannot overwrite its terminal state. If terminal persistence fails, the terminal result remains in the live map rather than being silently discarded. Such an operational failure can consume an admission slot until restart; inspect server logs and database health.

Startup changes stored RUNNING/CANCELLING snapshots to INTERRUPTED, clears their active-attempt gauge and records an explanation. It does not resume or replay HTTP operations. In-flight requests and completions after the last checkpoint may be missing. This is durable report checkpointing, not an exactly-once task queue.

## Local access boundary

The application and demo bind to loopback. Browser-origin checks reject unrelated sites. JSON bodies are bounded to 2 MiB, including chunked input; declared over-limit bodies receive 413 and over-limit streamed JSON is rejected as malformed input. Targets must match explicit HTTP(S) origins, and redirects are not followed. The allowlist is not a complete hostile-network or DNS-rebinding defense.

There is no multi-user authentication, secret vault, distributed execution, arbitrary scripting, automatic retry policy, fixed-arrival-rate mode or coordinated-omission correction. Protect the local workspace and use disposable test credentials. Definitions and reports include request headers/bodies/initial variables in plaintext, although response bodies and extracted runtime values are not saved in load-test samples.

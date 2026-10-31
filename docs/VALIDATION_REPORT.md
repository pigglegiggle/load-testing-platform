# Recorded validation report

Recorded on 2026-11-01T03:21:25.967412+07:00 (Asia/Bangkok).

## Executed checks

- 11 Java integration/unit tests passed on Temurin JDK 24.0.2.
- 3 real Chrome browser workflows passed, including request editing, a 24-request chained run, assertions, cancellation, import/export, comparison and a 390-pixel viewport.
- Angular production compilation passed using Angular 21.2.25 / build tooling 21.2.29.
- Packaged-app verification and SIGKILL recovery passed against H2 file storage and a real local PostgreSQL database.

## Controlled 40 ms target observations

Each row is one count run with 20 iterations per worker and a single GET step. The local target waits 40 ms before responding; the runner checks HTTP status and JSON content. All attempts in these observations passed. These are functional calibration observations, not a saturation benchmark.

| Storage | Workers | Requests | HTTP p50 / p95 (ms) | Completed requests/s |
|---|---:|---:|---:|---:|
| H2 file | 1 | 20 | 48.863 / 95.935 | 16.44 |
| H2 file | 4 | 80 | 45.407 / 46.751 | 89.18 |
| H2 file | 16 | 320 | 47.039 / 48.031 | 342.60 |
| PostgreSQL | 1 | 20 | 46.911 / 49.503 | 20.62 |
| PostgreSQL | 4 | 80 | 46.239 / 49.151 | 86.41 |
| PostgreSQL | 16 | 320 | 46.879 / 49.151 | 341.35 |

The demo target and generator share one JVM on the same Mac. Java HTTP connection reuse, scheduling, JSON assertions, report checkpoints and local CPU load influence these values. There was no CPU isolation, thermal control, repeated-batch confidence interval, distributed generator or external target host. Do not infer database performance or production capacity from these rows.

## Restart proof

For each storage backend, the verifier saved a scenario, started a duration run, crossed a durable checkpoint boundary, sent SIGKILL to its owned JVM and launched a new process. The saved scenario remained, the run became INTERRUPTED, its active-attempt gauge was reset to zero and no work was resumed. Last-checkpoint counts are retained; post-checkpoint completions and in-flight operations may be missing.

The PostgreSQL check used the locally installed PostgreSQL 14 server on a disposable project-owned cluster. The Docker Compose PostgreSQL 17 configuration was not executed because Docker was unavailable. The cluster was stopped after verification. The default user-facing app uses H2 and does not require this test cluster.

## Reproduction and raw data

```sh
make test
make build
make browser-test
python3 scripts/verify_system.py
```

[H2 raw observations](validation/h2.json) · [PostgreSQL raw observations](validation/postgresql.json) · [Test coverage and PostgreSQL invocation](TESTING.md). Each JSON report includes platform/runtime information, configuration, full bounded snapshots and the tested JAR SHA-256.

The checks were executed on macOS Apple Silicon with Chrome. Linux/WSL, Safari, Firefox, real mobile devices, sustained database failure and long-duration load were not exercised.

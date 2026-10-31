# Testing

## Backend and compilation

```sh
make test
```

The Java suite uses a real isolated HTTP server and a separate H2 in-memory workspace. It verifies validation and defensive copies, literal variable substitution, JSON/body/header assertions, extraction, allowed origins and no redirect following, stalled-body timeout, oversized response cancellation, network failure, concurrent scenario token isolation, failed-step short circuit, bounded active-run admission, cancellation, duration execution, sample retention, persisted definitions, interrupted-run recovery, HTTP API validation and known histogram percentiles. Assertion validation rejects null types and malformed header names as client errors. Regression checks prove unresolved expectation variables never contact the target and absent headers cannot pass an empty-value assertion.

## Browser workflows

```sh
make build
make browser-test
```

Chrome must be installed. Playwright starts its own packaged server on application port 8095 and demo port 8096, using an isolated in-memory database. Stop anything else on those ports first. The tests exercise real Angular + API workflows, not mocked responses.

For installed Playwright Chromium instead of Chrome:

```sh
cd frontend
npx playwright install chromium
PLAYWRIGHT_BROWSER=chromium npm run test:e2e
```

Browser downloads consume additional disk. No browser download was required on this Mac because Chrome was already installed. Failure traces and screenshots are under ignored `build/browser-results/`. The successful run screenshot is saved to `docs/images/web-runs.png` for the README.

Coverage includes real request/response inspection, blocked targets, scenario saving, multi-step load execution, exact successful counts, export/import, comparison, mobile-width layout, assertion failure and cancellation. Controlled delayed-response tests verify that an old baseline response cannot replace a newer selection, selecting the baseline as the primary run clears self-comparison, and background polling cannot replace a pending user-selected run. Browsers were exercised headlessly on macOS Chrome; Safari, Firefox, Windows and real mobile devices were not tested.

## Packaged process and abrupt recovery

```sh
python3 scripts/verify_system.py
```

This stdlib-only harness owns its server process, temporary ports and temporary H2-file directory. It checks the packaged Angular entry, runs a controlled 40 ms endpoint at 1/4/16 workers, validates counts/assertions, kills the JVM during active work and verifies INTERRUPTED recovery plus retained scenarios after restart. It does not modify the default local workspace.

Against a disposable PostgreSQL database:

```sh
python3 scripts/verify_system.py \
  --database-url jdbc:postgresql://127.0.0.1:55432/apirunner_test \
  --database-user apirunner --database-password local-development
```

That mode writes retained test scenarios/reports to the supplied database. The harness does not create/drop that database or delete unrelated rows. Recorded observations are in VALIDATION_REPORT.md.

## Scope of proof

These checks do not establish production capacity, physical power-loss durability, distributed semantics, hostile network isolation, unlimited history scalability, HTTPS interoperability across providers, browser secret isolation or long-duration soak behavior. The app is a local testing workspace. Database failure is logged and terminal results are retained in memory when possible; operational fault injection for sustained database unavailability is not included.

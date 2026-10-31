# How to run

## Local packaged application

Install a JDK 21+ and a Node release supported by Angular 21. Node 22.12+ in the 22 line or Node 24 is suitable. This project was exercised using Temurin JDK 24.0.2 and Node 25.2.1 on an Apple Silicon Mac; use an LTS Node release for your own setup.

From the repository root:

```sh
make setup
make test
make build
make run
```

`setup` downloads checksum-verified Maven 3.9.11 into `.tools/` and runs `npm ci`. `test` runs Java tests and the Angular production compiler. `build` creates the Angular bundle, copies it into Spring Boot resources and packages the executable JAR. `run` starts that existing JAR; rebuild after source changes.

Open <http://127.0.0.1:8085>. The separate built-in demo API is <http://127.0.0.1:8086>. Ctrl+C stops both. Default persisted data is `data/api-test-runner.mv.db` relative to the launch directory. Launch from the same directory each time. Do not start two H2-file instances against the same database file.

You can run the packaged app without Node or Maven once built:

```sh
java -Xmx768m -jar backend/target/api-test-runner-1.0.0.jar
```

## Development servers

Use two terminals from the root:

```sh
# Terminal 1
make dev-api
```

```sh
# Terminal 2
make dev-ui
```

Open <http://127.0.0.1:4200>. The Angular development server proxies `/api` to port 8085. Stop any packaged instance using 8085/8086 first. Run `make build` before returning to the packaged app so its embedded frontend reflects your changes. The proxy configuration assumes the default API port.

## First workflow

Request starts with the demo health URL. Send it to inspect status, headers and response body. Scenario starts with Demo checkout. Its requests use a `baseUrl` initial variable, extract a session token, fetch an item ID and submit an order-shaped request to the local fixture.

Two workers × four iterations × three passing steps produce 24 passed requests and eight successful iterations. Save the scenario, run it, then run again and select a baseline. Counts can be lower when a step fails, a run is cancelled or the safety deadline is reached. Use the recent sample table to see why.

A request URL, header, body or expected value can contain `${name}`. Extraction fields map names to JSON Pointers, for example `{"token":"/token"}`. Assertions support JSON_EQUALS, BODY_CONTAINS and HEADER_EQUALS. JSON_EQUALS compares scalar text or compact serialized object/array JSON; it is not a general expression language.

## Configure target origins and ports

```sh
TARGET_ORIGINS='http://127.0.0.1:8086,http://127.0.0.1:8000' make run
```

Origins are scheme + hostname + effective port. `localhost` and `127.0.0.1` are different allowed hostnames. No wildcard matching or redirects. Enter a full URL in Request or edit `baseUrl` in Scenario. The allowlist is read at startup.

| Environment variable | Default | Purpose |
|---|---|---|
| `PORT` | 8085 | Loopback application listener |
| `DEMO_PORT` | 8086 | Loopback demonstration target |
| `DEMO_ENABLED` | true | Start the built-in target |
| `TARGET_ORIGINS` | localhost/127.0.0.1 on 8086 | Outbound origin allowlist |
| `DATABASE_URL` | `jdbc:h2:file:./data/api-test-runner;DB_CLOSE_ON_EXIT=FALSE` | JDBC storage URL |
| `DATABASE_USER` | sa | Database username |
| `DATABASE_PASSWORD` | empty | Database password |

`.env.example` lists these variables, but the app does not load `.env` files automatically. Set them in your shell or process manager. Changing the demo port requires changing its allowed origin too. Numeric port 0 is useful in tests but not a stable user endpoint.

## PostgreSQL

With Docker Compose installed:

```sh
docker compose up -d --wait database
DATABASE_URL='jdbc:postgresql://127.0.0.1:55432/apirunner' \
DATABASE_USER=apirunner DATABASE_PASSWORD=local-development make run
```

The Compose database is restricted to local port 55432. Its development credentials are deliberately public example values. Data is retained in a named Docker volume. `docker compose down` stops the container without deleting that volume. This environment did not have Docker, so Compose itself was not executed; the JDBC path was verified against a real locally installed PostgreSQL server.

An existing PostgreSQL installation also works: create a database and a user allowed to create this app's tables, then set the JDBC URL and credentials. The application creates the two tables and index if absent. H2 data is not automatically migrated to PostgreSQL; switching URLs selects a different workspace.

## API examples

```sh
curl http://127.0.0.1:8085/api/config
curl http://127.0.0.1:8085/api/scenarios
curl http://127.0.0.1:8085/api/runs
```

Routes:

| Method / path | Operation |
|---|---|
| `GET /api/health` | Local process health |
| `GET /api/config` | Allowed origins and core limits |
| `GET /api/example` | Demo scenario definition |
| `GET /api/scenarios` | Saved definitions |
| `POST /api/scenarios` | Validate and save a new definition |
| `PUT /api/scenarios/{id}` | Replace a saved definition |
| `DELETE /api/scenarios/{id}` | Remove its saved copy |
| `POST /api/requests/send` | Execute `{step, variables}` interactively |
| `POST /api/runs` | Start `{scenario, mode, concurrency, iterations, durationSeconds}`; 202 |
| `GET /api/runs` | Latest 100 run summaries |
| `GET /api/runs/{id}` | Live or stored report |
| `POST /api/runs/{id}/cancel` | Request cancellation |

Use `Content-Type: application/json` for JSON operations. Validation errors are 400, missing IDs are 404, admission limits are 429. Network/assertion failures are structured execution outcomes, not HTTP 500 responses from the application API.

## Windows / Linux

On Windows, use WSL2 with Ubuntu. From administrator PowerShell:

```powershell
wsl --install -d Ubuntu
```

Finish Ubuntu setup, then run build commands inside Ubuntu. Install `openjdk-21-jdk`, `make`, `curl`, `tar` and `git` through the distribution package manager, plus a Node version compatible with Angular 21. The distro's default Node may be too old; check `node --version` before setup. Keep the checkout under the Linux filesystem, and create separate dependencies there instead of reusing Mac binaries.

Open the app from the Windows browser at `http://localhost:8085`. WSL/firewall configuration can affect localhost forwarding. Native Windows helper scripts are not supplied. Linux/WSL and Windows browsers were not executed on this Mac.

## Troubleshooting

| Symptom | Action |
|---|---|
| `mvn` missing | Use `scripts/mvnw`; setup downloads its pinned Maven. |
| `ng` missing | Run `make setup`; the project uses the local Angular CLI. |
| Java compilation fails | Check `java -version` and `javac -version`; both must be JDK 21+. |
| Ports in use | Stop the prior instance, or change PORT/DEMO_PORT and the allowlist. |
| Target rejected | Check the exact origin, including hostname and port; restart after editing the allowlist. |
| Requests return 401 | Run earlier extraction steps or supply the target's test credentials. |
| No later steps execute | Inspect the first failed assertion, extraction or request in that iteration. |
| Old frontend appears | Run `make build`, restart the JAR and reload the browser. |
| Compare delta is n/a | The baseline value is zero, so percentage change is undefined. |
| Run says INTERRUPTED | The backend restarted during active work; nothing was replayed. |
| Browser-test Chrome missing | Install Chrome, or use the Chromium setup in TESTING.md. |
| No space left on device | Free enough local disk before rerunning setup/build; avoid recording a partial build as successful. |

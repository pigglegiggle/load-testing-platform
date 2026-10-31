#!/usr/bin/env python3
"""Exercise packaged Java + Angular, real HTTP, and abrupt process recovery."""

import argparse
import datetime
import hashlib
import json
import os
from pathlib import Path
import platform
import socket
import subprocess
import tempfile
import time
import urllib.error
import urllib.request

ROOT = Path(__file__).resolve().parents[1]
JAR = ROOT / "backend/target/api-test-runner-1.0.0.jar"


def free_port():
    with socket.socket() as listener:
        listener.bind(("127.0.0.1", 0))
        return listener.getsockname()[1]


def wait_for(action, timeout=15):
    deadline = time.monotonic() + timeout
    last = None
    while time.monotonic() < deadline:
        try:
            result = action()
            if result:
                return result
        except (OSError, urllib.error.URLError) as error:
            last = error
        time.sleep(0.03)
    raise AssertionError(f"Condition timed out: {last}")


def main():
    parser = argparse.ArgumentParser()
    parser.add_argument("--database-url")
    parser.add_argument("--database-user", default="sa")
    parser.add_argument("--database-password", default="")
    parser.add_argument("--output", default="build/system-validation.json")
    options = parser.parse_args()
    port, demo_port = free_port(), free_port()
    while demo_port == port:
        demo_port = free_port()
    base = f"http://127.0.0.1:{port}"
    origin = f"http://127.0.0.1:{demo_port}"
    process = None

    def request(path, value=None):
        payload = None if value is None else json.dumps(value).encode()
        req = urllib.request.Request(
            base + path, data=payload, headers={"Content-Type": "application/json"}
        )
        with urllib.request.urlopen(req, timeout=15) as response:
            return json.load(response)

    with tempfile.TemporaryDirectory(prefix="api-runner-proof-") as work:
        log_path = Path(work) / "server.log"
        environment = dict(
            os.environ,
            PORT=str(port),
            DEMO_PORT=str(demo_port),
            DEMO_ENABLED="true",
            TARGET_ORIGINS=origin,
            DATABASE_URL=options.database_url or "jdbc:h2:file:./data/proof",
            DATABASE_USER=options.database_user,
            DATABASE_PASSWORD=options.database_password,
        )

        def launch():
            log = log_path.open("ab")
            child = subprocess.Popen(
                ["java", "-Xmx512m", "-jar", str(JAR)],
                cwd=work,
                env=environment,
                stdout=log,
                stderr=subprocess.STDOUT,
            )
            log.close()
            wait_for(lambda: child.poll() is None and request("/api/health")["status"] == "UP")
            return child

        try:
            process = launch()
            with urllib.request.urlopen(base, timeout=5) as page:
                assert b"<app-root>" in page.read(), "Packaged Angular entry point missing"
            example = request("/api/example")
            saved = request("/api/scenarios", example)
            observations = []
            for concurrency in (1, 4, 16):
                scenario = {
                    "name": f"40 ms fixture / {concurrency} workers",
                    "variables": {},
                    "steps": [
                        {
                            "name": "Read items",
                            "method": "GET",
                            "url": origin + "/items?delayMs=40",
                            "headers": {"Authorization": "Bearer demo-token"},
                            "body": "",
                            "timeoutMs": 3000,
                            "expectedStatus": 200,
                            "checks": [
                                {
                                    "type": "JSON_EQUALS",
                                    "path": "/items/0/name",
                                    "expected": "Notebook",
                                }
                            ],
                            "extract": {},
                        }
                    ],
                }
                configuration = {
                    "scenario": scenario,
                    "mode": "COUNT",
                    "concurrency": concurrency,
                    "iterations": 20,
                    "durationSeconds": 10,
                }
                started = request("/api/runs", configuration)
                result = wait_for(lambda: terminal(request("/api/runs/" + started["id"])))
                assert result["attempted"] == concurrency * 20
                assert (
                    result["passed"] == result["attempted"]
                    and result["failed"] == result["cancelled"] == result["activeRequests"] == 0
                )
                assert 35 <= result["p50Ms"] < 500, result
                observations.append(result)
            recovery = request(
                "/api/runs",
                {
                    "scenario": example,
                    "mode": "DURATION",
                    "concurrency": 2,
                    "iterations": 1,
                    "durationSeconds": 30,
                },
            )
            wait_for(lambda: request("/api/runs/" + recovery["id"])["attempted"] > 10)
            time.sleep(1.2)  # Cross the one-second durable checkpoint boundary before SIGKILL.
            process.kill()
            process.wait(timeout=5)
            process = launch()
            recovered = request("/api/runs/" + recovery["id"])
            assert recovered["state"] == "INTERRUPTED" and recovered["activeRequests"] == 0
            assert recovered["attempted"] > 0
            assert any(s["id"] == saved["id"] for s in request("/api/scenarios"))
            output = ROOT / options.output
            output.parent.mkdir(parents=True, exist_ok=True)
            report = {
                "recorded_at": datetime.datetime.now(
                    datetime.timezone(datetime.timedelta(hours=7))
                ).isoformat(),
                "platform": platform.platform(),
                "java": subprocess.check_output(
                    ["java", "-version"], stderr=subprocess.STDOUT, text=True
                ).strip(),
                "database": "PostgreSQL" if options.database_url else "H2 file",
                "jar_sha256": hashlib.sha256(JAR.read_bytes()).hexdigest(),
                "method": "Three closed-loop count runs, 20 iterations per worker, one real HTTP step with 40 ms target delay. Same-host demo target and runner share the JVM. Not isolated generator capacity or a production benchmark.",
                "restart": "SIGKILL during active work; restart recovered INTERRUPTED and retained the saved scenario.",
                "observations": observations,
                "recovered": recovered,
            }
            output.write_text(json.dumps(report, indent=2) + "\n")
            print(
                f"PASS: {report['database']}; real HTTP calibration, packaged UI and SIGKILL recovery"
            )
            print(output)
        except Exception:
            print(log_path.read_text()[-5000:])
            raise
        finally:
            if process is not None and process.poll() is None:
                process.terminate()
                try:
                    process.wait(timeout=8)
                except subprocess.TimeoutExpired:
                    process.kill()
                    process.wait(timeout=5)


def terminal(result):
    return result if result["state"] not in ("RUNNING", "CANCELLING") else None


if __name__ == "__main__":
    main()

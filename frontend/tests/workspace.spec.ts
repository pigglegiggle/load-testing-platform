import { test, expect } from "@playwright/test";

test("send a real request, inspect response, and show a blocked target", async ({
  page,
}) => {
  const errors: string[] = [];
  page.on("pageerror", (error) => errors.push(error.message));
  await page.goto("/");
  await expect(page.getByText("Backend connected")).toBeVisible();
  await page.getByRole("button", { name: "Send request", exact: true }).click();
  await expect(page.getByText("PASSED", { exact: true })).toBeVisible();
  await expect(page.locator(".body-preview")).toContainText("UP");
  await page.getByLabel("URL", { exact: true }).fill("http://127.0.0.1:1/");
  await page.getByRole("button", { name: "Send request", exact: true }).click();
  await expect(
    page.getByText("INVALID_REQUEST", { exact: true }),
  ).toBeVisible();
  await expect(page.getByText(/Target origin is not allowed/)).toBeVisible();
  expect(errors).toEqual([]);
});

test("save and run a chained scenario, compare runs, export and import", async ({
  page,
}) => {
  await page.goto("/");
  await expect(page.getByText("Backend connected")).toBeVisible();
  await page.getByRole("button", { name: "Scenario", exact: true }).click();
  await page
    .getByLabel("Scenario name", { exact: true })
    .fill("Checkout browser test");
  await page.getByRole("button", { name: "Save", exact: true }).click();
  await expect(
    page.getByText("Scenario saved.", { exact: true }),
  ).toBeVisible();
  await page.getByLabel("Concurrent workers").fill("2");
  await page.getByLabel("Iterations per worker", { exact: true }).fill("4");
  await page.getByRole("button", { name: "Start run", exact: true }).click();
  await expect(page.locator(".metrics")).toContainText("24", {
    timeout: 15000,
  });
  await expect(page.locator(".metrics")).toContainText("24 / 0 / 0");
  await expect(
    page.getByText("8 successful iterations", { exact: false }),
  ).toBeVisible();
  await expect(
    page.getByRole("button", { name: "Cancel run", exact: true }),
  ).toHaveCount(0);
  await page.evaluate(() => window.scrollTo(0, 0));
  await page.screenshot({
    path: "../docs/images/web-runs.png",
    fullPage: false,
  });
  const download = page.waitForEvent("download");
  await page
    .getByRole("button", { name: "Export result", exact: true })
    .click();
  expect((await download).suggestedFilename()).toMatch(/^run-.*\.json$/);
  await page.getByRole("button", { name: "Scenario", exact: true }).click();
  const scenarioDownload = page.waitForEvent("download");
  await page.getByRole("button", { name: "Export JSON", exact: true }).click();
  const exported = await scenarioDownload;
  const path = await exported.path();
  expect(path).toBeTruthy();
  await page.locator("input[type=file]").setInputFiles(path!);
  await expect(
    page.getByText("Scenario imported.", { exact: true }),
  ).toBeVisible();
  await page.getByRole("button", { name: "Start run", exact: true }).click();
  await expect(page.locator(".metrics")).toContainText("24 / 0 / 0");
  await page
    .getByRole("combobox", { name: "Baseline", exact: true })
    .selectOption({ index: 1 });
  await expect(page.getByText("Selected run", { exact: true })).toBeVisible();
  await page.setViewportSize({ width: 390, height: 844 });
  expect(
    await page.evaluate(
      () => document.documentElement.scrollWidth <= window.innerWidth,
    ),
  ).toBe(true);
});

test("failed assertions stop the iteration and a duration run can be cancelled", async ({
  page,
}) => {
  await page.goto("/");
  await expect(page.getByText("Backend connected")).toBeVisible();
  await page.getByRole("button", { name: "Scenario", exact: true }).click();
  await page.getByLabel("Expected status", { exact: true }).first().fill("418");
  await page.getByLabel("Concurrent workers").fill("1");
  await page.getByLabel("Iterations per worker", { exact: true }).fill("1");
  await page.getByRole("button", { name: "Start run", exact: true }).click();
  await expect(page.locator(".metrics")).toContainText("0 / 1 / 0");
  await expect(
    page.getByText("ASSERTION_FAILED", { exact: true }),
  ).toBeVisible();
  await page.getByRole("button", { name: "Scenario", exact: true }).click();
  await page.getByRole("button", { name: "Load demo", exact: true }).click();
  await page
    .getByRole("combobox", { name: "Mode", exact: true })
    .selectOption("DURATION");
  await page.getByLabel("Duration (seconds)").fill("20");
  await page.getByRole("button", { name: "Start run", exact: true }).click();
  await page.getByRole("button", { name: "Cancel run", exact: true }).click();
  await expect(
    page.getByRole("button", { name: "Cancel run", exact: true }),
  ).toHaveCount(0, { timeout: 10000 });
  await expect(
    page.getByText("CANCELLED", { exact: true }).first(),
  ).toBeVisible();
});

async function completedRun(
  request: import("@playwright/test").APIRequestContext,
  name: string,
) {
  const response = await request.post("/api/runs", {
    data: {
      scenario: {
        name,
        variables: {},
        steps: [
          {
            name: "Health",
            method: "GET",
            url: "http://127.0.0.1:8096/health",
            headers: {},
            body: "",
            timeoutMs: 1000,
            expectedStatus: 200,
            checks: [],
            extract: {},
          },
        ],
      },
      mode: "COUNT",
      concurrency: 1,
      iterations: 1,
      durationSeconds: 1,
    },
  });
  expect(response.ok()).toBeTruthy();
  const started = (await response.json()) as import("../src/models").Snapshot;
  await expect
    .poll(
      async () =>
        (await (await request.get("/api/runs/" + started.id)).json()).state,
    )
    .toBe("COMPLETED");
  return (await (
    await request.get("/api/runs/" + started.id)
  ).json()) as import("../src/models").Snapshot;
}

test("late baseline responses cannot replace the currently selected comparison", async ({
  page,
  request,
}) => {
  const first = await completedRun(request, "Baseline first");
  const second = await completedRun(request, "Baseline second");
  const selected = await completedRun(request, "Selected comparison run");
  let releaseFirst!: () => void;
  let firstRequested!: () => void;
  const delayed = new Promise<void>((resolve) => {
    releaseFirst = resolve;
  });
  const requested = new Promise<void>((resolve) => {
    firstRequested = resolve;
  });
  await page.route("**/api/runs/" + first.id, async (route) => {
    firstRequested();
    await delayed;
    await route.fulfill({ json: { ...first, p95Ms: 111 } });
  });
  await page.route("**/api/runs/" + second.id, (route) =>
    route.fulfill({ json: { ...second, p95Ms: 222 } }),
  );
  await page.goto("/");
  await expect(page.getByText("Backend connected")).toBeVisible();
  await page.getByRole("button", { name: /^Runs/ }).click();
  await page
    .getByRole("row")
    .filter({ hasText: selected.name })
    .getByRole("button", { name: "Open" })
    .click();
  const baseline = page.getByRole("combobox", {
    name: "Baseline",
    exact: true,
  });
  await baseline.selectOption(first.id);
  await requested;
  await baseline.selectOption(second.id);
  const comparison = page.locator("table").filter({
    has: page.getByRole("columnheader", { name: "Baseline", exact: true }),
  });
  const baselineP95 = comparison
    .getByRole("row")
    .filter({ hasText: "p95 (ms)" })
    .getByRole("cell")
    .nth(1);
  await expect(baselineP95).toHaveText("222.00");
  const lateResponse = page.waitForResponse("**/api/runs/" + first.id);
  releaseFirst();
  await (await lateResponse).finished();
  await page.evaluate(
    () =>
      new Promise((resolve) =>
        requestAnimationFrame(() => requestAnimationFrame(resolve)),
      ),
  );
  await expect(baselineP95).toHaveText("222.00");
  await page.unroute("**/api/runs/" + first.id);
  let releaseError!: () => void;
  let errorRequested!: () => void;
  const errorGate = new Promise<void>((resolve) => {
    releaseError = resolve;
  });
  const errorStarted = new Promise<void>((resolve) => {
    errorRequested = resolve;
  });
  await page.route("**/api/runs/" + first.id, async (route) => {
    errorRequested();
    await errorGate;
    await route.fulfill({
      status: 500,
      json: { error: "Obsolete baseline failed" },
    });
  });
  await baseline.selectOption(first.id);
  await errorStarted;
  await baseline.selectOption(second.id);
  await expect(baselineP95).toHaveText("222.00");
  const failedResponse = page.waitForResponse("**/api/runs/" + first.id);
  releaseError();
  await (await failedResponse).finished();
  await page.evaluate(
    () =>
      new Promise((resolve) =>
        requestAnimationFrame(() => requestAnimationFrame(resolve)),
      ),
  );
  await expect(
    page.getByText("Obsolete baseline failed", { exact: true }),
  ).toHaveCount(0);
  await page
    .getByRole("row")
    .filter({ hasText: second.name })
    .getByRole("button", { name: "Open" })
    .click();
  await expect(baseline).toHaveValue("");
  await expect(comparison).toHaveCount(0);
});

test("background polling preserves a newer pending run selection", async ({
  page,
  request,
}) => {
  const first = await completedRun(request, "Polling run");
  const second = await completedRun(request, "Newly selected run");
  await page.route("**/api/runs/" + first.id, (route) =>
    route.fulfill({ json: { ...first, state: "RUNNING" } }),
  );
  await page.goto("/");
  await expect(page.getByText("Backend connected")).toBeVisible();
  await page.getByRole("button", { name: /^Runs/ }).click();
  await page
    .getByRole("row")
    .filter({ hasText: first.name })
    .getByRole("button", { name: "Open" })
    .click();
  await expect(
    page.getByRole("heading", { name: first.name, exact: true }),
  ).toBeVisible();
  let releaseHistory!: () => void;
  let historyRequested!: () => void;
  const historyGate = new Promise<void>((resolve) => {
    releaseHistory = resolve;
  });
  const historyStarted = new Promise<void>((resolve) => {
    historyRequested = resolve;
  });
  const history = await (await request.get("/api/runs")).json();
  await page.route("**/api/runs", async (route) => {
    historyRequested();
    await historyGate;
    await route.fulfill({ json: history });
  });
  await historyStarted;
  let releaseSecond!: () => void;
  let secondRequested!: () => void;
  const secondGate = new Promise<void>((resolve) => {
    releaseSecond = resolve;
  });
  const secondStarted = new Promise<void>((resolve) => {
    secondRequested = resolve;
  });
  await page.route("**/api/runs/" + second.id, async (route) => {
    secondRequested();
    await secondGate;
    await route.fulfill({ json: second });
  });
  const nextRow = page.getByRole("row").filter({ hasText: second.name });
  await nextRow.getByRole("button", { name: "Open" }).click();
  await secondStarted;
  try {
    await expect(nextRow).toHaveClass(/current/);
    const historyResponse = page.waitForResponse("**/api/runs");
    releaseHistory();
    await (await historyResponse).finished();
    await page.evaluate(
      () =>
        new Promise((resolve) =>
          requestAnimationFrame(() => requestAnimationFrame(resolve)),
        ),
    );
    releaseSecond();
    await expect(
      page.getByRole("heading", { name: second.name, exact: true }),
    ).toBeVisible();
  } finally {
    releaseHistory();
    releaseSecond();
  }
});

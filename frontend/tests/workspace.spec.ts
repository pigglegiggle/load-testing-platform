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

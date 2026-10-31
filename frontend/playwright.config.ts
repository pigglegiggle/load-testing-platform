import { defineConfig } from "@playwright/test";
export default defineConfig({
  testDir: "./tests",
  workers: 1,
  fullyParallel: false,
  timeout: 30000,
  outputDir: "../build/browser-results",
  reporter: "list",
  use: {
    baseURL: "http://127.0.0.1:8095",
    viewport: { width: 1360, height: 960 },
    channel:
      process.env["PLAYWRIGHT_BROWSER"] === "chromium" ? undefined : "chrome",
    trace: "retain-on-failure",
  },
  webServer: {
    command: "java -Xmx512m -jar ../backend/target/api-test-runner-1.0.0.jar",
    url: "http://127.0.0.1:8095/api/health",
    reuseExistingServer: false,
    timeout: 30000,
    env: {
      PORT: "8095",
      DEMO_PORT: "8096",
      TARGET_ORIGINS: "http://127.0.0.1:8096",
      DATABASE_URL: "jdbc:h2:mem:browser",
      DATABASE_USER: "sa",
      DATABASE_PASSWORD: "",
    },
  },
});

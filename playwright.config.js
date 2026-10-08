import { defineConfig, devices } from "@playwright/test";
import { mkdtempSync } from "node:fs";
import { tmpdir } from "node:os";
import { join } from "node:path";

const testData = mkdtempSync(join(tmpdir(), "devprep-browser-"));

export default defineConfig({
  testDir: "./tests/e2e",
  fullyParallel: false,
  workers: 1,
  timeout: 30_000,
  retries: process.env.CI ? 1 : 0,
  reporter: "list",
  use: {
    baseURL: "http://127.0.0.1:8875",
    trace: "retain-on-failure",
    screenshot: "only-on-failure",
    ...devices["Desktop Chrome"],
    channel: process.env.PLAYWRIGHT_CHANNEL || undefined,
  },
  webServer: {
    command: `node scripts/start.mjs${process.env.DEVPREP_E2E_SKIP_BUILD === "1" ? " --skip-build" : ""}`,
    url: "http://127.0.0.1:8875/api/health",
    reuseExistingServer: false,
    timeout: 180_000,
    env: {
      DEVPREP_HOST: "127.0.0.1",
      DEVPREP_PORT: "8875",
      DEVPREP_DATA_DIR: testData,
      DEVPREP_DB_FILE: join(testData, "devprep"),
      DEVPREP_CONFIG_FILE: join(testData, "settings.toml"),
      DEVPREP_LOCAL_LEARNING_FILE: "",
    },
  },
});

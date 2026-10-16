import { defineConfig, devices } from "@playwright/test";
import { mkdtempSync, writeFileSync } from "node:fs";
import { tmpdir } from "node:os";
import { join } from "node:path";

const directory = mkdtempSync(join(tmpdir(), "devprep-account-browser-"));
const bootstrap = join(directory, "bootstrap.json");
// Disposable test fixtures, never used by a deployed instance.
writeFileSync(bootstrap, JSON.stringify({ accounts: [
  { username: "owner", password: "owner-test-password" },
  { username: "guest", password: "guest-test-password" },
] }));

export default defineConfig({
  testDir: "./tests/accounts-e2e", fullyParallel: false, workers: 1,
  timeout: 60_000, reporter: "list",
  use: { baseURL: "http://127.0.0.1:8877", ...devices["Desktop Chrome"], channel: process.env.PLAYWRIGHT_CHANNEL || undefined, screenshot: "only-on-failure" },
  webServer: {
    command: "node scripts/start.mjs --skip-build --builtin-only",
    url: "http://127.0.0.1:8877/api/health", timeout: 180_000,
    reuseExistingServer: false,
    env: {
      DEVPREP_PORT: "8877", DEVPREP_HOST: "127.0.0.1",
      DEVPREP_DATA_DIR: directory, DEVPREP_DB_FILE: join(directory, "devprep"),
      DEVPREP_CONFIG_FILE: join(directory, "settings.toml"),
      DEVPREP_AUTH_ENABLED: "true", DEVPREP_AUTH_SECURE_COOKIE: "false",
      DEVPREP_AUTH_BOOTSTRAP_FILE: bootstrap,
      DEVPREP_MEMBER_MODEL: "gpt-6.1-sol",
      DEVPREP_AGENT_MIN_INTERVAL_MILLIS: "0",
    },
  },
});

import { spawn, spawnSync } from "node:child_process";
import { existsSync } from "node:fs";
import { dirname, join } from "node:path";
import { fileURLToPath } from "node:url";
import { parseArgs } from "node:util";

const root = dirname(dirname(fileURLToPath(import.meta.url)));
const backend = join(root, "backend-java");
const jar = join(backend, "target", "devprep-backend-0.2.0-SNAPSHOT.jar");
const windows = process.platform === "win32";
let currentProcess;
let interrupted;
let killTimer;

const help = `Build and run DevPrep AI with Spring Boot.

Usage: node scripts/start.mjs [options]
       npm start -- [options]

Requirements: Node.js 20+ and JDK 17+ (Node.js 22 recommended).
Maven is downloaded automatically by the bundled Maven Wrapper.

Options:
  --port <number>  Override the local HTTP port (default: 8765).
  --skip-build     Use the existing frontend build and Spring Boot Jar.
  --install        Reinstall locked frontend dependencies before building.
  --builtin-only   Start with the bundled seed, without importing a local question pack.
  --test           Run Java backend tests without building the frontend or starting a server.
  -h, --help       Show this help.
`;

class CommandError extends Error {
  constructor(message, code = 1) {
    super(message);
    this.code = code;
  }
}

function terminateChild(signal) {
  if (!currentProcess?.pid) return;
  if (windows) {
    const target = currentProcess;
    const killer = spawn("taskkill", ["/PID", String(target.pid), "/T", "/F"], { stdio: "ignore" });
    killer.on("error", () => target.kill());
    return;
  }
  try {
    process.kill(-currentProcess.pid, signal);
  } catch (error) {
    if (error.code !== "ESRCH") currentProcess.kill(signal);
  }
}

function onSignal(signal) {
  if (interrupted) {
    terminateChild("SIGKILL");
    return;
  }
  interrupted = signal;
  terminateChild("SIGTERM");
  killTimer = setTimeout(() => terminateChild("SIGKILL"), 5000);
  killTimer.unref();
}

function commandFor(command, args) {
  // Batch filenames and arguments are fixed below; user input never enters cmd.exe.
  if (windows && (command === "npm.cmd" || command === "mvnw.cmd")) {
    return [process.env.ComSpec || "cmd.exe", ["/d", "/s", "/c", `${command} ${args.join(" ")}`]];
  }
  return [command, args];
}

async function run(command, args, { cwd = root, env = process.env } = {}) {
  if (interrupted) throw new CommandError("Interrupted.", interrupted === "SIGINT" ? 130 : 143);
  const [executable, commandArgs] = commandFor(command, args);
  await new Promise((resolve, reject) => {
    const child = spawn(executable, commandArgs, { cwd, env, stdio: "inherit", detached: !windows });
    currentProcess = child;
    child.once("error", (error) => reject(new CommandError(`Could not run ${command}: ${error.message}`)));
    child.once("close", (code, signal) => {
      if (currentProcess === child) currentProcess = undefined;
      clearTimeout(killTimer);
      if (interrupted) {
        reject(new CommandError("Interrupted.", interrupted === "SIGINT" ? 130 : 143));
      } else if (code !== 0) {
        reject(new CommandError(`${command} exited with ${signal || code}.`, code || 1));
      } else {
        resolve();
      }
    });
  });
}

function javaExecutable(name) {
  const filename = windows ? `${name}.exe` : name;
  return process.env.JAVA_HOME ? join(process.env.JAVA_HOME, "bin", filename) : filename;
}

function checkJava(needsCompiler) {
  for (const name of needsCompiler ? ["java", "javac"] : ["java"]) {
    const executable = javaExecutable(name);
    const result = spawnSync(executable, ["-version"], { cwd: root, encoding: "utf8" });
    const output = `${result.stdout || ""}\n${result.stderr || ""}`;
    const match = output.match(/(?:version\s+["']?|javac\s+)(\d+)(?:\.(\d+))?/i);
    const major = match?.[1] === "1" ? Number(match[2]) : Number(match?.[1]);
    if (result.error || result.status !== 0 || !Number.isFinite(major) || major < 17) {
      throw new CommandError(`JDK 17+ is required. Install a JDK and set JAVA_HOME or add java and javac to PATH (${name} check failed).`);
    }
  }
  return javaExecutable("java");
}

async function maven(args) {
  if (!existsSync(join(backend, windows ? "mvnw.cmd" : "mvnw"))) {
    throw new CommandError("Maven Wrapper is missing from backend-java/. Restore the repository files and retry.");
  }
  await run(windows ? "mvnw.cmd" : "sh", windows ? args : ["./mvnw", ...args], { cwd: backend });
}

async function main() {
  const { values } = parseArgs({
    options: {
      help: { type: "boolean", short: "h" },
      port: { type: "string" },
      "skip-build": { type: "boolean" },
      install: { type: "boolean" },
      "builtin-only": { type: "boolean" },
      test: { type: "boolean" },
    },
    strict: true,
  });
  if (values.help) {
    console.log(help);
    return;
  }
  if (Number(process.versions.node.split(".")[0]) < 20) {
    throw new CommandError("Node.js 20+ is required; Node.js 22 is recommended.");
  }
  if (values.port !== undefined && (!/^\d+$/.test(values.port) || Number(values.port) < 1 || Number(values.port) > 65535)) {
    throw new CommandError("--port must be an integer between 1 and 65535.");
  }
  if (values.install && values["skip-build"]) {
    throw new CommandError("--install cannot be combined with --skip-build.");
  }
  if (values.test && (values.install || values["skip-build"] || values["builtin-only"] || values.port !== undefined)) {
    throw new CommandError("--test cannot be combined with server or frontend build options.");
  }
  const java = checkJava(!values["skip-build"]);
  if (values.test) {
    await maven(["-B", "test"]);
    return;
  }
  if (values["skip-build"]) {
    if (!existsSync(join(root, "dist", "index.html")) || !existsSync(jar)) {
      throw new CommandError("Build artifacts are missing. Run npm start without --skip-build to build the frontend and Spring Boot Jar.");
    }
  } else {
    const npm = windows ? "npm.cmd" : "npm";
    if (values.install || !existsSync(join(root, "node_modules", ".bin", windows ? "vite.cmd" : "vite"))) {
      console.log("Installing locked frontend dependencies...");
      await run(npm, ["ci"]);
    }
    console.log("Building the frontend...");
    await run(npm, ["run", "build"]);
    console.log("Building the Spring Boot backend...");
    await maven(["-B", "-DskipTests", "package"]);
  }
  const env = { ...process.env, DEVPREP_ROOT: root };
  if (values["builtin-only"]) {
    env.DEVPREP_LOCAL_LEARNING_FILE = "";
  } else if (env.DEVPREP_LOCAL_LEARNING_FILE === undefined) {
    const localPack = ["local.learning.json", "devops.learning.json"]
      .map((filename) => join(root, ".cache", "content", filename))
      .find(existsSync);
    if (localPack) env.DEVPREP_LOCAL_LEARNING_FILE = localPack;
  }
  if (env.DEVPREP_LOCAL_LEARNING_FILE) console.log(`Loading local question pack: ${env.DEVPREP_LOCAL_LEARNING_FILE}`);
  if (values.port !== undefined) env.DEVPREP_PORT = String(Number(values.port));
  console.log(`Starting DevPrep AI at http://${env.DEVPREP_HOST || "127.0.0.1"}:${env.DEVPREP_PORT || "8765"}. Press Ctrl+C to stop.`);
  await run(java, ["-jar", jar], { env });
}

process.on("SIGINT", onSignal);
process.on("SIGTERM", onSignal);
try {
  await main();
} catch (error) {
  if (!interrupted) console.error(error.message);
  process.exitCode = interrupted === "SIGINT" ? 130 : interrupted === "SIGTERM" ? 143 : Number.isInteger(error.code) ? error.code : 1;
} finally {
  clearTimeout(killTimer);
  process.removeListener("SIGINT", onSignal);
  process.removeListener("SIGTERM", onSignal);
}

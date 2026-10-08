from __future__ import annotations

import argparse
import os
from pathlib import Path
import shutil
import subprocess
import sys


ROOT = Path(__file__).resolve().parent.parent


def run_command(command: list[str]) -> None:
    subprocess.run(command, cwd=ROOT, check=True)


def main() -> int:
    parser = argparse.ArgumentParser(description="Build and run the local DevPrep AI app.")
    parser.add_argument("--skip-build", action="store_true", help="Use an existing dist build.")
    parser.add_argument("--install", action="store_true", help="Refresh frontend dependencies before building.")
    parser.add_argument("--port", type=int, help="Override the local HTTP port (default: 8765).")
    args = parser.parse_args()

    if sys.version_info < (3, 11):
        parser.error("Python 3.11 or newer is required.")
    if args.port is not None and not 1 <= args.port <= 65535:
        parser.error("--port must be between 1 and 65535.")
    if args.skip_build and args.install:
        parser.error("--install cannot be combined with --skip-build.")

    if args.skip_build:
        if not (ROOT / "dist" / "index.html").is_file():
            parser.error("No dist build found. Run without --skip-build first.")
    else:
        npm = shutil.which("npm.cmd" if os.name == "nt" else "npm")
        if not npm:
            parser.error("Node.js and npm are required. Install Node.js 20+ and retry.")
        try:
            if args.install or not (ROOT / "node_modules" / ".bin" / ("vite.cmd" if os.name == "nt" else "vite")).exists():
                install_command = "ci" if (ROOT / "package-lock.json").is_file() else "install"
                print(f"Installing frontend dependencies with npm {install_command}...", flush=True)
                run_command([npm, install_command])
            print("Building the frontend...", flush=True)
            run_command([npm, "run", "build"])
        except subprocess.CalledProcessError as error:
            return error.returncode or 1
        except KeyboardInterrupt:
            return 130

    environment = os.environ.copy()
    if args.port is not None:
        environment["DEVPREP_PORT"] = str(args.port)
    print("Starting DevPrep AI. Press Ctrl+C to stop.", flush=True)
    try:
        process = subprocess.Popen(
            [sys.executable, str(ROOT / "backend" / "devprep_server.py")],
            cwd=ROOT,
            env=environment,
        )
    except OSError as error:
        print(f"Could not start the backend: {error}", file=sys.stderr)
        return 1
    try:
        return process.wait()
    except KeyboardInterrupt:
        if process.poll() is None:
            process.terminate()
            try:
                process.wait(timeout=5)
            except subprocess.TimeoutExpired:
                process.kill()
                process.wait()
        return 130


if __name__ == "__main__":
    raise SystemExit(main())

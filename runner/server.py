"""Internal judge broker. Each request gets a disposable, unnetworked container."""
import json
import os
import subprocess
import threading
import time
import uuid
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer

LANGUAGES = {
    "python": ("python:3.12-alpine", "main.py", None, ["python", "-I", "/workspace/main.py"]),
    "javascript": ("node:22-alpine", "main.js", None, ["node", "--max-old-space-size=128", "/workspace/main.js"]),
    "java": ("maven:3.9-eclipse-temurin-17", "Main.java", ["javac", "-J-Xmx128m", "-d", "/workspace", "/workspace/Main.java"], ["java", "-Xmx128m", "-XX:ActiveProcessorCount=1", "-XX:-UsePerfData", "-cp", "/workspace", "Main"]),
    "go": ("golang:1.24-alpine", "main.go", ["go", "build", "-o", "/workspace/main", "/workspace/main.go"], ["/workspace/main"]),
}
ACTIVE = threading.BoundedSemaphore(2)


def command(args, input=None, timeout=10):
    """Bound stdout/stderr while reading, including submissions printing forever."""
    proc = subprocess.Popen(args, stdin=subprocess.PIPE, stdout=subprocess.PIPE, stderr=subprocess.PIPE)
    buffers = [bytearray(), bytearray()]
    overflow = threading.Event()

    def read(stream, destination):
        while True:
            data = stream.read(4096)
            if not data:
                break
            if len(destination) + len(data) > 32000:
                overflow.set()
                proc.kill()
                break
            destination.extend(data)

    threads = [threading.Thread(target=read, args=(stream, target), daemon=True) for stream, target in zip((proc.stdout, proc.stderr), buffers)]
    for thread in threads:
        thread.start()
    try:
        if input:
            proc.stdin.write(input)
        proc.stdin.close()
        proc.wait(timeout=timeout)
    except subprocess.TimeoutExpired:
        proc.kill()
        proc.wait()
        raise TimeoutError("execution deadline exceeded")
    except BrokenPipeError:
        proc.wait(timeout=timeout)
    finally:
        for thread in threads:
            thread.join(timeout=1)
    return proc.returncode, buffers[0].decode("utf-8", errors="replace"), buffers[1].decode("utf-8", errors="replace"), overflow.is_set()


def run(body):
    language = body.get("language")
    code, inputs = body.get("code"), body.get("inputs")
    if language not in LANGUAGES or not isinstance(code, str) or not 0 < len(code) <= 30000 or not isinstance(inputs, list) or not 1 <= len(inputs) <= 20:
        raise ValueError("invalid submission")
    image, filename, compile_args, execute_args = LANGUAGES[language]
    name = "devprep-job-" + uuid.uuid4().hex
    try:
        rc, _, err, _ = command([
            "docker", "run", "-d", "--name", name, "--label", "devprep.sandbox=true",
            "--network", "none", "--read-only", "--cap-drop", "ALL", "--security-opt", "no-new-privileges",
            "--user", "10001:10001", "--cpus", "1", "--memory", "512m", "--memory-swap", "512m", "--pids-limit", "128",
            "--tmpfs", "/tmp:rw,size=256m,uid=10001,gid=10001", "--tmpfs", "/workspace:rw,exec,size=16m,uid=10001,gid=10001",
            "--workdir", "/workspace", "--env", "HOME=/tmp", "--env", "GOCACHE=/tmp/go-cache", "--env", "GOPATH=/tmp/go", "--env", "GOMAXPROCS=1", "--env", "GOTOOLCHAIN=local",
            "--log-driver", "none", image, "sleep", "90"], timeout=10)
        if rc:
            return {"status": "runner_error", "compiler_output": "无法启动语言容器，请先拉取运行镜像。"}
        # Only filenames from LANGUAGES enter this fixed shell command.
        rc, _, _, _ = command(["docker", "exec", "-i", name, "sh", "-c", "cat > /workspace/" + filename], code.encode("utf-8"))
        if rc:
            return {"status": "runner_error"}
        if compile_args:
            try:
                rc, out, err, overflow = command(["docker", "exec", name] + compile_args, timeout=35)
            except TimeoutError:
                return {"status": "compile_error", "compiler_output": "编译超过35秒限制。"}
            if rc or overflow:
                return {"status": "compile_error", "compiler_output": (out + err)[:8000]}
        cases = []
        for index, value in enumerate(inputs):
            started = time.monotonic()
            try:
                rc, out, err, overflow = command(["docker", "exec", "-i", name] + execute_args,
                    (json.dumps(value, ensure_ascii=False) + "\n").encode("utf-8"), timeout=3)
                status = "output_limit" if overflow else "ok" if rc == 0 else "runtime_error"
                if rc and not err:
                    err = out
            except TimeoutError:
                status, out, err = "timeout", "", "执行超过3秒限制。"
                # Killing docker exec alone does not kill the in-container process.
                command(["docker", "kill", name], timeout=5)
                cases.append({"status": status, "stdout": out, "stderr": err, "duration_ms": 3000})
                cases.extend({"status": "timeout", "stdout": "", "stderr": "", "duration_ms": 0} for _ in inputs[index+1:])
                break
            cases.append({"status": status, "stdout": out, "stderr": err[:8000], "duration_ms": round((time.monotonic()-started)*1000)})
            if overflow:
                command(["docker", "kill", name], timeout=5)
                cases.extend({"status": "output_limit", "stdout": "", "stderr": "", "duration_ms": 0} for _ in inputs[index+1:])
                break
        return {"status": "completed", "cases": cases}
    finally:
        try:
            command(["docker", "rm", "-f", name], timeout=8)
        except (TimeoutError, OSError):
            pass


class Handler(BaseHTTPRequestHandler):
    def do_GET(self):
        self.respond(200 if self.path == "/health" else 404, {"ok": self.path == "/health"})

    def do_POST(self):
        if self.path != "/run":
            self.respond(404, {"error": "not found"})
            return
        if not ACTIVE.acquire(blocking=False):
            self.respond(429, {"error": "busy"})
            return
        try:
            size = int(self.headers.get("Content-Length", "0"))
            if not 0 < size <= 100000:
                raise ValueError("request too large")
            self.respond(200, run(json.loads(self.rfile.read(size))))
        except (ValueError, TypeError):
            self.respond(400, {"error": "invalid submission"})
        except (OSError, TimeoutError):
            self.respond(200, {"status": "runner_error", "compiler_output": "运行环境暂时不可用。"})
        finally:
            ACTIVE.release()

    def respond(self, status, data):
        encoded = json.dumps(data, ensure_ascii=False).encode("utf-8")
        self.send_response(status)
        self.send_header("Content-Type", "application/json; charset=utf-8")
        self.send_header("Content-Length", str(len(encoded)))
        self.end_headers()
        self.wfile.write(encoded)

    def log_message(self, *args):
        pass


if __name__ == "__main__":
    ThreadingHTTPServer(("0.0.0.0", 8090), Handler).serve_forever()

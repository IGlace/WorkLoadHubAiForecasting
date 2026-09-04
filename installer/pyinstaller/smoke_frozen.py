"""Smoke-test a frozen service folder: version, data generation, forecast run, Copilot status,
serve handshake, health and one guarded route.

Usage: python installer/pyinstaller/smoke_frozen.py <dist-dir>   (dist-dir contains whf or whf.exe)
Exit code 0 on success. Standard library only, so it runs on the Windows build machine and on Linux CI.
"""

from __future__ import annotations

import json
import os
import subprocess
import sys
import tempfile
import time
import urllib.error
import urllib.request
from pathlib import Path


def _exe(dist: Path) -> Path:
    exe = dist / ("whf.exe" if os.name == "nt" else "whf")
    if not exe.exists():
        raise SystemExit(f"missing frozen executable: {exe}")
    return exe


def _run(exe: Path, *args: str, timeout: int = 300) -> str:
    out = subprocess.run([str(exe), *args], capture_output=True, text=True, timeout=timeout)
    if out.returncode != 0:
        raise SystemExit(f"{exe.name} {' '.join(args)} failed ({out.returncode}):\n{out.stdout}\n{out.stderr}")
    return out.stdout


def _get(url: str, token: str | None = None) -> tuple[int, dict]:
    req = urllib.request.Request(url, headers={"X-WHF-Token": token} if token else {})
    try:
        with urllib.request.urlopen(req, timeout=10) as res:
            return res.status, json.loads(res.read().decode("utf-8"))
    except urllib.error.HTTPError as exc:
        raw = exc.read().decode("utf-8")
        try:
            body = json.loads(raw) if raw else {}
        except json.JSONDecodeError:
            body = {}
        return exc.code, body


def _last_json_object(stdout: str) -> dict:
    """Parse the `run --json` payload from stdout, tolerating stray lines before or after it.

    `whf run --json` is documented to print exactly one JSON document, but a dependency writing a stray
    line to stdout (a warning, a progress message) should not make this smoke test crash with a
    JSONDecodeError on the whole blob — so take the last line that starts with `{` and parse only that.
    """
    candidates = [line for line in stdout.splitlines() if line.strip().startswith("{")]
    if not candidates:
        raise SystemExit(f"whf run --json printed no JSON object line: {stdout}")
    try:
        payload = json.loads(candidates[-1])
    except json.JSONDecodeError as exc:
        raise SystemExit(f"whf run --json did not print valid JSON: {candidates[-1]}") from exc
    if not isinstance(payload, dict):
        raise SystemExit(f"whf run --json did not print a JSON object: {candidates[-1]}")
    return payload


def _valid_handshake(line: str) -> dict | None:
    if not line:
        return None
    try:
        candidate = json.loads(line)
    except json.JSONDecodeError:
        return None
    if not isinstance(candidate, dict):
        return None
    if not isinstance(candidate.get("port"), int) or not isinstance(candidate.get("token"), str):
        return None
    return candidate


def main(dist_dir: str) -> int:
    dist = Path(dist_dir).resolve()
    exe = _exe(dist)
    version = _run(exe, "version").strip()
    print(f"ok version: {version}")
    with tempfile.TemporaryDirectory() as tmp:
        db = Path(tmp) / "smoke.db"
        _run(exe, "data", "generate", "--db", str(db), "--months", "3")
        print("ok data generate")

        run_out = _run(exe, "run", "--team", "1", "--db", str(db), "--json")
        run_payload = _last_json_object(run_out)
        if "run_id" not in run_payload:
            raise SystemExit(f"whf run --json missing run_id: {run_out}")
        print("ok run")

        bundled_cli = dist / "copilot-cli" / ("copilot.exe" if os.name == "nt" else "copilot")
        env = {**os.environ, "COPILOT_SKIP_CLI_DOWNLOAD": "1"}
        if bundled_cli.exists():
            env["COPILOT_CLI_PATH"] = str(bundled_cli)
            print(f"using bundled Copilot CLI: {bundled_cli}")
        else:
            print("no bundled CLI")
        copilot_out = subprocess.run(
            [str(exe), "copilot", "status", "--json"], capture_output=True, text=True, timeout=60, env=env
        )
        if copilot_out.returncode not in (0, 3):
            raise SystemExit(
                f"whf copilot status --json failed ({copilot_out.returncode}):\n"
                f"{copilot_out.stdout}\n{copilot_out.stderr}"
            )
        if "Traceback" in copilot_out.stderr:
            raise SystemExit(f"whf copilot status --json printed a traceback:\n{copilot_out.stderr}")
        print("ok copilot status")

        proc = subprocess.Popen(
            [str(exe), "serve", "--db", str(db)], stdout=subprocess.PIPE, stderr=subprocess.PIPE, text=True
        )
        try:
            raw_line = proc.stdout.readline() if proc.stdout else ""
            handshake = _valid_handshake(raw_line)
            if handshake is None:
                try:
                    proc.wait(timeout=5)
                except subprocess.TimeoutExpired:
                    pass
                returncode_before = proc.returncode
                if proc.poll() is None:
                    proc.terminate()
                    try:
                        proc.wait(timeout=5)
                    except subprocess.TimeoutExpired:
                        proc.kill()
                        proc.wait(timeout=5)
                outcome = (
                    "still running after 5 s, terminated"
                    if returncode_before is None
                    else f"exit code {returncode_before}"
                )
                stderr = proc.stderr.read() if proc.stderr else ""
                raise SystemExit(f"serve printed no valid handshake ({outcome}): line={raw_line!r}\nstderr:\n{stderr}")
            port, token = int(handshake["port"]), str(handshake["token"])
            print(f"ok handshake on port {port}")
            deadline = time.time() + 60
            while True:
                try:
                    status, body = _get(f"http://127.0.0.1:{port}/health")
                    if status == 200 and body.get("status") == "ok":
                        break
                except OSError:
                    pass
                if time.time() > deadline:
                    raise SystemExit("health check timed out")
                time.sleep(0.25)
            print("ok health")
            status, meta = _get(f"http://127.0.0.1:{port}/meta", token)
            if status != 200 or not meta.get("teams"):
                raise SystemExit(f"/meta failed: {status} {meta}")
            print(f"ok meta ({len(meta['teams'])} teams)")
        finally:
            proc.terminate()
            try:
                proc.wait(timeout=10)
            except subprocess.TimeoutExpired:
                proc.kill()
    return 0


if __name__ == "__main__":
    if len(sys.argv) != 2:
        raise SystemExit(__doc__)
    raise SystemExit(main(sys.argv[1]))

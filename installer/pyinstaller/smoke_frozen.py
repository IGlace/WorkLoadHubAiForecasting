"""Smoke-test a frozen service folder: version, data generation, serve handshake, health and one guarded route.

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
    with urllib.request.urlopen(req, timeout=10) as res:
        return res.status, json.loads(res.read().decode("utf-8"))


def main(dist_dir: str) -> int:
    dist = Path(dist_dir).resolve()
    exe = _exe(dist)
    version = _run(exe, "version").strip()
    print(f"ok version: {version}")
    with tempfile.TemporaryDirectory() as tmp:
        db = Path(tmp) / "smoke.db"
        _run(exe, "data", "generate", "--db", str(db), "--months", "3")
        print("ok data generate")
        proc = subprocess.Popen(
            [str(exe), "serve", "--db", str(db)], stdout=subprocess.PIPE, stderr=subprocess.PIPE, text=True
        )
        try:
            line = proc.stdout.readline() if proc.stdout else ""
            handshake = json.loads(line)
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

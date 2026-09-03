"""Where the Copilot CLI is, whether the user is signed in, and how to sign in."""

from __future__ import annotations

import asyncio
import os
import shutil
import subprocess
from collections.abc import Callable, Mapping
from dataclasses import dataclass
from pathlib import Path
from typing import Any, Literal

CliSource = Literal["environment", "path", "cache", "none"]


def get_cached_cli_path() -> str | None:
    """The CLI path the SDK itself cached, if any. Imports the SDK's private download module lazily
    (so `import whf.cli` alone never pulls in `copilot`) and tolerates that module moving or
    disappearing in a later SDK release: status reporting must degrade to "not found", not crash.
    """
    try:
        from copilot._cli_download import get_cached_cli_path as _get_cached_cli_path
    except ImportError:
        return None
    return _get_cached_cli_path()


@dataclass
class CopilotStatus:
    cli_path: str | None
    cli_source: CliSource
    authenticated: bool | None
    login: str | None
    message: str

    @property
    def ready(self) -> bool:
        return self.cli_path is not None and self.authenticated is True


def resolve_cli_path(env: Mapping[str, str] | None = None) -> tuple[str | None, CliSource]:
    env = os.environ if env is None else env
    explicit = env.get("COPILOT_CLI_PATH")
    if explicit and Path(explicit).exists():
        return explicit, "environment"
    found = shutil.which("copilot")
    if found:
        return found, "path"
    cached = get_cached_cli_path()
    if cached:
        return cached, "cache"
    return None, "none"


async def copilot_status(client_factory: Callable[[], Any] | None = None) -> CopilotStatus:
    cli_path, source = resolve_cli_path()
    if client_factory is None:
        from copilot import CopilotClient

        def client_factory() -> Any:
            return CopilotClient(log_level="error")

    client = client_factory()
    try:
        try:
            await client.start()
        except Exception as exc:
            return CopilotStatus(cli_path, source, None, None, f"Copilot CLI could not start: {exc}")
        auth = await client.get_auth_status()
        if getattr(auth, "isAuthenticated", False):
            return CopilotStatus(
                cli_path,
                source,
                True,
                getattr(auth, "login", None),
                f"signed in as {getattr(auth, 'login', None) or 'unknown user'}",
            )
        return CopilotStatus(
            cli_path, source, False, None, "not signed in: run `whf copilot login` (or `copilot login` in PowerShell)"
        )
    finally:
        try:
            await client.stop()
        except Exception:
            pass


def copilot_status_sync(client_factory: Callable[[], Any] | None = None) -> CopilotStatus:
    return asyncio.run(copilot_status(client_factory))


def login_command(cli_path: str) -> list[str]:
    return [cli_path, "login"]


def run_login(cli_path: str, runner: Callable[[list[str]], int] = subprocess.call) -> int:
    """Run the interactive device-login flow of the CLI, inheriting the terminal."""
    return int(runner(login_command(cli_path)))

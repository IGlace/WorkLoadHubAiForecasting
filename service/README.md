# whf: WorkloadHub AI Forecasting service

Python service, models and CLI. See `docs/superpowers/specs/2026-09-03-workload-forecast-design.md`.

## Develop

```powershell
cd service
uv sync            # creates .venv and installs everything incl. dev tools
uv run pytest      # tests
uv run ruff check . ; uv run ruff format --check .
uv run whf version
```

## Run

```powershell
uv run whf data generate                 # dummy data into the app data folder
uv run whf run --team 1                  # forecast one team, prints the table
uv run whf runs list ; uv run whf runs show 1 --json
uv run whf export 1 --format csv --out forecast.csv
uv run whf serve                         # prints {"port": ..., "token": ...} then serves on 127.0.0.1
```

## Copilot narrative

```powershell
uv run whf copilot status          # where the CLI is and whether you are signed in (exit 3 if not ready)
uv run whf copilot login           # device-flow sign-in in this terminal
uv run whf run --team 1 --ai       # forecast, then ask Copilot for the narrative
uv run whf narrate 1               # narrate an existing run; --json prints the stored document
```

The narrative is stored with the run (`run_narratives`) and `runs.ai_status` records `ok`, `unverified` (a number in the text is not in the facts) or `failed:<reason>`. Set `WHF_COPILOT_LIVE=1` to run the one live test.

## Build

Freeze the service into a one-folder PyInstaller build with the pinned Copilot CLI bundled, then smoke-test it:

```powershell
pwsh ../scripts/build-service.ps1     # Windows; -SkipCliDownload skips the Copilot CLI download
```

```bash
bash ../scripts/build-service.sh      # Linux/CI; WHF_SKIP_CLI_DOWNLOAD=1 skips the Copilot CLI download
```

Both run `uv run pyinstaller ... installer/pyinstaller/whf.spec`, then
`installer/pyinstaller/smoke_frozen.py` against the frozen `whf`/`whf.exe`
(version, data generation, a forecast run, `whf copilot status`, and a serve
handshake). The result lands in `service/dist/whf`; `scripts/build-installer.ps1`
embeds it into the desktop installer.

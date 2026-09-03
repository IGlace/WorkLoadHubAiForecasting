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

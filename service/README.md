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

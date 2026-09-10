# Forecast evaluation harness and Chronos-2 arrival model: design

> Superseded on 2026-09-10: the Python service and desktop app this document describes are archived on branch `archive/python-desktop-v1`. The current design is `docs/superpowers/specs/2026-09-09-java-forecast-module-design.md`.

Date: 2026-09-06. Status: approved in brainstorming, pending owner review of this document.
Inputs: the owner's decisions of 2026-09-06 (below), `docs/superpowers/specs/2026-09-03-workload-forecast-design.md`
sections 5.2, 5.5 and 5.7, `docs/research/2026-09-03-research-notes.md` section 2 and 3, and the
feasibility spike run on 2026-09-06 (section 9).

## 1. Goal

Two things, in this order:

1. An **evaluation harness** (`whf eval`) that measures, on any database, how good each arrival
   model is and how good the resulting demand forecast is, and writes the result as files that can be
   committed. It runs on the generated data today and on the real WorkloadHub export next week with the
   same command, so the model choice becomes a recorded measurement instead of an opinion.
2. A fourth arrival-model candidate, **Chronos-2** (Amazon, Apache 2.0, 120 M parameters), the
   time-series foundation model that leads the GIFT-Eval benchmark in 2026 and takes covariates natively.
   It joins the existing seasonal naive, TSB and gradient-boosting candidates; the rolling backtest still
   decides which one a run uses. Its own quantiles provide the low and high bands when it wins.

Non-goals: replacing the hand-written TSB and gradient-boosting models with Nixtla's libraries; a user
interface for evaluation results; scheduled evaluation; any change to the effort model, the placement
or the capacity arithmetic; any change to what Copilot does.

## 2. Owner decisions and constraints

- Target machines are **CPU only**, Windows, no GPU. Installer size is **not** a constraint.
- Real task history arrives **next week**; until then the harness is developed and exercised on
  generated data, and the model choice is made on the real data when it lands.
- Everything runs on Windows in PowerShell; nothing is downloaded on the user's machine at install or
  run time (the model weights ship inside the installer, like the Copilot CLI).
- The language model never produces a forecast number. Chronos-2 is a numeric model, not a language
  model; the rule is untouched. Copilot keeps reading facts and writing narrative.
- Demand is never capped by capacity; overload is reported.
- English and French everywhere in the interface: the new model gets display names in both.
- Test-driven development; property tests for arithmetic invariants.

## 3. Evaluation harness

### 3.1 Command

```
whf eval [--db PATH] [--as-of DATE] [--origins N] [--models naive,tsb,gbm,chronos2]
         [--teams 1,2,...] [--finetune] [--out DIR]
```

Defaults: the app database, today, six origins two weeks apart (the same schedule the run uses), all
registered models, all teams, no fine-tuning, `--out` = `<data dir>/eval/<as-of>/`. The command prints
the summary table and writes three files; exit code 0 when every requested model produced scores, 1
when a model was skipped (missing weights, import failure) so CI can notice.

### 3.2 Level A: arrival accuracy (per model)

The existing rolling backtest, run over every counted member of the database (not one team), for
horizons 1 and 2, reporting per model and horizon:

- MAE and MASE against seasonal naive (as today), plus the share of origins where the model beats
  the naive floor.
- Interval quality at the 10 to 90 percent band: empirical coverage (target 80 percent) and weighted
  quantile loss. For models without native quantiles the band is the residual-quantile band the run
  uses today, so the comparison is fair to what the user actually sees.
- Wall-clock seconds for fit plus predict per origin, on this CPU.

### 3.3 Level B: demand accuracy (per model, what the user sees)

For each origin and each team, the full pipeline is replayed as of that origin with the model forced
(no champion selection), producing demand hours per member and week exactly as a run would. These are
compared with the truth:

- **Generated data**: the generator's answer key, `effort_by_member_week`, is the true hours per
  member and week.
- **Real data**: realised hours per member and week, derived from completed tasks by spreading each
  task's `actual_hours` evenly over the working days between `assigned_at` and `completed_at`.
  This is an assumption (section 10, question 1); if the export carries time logs per day the harness
  uses those instead, through one function `realised_hours(conn) -> DataFrame[member_id, week_start,
  hours]` that is the only place the definition lives.

Reported per model: MAE and bias of demand hours per member-week; the same for the open-task
component alone (identical for every model, which shows how much error the arrival model can even
touch); and overload detection quality, since that is the decision the leader acts on: precision and
recall of "member is over capacity in this week" against the truth.

### 3.4 Outputs

- `scores.csv`: one row per model, horizon, origin, metric.
- `demand.csv`: one row per model, origin, team, member, week with forecast, truth, capacity.
- `summary.md`: the two tables above aggregated, the data fingerprint (row counts, date range, number
  of members and teams, SHA-256 of the database file), package versions (`whf`, `torch`,
  `chronos-forecasting`, `scikit-learn`), Chronos-2 weights revision, seed, CPU model and thread
  count, total runtime, and the list of skipped models with the reason.

Results that decide something are committed under `docs/eval/<date>-<label>/`, by hand, with a line
in `docs/backlog.md`. The harness never writes into the repository itself.

### 3.5 Fine-tuning experiment

`--finetune` adds a fifth candidate, `chronos2_ft`: Chronos-2 fine-tuned with LoRA on the training
window of each origin (the same window the other models see, so there is no leakage), a fixed small
number of steps, on CPU. It is an experiment reported by the harness, not a product feature: the run
pipeline does not fine-tune. If the real data shows a clear gain, productising it becomes a separate
decision (section 10, question 3).

## 4. Chronos-2 as an arrival model

### 4.1 Adapter

`service/src/whf/models/chronos2.py`, class `Chronos2Arrival`, `name = "chronos2"`, implementing
the existing `ArrivalModel` protocol (`fit(train, horizons)`, `predict(rows, horizon)`) so the
backtest, the champion selection and the run pipeline need no change to use it.

- `fit` keeps a reference to the training feature frame; nothing is trained (zero-shot).
- `predict(rows, horizon)` builds, for every member in `rows`, the long-format history the pipeline
  expects: one row per past week up to the row's week with the target (`est_hours`) and the past
  covariates, plus a future frame for the `horizon` target weeks with the known-future covariates.
  One call to `Chronos2Pipeline.predict_df` for all members at once (batch), `prediction_length =
  horizon`, quantile levels 0.1, 0.5, 0.9. The point forecast is the 0.5 quantile of the last step,
  clipped at zero (the spike showed negative lower quantiles on sparse series).
- Covariates come from columns the feature matrix already computes. Past covariates per week:
  `working_days`, `vacation_days`, `proj_active`, `proj_min_weeks_to_deadline`, `proj_starting`,
  `proj_ending` and the three `share_*` assignment-mode shares. The feature matrix stores the first
  six only as horizon-shifted columns (`x_h1` on the row of week w describes week w+1), so the adapter
  derives the value for week w from the `_h1` column of the preceding row of the same member; the
  first week of a member's history gets its own `_h1` value (a one-week approximation, documented in
  the code). The shares are per-row already. Known-future covariates for the target week: the
  `*_h{h}` versions of the project and availability features on the origin row. Member and team
  identity are not passed (Chronos-2 forecasts each series from its own context).
- Context: the whole available history per member, capped at the model's default context length.
- New optional protocol method `predict_quantiles(rows, horizon) -> (low, high)`; the pipeline uses
  it for the demand band when the champion provides it, otherwise the residual band as today. The
  band stays defined on the new-arrival component only, scaled by the estimate ratio, as today.

### 4.2 Loading and determinism

- One pipeline per service process, loaded lazily on first use through `whf.models.chronos2.load()`,
  and warmed up in a background thread right after `whf serve` starts so the first run does not pay
  the eight-second load.
- Weights resolution, first match wins: `WHF_CHRONOS2_PATH`; the bundled folder next to the frozen
  executable (`models/chronos-2`, see section 5); the Hugging Face cache (development only). When the
  bundled folder is used the service sets `HF_HUB_OFFLINE=1` before importing the library, so no
  network access is ever attempted on a user's machine.
- `torch.manual_seed(0)` before every predict; thread count `min(4, os.cpu_count())` so a forecast
  cannot starve the API and the app.
- If `torch` or `chronos` cannot be imported, or no weights are found, the model factory raises a
  typed `ModelUnavailable`; the backtest skips that candidate, the run continues with the other three,
  and the run facts record `models_unavailable: ["chronos2: <reason>"]` so the narrative and the UI
  can say so. A missing model is never a crash.

### 4.3 Registration and display

- Registered in `MODEL_FACTORIES` as `chronos2`; the champion rule is unchanged (a candidate must
  beat seasonal naive on mean MASE).
- Display names in the app's i18n (English "Chronos-2 (foundation model)", French "Chronos-2 (modèle
  de fondation)") wherever the champion is shown; the product skill `whf-forecast-interpretation`
  gets two factual lines on what the model is so Copilot can explain the choice without inventing.

## 5. Packaging

### 5.1 Dependencies

- `chronos-forecasting>=2.3,<3` in the service dependencies; it brings `torch`, `transformers`,
  `huggingface_hub`, `safetensors`.
- `torch` pinned to the **CPU wheel index** for every platform through `[tool.uv.sources]` (index
  `https://download.pytorch.org/whl/cpu`), so neither the developer machine, CI nor the freeze ever
  resolves the CUDA build (the spike resolved CUDA by default: 1.2 GB plus 3 GB of NVIDIA libraries).

Measured on 2026-09-06 with `torch 2.14.0+cpu` and `chronos-forecasting 2.3.1`:

| Component | Size on disk |
|---|---|
| torch CPU wheel, unpacked | 728 MB |
| Chronos-2 weights (`amazon/chronos-2`) | 478 MB |

The installer is expected to grow by roughly 600 to 800 MB after NSIS compression; the exact figure is
recorded from the first CI build.

### 5.2 Weights at build time

`scripts/build-service.ps1` and `.sh` download the weights with `huggingface_hub.snapshot_download`
at a **pinned revision** into `service/dist/whf/models/chronos-2/` (next to `copilot-cli/`), including
the licence file, unless `WHF_SKIP_MODEL_DOWNLOAD=1`. electron-builder already copies the whole frozen
folder as `extraResources`, so nothing changes in `installer/electron-builder.yml`.

### 5.3 Freeze

`installer/pyinstaller/whf.spec` collects the `chronos` package data and relies on the PyInstaller
community hooks for `torch` and `transformers`; CUDA-only modules are excluded. The frozen-service
smoke test gains one step: when the bundled weights exist, a Chronos-2 prediction on a three-month
generated database must succeed and be non-negative; when they do not (`WHF_SKIP_MODEL_DOWNLOAD=1`),
the run must complete with `chronos2` reported unavailable and the champion chosen among the other
three. Both branches are exercised: CI's `freeze-linux` job keeps the skip flag (fast, no download);
`package-windows` downloads the weights and tests the real path.

## 6. Run-time behaviour in the app

- A forecast run stays a synchronous request, as today. Measured cost of the new candidate: eight
  seconds to load once per service process (hidden by the warm-up), under one second per predict call,
  about twelve predict calls per run for the backtest, so a run grows by a few seconds. No change to
  the app's run flow; if the first real-data measurements show runs above about twenty seconds, the
  run gets the same background-plus-progress treatment the narrative already has (backlog item, not
  this design).
- The run page shows the champion's display name as before; the facts carry `models_unavailable`.

## 7. Testing

- Adapter unit tests with a stub pipeline injected (no torch in the fast suite): long-frame
  construction from the feature matrix, covariate selection, clipping, batch order preserved, quantile
  order low ≤ median ≤ high.
- Property tests (Hypothesis): predictions and quantiles are non-negative and finite for any sparse
  history; `predict_quantiles` low ≤ high.
- One `slow`-marked test that loads the real weights when present (`WHF_CHRONOS2_PATH` or the cache)
  and forecasts the generated data; skipped otherwise.
- Unavailability path: a factory raising `ModelUnavailable` is skipped by the backtest, the run
  succeeds and records the reason.
- Harness tests on a small generated database with the fast models only: file layout, metric
  arithmetic on a hand-made case (MAE, bias, coverage, precision/recall), fingerprint content, exit
  code on a skipped model.
- Frozen smoke as in 5.3; CI unchanged in shape.

## 8. Risks and mitigations

| Risk | Mitigation |
|---|---|
| PyInstaller freeze of torch on Windows breaks or bloats | Community hooks are established; `package-windows` CI job plus the smoke test are the gate; CUDA excluded by the CPU index pin |
| Installer size | Accepted by the owner; measured and recorded on the first build |
| Winner on generated data is an artefact of the generator | The harness is the deliverable; the choice is made on next week's real data and committed under `docs/eval/` |
| Realised-hours truth on real data is ill-defined | Single function `realised_hours`, assumption stated in the summary, owner question 1 |
| Hugging Face download blocked on the build machine | Build-time only, retried, skippable; the frozen service degrades to three candidates with a recorded reason |
| CPU contention between torch, uvicorn and the app | Thread cap of four; warm-up in a thread; measured run time in the summary |
| Non-determinism between runs | Seeded predict; zero-shot has no training randomness |

## 9. Feasibility spike (2026-09-06, this sandbox, CPU only)

- `chronos-forecasting 2.3.1` with `torch 2.14.0+cpu` installs from the CPU index.
- `Chronos2Pipeline.from_pretrained("amazon/chronos-2", device_map="cpu")`: 7.7 s to load; weights
  478 MB in the Hugging Face cache.
- `predict_df` on a synthetic panel of 48 members × 52 weekly points with two past covariates and a
  two-week future frame: 0.62 s for all members in one batch; output columns `id, timestamp,
  target_name, predictions, 0.1, 0.5, 0.9`; lower quantiles can be negative on sparse series.
- `Chronos2Pipeline.fit(..., finetune_mode="lora", num_steps=..., learning_rate=...)` exists for the
  fine-tuning experiment.

## 10. Open questions for the owner

1. **Truth for real data.** Does the WorkloadHub export contain hours logged per day or per week? If
   yes, the harness should use them; if no, the even spread of `actual_hours` over the task window
   stands. Default until answered: the even spread.
2. **Bundle the weights in every installer** (default, about half a gigabyte) or ship them as a
   separate optional download? Default: bundle.
3. **Fine-tuning in the product** is deferred until the harness shows a gain on real data.

## 11. Implementation order

1. Harness with the three existing models (works today, no new dependency).
2. Chronos-2 adapter, registration, tests, i18n, skill lines.
3. Dependency pin, build-time weights, freeze, smoke, CI.
4. First harness run on generated data committed under `docs/eval/`, backlog updated.

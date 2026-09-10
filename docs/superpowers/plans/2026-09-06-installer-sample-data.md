# Installer Sample Data Implementation Plan

> Superseded on 2026-09-10: the Python service and desktop app this document describes are archived on branch `archive/python-desktop-v1`. The current design is `docs/superpowers/specs/2026-09-09-java-forecast-module-design.md`.

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** The setup file loads the sample (dummy) data during installation, so the app that the installer launches at the end already has departments, teams, members and task history, and nobody has to open PowerShell.

**Architecture:** The service owns the decision: `whf data generate --if-empty` generates only when the database holds no departments and is a harmless no-op otherwise, so an upgrade or a reinstall never replaces existing data, stored runs or narratives. The installer owns the trigger: a custom NSIS include (`installer/nsis/installer.nsh`, wired through `nsis.include` in `installer/electron-builder.yml`) runs the bundled `whf.exe data generate --if-empty` in electron-builder's `customInstall` hook, after the files are copied and before the finish page launches the app. Failure to seed never fails the install; it is logged in the installer details with the manual command.

**Tech Stack:** Python 3.11 / Typer / SQLite (`service/`), PyInstaller one-folder freeze, electron-builder 26 NSIS target (assisted installer, per-user), NSIS `nsExec` plugin.

**Spec:** the owner's request of 2026-09-06 ("add loading the sample data as part of the installation of the application setup file") and the "A fresh install has no data" item in `docs/backlog.md` (under "Landed"), which describes the gap this closes. Design decisions recorded there and in this plan.

## Global Constraints

- Everything runs on Windows in PowerShell. No WSL at install or run time.
- The language model never produces a forecast number; this change does not touch forecasting.
- `data generate` without `--if-empty` keeps its current behaviour: it replaces all data.
- Test-driven development for every change. Python: `uv run pytest` in `service/`; ruff line length 120; `uv run ruff check .` and `uv run ruff format --check .` must pass.
- The frozen-service smoke test `installer/pyinstaller/smoke_frozen.py` is standard library only (it runs on the Windows build machine and on Linux CI).
- Commit messages: imperative subject, short body explaining why. Commit footer: `Co-Authored-By: Claude Fable 5.1 <noreply@anthropic.com>` and `Claude-Session: https://claude.ai/code/session_01CqJ57Eq8raMBmwPaDj5FVU`. Never `--no-verify`; the pre-commit hook may run the fast gate, so allow a long timeout.
- Branch: `dev`.
- English and French are both supported everywhere in the interface, including the installer's own messages.

---

### Task 1: `whf data generate --if-empty`

**Files:**
- Modify: `service/src/whf/cli.py:69-88` (the `data_generate` command)
- Modify: `service/src/whf/data/loader.py` (add `has_data`)
- Test: `service/tests/test_cli.py`

**Interfaces:**
- Consumes: `whf.db.connection.connect`, `whf.data.loader.load_generated`, `whf.data.loader.write_answer_key`, `whf.config.data_dir`.
- Produces: `whf.data.loader.has_data(conn: sqlite3.Connection) -> bool` (true when the `departments` table has at least one row) and the CLI option `--if-empty` on `whf data generate`. When the flag is set and `has_data` is true, the command prints `database already has data; nothing generated` and exits 0 without writing the database or the answer key. Task 2 and Task 3 rely on this exact flag name and on exit code 0 in both cases.

- [ ] **Step 1: Write the failing tests**

Append to `service/tests/test_cli.py`:

```python
def test_generate_if_empty_seeds_an_empty_database(tmp_path) -> None:
    db = tmp_path / "t.db"
    key = tmp_path / "answer_key.json"
    result = runner.invoke(
        app, ["data", "generate", "--db", str(db), "--months", "3", "--if-empty", "--answer-key", str(key)]
    )
    assert result.exit_code == 0, result.output
    assert "generated" in result.output and "tasks" in result.output
    assert key.exists()


def test_generate_if_empty_leaves_existing_data_alone(tmp_path) -> None:
    db = tmp_path / "t.db"
    key = tmp_path / "answer_key.json"
    _generate(db)
    run = runner.invoke(app, ["run", "--db", str(db), "--team", "1", "--as-of", "2026-09-03", "--json"])
    assert run.exit_code == 0, run.output
    before = db.read_bytes()
    result = runner.invoke(
        app, ["data", "generate", "--db", str(db), "--seed", "9", "--if-empty", "--answer-key", str(key)]
    )
    assert result.exit_code == 0, result.output
    assert "already has data" in result.output
    assert not key.exists()
    assert db.read_bytes() == before
    listed = runner.invoke(app, ["runs", "list", "--db", str(db)])
    assert listed.exit_code == 0 and "team   1" in listed.output


def test_generate_without_if_empty_still_replaces(tmp_path) -> None:
    db = tmp_path / "t.db"
    _generate(db)
    run = runner.invoke(app, ["run", "--db", str(db), "--team", "1", "--as-of", "2026-09-03", "--json"])
    assert run.exit_code == 0, run.output
    result = runner.invoke(
        app, ["data", "generate", "--db", str(db), "--months", "3", "--answer-key", str(tmp_path / "k.json")]
    )
    assert result.exit_code == 0, result.output
    listed = runner.invoke(app, ["runs", "list", "--db", str(db)])
    assert listed.exit_code == 0 and "team   1" not in listed.output
```

Note on `db.read_bytes() == before`: the no-op path must not even open the database for writing in a way that changes the file. Opening a SQLite connection and running a `SELECT` does not modify the file, so this assertion holds as long as no `INSERT`/`DELETE`/`UPDATE` runs. `connect()` applies the schema with `CREATE TABLE IF NOT EXISTS`, which is a no-op on an existing schema and leaves the bytes unchanged. If the assertion proves brittle on the implementer's machine (for example a `PRAGMA user_version` write in `connect`), replace it with a row-count comparison: `SELECT COUNT(*) FROM tasks` before and after through `whf.db.connection.connect`.

- [ ] **Step 2: Run the tests to verify they fail**

Run, in `service/`: `uv run pytest tests/test_cli.py -k if_empty -v`
Expected: the two `if_empty` tests FAIL with Typer's "No such option: --if-empty" (exit code 2); the third test passes already and stays as the regression guard.

- [ ] **Step 3: Add `has_data` to the loader**

In `service/src/whf/data/loader.py`, after `load_generated`:

```python
def has_data(conn: sqlite3.Connection) -> bool:
    """True when the database already holds organisational data (at least one department)."""
    row = conn.execute("SELECT COUNT(*) FROM departments").fetchone()
    return bool(row and row[0] > 0)
```

- [ ] **Step 4: Add the flag to the CLI**

In `service/src/whf/cli.py`, change the import line `from whf.data.loader import load_generated, write_answer_key` to `from whf.data.loader import has_data, load_generated, write_answer_key`, then replace the `data_generate` command with:

```python
@data_app.command("generate")
def data_generate(
    db: DbOption = None,
    seed: int = 42,
    months: int = 12,
    as_of: Annotated[str | None, typer.Option("--as-of")] = None,
    answer_key: Annotated[Path | None, typer.Option("--answer-key")] = None,
    if_empty: Annotated[
        bool, typer.Option("--if-empty", help="Do nothing when the database already has data (installer use)")
    ] = False,
) -> None:
    """Generate dummy data (replaces existing data in the database unless --if-empty)."""
    conn = _conn(db)
    if if_empty and has_data(conn):
        typer.echo("database already has data; nothing generated")
        return
    config = GeneratorConfig(seed=seed, months=months, as_of=_date(as_of) or GeneratorConfig().as_of)
    data = generate(config)
    load_generated(conn, data)
    key_path = answer_key or (data_dir() / "answer_key.json")
    write_answer_key(key_path, data)
    typer.echo(
        f"generated {len(data.members)} members, {len(data.teams)} teams, {len(data.projects)} projects, "
        f"{len(data.tasks)} tasks; answer key at {key_path}"
    )
```

The connection is opened before generation in both paths so the no-op check does not generate 6,000 tasks for nothing.

- [ ] **Step 5: Run the tests to verify they pass**

Run, in `service/`: `uv run pytest tests/test_cli.py -v` then `uv run ruff check . && uv run ruff format --check .`
Expected: all PASS, ruff clean.

- [ ] **Step 6: Commit**

```bash
git add service/src/whf/cli.py service/src/whf/data/loader.py service/tests/test_cli.py
git commit -m "feat(service): add --if-empty to data generate

The installer will seed the sample data at install time; the guard makes
that safe on an upgrade or a reinstall, where the database already holds
data, stored runs and narratives that must not be replaced."
```

---

### Task 2: Prove the frozen service honours `--if-empty`

**Files:**
- Modify: `installer/pyinstaller/smoke_frozen.py:100-105` (the `data generate` step in `main`)

**Interfaces:**
- Consumes: `whf.exe data generate --db <path> --months 3 --if-empty` from Task 1 (exit 0; prints `generated ...` on the first call and `database already has data; nothing generated` on the second).
- Produces: nothing new; the smoke test that `scripts/build-service.{ps1,sh}` and CI run now covers the installer's call.

- [ ] **Step 1: Extend the smoke test**

In `installer/pyinstaller/smoke_frozen.py`, replace

```python
        _run(exe, "data", "generate", "--db", str(db), "--months", "3")
        print("ok data generate")
```

with

```python
        key = Path(tmp) / "answer_key.json"
        first = _run(exe, "data", "generate", "--db", str(db), "--months", "3", "--if-empty", "--answer-key", str(key))
        if "generated" not in first:
            raise SystemExit(f"data generate --if-empty on an empty database did not generate: {first}")
        print("ok data generate --if-empty (seeded)")
        # The installer calls exactly this on every install; on an upgrade the database is already populated.
        second = _run(exe, "data", "generate", "--db", str(db), "--months", "3", "--if-empty", "--answer-key", str(key))
        if "already has data" not in second:
            raise SystemExit(f"data generate --if-empty on a populated database did not stay a no-op: {second}")
        print("ok data generate --if-empty (no-op)")
```

The `--answer-key` keeps the smoke run from writing `answer_key.json` into the build machine's real data folder.

- [ ] **Step 2: Verify the change against a real frozen build when one is available**

If `service/dist/whf/whf` (Linux) or `whf.exe` (Windows) exists from a previous `scripts/build-service.{sh,ps1}` run and predates Task 1, it will fail with "No such option: --if-empty": that is the expected "red" state. Rebuild with `bash scripts/build-service.sh` (Linux; set `WHF_SKIP_CLI_DOWNLOAD=1` if the Copilot CLI download is not possible) or `pwsh scripts/build-service.ps1` (Windows), which reruns the smoke test at the end.
Expected: the build prints `ok data generate --if-empty (seeded)` and `ok data generate --if-empty (no-op)` and ends with `service frozen at ...`.

If no freeze can be built in the current environment, at least run the same two commands through the dev CLI to check the strings: `cd service && uv run whf data generate --db /tmp/x.db --months 3 --if-empty --answer-key /tmp/k.json` twice, and run `uv run ruff check ../installer/pyinstaller/smoke_frozen.py` (ruff is configured for `service/`; the smoke file is outside it, so also run `python -m py_compile installer/pyinstaller/smoke_frozen.py` from the repository root).

- [ ] **Step 3: Commit**

```bash
git add installer/pyinstaller/smoke_frozen.py
git commit -m "test(installer): smoke the --if-empty seeding path of the frozen service

The installer calls whf.exe data generate --if-empty on every install, so
the frozen-service smoke test now runs it twice: once on an empty
database (must seed) and once on a populated one (must not touch it)."
```

---

### Task 3: Seed from the NSIS installer

**Files:**
- Create: `installer/nsis/installer.nsh`
- Modify: `installer/electron-builder.yml` (the `nsis:` block)

**Interfaces:**
- Consumes: `$INSTDIR\resources\service\whf\whf.exe data generate --if-empty` from Task 1 (exit 0 whether it seeds or not; non-zero only on a real failure).
- Produces: an installer that seeds the database; Task 4 documents it.

**Execution notes (2026-09-06):** the numeric locale IDs 1033/1036 are used because this include is
compiled before `installer.nsi` loads MUI2 and defines `LANG_ENGLISH`/`LANG_FRENCH`; `installerLanguages`
must stay exactly `[en_US, fr_FR]` in `installer/electron-builder.yml` because electron-builder compiles
every bundled language under `-WX`, and a language without a `whfSeed*` LangString fails the build; the
loading line goes to the status text above the progress bar (`SetDetailsPrint textonly`), not the details
log, because electron-builder's own templates suppress `DetailPrint` output for the whole install section,
and a failure shows a message box instead, for the same reason — there is no "Sample data ready." line;
an elevated ("for all users") install skips seeding altogether and shows an information box with the
manual command, because `%LOCALAPPDATA%` there is the administrator's profile, not the installing user's.
The committed `installer/nsis/installer.nsh` is the authority over the code block below, which is kept for
historical context and no longer matches it exactly.

Background for the implementer (electron-builder 26.15.3, `app/node_modules/app-builder-lib`):
- `nsis.include` is resolved by `PlatformPackager.getResource`: first relative to the build resources directory (`app/resources`), then relative to the project directory (`app/`). So `../installer/nsis/installer.nsh` resolves to `installer/nsis/installer.nsh` from the repository root.
- `templates/nsis/installSection.nsh` inserts `customInstall` after `installApplicationFiles` and the shortcuts, inside the install section; the assisted installer's finish page (`MUI_FINISHPAGE_RUN`, enabled by `runAfterFinish: true`) launches the app only after the section completes, so the app never starts before seeding finishes.
- The installer is per-user (`perMachine: false`), so it runs as the user and `whf.exe` resolves `%LOCALAPPDATA%\WorkloadHubForecast\whf.db` exactly as the app does. Do not pass `--db`.
- `nsExec::ExecToLog` (stock NSIS plugin, already used by electron-builder's own templates as `nsExec::Exec`) runs a console program without showing a console window and streams its output into the installer's details log; it pushes the exit code, or the string `error` when the program could not be started, onto the stack.
- electron-builder builds a multi-language installer by default; `en_US` (1033) and `fr_FR` (1036) are both in its bundled list, so `LangString` entries for both languages compile.

- [ ] **Step 1: Write the NSIS include**

Create `installer/nsis/installer.nsh`:

```nsis
; Custom NSIS steps for the WorkloadHub Forecast installer (electron-builder `nsis.include`).
;
; customInstall runs inside the install section, after the application files and shortcuts are in
; place and before the finish page offers to launch the app. It loads the sample data with the bundled
; frozen service. `--if-empty` makes the call a no-op when %LOCALAPPDATA%\WorkloadHubForecast\whf.db
; already holds data (upgrade, reinstall), so stored runs and narratives are never replaced.
; A failure here never fails the installation: it is written to the details log with the manual command.

LangString whfSeedStart ${LANG_ENGLISH} "Loading the sample data (this takes a moment)..."
LangString whfSeedStart ${LANG_FRENCH} "Chargement des données d'exemple (cela prend un instant)..."
LangString whfSeedDone ${LANG_ENGLISH} "Sample data ready."
LangString whfSeedDone ${LANG_FRENCH} "Données d'exemple prêtes."
LangString whfSeedFailed ${LANG_ENGLISH} "Could not load the sample data (code $0). The app will start empty; run this in PowerShell, then restart the app: & '$INSTDIR\resources\service\whf\whf.exe' data generate"
LangString whfSeedFailed ${LANG_FRENCH} "Impossible de charger les données d'exemple (code $0). L'application démarrera vide ; exécutez ceci dans PowerShell, puis redémarrez l'application : & '$INSTDIR\resources\service\whf\whf.exe' data generate"

!macro customInstall
  DetailPrint "$(whfSeedStart)"
  nsExec::ExecToLog '"$INSTDIR\resources\service\whf\whf.exe" data generate --if-empty'
  Pop $0
  ${If} $0 == "0"
    DetailPrint "$(whfSeedDone)"
  ${Else}
    DetailPrint "$(whfSeedFailed)"
  ${EndIf}
!macroend
```

Keep the file UTF-8 encoded; electron-builder compiles its NSIS scripts in Unicode mode, so the French accents survive. `LogicLib` (`${If}`) is already included by electron-builder's `common.nsh`.

- [ ] **Step 2: Wire it into electron-builder**

In `installer/electron-builder.yml`, add one line to the `nsis:` block (keep the existing keys):

```yaml
nsis:
  include: ../installer/nsis/installer.nsh
  oneClick: false
  perMachine: false
  allowToChangeInstallationDirectory: true
  createDesktopShortcut: true
  createStartMenuShortcut: true
  shortcutName: WorkloadHub Forecast
  runAfterFinish: true
  deleteAppDataOnUninstall: false
```

Also update the first comment line of `installer/electron-builder.yml` so it mentions the include: `# electron-builder configuration. Run from app/: npm run build:win (Windows) or npm run pack:dir (any OS, unpacked). Custom NSIS steps live in installer/nsis/installer.nsh.`

- [ ] **Step 3: Verify the installer still builds**

On Windows: `pwsh scripts/build-installer.ps1` (the full pipeline; needs the frozen service from Task 2). On Linux, electron-builder can compile the NSIS installer too (it downloads its own `makensis`; no Wine is needed for NSIS with electron-builder 26): with `app/out/` built (`npm run build` in `app/`) and `service/dist/whf` present, run in `app/`: `npx electron-builder --win nsis --x64 --config ../installer/electron-builder.yml`.
Expected: `dist/installer/WorkloadHub-Forecast-Setup-<version>.exe` is produced and the build log shows no NSIS warning about `whfSeed*` or `customInstall`. If the NSIS toolchain cannot be downloaded in the current environment, say so in the report; the `package-windows` job in `.github/workflows/ci.yml` compiles the same script on every push to `dev`.

Either way, run `npm run lint && npm run typecheck && npm test` in `app/` to confirm nothing else moved (nothing should: the change is configuration only).

- [ ] **Step 4: Commit**

```bash
git add installer/nsis/installer.nsh installer/electron-builder.yml
git commit -m "feat(installer): load the sample data during installation

A fresh install had an empty database and the first-run checklist told
the owner to run whf.exe data generate by hand. The setup file now runs
it itself, guarded by --if-empty so upgrades keep existing data."
```

---

### Task 4: Documentation

**Files:**
- Modify: `installer/README.md` (the "What it contains" list, the "First-run checklist" steps 1 to 3, the "Data, logs and uninstall" list)
- Modify: `docs/backlog.md` (the "A fresh install has no data" item under "Landed")

**Interfaces:**
- Consumes: the behaviour delivered by Tasks 1 to 3.
- Produces: nothing; documentation only.

- [ ] **Step 1: Update the installer README**

In `installer/README.md`:

1. In "What it contains", add a bullet after the Copilot CLI bullet:

   ```markdown
   - A custom NSIS step (`installer/nsis/installer.nsh`, wired in through `nsis.include`)
     that runs the bundled `whf.exe data generate --if-empty` at the end of the
     installation, so a fresh install starts with the sample data. `--if-empty`
     makes it a no-op when the database already holds data, so an upgrade or a
     reinstall keeps existing data, stored runs and narratives.
   ```

2. Replace steps 1 to 3 of the first-run checklist with:

   ```markdown
   1. Copy `WorkloadHub-Forecast-Setup-<version>.exe` to the target machine and
      run it. It installs per user (no administrator prompt) into the user's
      local app data and adds Desktop and Start Menu shortcuts. Near the end,
      the details log shows "Loading the sample data": the installer runs the
      bundled service once to fill the database (3 departments, 8 teams, 48
      members, 12 months of task history, plus `answer_key.json` beside the
      database, the ground truth the backtest compares against). When the
      installer finishes it launches the app itself (`runAfterFinish: true` in
      `installer/electron-builder.yml`).
   2. If the details log said the sample data could not be loaded, run the
      same command by hand and restart the app afterwards (it reads the
      database at startup):

      ```powershell
      & "$env:LOCALAPPDATA\Programs\WorkloadHub Forecast\resources\service\whf\whf.exe" data generate
      ```

      Adjust the path if the install directory was changed
      (`allowToChangeInstallationDirectory: true`). Without `--if-empty`,
      `data generate` **replaces** all existing data, including stored runs
      and their narratives, so never run it after a forecast you want to keep.
   3. Start the app from the Start Menu if the installer did not launch it.
   ```

   Keep steps 4 to 9 as they are.

3. In "Data, logs and uninstall", extend the answer-key bullet so it reads: "Answer key for the generated data: `%LOCALAPPDATA%\WorkloadHubForecast\answer_key.json`, written by the installer's seeding step and rewritten by every `data generate` so it always describes the data currently in the database".

4. In the uninstall paragraph, add one sentence at the end: "Because the folder survives, reinstalling over it keeps the data: the installer's seeding step sees a populated database and does nothing."

- [ ] **Step 2: Update the backlog**

In `docs/backlog.md`, in the "A fresh install has no data" item under "Landed", replace the last three sentences (from "Fixed as documentation only" to "has to install it.") with:

```markdown
Fixed as documentation first (a new step 2 in the first-run checklist), then properly on 2026-09-06 at the
owner's request: the setup file now loads the sample data itself. `whf data generate --if-empty` generates
only when the database holds no departments, and a custom NSIS step (`installer/nsis/installer.nsh`, run by
electron-builder's `customInstall` hook after the files are copied and before the finish page launches the
app) calls it through the bundled `whf.exe`. So a fresh install starts populated, an upgrade or a reinstall
keeps its data, runs and narratives, and a seeding failure is logged in the installer details with the
manual command rather than failing the install. The frozen-service smoke test exercises both paths of
`--if-empty`. Still open, lower priority now: an in-app "Load sample data" action in Settings (guarded by a
confirmation because it replaces everything) for someone who wants to reset to fresh data without
reinstalling.
```

Also update the state line near the top of the backlog if it mentions the seeding item as undecided (search for "seeding item below" and make the sentence say the installer now seeds the data).

- [ ] **Step 3: Read both files once end to end**

Check that no sentence still says nothing seeds the database, that step numbers stay continuous, and that the French/English rule is not contradicted.

- [ ] **Step 4: Commit**

```bash
git add installer/README.md docs/backlog.md
git commit -m "docs: the installer now loads the sample data

Update the first-run checklist and the backlog entry that recorded the
empty-database gap and the open decision it depended on."
```

---

## Verification before release

After the four tasks: run the fast gate (`uv run ruff check . && uv run ruff format --check . && uv run pytest -q -m "not slow" -n 6` in `service/`; `npm run lint && npm run typecheck && npm test` in `app/`), rebuild the frozen service and its smoke test, then push `dev` and fast-forward `main` once CI on `dev` (including `package-windows`, which compiles the NSIS script) is green. The owner's Windows verification: run the produced setup on a machine without `%LOCALAPPDATA%\WorkloadHubForecast`, choosing the default per-user install; while it runs, the status text above the progress bar briefly shows "Loading the sample data (this takes a moment)..."; no message box should appear; the launched app offers departments, teams and members in Settings; then run the setup a second time and confirm the data (and any stored run) is unchanged; optionally run it once more choosing "for all users" and confirm the information box with the manual command appears.

---

### Task 5: `whf copilot status` survives a missing Copilot CLI (CI `freeze-linux` fix)

Added during execution. The `freeze-linux` CI job builds the service with `WHF_SKIP_CLI_DOWNLOAD=1` and then runs `installer/pyinstaller/smoke_frozen.py`, whose `whf copilot status --json` step accepts exit codes 0 and 3 only. On a machine with no Copilot CLI at all (no `COPILOT_CLI_PATH`, none on `PATH`, none in the SDK cache) the job has failed on every push since the remote came back (runs for fea509e, dac2bc8, 957aca8, 5ef12cd): `CopilotClient(...)` raises `RuntimeError: Copilot CLI not found ...` from its constructor, which `whf.ai.status.copilot_status` calls *outside* the `try` that guards `client.start()`, so the frozen binary prints a traceback and exits 1. Locally it passes only because this machine has the SDK's cached CLI. The same traceback would greet a user whose bundled CLI is missing, so the fix belongs in the service, not in CI.

**Files:**
- Modify: `service/src/whf/ai/status.py` (the `copilot_status` coroutine)
- Test: `service/tests/test_ai_status.py`

**Interfaces:**
- Consumes: `CopilotStatus`, `resolve_cli_path` (unchanged).
- Produces: `copilot_status` returns a `CopilotStatus` with `code="start_failed"`, `authenticated=None` and a message that starts with `Copilot CLI could not start:` when the client factory itself raises; `whf copilot status` therefore exits 3 instead of crashing.

- [ ] **Step 1: Write the failing test**

Append to `service/tests/test_ai_status.py` (the `FakeClient` import and `copilot_status_sync` are already imported at the top of the file):

```python
def test_status_when_the_sdk_cannot_even_construct_a_client(monkeypatch) -> None:
    """With no CLI anywhere, CopilotClient() raises from its constructor, before start(): still exit 3, no traceback."""
    monkeypatch.delenv("COPILOT_CLI_PATH", raising=False)
    monkeypatch.setattr("whf.ai.status.shutil.which", lambda name: None)
    monkeypatch.setattr("whf.ai.status.get_cached_cli_path", lambda: None)

    def factory() -> FakeClient:
        raise RuntimeError("Copilot CLI not found. Install a published wheel ...")

    status = copilot_status_sync(client_factory=factory)
    assert status.cli_path is None and status.cli_source == "none"
    assert status.authenticated is None and not status.ready
    assert status.code == "start_failed"
    assert status.message.startswith("Copilot CLI could not start:") and "not found" in status.message
```

- [ ] **Step 2: Run it to verify it fails**

Run, in `service/`: `uv run pytest tests/test_ai_status.py -k cannot_even_construct -v`
Expected: FAIL with `RuntimeError: Copilot CLI not found ...` escaping from `copilot_status_sync`.

- [ ] **Step 3: Guard the construction**

In `service/src/whf/ai/status.py`, replace the lines

```python
    client = client_factory()
    try:
        try:
            await client.start()
        except Exception as exc:
            return CopilotStatus(cli_path, source, None, None, f"Copilot CLI could not start: {exc}", "start_failed")
```

with

```python
    try:
        # The SDK resolves its CLI in the constructor and raises RuntimeError when there is none anywhere.
        client = client_factory()
        await client.start()
    except Exception as exc:
        return CopilotStatus(cli_path, source, None, None, f"Copilot CLI could not start: {exc}", "start_failed")
    try:
```

so the rest of the function (the `get_auth_status` call and the `finally: client.stop()` block) is unchanged and still only runs with a constructed, started client.

- [ ] **Step 4: Run the tests to verify they pass**

Run, in `service/`: `uv run pytest tests/test_ai_status.py tests/test_ai_cli_api.py -v` then `uv run ruff check . && uv run ruff format --check .`
Expected: all PASS, ruff clean.

- [ ] **Step 5: Reproduce the CI failure against the frozen build, then see it pass**

The frozen service at `service/dist/whf` (Linux) was built before this fix. Run, from the repository root, with the SDK's cache and any CLI hidden the way CI has none:

```bash
HOME=$(mktemp -d) PATH=/usr/bin:/bin COPILOT_SKIP_CLI_DOWNLOAD=1 service/dist/whf/whf copilot status --json; echo "exit=$?"
```

Expected before the rebuild: a traceback and `exit=1` (the CI failure). Then rebuild with `WHF_SKIP_CLI_DOWNLOAD=1 bash scripts/build-service.sh` (which re-runs the smoke test; timeout at least 600000 ms) and repeat the command. Expected: one JSON line with `"code": "start_failed"` and `exit=3`, and the smoke test's `ok copilot status` line during the build. Note: with the cache hidden the smoke test's own `copilot status` step also runs without a CLI, which is exactly the CI condition; if the build machine still finds a CLI on `PATH`, hide it the same way for the smoke command: `HOME=$(mktemp -d) PATH=/usr/bin:/bin uv run --directory service python ../installer/pyinstaller/smoke_frozen.py ../service/dist/whf` (the `uv` binary must stay reachable; use its absolute path if needed).

- [ ] **Step 6: Commit**

```bash
git add service/src/whf/ai/status.py service/tests/test_ai_status.py
git commit -m "fix(service): report a missing Copilot CLI as a status, not a traceback

The SDK raises from CopilotClient's constructor when no CLI exists
anywhere; copilot_status only guarded start(), so the frozen binary
crashed with exit 1. The freeze-linux CI job, which skips the CLI
download, has failed on every push for this reason."
```

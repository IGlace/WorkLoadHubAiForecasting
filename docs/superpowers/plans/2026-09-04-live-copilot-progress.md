# Live Copilot progress Implementation Plan

> Superseded on 2026-09-10: the Python service and desktop app this document describes are archived on branch `archive/python-desktop-v1`. The current design is `docs/superpowers/specs/2026-09-09-java-forecast-module-design.md`.

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** While the Run page waits for the Copilot narrative, show what Copilot is actually doing right now instead of a single frozen "Asking Copilot…" line.

**Architecture:** The narrator already emits progress strings through a `progress` callback that only the CLI uses. Turn those strings into language-independent `ProgressEvent`s, have the API record them per run in a small bounded in-memory store while the narrative POST is running, expose them on `GET /runs/{run_id}/narrative/progress`, and let the renderer poll that route once a second and phrase each step itself in English or French. No streaming, no new IPC channel: the narrative route is a sync `def`, so FastAPI runs it in a threadpool and the poll is served concurrently.

**Tech Stack:** Python 3.11 (FastAPI, Typer, pytest), Electron + React + TypeScript (vitest, @testing-library/react).

**Spec:** `docs/superpowers/specs/2026-09-03-workload-forecast-design.md`; the item is "Live progress for the Copilot narrative" in `docs/backlog.md` ("Approved, not yet built"), owner decision 2026-09-04: "Add it".

## Global Constraints

- The language model never produces a forecast number; this feature only reports what the session is doing. No forecast arithmetic changes.
- English and French are both fully supported everywhere. Every new user-visible string exists in both `en` and `fr` in `app/src/renderer/src/i18n.ts`; `untranslatedKeys('en')` and `untranslatedKeys('fr')` must stay empty (asserted in `app/src/renderer/src/__tests__/i18n.test.ts`).
- Prose never crosses a process boundary for translation. The service returns a language-independent `code` plus optional technical `detail`; the renderer supplies the wording. This mirrors `CopilotStatus.code` and `LoginResult.code`.
- Test-driven development: write the failing test, watch it fail, then implement.
- Everything runs on Windows in PowerShell. No new dependency in either `service/pyproject.toml` or `app/package.json`.
- Commands: `uv run pytest` in `service/`; `npm test`, `npm run lint`, `npm run typecheck` in `app/`. `npx` must be run from inside `app/` (the machine-wide npm registry needs `app/.npmrc`).
- Commit messages: imperative subject, short body explaining why.

---

### Task 1: `ProgressEvent` and `ProgressStore`

**Files:**
- Create: `service/src/whf/ai/progress.py`
- Test: `service/tests/test_ai_progress.py`

**Interfaces:**
- Consumes: nothing.
- Produces: `ProgressCode` (a `Literal`), `ProgressEvent(code, detail=None)` with a `.message` property, and `ProgressStore` with `begin(run_id)`, `record(run_id, event)`, `steps(run_id) -> list[dict]`. Tasks 2 and 3 use both.

- [ ] **Step 1: Write the failing tests**

`service/tests/test_ai_progress.py`:

```python
"""The narrative POST blocks for a minute or more, so the app polls these steps while it waits."""

from whf.ai.progress import ProgressEvent, ProgressStore


class TestProgressEvent:
    def test_a_static_code_has_an_english_message_for_the_cli(self) -> None:
        assert ProgressEvent("starting").message == "starting Copilot"

    def test_a_tool_call_names_the_tool(self) -> None:
        assert ProgressEvent("tool", "team_overview").message == "tool team_overview"

    def test_an_attempt_is_numbered(self) -> None:
        assert ProgressEvent("asking", "2").message == "asking Copilot (attempt 2)"


class TestProgressStore:
    def test_a_run_with_no_narration_has_no_steps(self) -> None:
        assert ProgressStore().steps(1) == []

    def test_records_code_detail_and_a_timestamp_in_order(self) -> None:
        store = ProgressStore()
        store.begin(7)
        store.record(7, ProgressEvent("starting"))
        store.record(7, ProgressEvent("tool", "team_overview"))
        steps = store.steps(7)
        assert [(s["code"], s["detail"]) for s in steps] == [("starting", None), ("tool", "team_overview")]
        assert all(s["at"] for s in steps)

    def test_begin_clears_the_steps_of_an_earlier_narration_of_the_same_run(self) -> None:
        """Narrating a run twice must not show the first attempt's steps under the second."""
        store = ProgressStore()
        store.begin(7)
        store.record(7, ProgressEvent("starting"))
        store.begin(7)
        assert store.steps(7) == []

    def test_keeps_only_the_most_recent_steps_of_a_run(self) -> None:
        store = ProgressStore(keep_steps=3)
        store.begin(7)
        for name in ("a", "b", "c", "d"):
            store.record(7, ProgressEvent("tool", name))
        assert [s["detail"] for s in store.steps(7)] == ["b", "c", "d"]

    def test_forgets_the_oldest_runs(self) -> None:
        """The service can run for weeks; the store must not grow one step list per run forever."""
        store = ProgressStore(keep_runs=2)
        for run_id in (1, 2, 3):
            store.begin(run_id)
            store.record(run_id, ProgressEvent("starting"))
        assert store.steps(1) == []
        assert [s["code"] for s in store.steps(3)] == ["starting"]

    def test_recording_without_begin_still_works(self) -> None:
        store = ProgressStore()
        store.record(7, ProgressEvent("starting"))
        assert [s["code"] for s in store.steps(7)] == ["starting"]

    def test_steps_are_a_copy_so_a_caller_cannot_mutate_the_store(self) -> None:
        store = ProgressStore()
        store.record(7, ProgressEvent("starting"))
        store.steps(7).clear()
        assert len(store.steps(7)) == 1
```

- [ ] **Step 2: Run the tests and watch them fail**

Run: `cd service && uv run pytest tests/test_ai_progress.py -q`
Expected: FAIL, `ModuleNotFoundError: No module named 'whf.ai.progress'`.

- [ ] **Step 3: Write the implementation**

`service/src/whf/ai/progress.py`:

```python
"""What a Copilot narration is doing right now, for a caller that has to wait for it."""

from __future__ import annotations

import datetime as dt
import threading
from collections import OrderedDict, deque
from dataclasses import dataclass
from typing import Literal

# What the session is doing, independent of language. The desktop app phrases each of these in
# English or French itself; `message` is the English wording, for the CLI and the logs.
ProgressCode = Literal["starting", "session", "asking", "tool", "checking"]

_MESSAGES: dict[str, str] = {
    "starting": "starting Copilot",
    "session": "creating session",
    "checking": "checking the answer against the facts",
}


@dataclass(frozen=True)
class ProgressEvent:
    code: ProgressCode
    detail: str | None = None

    @property
    def message(self) -> str:
        if self.code == "tool":
            return f"tool {self.detail}" if self.detail else "tool"
        if self.code == "asking":
            return f"asking Copilot (attempt {self.detail})" if self.detail else "asking Copilot"
        return _MESSAGES[self.code]


class ProgressStore:
    """The steps of the narrations that are running or recently finished, in memory only.

    Bounded on both axes: the service can run for weeks, so it keeps the last few runs and the last
    few steps of each. Locked because the narration runs in FastAPI's threadpool while the polling
    request that reads it is served on another thread.
    """

    def __init__(self, keep_runs: int = 8, keep_steps: int = 50) -> None:
        self._keep_runs = keep_runs
        self._keep_steps = keep_steps
        self._runs: OrderedDict[int, deque[dict]] = OrderedDict()
        self._lock = threading.Lock()

    def begin(self, run_id: int) -> None:
        """Start a fresh list for this run, so a second narration does not show the first one's steps."""
        with self._lock:
            self._runs.pop(run_id, None)
            self._fresh(run_id)

    def record(self, run_id: int, event: ProgressEvent) -> None:
        with self._lock:
            steps = self._runs.get(run_id) or self._fresh(run_id)
            steps.append(
                {
                    "code": event.code,
                    "detail": event.detail,
                    "at": dt.datetime.now().isoformat(timespec="seconds"),
                }
            )

    def steps(self, run_id: int) -> list[dict]:
        with self._lock:
            return [dict(step) for step in self._runs.get(run_id, ())]

    def _fresh(self, run_id: int) -> deque[dict]:
        steps: deque[dict] = deque(maxlen=self._keep_steps)
        self._runs[run_id] = steps
        while len(self._runs) > self._keep_runs:
            self._runs.popitem(last=False)
        return steps
```

- [ ] **Step 4: Run the tests and watch them pass**

Run: `cd service && uv run pytest tests/test_ai_progress.py -q`
Expected: PASS (9 tests). Then `uv run ruff check . && uv run ruff format .`

- [ ] **Step 5: Commit**

```bash
git add service/src/whf/ai/progress.py service/tests/test_ai_progress.py
git commit -m "Add progress events and a bounded store for Copilot narration"
```

---

### Task 2: The narrator emits `ProgressEvent`s

**Files:**
- Modify: `service/src/whf/ai/session.py` (the `Narrator` protocol, `narrate_sync`, `narrate`)
- Modify: `service/src/whf/narrate.py:23-28` (the `progress` parameter type)
- Modify: `service/src/whf/cli.py:140` and `service/src/whf/cli.py:182` (the two `typer.echo` lambdas)
- Modify: `service/tests/ai_fakes.py:141-144` (the fake narrator's progress call)
- Test: `service/tests/test_ai_progress.py` (append), `service/tests/test_narrate.py:28` (adjust to the new type)

**Interfaces:**
- Consumes: `ProgressEvent` from Task 1.
- Produces: `progress: Callable[[ProgressEvent], None] | None` everywhere the old `Callable[[str], None]` was. Task 3 passes a `ProgressStore.record` binding into it.

- [ ] **Step 1: Write the failing test**

Append to `service/tests/test_ai_progress.py`:

```python
class TestTheNarratorReportsEvents:
    def test_a_narration_reports_coded_steps_not_prose(self) -> None:
        """The desktop app has to phrase each step in the user's language, so it needs codes."""
        from ai_fakes import FakeNarrator

        seen: list[ProgressEvent] = []
        FakeNarrator().narrate_sync({}, seen.append)
        assert [e.code for e in seen] == ["asking"]
```

and adjust `service/tests/test_narrate.py:28`, which collects progress with `seen.append`, so its assertion reads the events' codes rather than strings. Read that test first and keep its intent.

- [ ] **Step 2: Run the tests and watch them fail**

Run: `cd service && uv run pytest tests/test_ai_progress.py tests/test_narrate.py -q`
Expected: FAIL — the fake narrator still reports the string `"fake narrator"`.

- [ ] **Step 3: Change the callers**

In `service/src/whf/ai/session.py`: import `ProgressEvent` from `whf.ai.progress`; change the three `progress: Callable[[str], None] | None` annotations (the `Narrator` protocol, `narrate_sync`, `narrate`) to `Callable[[ProgressEvent], None] | None`; replace the `say = progress or (lambda _msg: None)` helper with one that takes a code and an optional detail:

```python
        emit = progress or (lambda _event: None)

        def say(code: ProgressCode, detail: str | None = None) -> None:
            emit(ProgressEvent(code, detail))
```

and change the five call sites to codes, keeping them where they are today:
- `say("starting Copilot")` → `say("starting")`
- `say(f"tool {event.data.tool_name}")` → `say("tool", event.data.tool_name)`
- `say("creating session")` → `say("session")`
- `say(f"asking Copilot (attempt {attempt})")` → `say("asking", str(attempt))`
- add `say("checking")` immediately after `raw = self._content_of(event, state)`, before the narrative is parsed and verified — that gap is otherwise silent while the checks run.

In `service/src/whf/narrate.py`, change the `progress` parameter to `Callable[[ProgressEvent], None] | None` and import `ProgressEvent`.

In `service/src/whf/cli.py`, both lambdas become `lambda e: typer.echo(f"  {e.message}")`, so the CLI prints exactly what it printed before.

In `service/tests/ai_fakes.py`, the fake reports `progress(ProgressEvent("asking", "1"))`.

- [ ] **Step 4: Run the tests and watch them pass**

Run: `cd service && uv run pytest tests/test_ai_progress.py tests/test_narrate.py tests/test_cli.py tests/test_ai_session.py -q`
Expected: PASS. Then `uv run ruff check . && uv run ruff format --check .`

- [ ] **Step 5: Commit**

```bash
git add service/src/whf service/tests
git commit -m "Report narration progress as coded events"
```

---

### Task 3: The API records and serves progress

**Files:**
- Modify: `service/src/whf/api.py` (`create_app`: a `ProgressStore` instance, `create_narrative`, one new route)
- Test: `service/tests/test_ai_cli_api.py` (append; it already builds an app with a fake narrator)

**Interfaces:**
- Consumes: `ProgressStore` (Task 1), the `progress` callback (Task 2).
- Produces: `GET /runs/{run_id}/narrative/progress` → `{"run_id": int, "steps": [{"code": str, "detail": str | null, "at": str}]}`. Task 4 types it and Task 5 renders it.

- [ ] **Step 1: Write the failing tests**

Append to `service/tests/test_ai_cli_api.py` (read the file first: it already has an app fixture with a fake narrator and a token header helper; reuse them rather than building new ones):

```python
def test_narrating_records_the_steps_the_app_polls_for(client) -> None:
    """The POST blocks while Copilot works, so the app polls this route to show what is happening."""
    run_id = <create a run through the existing helper in this file>
    assert client.get(f"/runs/{run_id}/narrative/progress").json()["steps"] == []
    client.post(f"/runs/{run_id}/narrative", json={"model": None})
    steps = client.get(f"/runs/{run_id}/narrative/progress").json()["steps"]
    assert [s["code"] for s in steps] == ["asking"]


def test_progress_of_an_unknown_run_is_empty_rather_than_an_error(client) -> None:
    """The app polls as soon as it sends the POST; a poll that arrives first must not be a 404."""
    assert client.get("/runs/999/narrative/progress").json() == {"run_id": 999, "steps": []}


def test_the_progress_route_needs_the_token(client_without_token) -> None:
    assert client_without_token.get("/runs/1/narrative/progress").status_code == 401
```

Match the file's existing fixtures and naming; the three assertions above are the requirement.

- [ ] **Step 2: Run the tests and watch them fail**

Run: `cd service && uv run pytest tests/test_ai_cli_api.py -q`
Expected: FAIL with 404 on the progress route.

- [ ] **Step 3: Implement**

In `service/src/whf/api.py`, inside `create_app`, next to the other setup:

```python
    # Progress of the narration that is running now, so the desktop app can poll it while the POST
    # below is still blocked on Copilot. In memory only: it is worthless once the run has finished.
    narrative_progress = ProgressStore()
```

In `create_narrative`, before calling `narrate_run`, call `narrative_progress.begin(run_id)`, and pass
`progress=lambda event: narrative_progress.record(run_id, event)` to `narrate_run`.

Add, beside the narrative route:

```python
    @app.get("/runs/{run_id}/narrative/progress", dependencies=guarded)
    def narrative_progress_route(run_id: int) -> dict:
        # No 404 for an unknown run: the app polls this the moment it sends the POST, and an empty
        # list is the honest answer both before the first step and long after the last one.
        return {"run_id": run_id, "steps": narrative_progress.steps(run_id)}
```

Note this route takes no database dependency; it must not, because it is polled while a narration holds its own connection.

- [ ] **Step 4: Run the tests and watch them pass**

Run: `cd service && uv run pytest tests/test_ai_cli_api.py tests/test_api.py -q`
Expected: PASS. Then `uv run ruff check . && uv run ruff format --check .`

- [ ] **Step 5: Commit**

```bash
git add service/src/whf/api.py service/tests/test_ai_cli_api.py
git commit -m "Serve the progress of a running narration"
```

---

### Task 4: The app types, fetches and phrases a step

**Files:**
- Modify: `app/src/shared/types.ts` (after the `NarrativeOutcome` interface)
- Modify: `app/src/renderer/src/api.ts` (one new call beside `createNarrative`)
- Modify: `app/src/renderer/src/i18n.ts` (both dictionaries)
- Create: `app/src/renderer/src/narrative-progress.ts`
- Test: `app/src/renderer/src/__tests__/narrative-progress.test.ts`

**Interfaces:**
- Consumes: the route from Task 3.
- Produces: `getNarrativeProgress(run_id)`, `progressLabel(step: NarrativeProgressStep | null): string`, and the `NarrativeProgressStep` type. Task 5 uses all three.

- [ ] **Step 1: Write the failing test**

`app/src/renderer/src/__tests__/narrative-progress.test.ts`:

```ts
import { setLanguage } from '../i18n'
import { progressLabel } from '../narrative-progress'

const step = (code: string, detail: string | null = null) => ({ code, detail, at: '2026-09-04T10:00:00' }) as never

describe('progressLabel', () => {
  beforeEach(() => setLanguage('en'))

  it('falls back to the generic line before the first step arrives', () => {
    expect(progressLabel(null)).toBe('Asking Copilot to explain the forecast…')
  })
  it('phrases each step', () => {
    expect(progressLabel(step('starting'))).toBe('Starting Copilot…')
    expect(progressLabel(step('session'))).toBe('Opening a Copilot session…')
    expect(progressLabel(step('checking'))).toBe('Checking the answer against the numbers…')
  })
  it('names the tool Copilot is reading', () => {
    expect(progressLabel(step('tool', 'team_overview'))).toBe('Copilot is reading team_overview…')
  })
  it('says nothing about attempts on the first attempt, and says so on a retry', () => {
    expect(progressLabel(step('asking', '1'))).toBe('Copilot is writing the explanation…')
    expect(progressLabel(step('asking', '2'))).toBe('Copilot is trying again (attempt 2)…')
  })
  it('phrases them in French too', () => {
    setLanguage('fr')
    expect(progressLabel(step('starting'))).toBe('Démarrage de Copilot…')
    expect(progressLabel(step('tool', 'team_overview'))).toBe('Copilot consulte team_overview…')
  })
  it('falls back to the generic line for a code it does not know', () => {
    // The service may add a step before the app is updated; an unknown code must not blank the line.
    expect(progressLabel(step('something_new'))).toBe('Asking Copilot to explain the forecast…')
  })
})
```

- [ ] **Step 2: Run the test and watch it fail**

Run (from `app/`): `npx vitest run src/renderer/src/__tests__/narrative-progress.test.ts`
Expected: FAIL, cannot resolve `../narrative-progress`.

- [ ] **Step 3: Implement**

In `app/src/shared/types.ts`, after `NarrativeOutcome`:

```ts
/** What the Copilot session is doing right now. `code` is language-independent; the app phrases it. */
export type NarrativeProgressCode = 'starting' | 'session' | 'asking' | 'tool' | 'checking'
export interface NarrativeProgressStep { code: NarrativeProgressCode; detail: string | null; at: string }
export interface NarrativeProgress { run_id: number; steps: NarrativeProgressStep[] }
```

In `app/src/renderer/src/api.ts`, beside `createNarrative`:

```ts
export const getNarrativeProgress = (run_id: number) => call<NarrativeProgress>('GET', `/runs/${run_id}/narrative/progress`)
```

(and add `NarrativeProgress` to the type import list).

In `app/src/renderer/src/i18n.ts`, add to **both** dictionaries, keeping the same key order in each:

```ts
  'run.progress.ai.starting': 'Starting Copilot…',
  'run.progress.ai.session': 'Opening a Copilot session…',
  'run.progress.ai.asking': 'Copilot is writing the explanation…',
  'run.progress.ai.retry': 'Copilot is trying again (attempt {attempt})…',
  'run.progress.ai.tool': 'Copilot is reading {tool}…',
  'run.progress.ai.checking': 'Checking the answer against the numbers…',
  'run.progress.elapsed': '{seconds} s so far',
```

French:

```ts
  'run.progress.ai.starting': 'Démarrage de Copilot…',
  'run.progress.ai.session': 'Ouverture d’une session Copilot…',
  'run.progress.ai.asking': 'Copilot rédige l’explication…',
  'run.progress.ai.retry': 'Copilot réessaie (tentative {attempt})…',
  'run.progress.ai.tool': 'Copilot consulte {tool}…',
  'run.progress.ai.checking': 'Vérification de la réponse par rapport aux chiffres…',
  'run.progress.elapsed': '{seconds} s écoulées',
```

`app/src/renderer/src/narrative-progress.ts`:

```ts
import type { NarrativeProgressStep } from '../../shared/types'
import { t } from './i18n'

/**
 * The sentence for the step Copilot is on. The service sends a code and a technical detail so that
 * the wording — and its language — is decided here. An unknown code falls back to the generic line
 * rather than showing nothing: the service may gain a step before the app knows about it.
 */
export function progressLabel(step: NarrativeProgressStep | null): string {
  if (!step) return t('run.progress.narrating')
  switch (step.code) {
    case 'starting': return t('run.progress.ai.starting')
    case 'session': return t('run.progress.ai.session')
    case 'checking': return t('run.progress.ai.checking')
    case 'tool': return t('run.progress.ai.tool', { tool: step.detail ?? '' })
    case 'asking':
      return step.detail && step.detail !== '1'
        ? t('run.progress.ai.retry', { attempt: step.detail })
        : t('run.progress.ai.asking')
    default: return t('run.progress.narrating')
  }
}
```

- [ ] **Step 4: Run the tests and watch them pass**

Run (from `app/`): `npx vitest run src/renderer/src/__tests__/narrative-progress.test.ts src/renderer/src/__tests__/i18n.test.ts`
Expected: PASS, including the existing parity assertion that both dictionaries are complete.

- [ ] **Step 5: Commit**

```bash
git add app/src/shared/types.ts app/src/renderer/src/api.ts app/src/renderer/src/i18n.ts app/src/renderer/src/narrative-progress.ts app/src/renderer/src/__tests__/narrative-progress.test.ts
git commit -m "Phrase each Copilot narration step in both languages"
```

---

### Task 5: The Run page shows the live step

**Files:**
- Modify: `app/src/renderer/src/pages/Run.tsx:74-75` and the state around `start()`
- Test: `app/src/renderer/src/__tests__/Run.test.tsx` (append)

**Interfaces:**
- Consumes: `getNarrativeProgress` and `progressLabel` from Task 4.
- Produces: nothing later tasks use.

- [ ] **Step 1: Write the failing test**

Append to `app/src/renderer/src/__tests__/Run.test.tsx`. Read the file first and follow its idiom (`installFakeWhf`, `RUN_CREATED`, `userEvent`). The test holds the narrative POST open with a promise the test resolves, so the intermediate state is observable:

```ts
  it('shows what Copilot is doing while the narrative is still running', async () => {
    let finish: (value: unknown) => void = () => {}
    const held = new Promise((resolve) => { finish = resolve })
    installFakeWhf({
      'GET /meta': META, 'GET /profile': { member_id: 11, role: 'team_leader' }, 'GET /copilot/status': ready,
      'POST /runs': RUN_CREATED,
      'GET /runs/5/narrative/progress': { run_id: 5, steps: [{ code: 'tool', detail: 'team_overview', at: '2026-09-04T10:00:00' }] },
      'POST /runs/5/narrative': () => held,
    })
    render(<MemoryRouter initialEntries={['/run?team=1']}><AppProvider><Run /></AppProvider></MemoryRouter>)
    await userEvent.click(await screen.findByRole('button', { name: 'Run forecast' }))
    expect(await screen.findByText(/Copilot is reading team_overview…/)).toBeInTheDocument()
    finish({ run_id: 5, status: 'ok', ai_status: 'ok', narrative: null, error: null, reason: null, attempts: 1, tool_calls: [] })
    expect(await screen.findByText('Forecast complete')).toBeInTheDocument()
  })

  it('stops polling for progress once the narrative is done', async () => {
    const fake = installFakeWhf({ /* same routes, with 'POST /runs/5/narrative' resolving immediately */ })
    // …run it, wait for 'Forecast complete', record fake.calls.filter(progress).length,
    // wait past one poll interval, and assert the count has not grown.
  })
```

The second test's requirement: after the run completes, no further `/runs/5/narrative/progress` request is made. Write it with the timers the file already uses; if the file uses real timers, `vi.useFakeTimers()` around this one test is acceptable as long as `userEvent.setup({ advanceTimers: vi.advanceTimersByTime })` is used.

- [ ] **Step 2: Run the test and watch it fail**

Run (from `app/`): `npx vitest run src/renderer/src/__tests__/Run.test.tsx`
Expected: FAIL — the page shows the generic "Asking Copilot to explain the forecast…" line and never requests progress.

- [ ] **Step 3: Implement**

In `app/src/renderer/src/pages/Run.tsx`:

- add state `const [step, setStep] = useState<NarrativeProgressStep | null>(null)` and `const [elapsed, setElapsed] = useState(0)`
- in `start()`, before the narrative call, `setStep(null); setElapsed(0)`
- add one effect that runs only while `phase === 'narrating'` and `result` is set, polls `getNarrativeProgress(result.run_id)` every second, keeps the **last** step (`steps[steps.length - 1] ?? null`), counts elapsed seconds, ignores errors (a failed poll must never fail the run), and clears its interval on cleanup and when the phase leaves `narrating`:

```tsx
  useEffect(() => {
    if (phase !== 'narrating' || !result) return
    const runId = result.run_id
    const started = Date.now()
    const tick = (): void => {
      setElapsed(Math.round((Date.now() - started) / 1000))
      getNarrativeProgress(runId)
        .then((p) => setStep(p.steps[p.steps.length - 1] ?? null))
        .catch(() => {})  // a poll that fails is not worth failing the run over; the next one may work
    }
    tick()
    const timer = setInterval(tick, 1000)
    return () => clearInterval(timer)
  }, [phase, result])
```

- replace line 75 with the live line, elapsed seconds beside it:

```tsx
      {phase === 'narrating' && (
        <StatusMessage kind="info">
          {progressLabel(step)} <span className="muted">{t('run.progress.elapsed', { seconds: String(elapsed) })}</span>
        </StatusMessage>
      )}
```

- [ ] **Step 4: Run the whole app suite**

Run (from `app/`): `npm test && npm run typecheck && npm run lint`
Expected: PASS, no type errors, no lint errors.

- [ ] **Step 5: Commit**

```bash
git add app/src/renderer/src/pages/Run.tsx app/src/renderer/src/__tests__/Run.test.tsx
git commit -m "Show the live Copilot step on the Run page"
```

---

## Self-review

- Spec coverage: the backlog item asks for live progress on the Run page where a user assumes the app has hung. Task 5 delivers it; Tasks 1-4 supply the events, the store, the route, the types and the wording. The i18n constraint is covered by Task 4 and by the existing parity assertion.
- Types: `ProgressEvent`/`ProgressCode` (Task 1) are used unchanged in Tasks 2 and 3; `NarrativeProgressStep` (Task 4) is used in Task 5; the route shape in Task 3 matches `NarrativeProgress` in Task 4.
- The one judgment call left to an implementer is the fixture names in `test_ai_cli_api.py` and `Run.test.tsx`, which exist only in those files.

# Copilot live view, run cost, and "run again" wording

Owner request (2026-09-07): the Run page does not show what Copilot is thinking while the narrative
is written, every run should show what it cost, and after a run it is not clear whether one may
run again. Design approved in chat the same day (bounded change; recommended options chosen: the
live panel shows the thinking and the answer as it is written; the Copilot settings panel shows the
remaining monthly quota).

Findings the design rests on (SDK `github-copilot-sdk` 1.0.11, the version pinned in `service/`):

- The session is created with `streaming=False` and the event handler ignores
  `assistant.intent`, `assistant.reasoning`, `assistant.reasoning_delta`, `assistant.message_delta`
  and `tool.execution_complete`. Only five coarse step codes reach the app.
- Usage: `session.rpc.usage.get_metrics()` returns `UsageGetMetricsResult` with
  `total_nano_aiu` (AI credits × 1e9; since 1 June 2026 Copilot bills AI credits, 1 credit = $0.01),
  `total_premium_request_cost` (legacy premium-request accounting, fractional),
  `total_user_requests`, `total_api_duration_ms`, `model_metrics: dict[model, m]` with
  `m.requests.count`, `m.requests.cost`, `m.usage.input_tokens`, `m.usage.output_tokens`,
  `m.usage.cache_read_tokens`, `m.usage.cache_write_tokens`, `m.usage.reasoning_tokens`.
  Per call, `assistant.usage` carries `input_tokens`, `output_tokens`, `cache_read_tokens`,
  `reasoning_tokens`, `model`; its `cost` field is the premium-request multiplier, not money.
- Quota: `client.rpc.account.get_quota(AccountGetQuotaRequest(git_hub_token=None))` returns
  `AccountGetQuotaResult.quota_snapshots: dict[str, AccountQuotaSnapshot]` with
  `entitlement_requests`, `is_unlimited_entitlement`, `overage`, `remaining_percentage`,
  `used_requests`, `reset_date` (ISO string or None). Keys seen in the SDK docs: `chat`,
  `completions`, `premium_interactions`; other keys may appear.

## Global Constraints

- The language model never produces a forecast number; nothing here changes the facts or the
  narrative schema. Demand is never capped.
- English and French everywhere: every new user-facing string has both, French with correct accents
  and the typographic apostrophe (’). The service sends codes and raw data; the app phrases them.
- Test-driven development: failing test first, then the code, for every behaviour below.
- Nothing downloads at run time; no new dependencies.
- No AI-assistant model names (Claude, Sonnet, Opus) anywhere in the repository.
- Commit footer on every commit, exactly:
  `Co-Authored-By: Claude Fable 5.1 <noreply@anthropic.com>` then
  `Claude-Session: https://claude.ai/code/session_01CqJ57Eq8raMBmwPaDj5FVU`.
  Never `--no-verify`, never `WHF_SKIP_HOOKS`. Stage by path, never `git add -A`.
- Gate before each commit: in `service/`: `uv run ruff check . && uv run ruff format --check . && uv run ty check && uv run pytest -q` (the fast suite; do not pass `-m chronos2_real`); in `app/`: `npm run lint && npm run typecheck && npm test`.
- Fakes for the Copilot SDK live in `service/tests/ai_fakes.py` (`FakeClient`, `FakeSession`, `make_event`); extend them rather than adding a second fake.
- Existing behaviour to keep: the five step codes, the once-a-second poll, the bounded store
  (8 runs, 50 steps), the empty-poll-keeps-last-step rule, the scoped narrating set on the team page.

## Task 1: Stream Copilot's thinking and answer to the app

**Service, `service/src/whf/ai/progress.py`.** Add live text to `ProgressStore`, per run, beside the
steps: a `thinking` buffer and an `answer` buffer, each a `str` kept to a tail of at most
`keep_chars` characters (constructor argument, default `16000`; when appending would exceed it,
keep the last `keep_chars` characters). New methods:

- `append(run_id, kind, text)` with `kind: Literal["thinking", "answer"]`; creates the run's entry if
  missing (like `record`).
- `reset_answer(run_id)` empties the answer buffer only (a retry attempt starts a new answer; the
  thinking is kept).
- `live(run_id) -> dict` returning `{"thinking": str, "answer": str}` (empty strings for an unknown
  run). `begin(run_id)` also clears both buffers. Keep `steps(run_id)` unchanged.

Add the step code `"tool_done"` to `ProgressCode` with `detail` = tool name; its English message is
`f"tool {detail} done"` (or `"tool done"` without detail).

**Service, `service/src/whf/ai/session.py`.**

- Extend the `progress` callback contract: `Narrator.narrate_sync(facts, progress=None, live=None)`
  where `live: Callable[[str, str], None] | None` receives `(kind, text)` for `kind` in
  `"thinking" | "answer"`, and a third optional hook is NOT added; instead `reset_answer` is signalled
  by calling `live("answer", "")` with the empty string — document this: an empty answer chunk means
  "start a new answer". `CopilotNarrator.narrate` and `narrate_sync` take the same `live` argument.
- Create the session with `streaming=True`.
- In `on_event`: `ASSISTANT_INTENT` → `live("thinking", event.data.intent + "\n")`;
  `ASSISTANT_REASONING_DELTA` → `live("thinking", event.data.delta_content)` and remember the
  `reasoning_id` in a set; `ASSISTANT_REASONING` → only if its `reasoning_id` was never seen as a
  delta, `live("thinking", event.data.content + "\n")` (models that send the full text without
  deltas still show up; models that sent deltas are not duplicated); `ASSISTANT_MESSAGE_DELTA` →
  `live("answer", event.data.delta_content)`; `TOOL_EXECUTION_COMPLETE` → `say("tool_done", name)`
  where the name is looked up from a dict `tool_call_id → tool_name` filled on
  `TOOL_EXECUTION_START` (`event.data.tool_call_id`); unknown ids emit `say("tool_done", None)`.
- Before each attempt's `send_and_wait`, call `live("answer", "")`.
- `ASSISTANT_MESSAGE` handling is unchanged (it still arrives at the end of a streamed turn).
- The default `live` is a no-op, like `progress`.

**Service, `service/src/whf/narrate.py`.** `narrate_run(..., progress=None, on_valid=None, live=None)`
passes `live` through to `narrate_sync`.

**Service, `service/src/whf/api.py`.** In `create_narrative`, pass
`live=lambda kind, text: narrative_progress.reset_answer(run_id) if (kind == "answer" and text == "") else narrative_progress.append(run_id, kind, text)`
(write it as a small local function, not a lambda with a conditional expression). The progress route
returns `{"run_id", "steps", "thinking", "answer"}` using `live(run_id)`.

**Service, `service/src/whf/cli.py`.** `whf narrate` keeps printing the coded steps; it does not
print the live text (the terminal would scroll for minutes). No change unless the signature change
forces one.

**Tests (service, write first).** In `service/tests/test_ai_progress.py`: append/live round trip;
tail bound at `keep_chars`; `reset_answer` keeps thinking; `begin` clears both; `tool_done` message.
In `service/tests/test_ai_session.py` (extend `FakeSession` in `service/tests/ai_fakes.py` with
optional lists `intents`, `reasoning_deltas`, `reasoning_full`, `message_deltas` it emits before the
reply, and a `TOOL_EXECUTION_COMPLETE` after each tool start): the narrator forwards intent and
reasoning deltas to `live("thinking", …)`, does not duplicate a full reasoning whose id was streamed,
forwards message deltas to `live("answer", …)`, sends `live("answer", "")` before each attempt
(assert two resets on a retried narration), emits `tool_done` with the tool name, and creates the
session with `streaming=True` (assert on `session_kwargs`). In `service/tests/test_api.py`: the
progress route returns `thinking` and `answer` after a `FakeNarrator` that calls `live`; extend
`FakeNarrator.narrate_sync` to accept `live` and call `live("thinking", "reading the facts")` and
`live("answer", "{")` when given.

**App, `app/src/shared/types.ts`.** `NarrativeProgressCode` gains `'tool_done'`;
`NarrativeProgress` gains `thinking: string; answer: string`.

**App, `app/src/renderer/src/narrative-progress.ts`.** `useNarrativeProgress(runId)` returns
`{ step, elapsed, thinking, answer, tools }` where `tools: { name: string; done: boolean }[]` is
derived from the steps in order (a `tool` step adds `{name, done:false}`; a `tool_done` step marks
the earliest not-done entry with that name as done; a `tool_done` with null detail marks the earliest
not-done entry). `thinking` and `answer` come from the poll and follow the same keep-last rule as the
step: an empty poll does not blank them. All reset with `runId`. `progressLabel` phrases `tool_done`
with the key `run.progress.ai.toolDone`.

**App, new `app/src/renderer/src/components/NarrativeLive.tsx`.** `NarrativeLive({ step, elapsed, thinking, answer, tools })`
renders: the existing info `StatusMessage` line (step label + elapsed) on top; then a
`<details open>` block titled `t('run.live.thinking')` with the thinking text in a
`<pre className="live">` that keeps whitespace and wraps (`white-space: pre-wrap`), showing
`t('run.live.none')` in muted text while empty; a second `<details open>` titled
`t('run.live.answer')` with the answer text the same way, hidden entirely while the answer is empty;
and, when `tools` is non-empty, a line `t('run.live.tools')` followed by the tool names, each with
"✓" appended when done. Add the `.live` style to `app/src/renderer/src/styles.css` (or the existing
stylesheet): monospace, max-height 16em, overflow auto, pre-wrap. Auto-scroll each `<pre>` to its
bottom when its text grows (a `useEffect` on the text setting `scrollTop = scrollHeight`).

**App, `app/src/renderer/src/pages/Run.tsx` and `TeamResult.tsx`.** Replace the inline progress
`StatusMessage` with `<NarrativeLive …/>` fed by the hook, in the same place, under the same
condition.

**App, `app/src/renderer/src/i18n.ts`.** English: `'run.progress.ai.toolDone': 'Copilot finished reading {tool}…'`,
`'run.live.thinking': 'What Copilot is thinking'`, `'run.live.answer': 'The answer as it is written'`,
`'run.live.tools': 'Facts read'`, `'run.live.none': 'Copilot has not sent anything yet.'`.
French: `'run.progress.ai.toolDone': 'Copilot a fini de consulter {tool}…'`,
`'run.live.thinking': 'Ce que Copilot pense'`, `'run.live.answer': 'La réponse en cours de rédaction'`,
`'run.live.tools': 'Faits consultés'`, `'run.live.none': 'Copilot n’a encore rien envoyé.'`.

**Tests (app, write first).** `narrative-progress.test.ts`: tools derivation (start, done by name,
done with null detail, two calls of the same tool), thinking/answer kept on an empty poll, reset on
`runId` change. New `components/__tests__/NarrativeLive.test.tsx` or in `__tests__/`: renders the
thinking text, hides the answer block while empty and shows it once text arrives, shows the
placeholder while thinking is empty, marks done tools. `Run.test.tsx` and `TeamResult.test.tsx`:
the existing progress tests still pass with the poll fixture gaining `thinking: ''` and `answer: ''`;
add one Run test where the poll returns `thinking: 'Reading capacity…'` and asserts it is on screen.

Commit: `feat: stream what Copilot is thinking and writing to the Run and team pages`.

## Task 2: Store and show the cost of each narration; show the quota in Settings

**Service, `service/src/whf/ai/session.py`.**

- `NarrativeOutcome.usage` becomes a dict with exactly these keys (all present, `None` when unknown):
  `input_tokens`, `output_tokens`, `cache_read_tokens`, `reasoning_tokens` (ints), `requests` (int),
  `premium_requests` (float), `ai_credits` (float), `usd` (float), `api_seconds` (float),
  `models` (dict `model → {"requests": int, "input_tokens": int, "output_tokens": int}`), and
  `source` (`"metrics"` | `"events"` | `"none"`).
- After the attempt loop (whatever its outcome, as long as a session exists), before
  `session.disconnect()`, call `await session.rpc.usage.get_metrics()` inside `try/except Exception`
  with a 10-second timeout argument. On success fill the usage from the result:
  tokens summed over `model_metrics` values (`usage.input_tokens`, `usage.output_tokens`,
  `usage.cache_read_tokens`, `usage.reasoning_tokens or 0`), `requests = total_user_requests`,
  `premium_requests = total_premium_request_cost`,
  `ai_credits = total_nano_aiu / 1e9` if `total_nano_aiu is not None` else `None`,
  `usd = ai_credits / 100` when `ai_credits` is not None (1 AI credit = $0.01), `api_seconds = total_api_duration_ms / 1000`,
  `models` from `model_metrics` (`requests.count`, `usage.input_tokens`, `usage.output_tokens`),
  `source = "metrics"`. On failure (exception, or a fake session without `rpc`), fall back to the
  `assistant.usage` events collected in `state`: sum `input_tokens`, `output_tokens`,
  `cache_read_tokens`, `reasoning_tokens` over the events (treat missing as 0, keep `None` only when
  no event carried the field), `requests = number of usage events`, `premium_requests = None`,
  `ai_credits = None`, `usd = None`, `api_seconds = None`, `models` per event model,
  `source = "events"`. With no session (failed before creation) leave every value `None` and
  `source = "none"`.
- Put this in a pure helper `usage_from_metrics(metrics) -> dict` and `usage_from_events(events: list[dict]) -> dict`
  in a new module `service/src/whf/ai/usage.py`, with `empty_usage() -> dict`; the session module only
  orchestrates. The `state["usage"]` entry becomes a list of per-event dicts.

**Service, `service/src/whf/ai/status.py`.** `CopilotStatus` gains
`quota: dict[str, dict] | None` (default `None`): after a successful `get_auth_status` that is
authenticated, call `await client.rpc.account.get_quota(AccountGetQuotaRequest(git_hub_token=None))`
inside `try/except Exception` (a failure leaves `quota=None` and never changes `code`); on success,
`quota = {key: {"used": s.used_requests, "entitlement": s.entitlement_requests, "unlimited": s.is_unlimited_entitlement, "remaining_percentage": s.remaining_percentage, "overage": s.overage, "reset_date": s.reset_date}}`.
The import of `AccountGetQuotaRequest` is lazy (inside the function) like the SDK import above it.

**Service, `service/src/whf/pipeline.py`.** `load_run` adds `"narrative_usage": envelope.get("usage")`
when the envelope is unwrapped, else `None`.

**Service, `service/src/whf/cli.py`.** Where `whf narrate` prints the outcome, add one line
`cost: {ai_credits:.3f} AI credits (~${usd:.2f}), {input_tokens} in / {output_tokens} out, {requests} requests`
when `ai_credits` is not None, else the tokens and requests only, else nothing.

**Tests (service, write first).** New `service/tests/test_ai_usage.py`: `usage_from_metrics` with
a `SimpleNamespace` mirroring the SDK result (two models; `total_nano_aiu=3.2e9` → `ai_credits == 3.2`,
`usd == 0.032`; `reasoning_tokens=None` counted as 0); `usage_from_events` sums and keeps `None`
for fields no event carried; `empty_usage` has every key. In `test_ai_session.py`: `FakeSession`
gains `rpc.usage.get_metrics()` returning a configurable result (default: a metrics namespace) or
raising when `metrics_error` is set; assert `outcome.usage["source"] == "metrics"` and the credits on
the happy path, `"events"` when the call raises, `"none"` when the session was never created; the
metrics call must happen before `disconnect` (record call order on the fake). In `test_ai_status.py`:
quota present when the fake client's `rpc.account.get_quota` returns snapshots; `quota is None` and
`code == "signed_in"` when it raises. In `test_api.py`: `GET /runs/{id}` returns `narrative_usage`
after a narration; `/copilot/status` includes `quota`. Property test (hypothesis, in
`test_ai_usage.py`): for any list of event dicts with non-negative ints, `usage_from_events` totals
equal the sums and `requests == len(events)`.

**App, `app/src/shared/types.ts`.** `NarrativeUsage` interface mirroring the keys above
(`models: Record<string, { requests: number; input_tokens: number; output_tokens: number }>`,
`source: 'metrics' | 'events' | 'none'`); `NarrativeOutcome.usage: NarrativeUsage`;
`RunDetail.narrative_usage: NarrativeUsage | null`; `CopilotStatus.quota: Record<string, CopilotQuota> | null`
with `CopilotQuota { used: number; entitlement: number; unlimited: boolean; remaining_percentage: number; overage: number; reset_date: string | null }`.

**App, new `app/src/renderer/src/components/CopilotCost.tsx`.** `CopilotCost({ usage })` renders
nothing when `usage` is null or `usage.source === 'none'`; otherwise one muted paragraph built from
parts joined by ` · `: when `ai_credits` is not null, `t('cost.credits', { credits, usd })` with
`credits` formatted to 2 decimals and `usd` to 2 decimals; when tokens are known,
`t('cost.tokens', { in, out })` with thousands separators in the current language
(`toLocaleString('en-US')` / `'fr-FR'`); when `requests` is not null, `t('cost.requests', { n })`;
when `ai_credits` is null and `premium_requests` is not null, `t('cost.premium', { n })` with 1
decimal. Prefix the paragraph with `t('cost.label')`.

**App, `app/src/renderer/src/pages/Run.tsx`.** Keep the narrative outcome in state and render
`<CopilotCost usage={outcome.usage} />` inside the "Forecast complete" panel under the week list.
**`TeamResult.tsx`:** render `<CopilotCost usage={detail.narrative_usage} />` right after the
narrative status line; after a successful `narrate()` set `narrative_usage` from `outcome.usage`
along with the narrative.

**App, `app/src/renderer/src/pages/Settings.tsx`.** In the ready branch, under the signed-in
sentence, for each quota entry whose `unlimited` is false, one muted line:
`t('settings.quota', { name, remaining, reset })` where `name` is `t('quota.' + key)` when the key
is one of `premium_interactions`, `chat`, `completions`, else the raw key; `remaining` is
`remaining_percentage` rounded to a whole number; `reset` is the `reset_date` cut to its first 10
characters or `t('settings.quotaNoReset')` when null. Nothing when `quota` is null or has no
non-unlimited entry.

**App, `app/src/renderer/src/i18n.ts`.** English: `'cost.label': 'Copilot usage:'`,
`'cost.credits': '{credits} AI credits (about ${usd})'`, `'cost.tokens': '{in} tokens in, {out} out'`,
`'cost.requests': '{n} requests'`, `'cost.premium': '{n} premium requests'`,
`'settings.quota': '{name}: {remaining}% remaining, resets on {reset}'`,
`'settings.quotaNoReset': 'no reset date'`, `'quota.premium_interactions': 'Premium requests'`,
`'quota.chat': 'Chat'`, `'quota.completions': 'Code completions'`.
French: `'cost.label': 'Utilisation de Copilot :'`, `'cost.credits': '{credits} crédits IA (environ {usd} $)'`,
`'cost.tokens': '{in} jetons en entrée, {out} en sortie'`, `'cost.requests': '{n} requêtes'`,
`'cost.premium': '{n} requêtes premium'`, `'settings.quota': '{name} : {remaining} % restants, réinitialisation le {reset}'`,
`'settings.quotaNoReset': 'sans date de réinitialisation'`, `'quota.premium_interactions': 'Requêtes premium'`,
`'quota.chat': 'Discussion'`, `'quota.completions': 'Complétions de code'`.

**Tests (app, write first).** `CopilotCost.test.tsx`: renders nothing for `source: 'none'`; full
line with credits, usd, tokens and requests; events-only line without credits; premium fallback.
`Run.test.tsx`: the cost line appears after a narration whose outcome carries usage.
`TeamResult.test.tsx`: a stored run with `narrative_usage` shows the line; a run without shows none.
`Settings.test.tsx`: the quota line for a non-unlimited `premium_interactions` entry, and no line
when every entry is unlimited or `quota` is null. Fixtures in `app/src/renderer/src/test/fixtures.ts`
gain a `USAGE` object and the fake status objects gain `quota: null`.

Commit: `feat: record what each Copilot narration cost and show the remaining quota`.

## Task 3: Make it clear that a forecast can be run again

**App, `app/src/renderer/src/pages/Run.tsx`.** The button label is `t('run.running')` while
`busy`, `t('run.again')` once `phase === 'done'`, else `t('run.start')`. When `phase === 'done'`,
a muted `<span>` after the button shows `t('run.againHint')`. Starting a new run resets to the
existing flow (the result panel disappears until the new run completes, as today).

**App, `app/src/renderer/src/i18n.ts`.** English: `'run.running': 'Running…'`,
`'run.again': 'Run another forecast'`,
`'run.againHint': 'Every run is kept in the history. Running again adds a new one; the previous run stays.'`.
French: `'run.running': 'Exécution…'`, `'run.again': 'Lancer une autre prévision'`,
`'run.againHint': 'Chaque exécution est conservée dans l’historique. Relancer en ajoute une nouvelle ; la précédente est conservée.'`.

**Tests (app, write first), `Run.test.tsx`.** After a completed run the button reads "Run another
forecast" and the hint is on screen; while the narrative request is held open the button reads
"Running…" and is disabled; clicking "Run another forecast" posts a second `/runs` request.

Commit: `feat(app): say that a forecast can be run again, and what that does`.

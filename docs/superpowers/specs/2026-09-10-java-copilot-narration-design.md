# Copilot narration from Java: design

Date: 2026-09-10. Status: approved by the owner on 2026-09-10 (design presented in session, one deviation
accepted: the `forecast_narratives` table is recreated by a V2 migration). Implements sections 10, 11 and
12 (`narrate`, `copilot status`) of `2026-09-09-java-forecast-module-design.md` for the Java module, and
section 6 of `2026-09-07-planned-work-and-likely-work-design.md` (the "likely work" narrative section)
against the Java facts. Where this document and the 2026-09-09 spec differ, this document wins; the
differences are listed in section 15.

## 1. Goal

The Java module narrates a finished forecast run through the requesting user's own GitHub Copilot seat:
it opens a Copilot session with the user's token, gives the model nine read-only tools over the run's
stored facts, streams the model's thinking and answer into the run's progress, validates the JSON answer
against a contract, verifies every number in it against the facts, and stores the narrative with its
verification report and its cost. It exposes this through `ForecastService.narrate`, the optional REST
controller, and the CLI commands `narrate` and `copilot status`. The permanent rule stands: the language
model never produces a forecast number; every figure it writes must already be in the facts.

## 2. The SDK as found (copilot-sdk-java 1.0.13-preview.6)

Verified on 2026-09-10 against the artifacts on Maven Central; these facts drive the design.

- **Runtime.** The Java SDK does not spawn the Copilot CLI by default. `copilot-sdk-java-runtime`
  (classifier `linux-x64`, 44 MB) carries `native/linux-x64/runtime.node`, a native library that the SDK
  loads **in process** through JNA (`com.github.copilot.ffi`). On first use it is extracted to
  `~/.copilot/runtime-cache/<version>/` (the cache root is fixed by the SDK; `COPILOT_HOME` does not
  move it). Nothing is downloaded at run time. The alternative is a CLI subprocess over stdio:
  `CopilotClientOptions.setCliPath` or the `COPILOT_CLI_PATH` environment variable.
- **JNA** is an optional dependency of the SDK, so the module declares `net.java.dev.jna:jna` 5.19.1
  itself.
- **Jackson.** The SDK uses Jackson 2 (`com.fasterxml.jackson`, 2.22.2) while the module uses Jackson 3
  (`tools.jackson`). The two coexist under different packages. Tool results are returned to the SDK as
  plain `Map`/`List` values, which Jackson 2 serialises; the module never touches Jackson 2 types
  except `ToolInvocation.getArguments()`.
- **Token.** `CopilotClientOptions.setGitHubToken(token)` passes the token to the runtime and turns
  `useLoggedInUser` off, so no stored login on the server is ever consulted. `client.getAuthStatus()`
  reports `isAuthenticated`, `login` and `statusMessage`.
- **Skills.** Contrary to the 2026-09-09 spec's assumption, `SessionConfig` has `setSkillDirectories`
  and `setEnableSkills`. The owner kept the spec's decision: the product skills are embedded in the
  system message (section 4.2), which needs no files on disk and makes the prompt one deterministic
  string.
- **Session API.** `SessionConfig` carries the model, the tool definitions, the system message
  (`SystemMessageMode.REPLACE`), `availableTools`, `streaming`, `onPermissionRequest`, and flags to
  switch off config discovery, the session store and custom instructions. `CopilotSession.sendAndWait
  (MessageOptions, timeoutMillis)` returns the final `AssistantMessageEvent`; `session.on(Consumer
  <SessionEvent>)` streams events; `session.getRpc().usage.getMetrics()` returns the session's billed
  usage; `client.getRpc().account.getQuota(new AccountGetQuotaParams(null, token))` returns the quota
  snapshots. All are `CompletableFuture`s.
- **Tools.** `ToolDefinition.from(name, description, Param.of(String.class, "member_id", ...), fn)`
  builds a tool with an explicit schema and no annotation processing; `skipPermission(true)` marks it
  pre-approved. The `@CopilotTool` annotation route needs the SDK's annotation processor at compile
  time; the module uses the explicit route (section 5).

## 3. Components (package `com.workloadhub.forecast.ai`, all package-private except where noted)

| Unit | Does | Depends on |
|---|---|---|
| `CopilotGateway` (interface) | Opens a connection for one token: `open(String token) -> CopilotConnection`. The seam between the module and the SDK; tests use a fake. | nothing |
| `CopilotConnection` (interface) | `authStatus()`, `createSession(SessionSpec, Consumer<NarrationEvent>) -> NarrationSession`, `quota(Duration)`, `runtime()`, `close()`. | nothing |
| `NarrationSession` (interface) | `ask(String prompt, Duration timeout) -> String` (the final answer text), `usage(Duration) -> Optional<UsageMetrics>`, `close()`. | nothing |
| `NarrationEvent` (record) | The module's own event: `kind` in `ANSWER_DELTA, THINKING, TOOL_START, TOOL_DONE, USAGE, ERROR`, plus `text`, `toolCallId`, `toolName`, and for `USAGE` the token counts and model. | nothing |
| `SdkCopilotGateway` | The real gateway over `CopilotClient`; maps SDK options, events, RPCs and failures (section 4). | copilot-sdk-java |
| `SkillTexts` | Reads the six `skills/whf-*/SKILL.md` classpath resources once; exposes them in a fixed order. | classpath |
| `Prompts` | The system prompt (rules plus skills) and the user and retry prompts, per language. | `SkillTexts`, `contract.schema.json` |
| `FactsTools` | The nine tools over one run's facts (section 5). | facts JSON |
| `NarrativeContract` | Parses the answer, validates it against the contract and cross-checks it against the facts (section 6). | facts JSON |
| `NumberVerifier` | Every number in the narrative text against the facts (section 7). | facts JSON |
| `Usage` | The one usage shape from metrics, from events, or empty (section 8). | nothing |
| `Narrator` | Orchestrates one narration: session, attempts, validation, verification, usage, progress (section 4.3). | all of the above |
| `NarrationOutcome` (record) | `status` (OK, UNVERIFIED, FAILED), `reason`, `narrativeJson`, `rawText`, `verificationJson`, `usageJson`, `model`, `attempts`, `toolCalls`, `error`. | nothing |
| `store.JdbcNarrativeStore` | `forecast_narratives` rows (section 9). | JdbcClient, Dialect |
| `service.RunProgressTracker` (extended) | Narration steps and the live thinking and answer tails (section 10). | nothing |
| `service.DefaultForecastService` (extended) | `narrate`, `narrative`, `copilotStatus` (section 11). | `Narrator`, stores |
| `web.ForecastController`, `web.ForecastExceptionHandler` | The REST surface (section 12). | spring-webmvc (optional) |
| `cli.NarrateCommand`, `cli.CopilotCommand` | `narrate`, `copilot status` (section 13). | picocli |

## 4. Runtime, client and session

### 4.1 Opening a connection

`SdkCopilotGateway.open(token)` builds `CopilotClientOptions` with: `setGitHubToken(token)`;
`setCopilotHome(<whf.work-dir>/copilot)` so session state and configuration live under the module's
own directory; `setLogLevel("error")`; `setCliPath(whf.copilot.cli-path)` when that property is not
blank (subprocess mode), otherwise the default in-process runtime; `setClientInfo` naming
`workloadhub-forecast` and the module version. It calls `client.start()` and returns the connection.

Failures map to `ForecastException` codes:

| What happened | Code | When |
|---|---|---|
| No token stored for `requestedBy` | `TOKEN_MISSING` | before any client is created |
| `whf.token-key` unset | `TOKEN_KEY_MISSING` | before any client is created |
| `start()` throws (runtime artifact missing, native load failure, CLI path wrong) | `COPILOT_UNAVAILABLE` | message carries the SDK's own text |
| `getAuthStatus()` throws or reports not authenticated | `TOKEN_REJECTED` | message carries `statusMessage` |

All of these are thrown by `narrate` before a session exists, so nothing is stored and nothing is billed.

### 4.2 The session

> Amended on 2026-09-10: the horizon is two windows of five weekdays; the facts carry `run.windows`, per-window forecast rows and a member-level `days` list, the tools return `windows` (and `days` for capacity), and the contract fields are `window` (a window's first day, ISO 8601); see `docs/superpowers/specs/2026-09-10-rolling-forecast-windows-design.md`, sections 8 and 9.

`SessionSpec` holds the model (request override, else `whf.copilot.model`, blank means unset, the
account default), the system message, the tool definitions and the tool names. The gateway builds
`SessionConfig` with: `setModel` when not blank; `setSystemMessage(mode REPLACE, content)`;
`setTools(definitions)`; `setAvailableTools(new ToolSet().addCustom("*"))`; `setStreaming(true)`;
`setEnableSkills(false)`, `setEnableConfigDiscovery(false)`, `setEnableSessionStore(false)`,
`setSkipCustomInstructions(true)`, `setInfiniteSessions(disabled)` so nothing from the server's home
directory or earlier sessions reaches the prompt; `setOnPermissionRequest` with a handler that approves
a request only when its `kind` is `custom-tool` and its tool name is one of the module's nine, and
rejects everything else with the feedback "the narrator may only read the run facts". The tools are
also marked `skipPermission(true)`, so the handler is the safety net, not the normal path.

The system message is: the rules text (the Python `SYSTEM_PROMPT`, adjusted for string member ids
and the Java model names), then each product skill under a `## Skill: <name>` heading, in the order
`whf-domain`, `whf-forecast-interpretation`, `whf-pattern-discovery`, `whf-likely-work`,
`whf-rebalancing-advice`, `whf-report-style`. The six skills are moved from the Python package and
rewritten for the Java facts: models `xgboost` and `seasonal_naive` only, capacity default 40 h, member
ids as UUID strings, task keys, families, `due_hours`, `planned_hours`, `reopened_tasks`,
`logged_hours_4w`, `unlogged_tasks`, `likely_work`, `team_capacity`, `pending_holidays`. The
rebalancing fit table of the 2026-09-07 design (section 6.5) is not built: the Java facts carry no fit
table, and a move's `task_keys` are checked against the source's open tasks only. `whf-likely-work` is
the skill of section 6.3 of the 2026-09-07 design.

The user prompt states the run id, team name and id, `as_of`, the two forecast weeks, the language
(`en` or `fr`, anything else is `INVALID_REQUEST`), the members to cover (id, name, role), the
procedure (overview, then per member forecast, capacity, patterns, history, open tasks, likely work,
then project timelines, planned work, rebalancing candidates, then the JSON document), the contract as
the JSON Schema text of `contract.schema.json`, and "return only the JSON document". The retry prompt
lists the problems as bullets and asks for the corrected document only. Both are English regardless
of the narrative's language; the language instruction is what changes.

### 4.3 One narration

`Narrator.narrate(factsJson, language, model, requestedByToken, progress)`:

1. `progress.step(STARTING)`; open the connection (section 4.1 failures propagate).
2. `progress.step(SESSION)`; create the session with `FactsTools` bound to the facts and an event
   consumer that forwards `ANSWER_DELTA` to `progress.answer`, `THINKING` to `progress.thinking`,
   `TOOL_START` to `progress.step(TOOL, name)` and records the name, `TOOL_DONE` to
   `progress.step(TOOL_DONE, name)` (name looked up by call id, "unknown" when the id was never seen),
   `USAGE` into the usage event list, `ERROR` into the last-error slot.
3. For attempt 1 to 2: `progress.step(ASKING, attempt)`, `progress.resetAnswer()`, `ask(prompt,
   timeout)`. A timeout ends the narration `FAILED` with reason `timeout`; any other exception ends it
   `FAILED` with reason `model_error` (the message, plus the last session error when one was seen).
   Then `progress.step(CHECKING)`: parse and validate (section 6). Valid: verify (section 7), status
   `OK` when every number checks out, else `UNVERIFIED`; stop. Invalid: the problems become the retry
   prompt; after the second failure the narration is `FAILED` with reason `invalid_output` and the
   problems joined as the error, `rawText` the last answer.
4. In every case, before closing: `usage(10 s)` from the session metrics, else the usage events, else
   empty (section 8). Then close the session and the connection; a failure to close is logged and never
   changes the outcome.

The answer text of an attempt is the final message's content when the SDK returns one, else the
concatenated deltas of that attempt (a turn that ends without a final message still has its text).
Reasoning arrives as deltas or as one block; an id already streamed as deltas is not repeated when
its full block arrives. Intent events are forwarded as thinking with a trailing newline.

## 5. Tools

> Amended on 2026-09-10: the horizon is two windows of five weekdays; the facts carry `run.windows`, per-window forecast rows and a member-level `days` list, the tools return `windows` (and `days` for capacity), and the contract fields are `window` (a window's first day, ISO 8601); see `docs/superpowers/specs/2026-09-10-rolling-forecast-windows-design.md`, sections 8 and 9.

Nine tools, built with `ToolDefinition.from`, each `skipPermission(true)`, reading only the facts
JSON of the run (never the database). Member tools take `member_id` (string, the UUID from
`get_run_overview`); an unknown id returns `{"error": "...", "known_member_ids": [...]}`.

| Tool | Returns |
|---|---|
| `get_run_overview` | `run`, `team` (without `planned_backlog`), `members` (id, name, role), `model`, `rebalancing_candidates`, `data_quality`, `pending_holidays`, `how_to_proceed` |
| `get_member_history` | `member_id`, `name`, `history_13w`, `logged_hours_4w`, `unlogged_tasks`, `reopened_tasks` |
| `get_member_forecast` | `member_id`, `name`, `forecast` (the member's rows as stored) |
| `get_member_patterns` | `member_id`, `name`, `patterns` |
| `get_member_open_tasks` | `member_id`, `name`, `open_tasks` |
| `get_member_capacity` | `member_id`, `name`, `weeks` (week, capacity, demand, overload, working_days, absence_hours) |
| `get_project_timelines` | `weeks`, `projects` |
| `get_rebalancing_candidates` | `overloaded`, `underloaded` |
| `get_planned_work` | `planned_backlog` (the team's), and per member `likely_work` under `members` (id, name, likely_work) |

Tool results are `Map`/`List` values converted from the facts tree; numbers keep the facts' values
exactly, so a number the model copies from a tool result is a number the verifier will find.

## 6. Contract and validation

> Amended on 2026-09-10: the horizon is two windows of five weekdays; the facts carry `run.windows`, per-window forecast rows and a member-level `days` list, the tools return `windows` (and `days` for capacity), and the contract fields are `window` (a window's first day, ISO 8601); see `docs/superpowers/specs/2026-09-10-rolling-forecast-windows-design.md`, sections 8 and 9.

`contract.schema.json` (classpath, also the text embedded in the user prompt) describes:

- `run_summary` (1..2000), `members[]`, `team_risks[]`, `rebalancing[]`, `suggested_adjustments[]`,
  `model_notes` (0..1000, default empty).
- `members[]`: `member_id` (UUID string), `name`, `risk_level` (`low|medium|high`), `summary`
  (1..1200), `patterns[]` (`kind` in the nine Python kinds, `statement` 1..400, `evidence` 1..400),
  `warnings[]` (strings), `likely_work[]` (at most four: `statement` 1..300, `evidence` 1..300,
  `confidence` `low|medium|high`).
- `team_risks[]`: `title` (1..120), `detail` (1..800), `severity`, `member_ids[]`.
- `rebalancing[]`: `from_member_id`, `to_member_id`, `week` (ISO date), `hours` (> 0), `reason`
  (1..600), `confidence`, `task_keys[]` (default empty).
- `suggested_adjustments[]`: `member_id`, `week`, `delta_hours` (finite, non-zero), `reason` (1..600).

`NarrativeContract.parse(text)` strips a Markdown fence and any text around the outermost braces, then
walks the tree with a hand-written validator that names the path of every problem ("members[2].
risk_level: must be one of low, medium, high"); unknown fields are problems. `validateAgainstFacts`
adds the Python cross-checks: every member of the run exactly once and no unknown member; team-risk
member ids known; a move's members distinct and known, its week a forecast week, its hours at most
the source's overload in that week plus 0.05 and not pushing the target's demand above its capacity
plus 0.05 (skipped when either row is missing); adjustment members and weeks known and the delta
usable; and, new, every `task_keys` entry an open task of the source member. Parse and validation
problems are what the retry prompt lists.

## 7. Verification

`NumberVerifier` ports `verify.py` unchanged in its rules: a number in the text matches when its
one-decimal rounding equals a fact rounded to one decimal or to the nearest integer; dates, clock
times and percentages are removed before extraction; thousands separators are handled before the
decimal comma; an hours unit after a number (`h`, `hr`, `hrs`, `hour`, `hours`, `heure`, `heures`) is
recognised; integers up to 20 without an hours unit are skipped as counts. Scopes: a member's summary,
warnings, patterns and likely-work text may cite the shared numbers (everything outside `members` and
`rebalancing_candidates`) plus that member's own numbers (their entry and their candidacy); team
fields (`run_summary`, `model_notes`, `team_risks[].detail`) may cite anything; a move's reason may
cite both members' numbers and the move's own hours; an adjustment's reason may cite its own delta.
The report is `{"checked": n, "unverified": ["path: value is not in the facts (...)"], "fields":
{path: [numbers]}}`. `UNVERIFIED` is stored and returned as such: it is never silently promoted to
`OK`, and a narration is never rejected for it (the numbers are visible to the reader with the list).

## 8. Usage and quota

`Usage` is one JSON shape whatever the source: `input_tokens`, `output_tokens`, `cache_read_tokens`,
`reasoning_tokens`, `requests`, `premium_requests`, `ai_credits`, `usd`, `api_seconds`, `models`
(name to requests, input and output tokens), `source` (`metrics`, `events`, `none`). From
`SessionUsageGetMetricsResult`: tokens summed over `modelMetrics`, `requests = totalUserRequests`,
`premium_requests = totalPremiumRequestCost`, `ai_credits = totalNanoAiu / 1e9`, `usd = credits / 100`,
`api_seconds = totalApiDurationMs / 1000`. From the `AssistantUsageEvent` stream: tokens summed where
reported, `requests` the event count, money unknown (null). Every unknown value is null, never zero,
except `models`, which is an empty map.

Quota comes from `account.getQuota` with the user's token and a 10-second timeout; per snapshot key:
`used`, `entitlement`, `unlimited`, `remaining_percentage`, `overage`, `reset_date`. On timeout or
error the quota is null and the status message says why.

## 9. Persistence

V2 migration (both dialects, `db/forecast/<dialect>/V2__narratives_with_status.sql`) drops and
recreates `forecast_narratives`, empty in every deployment so far, as:

```sql
CREATE TABLE forecast_narratives (
  id                TEXT PRIMARY KEY,             -- UUID on PostgreSQL
  run_id            TEXT NOT NULL REFERENCES forecast_runs(id),
  language          TEXT NOT NULL,
  status            TEXT NOT NULL,                -- OK, UNVERIFIED, FAILED
  model             TEXT,
  narrative_json    TEXT,                         -- null when FAILED
  raw_text          TEXT,                         -- the last answer when FAILED, else null
  verification_json TEXT NOT NULL,                -- {} when FAILED
  usage_json        TEXT NOT NULL,
  error             TEXT,                         -- "<reason>: <detail>" when FAILED
  attempts          INTEGER NOT NULL,
  tool_calls        INTEGER NOT NULL,
  created_at        TEXT NOT NULL                 -- TIMESTAMP on PostgreSQL
);
CREATE INDEX forecast_narratives_run_idx ON forecast_narratives (run_id, language, created_at);
```

`NarrativeResult` becomes `(id, runId, language, status, model, narrativeJson, rawText,
verificationJson, usageJson, error, attempts, toolCalls, createdAt)` with `NarrativeStatus` an enum in
`api`. `JdbcNarrativeStore.save(runId, request, outcome)` inserts one row per narration;
`latest(runId, language)` returns the newest row of that language whatever its status;
`find(id)`. The exact facts sent are already in `forecast_facts`; the tools read from that row, so
the audit trail is the stored facts plus the stored narrative.

## 10. Progress

> Amended on 2026-09-11: `RunProgress` carries a bilingual `label` that rotates through phrases while Copilot
> works, and no thinking or answer tails; see `docs/superpowers/specs/2026-09-11-host-integration-design.md`,
> section 4.1.

`RunProgressTracker` gains `narration(runId, step, detail)`, `thinking(runId, text)`,
`answer(runId, text)`, `resetAnswer(runId)`, `narrated(runId)` and `narrationFailed(runId, message)`.
While narrating, `RunProgress` has phase `NARRATING`, a percent by step (`STARTING` 5, `SESSION` 10,
`ASKING` 20, each tool call adds 5 up to 80, `CHECKING` 90), the step's message ("asking Copilot
(attempt 2)", "tool get_member_forecast", "tool get_member_forecast done", "checking the answer
against the facts"), and the `thinking` and `answer` tails, each capped at the last 16 000
characters. A retry resets the answer and keeps the thinking. When it ends, phase `NARRATED` or
`NARRATION_FAILED` at 100 with the error as the message. A run with no live entry reports its stored
status as today (`DONE` at 100), so a caller polling after a server restart sees the run, not the
narration; the narrative itself is read through `narrative(runId, language)`.

## 11. Service

- `narrate(NarrativeRequest)`: the run must exist and be `DONE` (`RUN_NOT_FOUND`, `RUN_NOT_DONE`);
  `language` must be `en` or `fr` and `requestedBy` non-null (`INVALID_REQUEST`); the token is loaded
  through `GitHubTokenStore.load(requestedBy)`, the one place the module reads it (`TOKEN_MISSING`,
  `TOKEN_KEY_MISSING`). The facts come from `forecast_facts`. The narrator runs on the caller's thread
  and returns when the narration is over; the progress is readable from another thread meanwhile. The
  outcome, whatever its status, is stored and returned as a `NarrativeResult`. Only the pre-session
  failures of section 4.1 throw. The token never appears in logs, progress, facts or results.
- `narrative(runId, language)`: the latest stored row of that language, empty when none; `RUN_NOT_FOUND`
  when the run does not exist.
- `copilotStatus(userId)`: `hasToken`; the runtime path and SDK version (`NativeRuntimeLoader.resolve()`
  or the configured CLI path, and `copilot-runtime.properties`'s `version`), `runtimeAvailable` false
  with the resolver's message when it fails; when a token is stored and the runtime is available, a
  client is started with the token to read `authenticated`, `login` and the quota (section 8), then
  closed. `CopilotStatus` becomes `(userId, hasToken, runtimeAvailable, runtimePath, runtimeVersion,
  authenticated, login, quotaJson, message)`; `authenticated` and `login` are null without a token.
  Concurrency: narrations are independent (one client and session each) and share nothing but the
  progress tracker; `whf.run-threads` does not bound them, the host does.

## 12. REST controller and auto-configuration

`ForecastAutoConfiguration` registers, each `@ConditionalOnMissingBean`: `CopilotGateway`
(`SdkCopilotGateway` built from the properties), `JdbcNarrativeStore`, and `Narrator`; the service bean
takes them. A host or a test replaces the gateway by declaring its own bean.

`ForecastController` under `whf.web.base-path` (default `/api/forecast`), registered by a nested
`@Configuration` in `ForecastAutoConfiguration` that is `@ConditionalOnProperty(whf.web.enabled)`,
`@ConditionalOnWebApplication(SERVLET)` and `@ConditionalOnClass(DispatcherServlet)`. `spring-webmvc`
is an optional dependency of `forecast-core`; a host without Spring MVC never loads the class.

| Route | Body in | Out |
|---|---|---|
| `POST /runs` | `RunRequest` | 202, `{"id": ...}` |
| `GET /runs/{id}` | | 200, `RunResult` |
| `GET /teams/{teamId}/runs?limit=20` | | 200, `RunSummary[]` |
| `GET /runs/{id}/progress` | | 200, `RunProgress` |
| `POST /runs/{id}/narratives` | `{"requestedBy", "language", "model"}` | 200, `NarrativeResult` (any status) |
| `GET /runs/{id}/narratives/{lang}` | | 200, `NarrativeResult`; 404 when none |
| `GET /copilot/status?userId=` | | 200, `CopilotStatus` |
| `PUT /users/{id}/github-token` | `{"token"}` | 204 |
| `DELETE /users/{id}/github-token` | | 204 |

`ForecastExceptionHandler` (`@RestControllerAdvice`) maps `ForecastException` to `{"code",
"message"}`: 404 for `TEAM_NOT_FOUND`, `RUN_NOT_FOUND`, `USER_NOT_FOUND`; 400 for `INVALID_REQUEST`;
409 for every other code (`RUN_NOT_DONE`, `TOKEN_MISSING`, `TOKEN_KEY_MISSING`, `TOKEN_REJECTED`,
`COPILOT_UNAVAILABLE`). The narration route blocks for the narration's duration, as the service does;
a host that wants it asynchronous runs it on its own executor and polls the progress route.

The sample host is a test: `forecast-core/src/test/java/.../samplehost/SampleHostApplication.java`,
a minimal `@SpringBootApplication` with an in-memory SQLite `DataSource`, and
`SampleHostIntegrationTest`, a `@SpringBootTest` with `MockMvc` and `whf.web.enabled=true` that boots
the auto-configuration through the real imports file, seeds a small dataset, starts a run through
`POST /runs`, polls `GET /runs/{id}/progress` to `DONE`, reads `GET /runs/{id}`, stores a token
through `PUT /users/{id}/github-token`, reads `GET /copilot/status` (runtime status without a live
client, since the test runs with a fake gateway bean), and checks the error mapping on a missing run.
The web starter is a test-scoped dependency of `forecast-core` only.

## 13. The command line

- `narrate --run <id> --lang en|fr --user <name or id> [--model m] [--token-env GITHUB_TOKEN]
  [--db f] [--json]`: reads the token from the named environment variable (default `GITHUB_TOKEN`),
  stores it for the user through the token store, narrates, streams the progress steps, the thinking
  and the answer as it is written to stderr, then prints the stored result: the
  status, model, attempts, cost line, the verification problems if any, and the narrative JSON on
  stdout (`--json` prints the whole `NarrativeResult`). Exit 0 for `OK`, 3 for `UNVERIFIED`, 1 for
  `FAILED` or a thrown error, 2 for a usage error.
- `copilot status --user <name or id> [--db f]`: prints the `CopilotStatus` fields as lines.
- Both need the token key: the CLI reads `WHF_TOKEN_KEY` (base64, 32 bytes; `openssl rand -base64 32`
  makes one) and fails with a clear message when it is unset. `Services.open` builds the token store
  with that key and the narrator with the real gateway; `--user` resolves like `--team` (name or id)
  through a `UserArg` helper.
- `whf.work-dir` for the CLI defaults to `~/.workloadhub-forecast`; the Copilot home is under it.

## 14. Testing

No test in the suite talks to Copilot; the live path is exercised by hand and documented in
`server/README.md` ("Narrating with Copilot": the token, the key, the runtime cache, what the first
call extracts, how to point at an installed CLI instead).

- `SkillTextsTest`: six skills load, in order, non-empty, each with the name in its front matter.
- `PromptsTest`: the system prompt contains the rules and every skill once; the user prompt names every
  member once, the two weeks, the language, and embeds the contract text; the retry prompt lists each
  problem.
- `ContractSchemaTest`: the property names of `contract.schema.json` equal the validator's field sets,
  per object, so the prompt and the validator cannot drift apart.
- `NarrativeContractTest`: table-driven port of the ten `test_ai_schema.py` cases plus `task_keys`
  (unknown key, key of another member, key of a done task), likely-work length limits and the fence
  stripping.
- `NumberVerifierTest`: port of the sixteen `test_ai_verify.py` cases with string member ids; and a
  jqwik property: for random facts trees, every number in them written with one decimal in a
  team-level field is verified, and a number absent from the tree with a non-integer fraction is
  reported.
- `UsageTest`: port of the six `test_ai_usage.py` cases.
- `FactsToolsTest`: over the facts of a real `runNow` on `SeededData`: every tool returns the documented
  keys, the unknown-member error, and the numbers in `get_member_forecast` equal the stored member
  weeks.
- `NarratorTest`: a `FakeGateway` scripting auth status, events and replies; ports the twenty-six
  `test_ai_session.py` cases (happy path, retry then accept, persistent invalid output, unverified
  numbers, not authenticated, start failure, timeout, model error, session error enriching the message,
  deltas-only turn, retry resetting the answer, tool steps, metrics read before close, events fallback,
  close failure not masking the outcome) as they apply to the Java shape.
- `RunProgressTrackerTest`: narration steps, percent, tails capped, reset keeps thinking.
- `JdbcNarrativeStoreTest`: save and read back each status on SQLite; PostgreSQL through Testcontainers
  when Docker is present, like the other store tests; V2 applies on a database that already ran V1.
- `DefaultForecastServiceTest`: `narrate` with a fake gateway bean (OK, UNVERIFIED, FAILED stored and
  returned; `TOKEN_MISSING`, `RUN_NOT_DONE`, `INVALID_REQUEST` thrown and nothing stored);
  `narrative` latest per language; `copilotStatus` without a token.
- `ForecastControllerTest` (`WebMvcTest` with a mocked service): every route, the error mapping, the
  base path property.
- `SampleHostIntegrationTest`: section 12.
- CLI: `CliSmokeTest` gains `narrate` without `WHF_TOKEN_KEY` (exit 2, message: a usage error, section 13), `narrate` on an
  unknown run (exit 1), `copilot status` for a user without a token (exit 0, `hasToken false`).
- `SdkCopilotGatewayTest`: option mapping only (token set, `useLoggedInUser` off, Copilot home, CLI
  path when configured, the permission handler's decisions), without starting a client.

## 15. Deviations from the 2026-09-09 spec, to record in the backlog

1. The runtime is in process (JNA) by default, not a CLI subprocess; `whf.copilot.cli-path` switches to
   the subprocess. The runtime cache is `~/.copilot/runtime-cache`, fixed by the SDK.
2. The SDK does support skill directories; the skills are embedded in the system message anyway (owner's
   choice, 2026-09-10).
3. Tools use `ToolDefinition.from`, not `@CopilotTool`.
4. `forecast_narratives` is recreated by V2 with `status`, `raw_text`, `error`, `attempts` and
   `tool_calls`; `NarrativeResult` and `CopilotStatus` gain fields accordingly.
5. `narrate` returns a `FAILED` result after a session existed instead of throwing `NARRATIVE_INVALID`,
   so the cost is kept; the code `NARRATIVE_INVALID` is not used.
6. `DELETE /users/{id}/github-token` is added (the store already has `clear`).
7. The rebalancing fit table of the 2026-09-07 design (section 6.5) is not built; `task_keys` are
   validated against the source's open tasks only. The `whf-rebalancing-advice` skill is worded for
   that.
8. New properties: `whf.work-dir` (default `${user.home}/.workloadhub-forecast`).
9. `GET /runs/{id}/narratives/{lang}` answers `NARRATIVE_NOT_FOUND` (404) when no narrative of that language
   exists; the code was not in the 2026-09-09 list and follows the handler's `_NOT_FOUND` rule.
10. `narrate` and `copilot status` without `WHF_TOKEN_KEY` exit 2, the usage-error code of section 13; the test
    list of section 14 said 1 and has been corrected.

## 16. Non-goals

No token refresh; no session resume; no narration queue or executor inside the module; no
model-listing endpoint; no fit table; no Windows-native runtime (the SDK's `linux-x64` classifier
is the only one bundled; WSL is the Windows path).

/**
 * The REST surface of forecast-web, one entry per route (design 2026-09-18, section 3.3). The reference page is
 * generated from this table, and a test holds it to the route list, so the two cannot drift apart silently.
 */
export type Method = 'GET' | 'POST' | 'PUT' | 'DELETE'

export interface Endpoint {
  method: Method
  path: string
  group: 'System' | 'Directory' | 'Me' | 'Permissions' | 'Runs' | 'Team forecast' | 'Narratives' | 'Tokens'
  who: string
  purpose: string
  body?: string
  response: string
  errors: string[]
  /** Host code the production server writes (true) or demo-only wiring it deletes (false). */
  production: boolean
  /** The ForecastService or GitHubTokenStore call behind the route, when there is one. */
  moduleCall?: string
}

export const ENDPOINTS: Endpoint[] = [
  { method: 'GET', path: '/api/system', group: 'System', who: 'anyone, no acting user', production: false,
    purpose: 'The day the host runs on, the module\'s settings (windows, weekly hours, run threads), the database and whether it was seeded at this start.',
    response: '{today, clockPinned, clockAdjustable, windows, defaultWeeklyHours, runThreads, dialect, database, seededAtStart, actingUserHeader}', errors: [] },
  { method: 'POST', path: '/api/system/clock', group: 'System', who: 'ADMIN, while forecast-web.clock.adjustable is true', production: false,
    purpose: 'Demo only: moves the day a run starts from. Run at an earlier date, move forward, run again, and the accuracy page has weekdays that were forecast before they arrived.',
    body: '{today: "YYYY-MM-DD" | null}   (null releases the pin to the system date)', response: 'the same body as GET /api/system', errors: ['403 FORBIDDEN'] },
  { method: 'GET', path: '/api/directory/users', group: 'Directory', who: 'any acting user', production: true,
    purpose: 'Every user with their role and the teams they manage, belong to, or manage the parent of. The module answers in ids; the host joins the names.',
    response: '[{id, fullName, role, jobTitle, department, active, teams: [{teamId, name, relation}]}]', errors: ['401 ACTING_USER_MISSING', '401 ACTING_USER_UNKNOWN'] },
  { method: 'GET', path: '/api/directory/teams', group: 'Directory', who: 'any acting user', production: true,
    purpose: 'Every team with its manager, parent and member count.', response: '[{id, name, managerId, managerName, parentTeamId, parentTeamName, memberCount}]', errors: ['401'] },
  { method: 'GET', path: '/api/directory/teams/{id}', group: 'Directory', who: 'any acting user', production: true,
    purpose: 'One team with its members.', response: '{id, name, managerId, managerName, parentTeamId, parentTeamName, members: [{id, fullName, role, jobTitle}]}', errors: ['401', '404 TEAM_NOT_FOUND'] },
  { method: 'GET', path: '/api/me', group: 'Me', who: 'any acting user', production: true,
    purpose: 'Who the acting user is, whether they have a token, and for every team whether they may run and view it, with the reasons. The page disables buttons from this.',
    response: '{user: {id, fullName, role, jobTitle}, hasToken, teams: [{teamId, name, canRun, canView, runReason, viewReason}]}', errors: ['401'], moduleCall: 'GitHubTokenStore.has(userId)' },
  { method: 'GET', path: '/api/permissions', group: 'Permissions', who: 'any acting user (a test tool for the owner)', production: false,
    purpose: 'What any user may do on any team, with the reasons: the role rules of design 2026-09-11, section 3.2.',
    body: 'query: userId, teamId', response: '{userId, role, teamId, canRun, canView, runReason, viewReason}', errors: ['400 INVALID_REQUEST', '404 USER_NOT_FOUND', '404 TEAM_NOT_FOUND'] },
  { method: 'POST', path: '/api/teams/{teamId}/forecast-runs', group: 'Runs', who: 'canRun on the team', production: true,
    purpose: 'Starts a forecast run for the team and returns at once; the run day is the host\'s today. The host records (runId, teamId, requestedBy) in its own table. A skill team leader gets one run at a time.',
    response: '202 {id}', errors: ['403 FORBIDDEN (role, or one at a time)', '404 TEAM_NOT_FOUND'], moduleCall: 'ForecastService.startRun(new RunRequest(teamId, userId))' },
  { method: 'GET', path: '/api/forecast-runs/{id}/progress', group: 'Runs', who: 'canView on the run\'s team', production: true,
    purpose: 'Where the run stands: phase, percent, a technical message, and a label in both languages for people. Poll it once a second until DONE or FAILED; a narration continues it with NARRATING, then NARRATED or NARRATION_FAILED.',
    response: '{runId, phase, percent, message, label: {en, fr}}', errors: ['403', '404 RUN_NOT_FOUND'], moduleCall: 'ForecastService.progress(runId)' },
  { method: 'GET', path: '/api/forecast-runs/{id}', group: 'Runs', who: 'canView on the run\'s team', production: true,
    purpose: 'The whole run once DONE: the backtest MAE and the scores behind it, the windows per member (demand, band, capacity, overload, the two pressures), the day rows, the exact facts sent to Copilot as an object, and the members named.',
    response: '{run: RunSummary, scores: [{origin, horizon, mae}], memberWindows: [...], memberDays: [...], facts: {...}, members: [{id, fullName, role, jobTitle}]}',
    errors: ['403', '404 RUN_NOT_FOUND', '409 RUN_NOT_DONE'], moduleCall: 'ForecastService.getRun(runId)' },
  { method: 'GET', path: '/api/teams/{teamId}/forecast-runs', group: 'Runs', who: 'canView', production: true,
    purpose: 'The run history of a team, newest first.', body: 'query: limit (default 20)', response: '[{id, teamId, requestedBy, asOf, status, mae, error, createdAt, finishedAt}]', errors: ['403'],
    moduleCall: 'ForecastService.listRuns(teamId, limit)' },
  { method: 'GET', path: '/api/teams/{teamId}/forecast', group: 'Team forecast', who: 'canView', production: true,
    purpose: 'The current forecast: the latest value per member and day, whichever run produced it, so the team page keeps working while a new run computes. Overload is measured day by day and never capped.',
    body: 'query: from, to (defaults: today and today + 20 days)', response: '[{teamId, userId, day, runId, demandHrs, capacityHrs, overloadHrs, forecastAt}]', errors: ['400', '403'],
    moduleCall: 'ForecastService.currentForecast(teamId, from, to)' },
  { method: 'GET', path: '/api/teams/{teamId}/accuracy', group: 'Team forecast', who: 'canView', production: true,
    purpose: 'How the forecasts made before each past weekday compared with the hours logged on it: the rows and the scores by team, member and lead. Recomputed on every call, nothing stored.',
    body: 'query: from, to (defaults: today − 28 days and today − 1)', response: '{teamId, from, to, evaluatedAt, current: [rows], scores: [{scope, key, n, mae, bias, mase, maseN, overloadPrecision, overloadRecall}], nonWorkingDays}',
    errors: ['400', '403'], moduleCall: 'ForecastService.accuracy(teamId, from, to)' },
  { method: 'POST', path: '/api/forecast-runs/{id}/narratives', group: 'Narratives', who: 'canView, with a stored token, on a DONE run', production: true,
    purpose: 'Asks Copilot, with the acting user\'s own seat, to narrate the run. The host submits it to its own two-thread executor and answers 202; poll progress, then read the stored narrative. One narration per run and language in flight.',
    body: '{language: "en" | "fr", model?: string}', response: '202 {runId, language, phase: "NARRATING"}',
    errors: ['400 INVALID_REQUEST', '403', '404 RUN_NOT_FOUND', '409 TOKEN_MISSING', '409 RUN_NOT_DONE', '409 NARRATION_IN_PROGRESS'],
    moduleCall: 'ForecastService.narrate(new NarrativeRequest(runId, userId, language, model))' },
  { method: 'GET', path: '/api/forecast-runs/{id}/narratives/{lang}', group: 'Narratives', who: 'canView on the run\'s team', production: true,
    purpose: 'The latest stored narrative of that language: the narrative and the verification as objects, the status (OK, UNVERIFIED with the numbers not found in the facts, FAILED with the reason and the raw answer), attempts, tool calls, usage.',
    response: '{id, runId, language, status, model, narrative, rawText, verification: {checked, unverified: [...]}, usage, error, attempts, toolCalls, createdAt}',
    errors: ['403', '404 NARRATIVE_NOT_FOUND'], moduleCall: 'ForecastService.narrative(runId, language)' },
  { method: 'GET', path: '/api/me/copilot', group: 'Me', who: 'any acting user', production: true,
    purpose: 'Opens a Copilot session with the stored token and reports the seat: token, runtime, authenticated, login, quota. The settings page\'s call, not a pre-check before narrating.',
    response: '{userId, hasToken, runtimeAvailable, runtimePath, runtimeVersion, authenticated, login, quotaJson, message}', errors: ['401'], moduleCall: 'ForecastService.copilotStatus(userId)' },
  { method: 'GET', path: '/api/me/github-token', group: 'Tokens', who: 'any acting user', production: true,
    purpose: 'Whether the acting user has a token stored. The token itself is never returned by anything.', response: '{hasToken}', errors: ['401'], moduleCall: 'GitHubTokenStore.has(userId)' },
  { method: 'PUT', path: '/api/me/github-token', group: 'Tokens', who: 'any acting user, for themselves', production: true,
    purpose: 'Stores the token, encrypted with whf.token-key on the user\'s row. gho_, ghu_ and github_pat_ are accepted; classic ghp_ tokens are refused.',
    body: '{token}', response: '204', errors: ['400 INVALID_REQUEST', '409 TOKEN_KEY_MISSING'], moduleCall: 'GitHubTokenStore.save(userId, token)' },
  { method: 'DELETE', path: '/api/me/github-token', group: 'Tokens', who: 'any acting user, for themselves', production: true,
    purpose: 'Clears the stored token.', response: '204', errors: ['401'], moduleCall: 'GitHubTokenStore.clear(userId)' },
]

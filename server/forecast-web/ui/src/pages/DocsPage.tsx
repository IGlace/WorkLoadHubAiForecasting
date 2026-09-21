import { useState } from 'react'
import { api } from '../api'
import { Behind } from '../components/Behind'
import { JsonView } from '../components/JsonView'
import { ErrorNotice } from '../components/Notice'
import { ENDPOINTS, type Endpoint } from '../lib/endpoints'
import { useAsync } from '../lib/useAsync'
import { useActing } from '../state/acting'

const GROUPS = ['System', 'Directory', 'Me', 'Permissions', 'Runs', 'Team forecast', 'Narratives', 'Tokens'] as const

/** The integration guide and the API reference, generated from the endpoint table, with "try it" on the GET routes. */
export function DocsPage() {
  const { userId, me } = useActing()
  const teams = useAsync(() => (userId ? api.teams() : Promise.resolve([])), [userId])
  const [teamId, setTeamId] = useState('')
  const runs = useAsync(() => (userId && teamId ? api.runs(teamId, 5) : Promise.resolve([])), [userId, teamId])
  const [runId, setRunId] = useState('')
  const [lang, setLang] = useState('en')
  const [tried, setTried] = useState<{ path: string; result: unknown; error: unknown } | null>(null)
  const firstDone = runs.data?.find((r) => r.status === 'DONE')?.id ?? ''
  const effectiveRun = runId || firstDone

  function fill(path: string): string {
    return path.replace('{teamId}', teamId).replace('{id}', path.includes('forecast-runs') ? effectiveRun : teamId).replace('{lang}', lang)
      + (path === '/api/permissions' ? `?userId=${userId ?? ''}&teamId=${teamId}` : '')
  }
  async function tryIt(e: Endpoint) {
    const path = fill(e.path)
    try { setTried({ path, result: await api.getRaw(path), error: null }) } catch (err) { setTried({ path, result: null, error: err }) }
  }

  return (
    <div className="docs">
      <h1>Integration guide &amp; API reference</h1>
      <p className="lead">What the WorkloadHub server's developer needs to add the forecast: which part is the module, which part is the host's own code (this application is the reference for it), and the REST surface this host exposes, route by route.</p>

      <h2>1. Module and host</h2>
      <div className="card">
        <p><b>The module</b>, <code>forecast-core</code>, is one Maven dependency. Its auto-configuration sees the host's own <code>DataSource</code>, runs its Flyway migrations under its own history table (<code>forecast_schema_history</code>) and registers <code>ForecastService</code>, <code>GitHubTokenStore</code> and everything under them. Eleven service methods are the whole interface: <code>startRun</code>, <code>progress</code>, <code>getRun</code>, <code>listRuns</code>, <code>findRun</code>, <code>latestRunOf</code>, <code>currentForecast</code>, <code>accuracy</code>, <code>narrate</code>, <code>narrative</code>, <code>copilotStatus</code>; the token store adds <code>save</code>, <code>has</code>, <code>clear</code>.</p>
        <p><b>The host</b> (this application's <code>host</code> package) adds what the module deliberately leaves out: the role check before every call (<code>ForecastAccess</code>), authorising a poll by asking the module itself which team a run belongs to (<code>ForecastService.findRun(runId)</code>, no matter the run's status, so a restart needs no state of its own), one run at a time for a skill team leader, the narration executor (two daemon threads, one narration per run and language in flight), and the HTTP translation (<code>HostForecastFacade</code>, the controllers, <code>ApiExceptionHandler</code>). The production server writes these classes against its session instead of the <code>X-Acting-User</code> header; nothing else differs.</p>
        <p><b>A team</b>, everywhere below, is a team leader and the people who report to them directly, and its <code>teamId</code> is that leader’s own user id. The company structure is <code>users.manager_id</code>; WorkloadHub’s <code>teams</code> table groups people around a project and says nothing about who manages whom, so neither the module nor this host reads it. A skill team leader manages team leaders, does no technical work and is never forecast; they run a forecast only by choosing one leader beneath them.</p>
        <p><b>Demo only</b> (the <code>demo</code> package, deleted by production): the demo clock, the generated token key file, the served front end, and the routes marked <span className="badge">demo</span> below.</p>
        <p className="small muted">Reference: <code>server/README.md</code> ("Using the module from the WorkloadHub server"), <code>docs/superpowers/specs/2026-09-11-host-integration-design.md</code>, <code>docs/superpowers/specs/2026-09-18-forecast-web-on-dev-design.md</code>, <code>docs/superpowers/specs/2026-09-21-hierarchy-teams-design.md</code>.</p>
      </div>

      <h2>2. The flow of a run</h2>
      <div className="card">
        <ol>
          <li>The page asks to forecast a team: <code>POST /api/teams/{'{teamId}'}/forecast-runs</code>. The host checks the role, calls <code>startRun(new RunRequest(teamId, userId))</code> and answers <b>202</b> with the id. The run computes on the module's own pool (<code>whf.run-threads</code>).</li>
          <li>The page polls <code>GET /api/forecast-runs/{'{id}'}/progress</code> once a second and shows <code>label.en</code> or <code>label.fr</code>: reading the team's data, preparing the history, scoring the models, predicting the coming weeks, assembling the facts, saving the run. <code>phase</code> is what code branches on; <b>DONE</b> or <b>FAILED</b> ends it.</li>
          <li>On DONE the page reads <code>GET /api/forecast-runs/{'{id}'}</code> (the windows per member, the days, the backtest, the facts) and the team page reads <code>GET /api/teams/{'{teamId}'}/forecast</code>, the current forecast per member and day, which keeps working while a newer run computes.</li>
          <li>A run day later, <code>GET /api/teams/{'{teamId}'}/accuracy</code> scores what was forecast before each weekday against the hours logged on it.</li>
        </ol>
        <p className="small muted">The run day is today by the module's <code>Clock</code> bean; a run covers <code>whf.forecast.windows</code> five-weekday windows starting the first weekday after it. Demand is never capped by capacity: overload is reported.</p>
      </div>

      <h2>3. The flow of a narration</h2>
      <div className="card">
        <ol>
          <li>The settings page stores the user's token (<code>PUT /api/me/github-token</code>) and may show the seat (<code>GET /api/me/copilot</code>, which opens a Copilot session: not a pre-check).</li>
          <li>The run page asks for a narrative: <code>POST /api/forecast-runs/{'{id}'}/narratives</code> with the language. The host checks the role, <code>GitHubTokenStore.has(userId)</code> (one query), that the run is DONE and that none is in flight for that run and language, submits <code>narrate(new NarrativeRequest(runId, userId, language, model))</code> to its own executor and answers <b>202</b>.</li>
          <li>The page polls the same progress route: <b>NARRATING</b> with a label that rotates through "collecting data", "consulting Copilot", "thinking"; then <b>NARRATED</b> or <b>NARRATION_FAILED</b>.</li>
          <li>The page reads <code>GET /api/forecast-runs/{'{id}'}/narratives/{'{lang}'}</code>: the narrative by section, the verification (every number checked against the facts; <b>UNVERIFIED</b> lists the ones not found and is never promoted), attempts, tool calls, usage. Every narration is a stored row, whatever its status.</li>
        </ol>
        <p className="small muted">The language model never produces a forecast number. Copilot reads the facts through nine tools and writes narrative; Copilot access goes through the SDK with the user's own token, read in one place. English and French only.</p>
      </div>

      <h2>4. Properties</h2>
      <div className="card scroll">
        <table>
          <thead><tr><th>Property</th><th>Default</th><th>What it does</th></tr></thead>
          <tbody>
            <tr><td><code>whf.token-key</code></td><td>unset</td><td>Base64 AES-256 key for the stored tokens. Production: the secret store. This host: <code>WHF_TOKEN_KEY</code>, else a key file it generates once.</td></tr>
            <tr><td><code>whf.work-dir</code></td><td><code>~/.workloadhub-forecast</code></td><td>The module's own directory (the Copilot runtime unpacks under it); must be writable by the service account.</td></tr>
            <tr><td><code>whf.default-weekly-hours</code></td><td>44</td><td>Weekly capacity of a member, spread over the working days, minus public holidays and approved leaves.</td></tr>
            <tr><td><code>whf.run-threads</code></td><td>2</td><td>The pool that runs forecasts; narrations run on the host's executor.</td></tr>
            <tr><td><code>whf.forecast.windows</code></td><td>2</td><td>Windows of five weekdays per run, 1 to 6.</td></tr>
            <tr><td><code>whf.copilot.model</code> / <code>cli-path</code> / <code>timeout-seconds</code></td><td>blank / blank / 300</td><td>The model asked for (blank: the account default), an installed Copilot CLI instead of the in-process runtime, how long one ask may take.</td></tr>
            <tr><td><code>whf.web.enabled</code></td><td>false</td><td>The module's own REST controller, which trusts <code>requestedBy</code>; this host keeps it off and has its own.</td></tr>
            <tr><td><code>whf.flyway.enabled</code></td><td>true</td><td>The module migrates its tables at start-up; false when the host migrates them itself.</td></tr>
            <tr><td><code>forecast-web.*</code></td><td colSpan={2}>Demo wiring of this host only: the demo clock (<code>clock.today</code>, blank to follow the system date; <code>clock.adjustable</code>), the token key file (<code>token-key-file</code>), the served UI directory (<code>ui-dir</code>). Production has none of them; nothing here seeds or creates a database.</td></tr>
          </tbody>
        </table>
      </div>

      <h2>5. Errors</h2>
      <div className="card">
        <p>Every error is <code>{'{code, message}'}</code>. <b>401</b> <code>ACTING_USER_MISSING</code> / <code>ACTING_USER_UNKNOWN</code> (this host's session stand-in); <b>403</b> <code>FORBIDDEN</code> with the reason (a refused role check, or one run at a time); <b>404</b> for a code ending in <code>_NOT_FOUND</code> (<code>TEAM_NOT_FOUND</code>, <code>RUN_NOT_FOUND</code>, <code>USER_NOT_FOUND</code>, <code>NARRATIVE_NOT_FOUND</code>); <b>400</b> <code>INVALID_REQUEST</code> (a malformed body, a path variable that is not a UUID, a missing parameter, a refused token prefix); <b>409</b> for every other module code (<code>RUN_NOT_DONE</code>, <code>TOKEN_MISSING</code>, <code>TOKEN_KEY_MISSING</code>, <code>COPILOT_UNAVAILABLE</code>, <code>NARRATION_IN_PROGRESS</code>).</p>
      </div>

      <h2>6. API reference</h2>
      <div className="card">
        <p className="small muted">Every route of this host. <span className="badge ok">production</span> marks host code the server writes; <span className="badge">demo</span> marks wiring it deletes. "Try it" calls the GET routes as the acting user with the ids chosen here; the other verbs are exercised by the pages.</p>
        <div className="row">
          <label className="field">team
            <select value={teamId} onChange={(e) => { setTeamId(e.target.value); setRunId('') }}><option value="">— team —</option>{(teams.data ?? []).map((t) => <option key={t.id} value={t.id}>{t.name}</option>)}</select>
          </label>
          <label className="field">run
            <select value={effectiveRun} onChange={(e) => setRunId(e.target.value)}><option value="">— run —</option>{(runs.data ?? []).map((r) => <option key={r.id} value={r.id}>{r.id.slice(0, 8)} · {r.asOf} · {r.status}</option>)}</select>
          </label>
          <label className="field">language
            <select value={lang} onChange={(e) => setLang(e.target.value)}><option value="en">en</option><option value="fr">fr</option></select>
          </label>
          <span className="small muted">acting as {me?.user.fullName ?? 'nobody'}</span>
        </div>
        {tried && (
          <div className="card tight mt">
            <div className="row spread"><span className="mono small">GET {tried.path}</span><button className="small" onClick={() => setTried(null)}>close</button></div>
            <ErrorNotice error={tried.error} />
            {tried.result !== null && <JsonView value={tried.result} depth={2} />}
          </div>
        )}
        {GROUPS.map((g) => (
          <div key={g}>
            <h3>{g}</h3>
            {ENDPOINTS.filter((e) => e.group === g).map((e) => (
              <div className="endpoint" key={e.method + e.path}>
                <div className="row spread">
                  <span><span className="method">{e.method}</span><span className="path">{e.path}</span> <span className={'badge ' + (e.production ? 'ok' : '')}>{e.production ? 'production' : 'demo'}</span></span>
                  {e.method === 'GET' && <button className="small" disabled={!userId && e.path !== '/api/system'} onClick={() => void tryIt(e)}>try it</button>}
                </div>
                <p style={{ margin: '4px 0' }}>{e.purpose}</p>
                <dl>
                  <dt>who</dt><dd>{e.who}</dd>
                  {e.body && <><dt>in</dt><dd className="mono">{e.body}</dd></>}
                  <dt>out</dt><dd className="mono">{e.response}</dd>
                  {e.errors.length > 0 && <><dt>errors</dt><dd className="mono">{e.errors.join(' · ')}</dd></>}
                  {e.moduleCall && <><dt>module call</dt><dd className="mono">{e.moduleCall}</dd></>}
                </dl>
              </div>
            ))}
          </div>
        ))}
      </div>

      <h2>7. Hard rules to keep</h2>
      <div className="card">
        <ul>
          <li>The language model never produces a forecast number; deterministic code computes demand, capacity and overload, and every number Copilot writes is verified against the facts.</li>
          <li>Demand is never capped by capacity; overload is reported.</li>
          <li>Copilot only through the GitHub Copilot SDK with the user's own token, stored encrypted and read in one place. No API key of another provider.</li>
          <li>The facts sent to Copilot are stored for audit; all other data stays local.</li>
          <li>English and French in the narrative, no third language.</li>
        </ul>
      </div>
      <Behind />
    </div>
  )
}

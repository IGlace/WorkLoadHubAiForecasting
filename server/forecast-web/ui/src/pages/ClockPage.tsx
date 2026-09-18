import { useState } from 'react'
import { Link } from 'react-router-dom'
import { api } from '../api'
import { Behind } from '../components/Behind'
import { ErrorNotice, Notice } from '../components/Notice'
import { addDays } from '../lib/format'
import { useActing } from '../state/acting'

/** Demo only: the day a run starts from. Production keeps the module's default clock and has no such page. */
export function ClockPage() {
  const { system, me, refresh } = useActing()
  const [day, setDay] = useState('')
  const [error, setError] = useState<unknown>(null)
  const isAdmin = me?.user.role === 'ADMIN'

  async function set(today: string | null) {
    setError(null)
    try { await api.setClock(today); await refresh() } catch (e) { setError(e) }
  }

  return (
    <div>
      <h1>Demo clock</h1>
      <p className="lead">A run starts from "today" by the module's <code>Clock</code> bean. This host's clock follows the system date by default and lets an <b>ADMIN</b> pin it to another day (<code>forecast-web.clock.today</code>, or the buttons below), so the accuracy page can be filled without waiting for real days to pass. The production server deletes this bean and keeps the module's default: there is nothing to configure there.</p>
      <div className="card">
        <div className="stats">
          <div className="stat"><div className="label">Today</div><div className="value">{system?.today ?? '–'}</div></div>
          <div className="stat"><div className="label">Pinned</div><div className="value small">{system ? (system.clockPinned ? 'yes' : 'no, system date') : '–'}</div></div>
          <div className="stat"><div className="label">Adjustable</div><div className="value small">{system ? String(system.clockAdjustable) : '–'}</div></div>
        </div>
        {!isAdmin && <Notice kind="warn">Only an ADMIN may move the clock (<code>POST /api/system/clock</code> answers 403 otherwise). Act as the seed's admin to try it.</Notice>}
        <div className="row">
          <label className="field">move today to<input type="date" value={day} onChange={(e) => setDay(e.target.value)} /></label>
          <button className="primary" disabled={!isAdmin || !day} onClick={() => void set(day)}>Set</button>
          {system && <button disabled={!isAdmin} onClick={() => void set(addDays(system.today, -28))}>back 4 weeks</button>}
          {system && <button disabled={!isAdmin} onClick={() => void set(addDays(system.today, 28))}>forward 4 weeks</button>}
          <button disabled={!isAdmin} onClick={() => void set(null)}>release to the system date</button>
        </div>
        <ErrorNotice error={error} />
      </div>
      <div className="card">
        <h2 style={{ marginTop: 0 }}>Filling the accuracy page</h2>
        <ol>
          <li>Open a <Link to="/teams">team</Link> and start a forecast on today's date: it predicts the two windows after that day.</li>
          <li>Acting as the admin, move today forward four weeks (2026-07-26).</li>
          <li>Open the team's accuracy page: every weekday between the run day and the new today was forecast before it arrived, and is now scored against the hours actually logged on it. Start another run to cover the days after the new today.</li>
          <li>The seed's history ends on the day it was generated, with no taper toward the end, so moving today forward by a few weeks opens up days for the accuracy page to score no matter where the seed started.</li>
        </ol>
        <p className="small muted">Progress labels keep rotating while the date is pinned, because the clock keeps the system's time of day; only the date is fixed.</p>
      </div>
      <Behind />
    </div>
  )
}

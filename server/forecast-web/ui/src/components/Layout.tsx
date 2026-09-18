import { NavLink, Outlet } from 'react-router-dom'
import { useActing } from '../state/acting'
import { ActingUserPicker } from './ActingUserPicker'
import { Notice } from './Notice'

const link = ({ isActive }: { isActive: boolean }) => (isActive ? 'active' : '')

export function Layout() {
  const { system, error } = useActing()
  return (
    <div className="shell">
      <aside className="side">
        <div className="brand">WorkloadHub forecast<small>showcase host · forecast-web</small></div>
        <nav>
          <div className="group">Forecast</div>
          <NavLink to="/teams" className={link}>Teams</NavLink>
          <div className="group">Acting user</div>
          <NavLink to="/settings" className={link}>Copilot &amp; token</NavLink>
          <NavLink to="/permissions" className={link}>Permissions</NavLink>
          <div className="group">Demo</div>
          <NavLink to="/clock" className={link}>Demo clock</NavLink>
          <div className="group">Developer</div>
          <NavLink to="/docs" className={link}>Integration guide &amp; API</NavLink>
        </nav>
        {system && (
          <div className="small muted" style={{ padding: '18px 10px 0' }}>
            today <b>{system.today}</b>{system.clockPinned ? ' (pinned)' : ''}<br />
            {system.windows} windows · {system.defaultWeeklyHours} h/week
          </div>
        )}
      </aside>
      <main className="main">
        <div className="topbar"><ActingUserPicker /></div>
        {error && <Notice kind="error">{error}</Notice>}
        <Outlet />
      </main>
    </div>
  )
}

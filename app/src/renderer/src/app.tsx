import type React from 'react'
import { NavLink, Route, Routes } from 'react-router-dom'
import { StatusMessage } from './components/StatusMessage'
import { AppProvider, useApp } from './context'
import { t } from './i18n'
import { Capacity } from './pages/Capacity'
import { Dashboard } from './pages/Dashboard'
import { MemberDetail } from './pages/MemberDetail'
import { Projects } from './pages/Projects'
import { Rebalancing } from './pages/Rebalancing'
import { Run } from './pages/Run'
import { Runs } from './pages/Runs'
import { Settings } from './pages/Settings'
import { TeamResult } from './pages/TeamResult'
import { TimeOff } from './pages/TimeOff'

const NAV: [string, string][] = [
  ['/', 'nav.dashboard'], ['/run', 'nav.run'], ['/rebalancing', 'nav.rebalancing'], ['/projects', 'nav.projects'],
  ['/capacity', 'nav.capacity'], ['/timeoff', 'nav.timeoff'], ['/runs', 'nav.runs'], ['/settings', 'nav.settings'],
]

function Shell(): React.JSX.Element {
  const { state, me, error } = useApp()
  return (
    <div className="layout">
      <nav className="nav">
        <h2>{t('app.title')}</h2>
        {NAV.map(([to, key]) => <NavLink key={to} to={to} end={to === '/'}>{t(key)}</NavLink>)}
        {me && <p className="muted">{me.name}</p>}
      </nav>
      <main className="content">
        {state.service === 'starting' && <StatusMessage kind="info">{t('service.starting')}</StatusMessage>}
        {state.service === 'failed' && <StatusMessage kind="error">{state.serviceMessage}</StatusMessage>}
        {error && state.service === 'ready' && <StatusMessage kind="error">{t('common.error', { message: error })}</StatusMessage>}
        {!me && state.service === 'ready' && <StatusMessage kind="info">{t('profile.none')}</StatusMessage>}
        <Routes>
          <Route path="/" element={<Dashboard />} />
          <Route path="/run" element={<Run />} />
          <Route path="/runs" element={<Runs />} />
          <Route path="/runs/:runId" element={<TeamResult />} />
          <Route path="/runs/:runId/members/:memberId" element={<MemberDetail />} />
          <Route path="/rebalancing" element={<Rebalancing />} />
          <Route path="/projects" element={<Projects />} />
          <Route path="/capacity" element={<Capacity />} />
          <Route path="/timeoff" element={<TimeOff />} />
          <Route path="/settings" element={<Settings />} />
        </Routes>
      </main>
    </div>
  )
}

export function App(): React.JSX.Element {
  return <AppProvider><Shell /></AppProvider>
}

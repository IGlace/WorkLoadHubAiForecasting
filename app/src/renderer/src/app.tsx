import type React from 'react'
import { NavLink, Route, Routes } from 'react-router-dom'
import { StatusMessage } from './components/StatusMessage'
import { AppProvider, useApp } from './context'
import { t } from './i18n'
import { Dashboard } from './pages/Dashboard'
import { Run } from './pages/Run'
import { Runs } from './pages/Runs'
import { Settings } from './pages/Settings'
import { TeamResult } from './pages/TeamResult'

const NAV: [string, string][] = [
  ['/', 'nav.dashboard'], ['/run', 'nav.run'], ['/rebalancing', 'nav.rebalancing'], ['/projects', 'nav.projects'],
  ['/capacity', 'nav.capacity'], ['/timeoff', 'nav.timeoff'], ['/runs', 'nav.runs'], ['/settings', 'nav.settings'],
]

function Placeholder({ title }: { title: string }): React.JSX.Element { return <h1>{title}</h1> }

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
          <Route path="/runs/:runId/members/:memberId" element={<Placeholder title={t('member.title')} />} />
          <Route path="/rebalancing" element={<Placeholder title={t('nav.rebalancing')} />} />
          <Route path="/projects" element={<Placeholder title={t('nav.projects')} />} />
          <Route path="/capacity" element={<Placeholder title={t('nav.capacity')} />} />
          <Route path="/timeoff" element={<Placeholder title={t('nav.timeoff')} />} />
          <Route path="/settings" element={<Settings />} />
        </Routes>
      </main>
    </div>
  )
}

export function App(): React.JSX.Element {
  return <AppProvider><Shell /></AppProvider>
}

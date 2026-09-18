import { Navigate, Route, Routes } from 'react-router-dom'
import { Layout } from './components/Layout'
import { AccuracyPage } from './pages/AccuracyPage'
import { ClockPage } from './pages/ClockPage'
import { DocsPage } from './pages/DocsPage'
import { PermissionsPage } from './pages/PermissionsPage'
import { RunPage } from './pages/RunPage'
import { SettingsPage } from './pages/SettingsPage'
import { TeamPage } from './pages/TeamPage'
import { TeamsPage } from './pages/TeamsPage'
import { ActingProvider } from './state/acting'

export default function App() {
  return (
    <ActingProvider>
      <Routes>
        <Route element={<Layout />}>
          <Route index element={<Navigate to="/teams" replace />} />
          <Route path="/teams" element={<TeamsPage />} />
          <Route path="/teams/:id" element={<TeamPage />} />
          <Route path="/teams/:id/accuracy" element={<AccuracyPage />} />
          <Route path="/runs/:id" element={<RunPage />} />
          <Route path="/settings" element={<SettingsPage />} />
          <Route path="/permissions" element={<PermissionsPage />} />
          <Route path="/clock" element={<ClockPage />} />
          <Route path="/docs" element={<DocsPage />} />
          <Route path="*" element={<Navigate to="/teams" replace />} />
        </Route>
      </Routes>
    </ActingProvider>
  )
}

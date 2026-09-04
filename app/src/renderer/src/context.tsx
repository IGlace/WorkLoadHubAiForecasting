import type React from 'react'
import { createContext, useCallback, useContext, useEffect, useMemo, useState } from 'react'
import { DEFAULT_SETTINGS, type AppState, type Settings } from '../../shared/ipc'
import type { Member, Meta, Profile, Team } from '../../shared/types'
import { getMeta, getProfile, setProfile } from './api'
import { setLanguage } from './i18n'

export interface AppContextValue {
  meta: Meta | null; profile: Profile | null; settings: Settings; state: AppState; me: Member | null; visibleTeams: Team[]
  error: string | null
  canRun(teamId: number): boolean
  refresh(): Promise<void>
  saveSettings(patch: Partial<Settings>): Promise<void>
  saveProfile(memberId: number | null): Promise<void>
}

const Ctx = createContext<AppContextValue | null>(null)

export function AppProvider({ children }: { children: React.ReactNode }): React.JSX.Element {
  const [meta, setMeta] = useState<Meta | null>(null)
  const [profile, setProfileState] = useState<Profile | null>(null)
  const [settings, setSettings] = useState<Settings>(DEFAULT_SETTINGS)
  const [state, setState] = useState<AppState>({ service: 'starting', serviceMessage: '', version: '', platform: '' })
  const [error, setError] = useState<string | null>(null)
  const [, bump] = useState(0)

  const refresh = useCallback(async () => {
    try {
      const [m, p] = await Promise.all([getMeta(), getProfile()])
      setMeta(m); setProfileState(p); setError(null)
    } catch (err) { setError(err instanceof Error ? err.message : String(err)) }
  }, [])

  useEffect(() => {
    void window.whf.getSettings().then((s) => { setSettings(s); setLanguage(s.language); bump((n) => n + 1) })
      .catch((err: unknown) => setError(err instanceof Error ? err.message : String(err)))
    void window.whf.getState().then((s) => { setState(s); void refresh() })
      .catch((err: unknown) => setError(err instanceof Error ? err.message : String(err)))
    const off = window.whf.onStateChanged((s) => { setState(s); if (s.service === 'ready') void refresh() })
    return off
  }, [refresh])

  const me = useMemo(() => meta?.members.find((m) => m.id === profile?.member_id) ?? null, [meta, profile])
  const visibleTeams = useMemo(() => {
    if (!meta || !me) return []
    if (me.role === 'skill_team_leader') return meta.teams.filter((t) => t.department_id === me.department_id)
    return meta.teams.filter((t) => t.id === me.team_id)
  }, [meta, me])

  const value: AppContextValue = {
    meta, profile, settings, state, me, visibleTeams, error,
    canRun: (teamId) => visibleTeams.some((t) => t.id === teamId),
    refresh,
    saveSettings: async (patch) => { const s = await window.whf.setSettings(patch); setSettings(s); setLanguage(s.language); bump((n) => n + 1) },
    saveProfile: async (memberId) => { setProfileState(await setProfile(memberId)) },
  }
  return <Ctx.Provider value={value}>{children}</Ctx.Provider>
}

export function useApp(): AppContextValue {
  const v = useContext(Ctx)
  if (!v) throw new Error('useApp must be used inside AppProvider')
  return v
}

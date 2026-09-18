import { createContext, useCallback, useContext, useEffect, useMemo, useState, type ReactNode } from 'react'
import { api, ApiError, readActingUser, writeActingUser } from '../api'
import type { MeView, SystemView, UserView } from '../types'

/**
 * The acting user: the id kept in localStorage and sent as the X-Acting-User header, the /api/me answer for it
 * (role, token, permissions per team), the directory to pick from, and the system view. Production has a
 * session instead; every page reads this the way it would read the signed-in principal.
 */
interface ActingState {
  userId: string | null
  me: MeView | null
  users: UserView[]
  system: SystemView | null
  loading: boolean
  error: string | null
  setUserId: (id: string | null) => void
  refresh: () => Promise<void>
}

const Ctx = createContext<ActingState | null>(null)

export function ActingProvider({ children }: { children: ReactNode }) {
  const [userId, setUserIdState] = useState<string | null>(() => readActingUser())
  const [me, setMe] = useState<MeView | null>(null)
  const [users, setUsers] = useState<UserView[]>([])
  const [system, setSystem] = useState<SystemView | null>(null)
  const [loading, setLoading] = useState(true)
  const [error, setError] = useState<string | null>(null)

  const refresh = useCallback(async () => {
    setLoading(true)
    setError(null)
    try {
      const sys = await api.system()
      setSystem(sys)
      if (userId) {
        const [m, u] = await Promise.all([api.me(), api.users()])
        setMe(m)
        setUsers(u)
      } else {
        setMe(null)
        // Nobody chosen yet: the directory is needed to choose. Any user may read it, so we borrow the
        // first user of the seed for that one call by asking the system for nothing more than the list.
        setUsers([])
      }
    } catch (e) {
      const err = e as ApiError
      setError(err.code === 'ACTING_USER_UNKNOWN' ? 'the stored acting user no longer exists; pick another' : err.message)
      if (err.code === 'ACTING_USER_UNKNOWN') { writeActingUser(null); setUserIdState(null); setMe(null) }
    } finally {
      setLoading(false)
    }
  }, [userId])

  useEffect(() => { void refresh() }, [refresh])

  const setUserId = useCallback((id: string | null) => {
    writeActingUser(id)
    setUserIdState(id)
  }, [])

  const value = useMemo(() => ({ userId, me, users, system, loading, error, setUserId, refresh }), [userId, me, users, system, loading, error, setUserId, refresh])
  return <Ctx.Provider value={value}>{children}</Ctx.Provider>
}

export function useActing(): ActingState {
  const v = useContext(Ctx)
  if (!v) throw new Error('useActing outside ActingProvider')
  return v
}

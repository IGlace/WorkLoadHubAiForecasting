import { useMemo, useState } from 'react'
import { useActing } from '../state/acting'
import type { UserView } from '../types'
import { ROLES } from '../lib/permissions'

/**
 * Who the page acts as. There is no sign-in yet, so the owner picks any user of the directory; the id goes in
 * the X-Acting-User header of every call. Reading the directory itself needs an acting user, so before the
 * first choice the picker offers the seed's admin, which GET /api/system names on the demo database.
 */
export function ActingUserPicker() {
  const { userId, me, users, system, loading, setUserId, refresh } = useActing()
  const [filter, setFilter] = useState('')
  const [pasted, setPasted] = useState('')

  const grouped = useMemo(() => {
    const f = filter.trim().toLowerCase()
    const out: Record<string, UserView[]> = {}
    for (const u of users) {
      if (f && !u.fullName.toLowerCase().includes(f) && !u.role.toLowerCase().includes(f) && !(u.jobTitle ?? '').toLowerCase().includes(f)) continue
      ;(out[u.role] ??= []).push(u)
    }
    return out
  }, [users, filter])

  if (userId && users.length === 0 && loading) {
    return <div className="actas"><span className="muted small">Acting as {userId.slice(0, 8)}… loading the directory</span></div>
  }
  if (!userId || users.length === 0) {
    return (
      <div className="actas">
        <span className="muted small">Acting as nobody yet.</span>
        {system?.bootstrapUserId && <button className="small primary" onClick={() => setUserId(system.bootstrapUserId)}>act as the seed's admin</button>}
        <input placeholder="or paste a user id" value={pasted} onChange={(e) => setPasted(e.target.value)} />
        <button className="small" disabled={!pasted.trim()} onClick={() => setUserId(pasted.trim())}>use it</button>
      </div>
    )
  }
  return (
    <div className="actas">
      <span className="muted small">Acting as</span>
      <input placeholder="filter by name, role, title" value={filter} onChange={(e) => setFilter(e.target.value)} />
      <select value={userId} onChange={(e) => e.target.value && setUserId(e.target.value)} aria-label="acting user">
        {ROLES.filter((r) => grouped[r]?.length).map((role) => (
          <optgroup key={role} label={role}>
            {grouped[role].map((u) => <option key={u.id} value={u.id}>{u.fullName}{u.jobTitle ? ' · ' + u.jobTitle : ''}</option>)}
          </optgroup>
        ))}
        {!users.some((u) => u.id === userId) && <option value={userId}>{userId}</option>}
      </select>
      {me && <span className="badge accent">{me.user.role}</span>}
      {me && <span className={'badge ' + (me.hasToken ? 'ok' : 'no')}>{me.hasToken ? 'token stored' : 'no token'}</span>}
      <button className="small" onClick={() => void refresh()}>refresh</button>
    </div>
  )
}

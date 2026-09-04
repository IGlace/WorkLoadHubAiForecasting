import type React from 'react'
import { useEffect, useState } from 'react'
import type { Project } from '../../../shared/types'
import { createProject, getProjects, updateProject } from '../api'
import { Field } from '../components/Field'
import { StatusMessage } from '../components/StatusMessage'
import { useApp } from '../context'
import { t } from '../i18n'

interface Draft { name: string; start_date: string; deadline: string; team_ids: number[]; type: string; status: 'planned' | 'active' | 'done' }
const empty: Draft = { name: '', start_date: '', deadline: '', team_ids: [], type: 'delivery', status: 'planned' }

function validate(d: Draft): string | null {
  if (!d.name.trim()) return t('projects.name')
  if (!d.start_date || !d.deadline || d.deadline <= d.start_date) return t('projects.deadlineError')
  if (d.team_ids.length === 0) return t('projects.teamsError')
  return null
}

function ProjectForm({ initial, onSave, onCancel, editing }: { initial: Draft; onSave: (d: Draft) => Promise<void>; onCancel: () => void; editing: boolean }): React.JSX.Element {
  const { visibleTeams } = useApp()
  const [draft, setDraft] = useState<Draft>(initial)
  const [error, setError] = useState<string | null>(null)
  const toggle = (id: number): void => setDraft((d) => ({ ...d, team_ids: d.team_ids.includes(id) ? d.team_ids.filter((x) => x !== id) : [...d.team_ids, id].sort((a, b) => a - b) }))
  return (
    <form className="panel" onSubmit={(e) => { e.preventDefault(); const problem = validate(draft); if (problem) { setError(problem); return } setError(null); void onSave(draft).catch((err: Error) => setError(err.message)) }}>
      {error && <StatusMessage kind="error">{error}</StatusMessage>}
      <Field label={t('projects.name')}>{(id) => <input id={id} value={draft.name} onChange={(e) => setDraft({ ...draft, name: e.target.value })} />}</Field>
      <Field label={t('projects.start')}>{(id) => <input id={id} type="date" value={draft.start_date} onChange={(e) => setDraft({ ...draft, start_date: e.target.value })} />}</Field>
      <Field label={t('projects.deadline')}>{(id) => <input id={id} type="date" value={draft.deadline} onChange={(e) => setDraft({ ...draft, deadline: e.target.value })} />}</Field>
      <div className="field">
        <label>{t('projects.teams')}</label>
        {visibleTeams.map((tm) => <label key={tm.id}><input type="checkbox" checked={draft.team_ids.includes(tm.id)} onChange={() => toggle(tm.id)} /> {tm.name}</label>)}
      </div>
      <Field label={t('projects.type')}>{(id) => <select id={id} value={draft.type} onChange={(e) => setDraft({ ...draft, type: e.target.value })}><option value="delivery">delivery</option><option value="maintenance">maintenance</option><option value="internal">internal</option></select>}</Field>
      {editing && <Field label={t('projects.status')}>{(id) => <select id={id} value={draft.status} onChange={(e) => setDraft({ ...draft, status: e.target.value as Draft['status'] })}><option value="planned">planned</option><option value="active">active</option><option value="done">done</option></select>}</Field>}
      <button className="primary" type="submit">{t('projects.save')}</button>{' '}
      <button type="button" onClick={onCancel}>{t('projects.cancel')}</button>
    </form>
  )
}

export function Projects(): React.JSX.Element {
  const { me, meta } = useApp()
  const [projects, setProjects] = useState<Project[]>([])
  const [creating, setCreating] = useState(false)
  const [editing, setEditing] = useState<Project | null>(null)
  const [error, setError] = useState<string | null>(null)
  const load = (): void => { getProjects().then((p) => { setProjects(p); setError(null) }).catch((e: Error) => setError(e.message)) }
  useEffect(() => { getProjects().then((p) => { setProjects(p); setError(null) }).catch((e: Error) => setError(e.message)) }, [])
  const teamName = new Map(meta?.teams.map((tm) => [tm.id, tm.name]) ?? [])
  const mine = projects.filter((p) => !me || p.department_id === me.department_id)
  return (
    <div>
      <h1>{t('projects.title')}</h1>
      {error && <StatusMessage kind="error">{t('common.error', { message: error })}</StatusMessage>}
      {!creating && !editing && <button className="primary" onClick={() => setCreating(true)}>{t('projects.new')}</button>}
      {creating && me && (
        <ProjectForm initial={empty} editing={false} onCancel={() => setCreating(false)}
          onSave={async (d) => { await createProject({ name: d.name.trim(), department_id: me.department_id, start_date: d.start_date, deadline: d.deadline, team_ids: d.team_ids, type: d.type }); setCreating(false); load() }} />
      )}
      {editing && (
        <ProjectForm initial={{ name: editing.name, start_date: editing.start_date, deadline: editing.deadline, team_ids: editing.team_ids, type: editing.type, status: editing.status as Draft['status'] }} editing onCancel={() => setEditing(null)}
          onSave={async (d) => { await updateProject(editing.id, { name: d.name.trim(), start_date: d.start_date, deadline: d.deadline, team_ids: d.team_ids, type: d.type, status: d.status }); setEditing(null); load() }} />
      )}
      <table>
        <thead><tr><th>{t('projects.name')}</th><th>{t('projects.start')}</th><th>{t('projects.deadline')}</th><th>{t('projects.teams')}</th><th>{t('projects.type')}</th><th>{t('projects.status')}</th><th></th></tr></thead>
        <tbody>
          {mine.map((p) => (
            <tr key={p.id}>
              <td>{p.name}</td><td>{p.start_date}</td><td>{p.deadline}</td><td>{p.team_ids.map((id) => teamName.get(id) ?? id).join(', ')}</td><td>{p.type}</td><td>{p.status}</td>
              <td><button onClick={() => { setCreating(false); setEditing(p) }}>{t('projects.edit')}</button></td>
            </tr>
          ))}
        </tbody>
      </table>
    </div>
  )
}

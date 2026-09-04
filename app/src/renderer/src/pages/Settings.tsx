import type React from 'react'
import { useEffect, useState } from 'react'
import type { CopilotStatus } from '../../../shared/types'
import { getCopilotStatus } from '../api'
import { Field } from '../components/Field'
import { StatusMessage } from '../components/StatusMessage'
import { useApp } from '../context'
import { t } from '../i18n'

export function Settings(): React.JSX.Element {
  const { meta, profile, settings, saveSettings, saveProfile } = useApp()
  const [copilot, setCopilot] = useState<CopilotStatus | null>(null)
  const [loginMessage, setLoginMessage] = useState<string | null>(null)
  const [model, setModel] = useState(settings.model ?? '')
  const [modelSource, setModelSource] = useState(settings.model)
  const [error, setError] = useState<string | null>(null)

  const loadStatus = (): void => { getCopilotStatus().then(setCopilot).catch((e: Error) => setError(e.message)) }
  useEffect(loadStatus, [])
  if (modelSource !== settings.model) {
    setModelSource(settings.model)
    setModel(settings.model ?? '')
  }

  const leaders = (meta?.members ?? []).filter((m) => m.role !== 'member')
  return (
    <div>
      <h1>{t('settings.title')}</h1>
      {error && <StatusMessage kind="error">{t('common.error', { message: error })}</StatusMessage>}
      <section className="panel">
        <h2>{t('settings.profile')}</h2>
        <Field label={t('settings.iam')}>
          {(id) => (
            <select id={id} value={profile?.member_id ?? ''} onChange={(e) => { void saveProfile(e.target.value ? Number(e.target.value) : null).catch((err: Error) => setError(err.message)) }}>
              <option value="">{t('settings.nobody')}</option>
              {leaders.map((m) => <option key={m.id} value={m.id}>{m.name} ({m.role.replace(/_/g, ' ')})</option>)}
            </select>
          )}
        </Field>
      </section>
      <section className="panel">
        <h2>{t('settings.copilot')}</h2>
        {copilot && (
          <p>{copilot.ready ? t('settings.ready', { login: copilot.login ?? '' }) : copilot.message}
            {copilot.cli_path && <span className="muted"> · {copilot.cli_path}</span>}</p>
        )}
        {loginMessage && <StatusMessage kind="info">{loginMessage}</StatusMessage>}
        <button className="primary" onClick={() => { void window.whf.copilotLogin().then((r) => setLoginMessage(r.message)).catch((e: Error) => setError(e.message)) }}>{t('settings.signin')}</button>{' '}
        <button onClick={loadStatus}>{t('settings.recheck')}</button>
      </section>
      <section className="panel">
        <Field label={t('settings.language')}>
          {(id) => (
            <select id={id} value={settings.language} onChange={(e) => { void saveSettings({ language: e.target.value as 'en' | 'fr' }) }}>
              <option value="en">English</option>
              <option value="fr">Français</option>
            </select>
          )}
        </Field>
        <Field label={t('settings.model')}>
          {(id) => <input id={id} value={model} onChange={(e) => setModel(e.target.value)} onBlur={() => { void saveSettings({ model: model.trim() || null }) }} />}
        </Field>
        <div className="field">
          <label><input type="checkbox" checked={settings.launchAtLogin} onChange={(e) => { void saveSettings({ launchAtLogin: e.target.checked }) }} /> {t('settings.launch')}</label>
        </div>
        <div className="field">
          <label><input type="checkbox" checked={settings.closeToTray} onChange={(e) => { void saveSettings({ closeToTray: e.target.checked }) }} /> {t('settings.tray')}</label>
        </div>
      </section>
    </div>
  )
}

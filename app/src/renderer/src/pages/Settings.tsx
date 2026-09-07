import type React from 'react'
import { useState } from 'react'
import type { CopilotQuota } from '../../../shared/types'
import { Field } from '../components/Field'
import { StatusMessage } from '../components/StatusMessage'
import { useApp } from '../context'
import { useCopilotStatus } from '../hooks/useCopilotStatus'
import { t } from '../i18n'

// A private-use codepoint no login name will ever contain, used to find where `{login}`
// landed in the interpolated "Signed in as {login}" sentence so only the name can be bolded.
const READY_SENTINEL = '\uE000'

// The quota types this version has words for; Copilot may report others, which are shown as they
// come rather than hidden, since an unnamed quota running out still explains a refusal.
const NAMED_QUOTAS = ['premium_interactions', 'chat', 'completions']

/**
 * One line per quota that can run out. An unlimited entitlement has nothing to report, and a
 * Copilot that answered no quota at all (`null`) says nothing rather than showing zeros.
 */
function quotaLines(quota: Record<string, CopilotQuota> | null): React.JSX.Element[] {
  return Object.entries(quota ?? {})
    .filter(([, snapshot]) => !snapshot.unlimited)
    .map(([key, snapshot]) => (
      <p key={key} className="muted">
        {t('settings.quota', {
          name: NAMED_QUOTAS.includes(key) ? t(`quota.${key}`) : key,
          remaining: Math.round(snapshot.remaining_percentage),
          // The reset date is an ISO timestamp; the day is all that matters here.
          reset: snapshot.reset_date ? snapshot.reset_date.slice(0, 10) : t('settings.quotaNoReset'),
        })}
      </p>
    ))
}

export function Settings({ pollMs, maxPollMs }: { pollMs?: number; maxPollMs?: number }): React.JSX.Element {
  const { meta, profile, settings, saveSettings, saveProfile } = useApp()
  const { status: copilot, error: copilotError, loginPending, timedOut, startLogin, loginMessage } = useCopilotStatus({ pollMs, maxPollMs })
  const [model, setModel] = useState(settings.model ?? '')
  const [modelSource, setModelSource] = useState(settings.model)
  const [error, setError] = useState<string | null>(null)

  // Render-time state adjustment (not an effect): when settings.model changes underneath
  // this component (e.g. another save completed), reset the local draft to match before
  // this render commits, so the input doesn't briefly show stale text and there's no extra
  // render caused by a `set-state-in-effect` round trip.
  if (modelSource !== settings.model) {
    setModelSource(settings.model)
    setModel(settings.model ?? '')
  }

  const leaders = (meta?.members ?? []).filter((m) => m.role !== 'member')
  const shownError = error ?? copilotError
  return (
    <div>
      <h1>{t('settings.title')}</h1>
      {shownError && <StatusMessage kind="error">{t('common.error', { message: shownError })}</StatusMessage>}
      <section className="panel">
        <h2>{t('settings.profile')}</h2>
        <Field label={t('settings.iam')}>
          {(id) => (
            <select id={id} value={profile?.member_id ?? ''} onChange={(e) => { void saveProfile(e.target.value ? Number(e.target.value) : null).catch((err: Error) => setError(err.message)) }}>
              <option value="">{t('settings.nobody')}</option>
              {leaders.map((m) => <option key={m.id} value={m.id}>{m.name} ({t(`role.${m.role}`)})</option>)}
            </select>
          )}
        </Field>
      </section>
      <section className="panel">
        <h2>{t('settings.copilot')}</h2>
        {copilot && (
          copilot.ready
            // The login name is bolded, so the sentence is split around a sentinel rather than
            // interpolated straight into text: `{login}` could in principle contain the sentinel's
            // own bytes, but never this private-use codepoint, so the split is always exactly one cut.
            ? (() => {
                const [before, after] = t('settings.ready', { login: READY_SENTINEL }).split(READY_SENTINEL)
                return (
                  <>
                    <p>{before}<strong>{copilot.login ?? ''}</strong>{after}</p>
                    {quotaLines(copilot.quota)}
                  </>
                )
              })()
            // `copilot.message` is English prose, partly the CLI's own words, so the sentence comes from
            // the code and the raw message stays beside it as technical detail.
            : <p>{t(`copilot.${copilot.code}`)}
              {copilot.message && <span className="muted"> · {copilot.message}</span>}
              {copilot.cli_path && <span className="muted"> · {copilot.cli_path}</span>}</p>
        )}
        {(loginPending || loginMessage) && <StatusMessage kind="info">{loginMessage ? t(loginMessage) : t('settings.waiting')}</StatusMessage>}
        {timedOut && <StatusMessage kind="info">{t('settings.loginTimeout')}</StatusMessage>}
        <button className="primary" disabled={copilot?.ready ?? false} aria-disabled={copilot?.ready ?? false} onClick={() => { void startLogin() }}>{t('settings.signin')}</button>
        {copilot?.ready && <span className="muted"> {t('settings.signedInHint')}</span>}
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

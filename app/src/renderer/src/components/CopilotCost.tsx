import type React from 'react'
import type { NarrativeUsage } from '../../../shared/types'
import { getLanguage, useT } from '../i18n'

/**
 * What one narration cost, in one muted line under the result it belongs to.
 *
 * Only what is known is said: a session billed in AI credits shows the credits and what they are
 * worth, a session whose metrics could not be read shows the tokens it streamed and no price at
 * all, and a narration that never opened a session shows nothing — an invented zero would read as
 * "this was free".
 */
export function CopilotCost({ usage }: { usage: NarrativeUsage | null }): React.JSX.Element | null {
  const t = useT()
  if (!usage || usage.source === 'none') return null
  const locale = getLanguage() === 'fr' ? 'fr-FR' : 'en-US'
  const parts: string[] = []
  if (usage.ai_credits !== null) {
    parts.push(t('cost.credits', { credits: usage.ai_credits.toFixed(2), usd: (usage.usd ?? usage.ai_credits / 100).toFixed(2) }))
  }
  if (usage.input_tokens !== null && usage.output_tokens !== null) {
    parts.push(t('cost.tokens', { in: usage.input_tokens.toLocaleString(locale), out: usage.output_tokens.toLocaleString(locale) }))
  }
  if (usage.requests !== null) parts.push(t('cost.requests', { n: usage.requests }))
  // The premium-request count is the older way Copilot accounted for a call; it is worth showing
  // only when there are no credits to show instead.
  if (usage.ai_credits === null && usage.premium_requests !== null) {
    parts.push(t('cost.premium', { n: usage.premium_requests.toFixed(1) }))
  }
  if (parts.length === 0) return null
  return <p className="muted">{t('cost.label')} {parts.join(' · ')}</p>
}

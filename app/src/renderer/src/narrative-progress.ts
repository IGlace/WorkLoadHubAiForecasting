import type { NarrativeProgressStep } from '../../shared/types'
import { t } from './i18n'

/**
 * The sentence for the step Copilot is on. The service sends a code and a technical detail so that
 * the wording — and its language — is decided here. An unknown code falls back to the generic line
 * rather than showing nothing: the service may gain a step before the app knows about it.
 */
export function progressLabel(step: NarrativeProgressStep | null): string {
  if (!step) return t('run.progress.narrating')
  switch (step.code) {
    case 'starting': return t('run.progress.ai.starting')
    case 'session': return t('run.progress.ai.session')
    case 'checking': return t('run.progress.ai.checking')
    case 'tool': return t('run.progress.ai.tool', { tool: step.detail ?? '' })
    case 'asking':
      return step.detail && step.detail !== '1'
        ? t('run.progress.ai.retry', { attempt: step.detail })
        : t('run.progress.ai.asking')
    default: return t('run.progress.narrating')
  }
}

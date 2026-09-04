import { useEffect, useState } from 'react'
import type { NarrativeProgressStep } from '../../shared/types'
import { getNarrativeProgress } from './api'
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

/**
 * Polls `GET /runs/{run_id}/narrative/progress` once a second while a narration is in flight, and
 * tracks how long it has been running. `runId === null` means no narration is in flight: polling
 * stops and the state resets so the next narration starts from the generic label and 0 seconds.
 */
export function useNarrativeProgress(runId: number | null): { step: NarrativeProgressStep | null; elapsed: number } {
  const [step, setStep] = useState<NarrativeProgressStep | null>(null)
  const [elapsed, setElapsed] = useState(0)

  // Polls rather than streams: both the narrative POST and this progress GET are synchronous `def`s
  // in the service, so FastAPI runs them on separate threadpool workers — concurrent without a new
  // channel, an SSE endpoint or an extra IPC surface.
  useEffect(() => {
    // Every runId change - including one to null - starts from the generic label at 0 seconds: a new
    // narration must not show the previous one's last step, and a null runId (no narration in flight)
    // must not keep showing stale progress from whichever run just finished or was navigated away from.
    const reset = (): void => { setStep(null); setElapsed(0) }
    reset()
    if (runId === null) return
    const started = Date.now()
    const tick = (): void => {
      setElapsed(Math.round((Date.now() - started) / 1000))
      getNarrativeProgress(runId)
        // An empty poll (service restart, or this run fell out of the store's bounded history) must
        // not blank the label back to the generic line: keep whatever step was last seen.
        .then((p) => { if (p.steps.length > 0) setStep(p.steps[p.steps.length - 1]!) })
        .catch(() => {})  // a poll that fails is not worth failing the run over; the next one may work
    }
    tick()
    const timer = setInterval(tick, 1000)
    return () => clearInterval(timer)
  }, [runId])

  return { step, elapsed }
}

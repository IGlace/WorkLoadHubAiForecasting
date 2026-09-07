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
    case 'tool_done': return t('run.progress.ai.toolDone', { tool: step.detail ?? '' })
    case 'asking':
      return step.detail && step.detail !== '1'
        ? t('run.progress.ai.retry', { attempt: step.detail })
        : t('run.progress.ai.asking')
    default: return t('run.progress.narrating')
  }
}

/** One fact Copilot has read: the tool it called, and whether that call has come back. */
export interface NarrativeTool { name: string; done: boolean }

/**
 * The facts read so far, in the order they were asked for. A `tool_done` step marks the earliest
 * call of that tool that is still open — the service reports the tool by name, and the same tool
 * can be called more than once — and a `tool_done` the service could not name (its start scrolled
 * out of the store, or arrived unmatched) marks the earliest open call whatever its name.
 */
export function toolsFromSteps(steps: NarrativeProgressStep[]): NarrativeTool[] {
  const tools: NarrativeTool[] = []
  for (const step of steps) {
    if (step.code === 'tool') tools.push({ name: step.detail ?? '', done: false })
    else if (step.code === 'tool_done') {
      const open = tools.findIndex((tool) => !tool.done && (step.detail === null || tool.name === step.detail))
      if (open >= 0) tools[open]!.done = true
    }
  }
  return tools
}

export interface NarrativeLiveState {
  step: NarrativeProgressStep | null
  elapsed: number
  thinking: string
  answer: string
  tools: NarrativeTool[]
}

/**
 * Polls `GET /runs/{run_id}/narrative/progress` once a second while a narration is in flight, and
 * tracks how long it has been running. `runId === null` means no narration is in flight: polling
 * stops and the state resets so the next narration starts from the generic label and 0 seconds.
 */
export function useNarrativeProgress(runId: number | null): NarrativeLiveState {
  const [step, setStep] = useState<NarrativeProgressStep | null>(null)
  const [elapsed, setElapsed] = useState(0)
  const [thinking, setThinking] = useState('')
  const [answer, setAnswer] = useState('')
  const [tools, setTools] = useState<NarrativeTool[]>([])

  // Polls rather than streams: both the narrative POST and this progress GET are synchronous `def`s
  // in the service, so FastAPI runs them on separate threadpool workers — concurrent without a new
  // channel, an SSE endpoint or an extra IPC surface.
  useEffect(() => {
    // Every runId change - including one to null - starts from the generic label at 0 seconds: a new
    // narration must not show the previous one's last step, and a null runId (no narration in flight)
    // must not keep showing stale progress from whichever run just finished or was navigated away from.
    const reset = (): void => { setStep(null); setElapsed(0); setThinking(''); setAnswer(''); setTools([]) }
    reset()
    if (runId === null) return
    const started = Date.now()
    const tick = (): void => {
      setElapsed(Math.round((Date.now() - started) / 1000))
      getNarrativeProgress(runId)
        // An empty poll (service restart, or this run fell out of the store's bounded history) must
        // not blank the label back to the generic line: keep whatever step was last seen.
        .then((p) => {
          if (p.steps.length > 0) { setStep(p.steps[p.steps.length - 1]!); setTools(toolsFromSteps(p.steps)) }
          // Same rule for the live text: a poll with nothing in it keeps what is already on screen.
          if (p.thinking) setThinking(p.thinking)
          if (p.answer) setAnswer(p.answer)
        })
        .catch(() => {})  // a poll that fails is not worth failing the run over; the next one may work
    }
    tick()
    const timer = setInterval(tick, 1000)
    return () => clearInterval(timer)
  }, [runId])

  return { step, elapsed, thinking, answer, tools }
}

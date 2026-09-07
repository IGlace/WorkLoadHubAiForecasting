import type React from 'react'
import { useEffect, useRef } from 'react'
import type { NarrativeProgressStep } from '../../../shared/types'
import { t } from '../i18n'
import { progressLabel, type NarrativeTool } from '../narrative-progress'
import { StatusMessage } from './StatusMessage'

/** Scrolls a growing text box to its last line, so the newest words stay in view. */
function useScrolledToBottom(text: string): React.RefObject<HTMLPreElement | null> {
  const ref = useRef<HTMLPreElement>(null)
  useEffect(() => { if (ref.current) ref.current.scrollTop = ref.current.scrollHeight }, [text])
  return ref
}

/**
 * What Copilot is doing, thinking and writing while the narrative runs. A narration takes minutes,
 * so the coded step alone leaves the user staring at one line: the reasoning and the answer as it
 * is written are shown as they arrive. Both are collapsible, and the answer only appears once
 * there is one — an empty box would read as a failure.
 */
export function NarrativeLive({ step, elapsed, thinking, answer, tools }: {
  step: NarrativeProgressStep | null
  elapsed: number
  thinking: string
  answer: string
  tools: NarrativeTool[]
}): React.JSX.Element {
  const thinkingRef = useScrolledToBottom(thinking)
  const answerRef = useScrolledToBottom(answer)
  return (
    <>
      <StatusMessage kind="info">
        {progressLabel(step)} <span className="muted">{t('run.progress.elapsed', { seconds: String(elapsed) })}</span>
      </StatusMessage>
      <details open>
        <summary>{t('run.live.thinking')}</summary>
        <pre className="live" ref={thinkingRef}>
          {thinking || <span className="muted">{t('run.live.none')}</span>}
        </pre>
      </details>
      {answer !== '' && (
        <details open>
          <summary>{t('run.live.answer')}</summary>
          <pre className="live" ref={answerRef}>{answer}</pre>
        </details>
      )}
      {tools.length > 0 && (
        <p className="muted">
          {t('run.live.tools')}: {tools.map((tool) => `${tool.name}${tool.done ? ' ✓' : ''}`).join(', ')}
        </p>
      )}
    </>
  )
}

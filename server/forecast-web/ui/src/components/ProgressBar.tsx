import type { RunProgress } from '../types'

/** What a person sees while a run or a narration is in progress: the phase, the percent and the bilingual label. */
export function ProgressBar({ progress }: { progress: RunProgress | null }) {
  if (!progress) return null
  return (
    <div>
      <div className="row spread small">
        <span><b>{progress.phase}</b> · {progress.label.en} / {progress.label.fr}</span>
        <span className="muted mono">{progress.message}</span>
      </div>
      <div className="progress"><div style={{ width: progress.percent + '%' }} /></div>
    </div>
  )
}

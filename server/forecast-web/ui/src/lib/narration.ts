/**
 * What a narration poll should do with the phase it just read.
 *
 * `POST /api/forecast-runs/{id}/narratives` answers 202 before the narration runs: the host submits it to its
 * own executor, and the phase only becomes `NARRATING` once a worker picks it up. So the first poll usually
 * still reads the run's own `DONE`, and a poll that treated that as terminal would stop before the narration
 * even started, leaving the page with no label and no narrative while Copilot was working.
 */
export type PollAction = 'wait' | 'running' | 'settled' | 'gave-up'

export const NARRATION_START_TIMEOUT_MS = 60_000

/**
 * @param phase the phase just read from the progress route
 * @param sawNarrating whether any earlier poll of this narration already saw NARRATING
 * @param waitedMs how long since the narration was asked for
 */
export function pollAction(phase: string | null | undefined, sawNarrating: boolean, waitedMs = 0): PollAction {
  if (phase === 'NARRATED' || phase === 'NARRATION_FAILED') return 'settled'
  if (phase === 'NARRATING') return 'running'
  // DONE, FAILED or a phase of a forecast run: the narration has not reached a worker yet. Once it has been
  // seen running, a phase that is not a narration phase means the tracker moved on, so stop and read the row.
  if (sawNarrating) return 'settled'
  return waitedMs >= NARRATION_START_TIMEOUT_MS ? 'gave-up' : 'wait'
}

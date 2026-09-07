import { useCallback, useEffect, useRef, useState } from 'react'
import type { CopilotStatus } from '../../../shared/types'
import { getCopilotStatus } from '../api'

const DEFAULT_POLL_MS = 4000
const DEFAULT_MAX_POLL_MS = 600000

export interface UseCopilotStatusResult {
  status: CopilotStatus | null
  error: string | null
  loginPending: boolean
  timedOut: boolean
  startLogin: () => Promise<void>
  loginMessage: string | null
}

/**
 * Tracks the GitHub Copilot sign-in status. The device-login flow finishes in a separate
 * PowerShell window, so a one-shot fetch on mount goes stale the moment the user switches back:
 * this hook also refetches whenever the window regains focus or the tab becomes visible again,
 * and — once the user starts a sign-in — polls until the service reports `ready` or gives up
 * after `maxPollMs`, so the page updates itself instead of needing a manual "check again".
 */
export function useCopilotStatus(options: { pollMs?: number; maxPollMs?: number } = {}): UseCopilotStatusResult {
  const pollMs = options.pollMs ?? DEFAULT_POLL_MS
  const maxPollMs = options.maxPollMs ?? DEFAULT_MAX_POLL_MS
  const [status, setStatus] = useState<CopilotStatus | null>(null)
  const [error, setError] = useState<string | null>(null)
  const [loginPending, setLoginPending] = useState(false)
  const [timedOut, setTimedOut] = useState(false)
  const [loginMessage, setLoginMessage] = useState<string | null>(null)

  // Bumped on unmount and on every new startLogin call, so a fetch or poll tick started before
  // the bump knows it has been superseded and must not set state for a run nobody cares about
  // any more (the `cancelled` flag pattern, generalised to cover a whole poll sequence).
  const generationRef = useRef(0)
  const pollTimerRef = useRef<ReturnType<typeof setTimeout> | null>(null)
  // Bumped on every fetch this hook issues (mount, a focus/visibility refetch, or a poll tick).
  // Responses can arrive out of order — a slow focus refetch can outlive a poll tick started
  // after it — so a response is only applied when it is still the most recently issued fetch.
  const fetchSeqRef = useRef(0)

  const clearPoll = useCallback((): void => {
    if (pollTimerRef.current !== null) { clearTimeout(pollTimerRef.current); pollTimerRef.current = null }
  }, [])

  const refetch = useCallback((): void => {
    const generation = generationRef.current
    const seq = ++fetchSeqRef.current
    getCopilotStatus()
      .then((s) => { if (generation === generationRef.current && seq === fetchSeqRef.current) { setStatus(s); setError(null) } })
      .catch((e: Error) => { if (generation === generationRef.current && seq === fetchSeqRef.current) setError(e.message) })
  }, [])

  // Fetch once on mount.
  useEffect(() => { refetch() }, [refetch])

  // Refetch whenever the window regains focus or the page becomes visible again: the user may
  // just have finished the device-login flow in the separate terminal window.
  useEffect(() => {
    const onFocus = (): void => refetch()
    const onVisibility = (): void => { if (document.visibilityState === 'visible') refetch() }
    window.addEventListener('focus', onFocus)
    document.addEventListener('visibilitychange', onVisibility)
    return () => {
      window.removeEventListener('focus', onFocus)
      document.removeEventListener('visibilitychange', onVisibility)
    }
  }, [refetch])

  // Render-time state adjustment (not an effect): whichever fetch (mount, focus, visibility, or
  // a poll tick) is the one that first sees `ready`, stop polling, drop the login message and
  // clear a stale timeout before this render commits — the sign-in is done, no matter how the
  // app found out. The guard makes this a no-op once all three are already at rest.
  if (status?.ready && (loginPending || loginMessage !== null || timedOut)) {
    setLoginPending(false)
    setLoginMessage(null)
    setTimedOut(false)
  }

  useEffect(() => () => { generationRef.current += 1; clearPoll() }, [clearPoll])

  const startLogin = useCallback(async (): Promise<void> => {
    generationRef.current += 1
    const generation = generationRef.current
    clearPoll()
    setError(null)
    setTimedOut(false)
    try {
      const result = await window.whf.copilotLogin()
      if (generation !== generationRef.current) return
      setLoginMessage(result.code)
      if (!result.started) return
      setLoginPending(true)
      const startedAt = Date.now()
      const tick = (): void => {
        if (generation !== generationRef.current) return
        const seq = ++fetchSeqRef.current
        getCopilotStatus()
          .then((s) => {
            if (generation !== generationRef.current || seq !== fetchSeqRef.current) return
            setStatus(s)
            setError(null)
            // The login-started sentence stays up for as long as polling continues; it is only
            // cleared once the status is ready (see the render-time adjustment above).
            if (s.ready) { setLoginPending(false); return }
            if (Date.now() - startedAt >= maxPollMs) { setLoginPending(false); setTimedOut(true); return }
            pollTimerRef.current = setTimeout(tick, pollMs)
          })
          .catch((e: Error) => {
            if (generation !== generationRef.current || seq !== fetchSeqRef.current) return
            setError(e.message)
            if (Date.now() - startedAt >= maxPollMs) { setLoginPending(false); setTimedOut(true); return }
            pollTimerRef.current = setTimeout(tick, pollMs)
          })
      }
      pollTimerRef.current = setTimeout(tick, pollMs)
    } catch (e) {
      if (generation === generationRef.current) setError((e as Error).message)
    }
  }, [pollMs, maxPollMs, clearPoll])

  return { status, error, loginPending, timedOut, startLogin, loginMessage }
}

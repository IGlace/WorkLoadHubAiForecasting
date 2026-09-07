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

  const clearPoll = useCallback((): void => {
    if (pollTimerRef.current !== null) { clearTimeout(pollTimerRef.current); pollTimerRef.current = null }
  }, [])

  // Fetch once on mount.
  useEffect(() => {
    const generation = generationRef.current
    getCopilotStatus()
      .then((s) => { if (generation === generationRef.current) { setStatus(s); setError(null) } })
      .catch((e: Error) => { if (generation === generationRef.current) setError(e.message) })
  }, [])

  // Refetch whenever the window regains focus or the page becomes visible again: the user may
  // just have finished the device-login flow in the separate terminal window.
  useEffect(() => {
    const refetch = (): void => {
      const generation = generationRef.current
      getCopilotStatus()
        .then((s) => { if (generation === generationRef.current) { setStatus(s); setError(null) } })
        .catch((e: Error) => { if (generation === generationRef.current) setError(e.message) })
    }
    const onFocus = (): void => refetch()
    const onVisibility = (): void => { if (document.visibilityState === 'visible') refetch() }
    window.addEventListener('focus', onFocus)
    document.addEventListener('visibilitychange', onVisibility)
    return () => {
      window.removeEventListener('focus', onFocus)
      document.removeEventListener('visibilitychange', onVisibility)
    }
  }, [])

  // Render-time state adjustment (not an effect): whichever fetch (mount, focus, or a poll
  // tick) is the one that first sees `ready`, stop polling and drop the login message before
  // this render commits — the sign-in is done, no matter how the app found out. The guard
  // makes this a no-op once both are already at rest, so it does not loop.
  if (status?.ready && (loginPending || loginMessage !== null)) {
    setLoginPending(false)
    setLoginMessage(null)
  }

  useEffect(() => () => { generationRef.current += 1; clearPoll() }, [clearPoll])

  const startLogin = useCallback(async (): Promise<void> => {
    generationRef.current += 1
    const generation = generationRef.current
    clearPoll()
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
        getCopilotStatus()
          .then((s) => {
            if (generation !== generationRef.current) return
            setStatus(s)
            setError(null)
            // The login-started sentence has done its job once the first check comes back;
            // from here a plain "waiting" message is enough until the status is ready.
            setLoginMessage(null)
            if (s.ready) { setLoginPending(false); return }
            if (Date.now() - startedAt >= maxPollMs) { setLoginPending(false); setTimedOut(true); return }
            pollTimerRef.current = setTimeout(tick, pollMs)
          })
          .catch((e: Error) => {
            if (generation !== generationRef.current) return
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

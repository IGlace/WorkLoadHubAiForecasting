import { act, renderHook, waitFor } from '@testing-library/react'
import type { NarrativeProgress } from '../../../shared/types'
import { setLanguage } from '../i18n'
import { progressLabel, useNarrativeProgress } from '../narrative-progress'
import { installFakeWhf } from '../test/fake-whf'

const step = (code: string, detail: string | null = null) => ({ code, detail, at: '2026-09-04T10:00:00' }) as never

describe('progressLabel', () => {
  beforeEach(() => setLanguage('en'))

  it('falls back to the generic line before the first step arrives', () => {
    expect(progressLabel(null)).toBe('Asking Copilot to explain the forecast…')
  })
  it('phrases each step', () => {
    expect(progressLabel(step('starting'))).toBe('Starting Copilot…')
    expect(progressLabel(step('session'))).toBe('Opening a Copilot session…')
    expect(progressLabel(step('checking'))).toBe('Checking the answer against the numbers…')
  })
  it('names the tool Copilot is reading, and says when it has finished with it', () => {
    expect(progressLabel(step('tool', 'team_overview'))).toBe('Copilot is reading team_overview…')
    expect(progressLabel(step('tool_done', 'team_overview'))).toBe('Copilot finished reading team_overview…')
  })
  it('says nothing about attempts on the first attempt, and says so on a retry', () => {
    expect(progressLabel(step('asking', '1'))).toBe('Copilot is writing the explanation…')
    expect(progressLabel(step('asking', '2'))).toBe('Copilot is trying again (attempt 2)…')
  })
  it('phrases them in French too', () => {
    setLanguage('fr')
    expect(progressLabel(step('starting'))).toBe('Démarrage de Copilot…')
    expect(progressLabel(step('tool', 'team_overview'))).toBe('Copilot consulte team_overview…')
    expect(progressLabel(step('tool_done', 'team_overview'))).toBe('Copilot a fini de consulter team_overview…')
  })
  it('falls back to the generic line for a code it does not know', () => {
    // The service may add a step before the app is updated; an unknown code must not blank the line.
    expect(progressLabel(step('something_new'))).toBe('Asking Copilot to explain the forecast…')
  })
})

describe('useNarrativeProgress', () => {
  const poll = (over: Partial<NarrativeProgress> = {}): NarrativeProgress =>
    ({ run_id: 5, steps: [], thinking: '', answer: '', ...over })

  beforeEach(() => setLanguage('en'))
  afterEach(() => { vi.useRealTimers() })

  it('lists the tools Copilot has read, and marks the finished ones', async () => {
    installFakeWhf({ 'GET /runs/5/narrative/progress': poll({ steps: [
      step('tool', 'team_overview'), step('tool', 'member_history'), step('tool_done', 'team_overview'),
    ] }) })
    const { result } = renderHook(() => useNarrativeProgress(5))
    await waitFor(() => expect(result.current.tools).toEqual([
      { name: 'team_overview', done: true }, { name: 'member_history', done: false },
    ]))
  })

  it('marks the earliest unfinished call when the same tool is read twice', async () => {
    installFakeWhf({ 'GET /runs/5/narrative/progress': poll({ steps: [
      step('tool', 'member_history'), step('tool', 'member_history'), step('tool_done', 'member_history'),
    ] }) })
    const { result } = renderHook(() => useNarrativeProgress(5))
    await waitFor(() => expect(result.current.tools).toEqual([
      { name: 'member_history', done: true }, { name: 'member_history', done: false },
    ]))
  })

  it('marks the earliest unfinished call when the service could not name the tool', async () => {
    installFakeWhf({ 'GET /runs/5/narrative/progress': poll({ steps: [
      step('tool', 'team_overview'), step('tool_done', null),
    ] }) })
    const { result } = renderHook(() => useNarrativeProgress(5))
    await waitFor(() => expect(result.current.tools).toEqual([{ name: 'team_overview', done: true }]))
  })

  it('shows what Copilot is thinking and the answer as it is written', async () => {
    installFakeWhf({ 'GET /runs/5/narrative/progress': poll({ thinking: 'Reading capacity…', answer: '{"run' }) })
    const { result } = renderHook(() => useNarrativeProgress(5))
    await waitFor(() => expect(result.current.thinking).toBe('Reading capacity…'))
    expect(result.current.answer).toBe('{"run')
  })

  it('keeps the live text when a poll comes back empty', async () => {
    let polls = 0
    installFakeWhf({ 'GET /runs/5/narrative/progress': () => {
      polls += 1
      return polls === 1 ? poll({ thinking: 'Reading capacity…', answer: '{"run', steps: [step('tool', 'team_overview')] }) : poll()
    } })
    const { result } = renderHook(() => useNarrativeProgress(5))
    await waitFor(() => expect(result.current.thinking).toBe('Reading capacity…'))
    vi.useFakeTimers()
    // The service restarted, or this run fell out of the store's bounded history: the panel must
    // keep what it has rather than blanking out.
    await act(async () => { await vi.advanceTimersByTimeAsync(1000) })
    expect(result.current.thinking).toBe('Reading capacity…')
    expect(result.current.answer).toBe('{"run')
    expect(result.current.tools).toEqual([{ name: 'team_overview', done: false }])
  })

  it('forgets the previous narration when the run changes', async () => {
    installFakeWhf({ 'GET /runs/5/narrative/progress': poll({ thinking: 'Reading capacity…', steps: [step('tool', 'team_overview')] }) })
    const { result, rerender } = renderHook(({ id }: { id: number | null }) => useNarrativeProgress(id), {
      initialProps: { id: 5 as number | null },
    })
    await waitFor(() => expect(result.current.thinking).toBe('Reading capacity…'))
    rerender({ id: null })
    expect(result.current.thinking).toBe('')
    expect(result.current.answer).toBe('')
    expect(result.current.tools).toEqual([])
    expect(result.current.step).toBeNull()
  })
})

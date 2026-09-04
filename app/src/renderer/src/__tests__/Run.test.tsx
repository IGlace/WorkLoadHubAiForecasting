import { act, render, screen, waitFor, within } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import { MemoryRouter } from 'react-router-dom'
import { vi } from 'vitest'
import { AppProvider } from '../context'
import { Run } from '../pages/Run'
import { installFakeWhf, META } from '../test/fake-whf'
import { RUN_CREATED } from '../test/fixtures'

const ready = { cli_path: 'c', cli_source: 'path', authenticated: true, login: 'ali', message: 'ok', ready: true }

describe('Run', () => {
  it('runs the forecast then the narrative and links to the result', async () => {
    const fake = installFakeWhf({
      'GET /meta': META, 'GET /profile': { member_id: 11, role: 'team_leader' }, 'GET /copilot/status': ready,
      'POST /runs': RUN_CREATED, 'POST /runs/5/narrative': { run_id: 5, status: 'ok', ai_status: 'ok', narrative: null, error: null, reason: null, attempts: 1, tool_calls: ['get_run_overview'] },
    })
    render(<MemoryRouter initialEntries={['/run?team=1']}><AppProvider><Run /></AppProvider></MemoryRouter>)
    await userEvent.click(await screen.findByRole('button', { name: 'Run forecast' }))
    expect(await screen.findByText('Forecast complete')).toBeInTheDocument()
    expect(screen.getByRole('link', { name: 'Open the result' })).toHaveAttribute('href', '/runs/5')
    await waitFor(() => expect(fake.calls.some((c) => c.path === '/runs/5/narrative')).toBe(true))
    const body = fake.calls.find((c) => c.path === '/runs')!.body as { team_id: number; requested_by: number }
    expect(body.team_id).toBe(1)
    expect(body.requested_by).toBe(11)
  })
  it('keeps the forecast when the narrative fails and shows the reason', async () => {
    installFakeWhf({
      'GET /meta': META, 'GET /profile': { member_id: 10, role: 'skill_team_leader' }, 'GET /copilot/status': ready,
      'POST /runs': RUN_CREATED, 'POST /runs/5/narrative': { run_id: 5, status: 'failed', ai_status: 'failed:timeout', narrative: null, error: 'timed out', reason: 'timeout', attempts: 1, tool_calls: [] },
    })
    render(<MemoryRouter initialEntries={['/run']}><AppProvider><Run /></AppProvider></MemoryRouter>)
    await userEvent.selectOptions(await screen.findByLabelText('Team'), '1')
    expect(screen.getByText('You are running this forecast on behalf of Ali Benjelloun.')).toBeInTheDocument()
    await userEvent.click(screen.getByRole('button', { name: 'Run forecast' }))
    expect(await screen.findByText('Forecast complete')).toBeInTheDocument()
    expect(await screen.findByText('Copilot narrative failed: timed out')).toBeInTheDocument()
  })
  it('keeps the completed forecast when the narrative request itself errors', async () => {
    installFakeWhf({
      'GET /meta': META, 'GET /profile': { member_id: 11, role: 'team_leader' }, 'GET /copilot/status': ready,
      'POST /runs': RUN_CREATED, 'POST /runs/5/narrative': () => new Error('network down'),
    })
    render(<MemoryRouter initialEntries={['/run?team=1']}><AppProvider><Run /></AppProvider></MemoryRouter>)
    await userEvent.click(await screen.findByRole('button', { name: 'Run forecast' }))
    expect(await screen.findByText('Forecast complete')).toBeInTheDocument()
    expect(await screen.findByText('Copilot narrative failed: network down')).toBeInTheDocument()
    expect(screen.getByRole('link', { name: 'Open the result' })).toHaveAttribute('href', '/runs/5')
  })
  it('omits the empty team option when exactly one team is visible', async () => {
    installFakeWhf({ 'GET /meta': META, 'GET /profile': { member_id: 11, role: 'team_leader' }, 'GET /copilot/status': ready })
    render(<MemoryRouter><AppProvider><Run /></AppProvider></MemoryRouter>)
    const select = await screen.findByLabelText('Team')
    expect(within(select).queryByRole('option', { name: '–' })).not.toBeInTheDocument()
    expect(select).toHaveValue('1')
  })
  it('disables the AI step when Copilot is not ready', async () => {
    installFakeWhf({ 'GET /meta': META, 'GET /profile': { member_id: 11, role: 'team_leader' }, 'GET /copilot/status': { ...ready, ready: false, authenticated: false, message: 'Not signed in' } })
    render(<MemoryRouter><AppProvider><Run /></AppProvider></MemoryRouter>)
    const ai = await screen.findByLabelText('Ask Copilot for the narrative')
    expect(ai).toBeDisabled()
    expect(screen.getByText('Not signed in')).toBeInTheDocument()
  })
  it('shows what Copilot is doing while the narrative is still running', async () => {
    let finish: (value: unknown) => void = () => {}
    const held = new Promise((resolve) => { finish = resolve })
    installFakeWhf({
      'GET /meta': META, 'GET /profile': { member_id: 11, role: 'team_leader' }, 'GET /copilot/status': ready,
      'POST /runs': RUN_CREATED,
      'GET /runs/5/narrative/progress': { run_id: 5, steps: [{ code: 'tool', detail: 'team_overview', at: '2026-09-04T10:00:00' }] },
      'POST /runs/5/narrative': () => held,
    })
    render(<MemoryRouter initialEntries={['/run?team=1']}><AppProvider><Run /></AppProvider></MemoryRouter>)
    const button = await screen.findByRole('button', { name: 'Run forecast' })
    // See the "stops polling" test below for why this wait matters: the "with AI" checkbox only
    // enables once the unawaited copilot-status mount effect resolves, and that race decides whether
    // the run ever reaches the narrating phase this test depends on.
    await waitFor(() => expect(screen.getByLabelText('Ask Copilot for the narrative')).not.toBeDisabled())
    // Fake timers so the elapsed counter shown next to the step label can be pinned to an exact value.
    vi.useFakeTimers()
    try {
      const user = userEvent.setup({ advanceTimers: vi.advanceTimersByTime })
      const clickPromise = user.click(button)
      await act(async () => { await vi.advanceTimersByTimeAsync(500) })
      await clickPromise
      expect(screen.getByText(/Copilot is reading team_overview…/)).toBeInTheDocument()
      expect(screen.getByText('0 s so far')).toBeInTheDocument()
      await act(async () => { await vi.advanceTimersByTimeAsync(3000) })
      expect(screen.getByText('3 s so far')).toBeInTheDocument()
      finish({ run_id: 5, status: 'ok', ai_status: 'ok', narrative: null, error: null, reason: null, attempts: 1, tool_calls: [] })
      await act(async () => { await vi.advanceTimersByTimeAsync(0) })
      expect(screen.getByText('Forecast complete')).toBeInTheDocument()
    } finally {
      vi.useRealTimers()
    }
  })
  it('keeps the last known step instead of reverting to the generic message when a poll comes back empty', async () => {
    let finish: (value: unknown) => void = () => {}
    const held = new Promise((resolve) => { finish = resolve })
    let pollCount = 0
    installFakeWhf({
      'GET /meta': META, 'GET /profile': { member_id: 11, role: 'team_leader' }, 'GET /copilot/status': ready,
      'POST /runs': RUN_CREATED,
      'GET /runs/5/narrative/progress': () => {
        pollCount += 1
        return pollCount === 1
          ? { run_id: 5, steps: [{ code: 'tool', detail: 'team_overview', at: '2026-09-04T10:00:00' }] }
          : { run_id: 5, steps: [] }
      },
      'POST /runs/5/narrative': () => held,
    })
    render(<MemoryRouter initialEntries={['/run?team=1']}><AppProvider><Run /></AppProvider></MemoryRouter>)
    const button = await screen.findByRole('button', { name: 'Run forecast' })
    // See the "stops polling" test below for why this wait matters: the "with AI" checkbox only
    // enables once the unawaited copilot-status mount effect resolves, and that race decides whether
    // the run ever reaches the narrating phase this test depends on.
    await waitFor(() => expect(screen.getByLabelText('Ask Copilot for the narrative')).not.toBeDisabled())
    vi.useFakeTimers()
    try {
      const user = userEvent.setup({ advanceTimers: vi.advanceTimersByTime })
      const clickPromise = user.click(button)
      await act(async () => { await vi.advanceTimersByTimeAsync(500) })
      await clickPromise
      expect(screen.getByText(/Copilot is reading team_overview…/)).toBeInTheDocument()
      // The next poll (the service restarted, or this run fell out of the store's history) returns no
      // steps: the label must stay put rather than reverting to the generic "asking Copilot" line.
      await act(async () => { await vi.advanceTimersByTimeAsync(1000) })
      expect(screen.getByText(/Copilot is reading team_overview…/)).toBeInTheDocument()
      finish({ run_id: 5, status: 'ok', ai_status: 'ok', narrative: null, error: null, reason: null, attempts: 1, tool_calls: [] })
      await act(async () => { await vi.advanceTimersByTimeAsync(0) })
      expect(screen.getByText('Forecast complete')).toBeInTheDocument()
    } finally {
      vi.useRealTimers()
    }
  })
  it('stops polling for progress once the narrative is done', async () => {
    let finish: (value: unknown) => void = () => {}
    const held = new Promise((resolve) => { finish = resolve })
    const fake = installFakeWhf({
      'GET /meta': META, 'GET /profile': { member_id: 11, role: 'team_leader' }, 'GET /copilot/status': ready,
      'POST /runs': RUN_CREATED,
      'GET /runs/5/narrative/progress': { run_id: 5, steps: [{ code: 'tool', detail: 'team_overview', at: '2026-09-04T10:00:00' }] },
      'POST /runs/5/narrative': () => held,
    })
    render(<MemoryRouter initialEntries={['/run?team=1']}><AppProvider><Run /></AppProvider></MemoryRouter>)
    // Let the initial mount (context loads, copilot status) settle with real timers before switching to
    // fake ones: the interval this test cares about is only created once the run reaches the narrating
    // phase, so it must be created — and ticked — entirely under the fake clock to mean anything.
    const button = await screen.findByRole('button', { name: 'Run forecast' })
    // The "with AI" checkbox is disabled until copilot status has arrived (Run.tsx:71); that status is
    // set by an unawaited mount effect racing the button's own readiness. Wait for the checkbox to be
    // enabled under real timers before installing fake ones, or a slow status response makes the run
    // skip straight from forecasting to done with no narration and zero polls.
    await waitFor(() => expect(screen.getByLabelText('Ask Copilot for the narrative')).not.toBeDisabled())
    vi.useFakeTimers()
    try {
      const user = userEvent.setup({ advanceTimers: vi.advanceTimersByTime })
      // Fire the click without awaiting it yet: userEvent's internal waits need the fake clock advanced
      // concurrently, or the click promise itself never settles.
      const clickPromise = user.click(button)
      await act(async () => { await vi.advanceTimersByTimeAsync(500) })
      await clickPromise
      const progressCalls = (): number => fake.calls.filter((c) => c.path === '/runs/5/narrative/progress').length
      // Advance past several poll intervals while the narrative is still held open, to prove polling is
      // actually happening (and not just theoretically wired up).
      for (let i = 0; i < 3; i++) {
        await act(async () => { await vi.advanceTimersByTimeAsync(1000) })
      }
      const duringNarrating = progressCalls()
      expect(duringNarrating).toBeGreaterThan(0)
      finish({ run_id: 5, status: 'ok', ai_status: 'ok', narrative: null, error: null, reason: null, attempts: 1, tool_calls: [] })
      await act(async () => { await vi.advanceTimersByTimeAsync(0) })
      expect(screen.getByText('Forecast complete')).toBeInTheDocument()
      const afterDone = progressCalls()
      await act(async () => { await vi.advanceTimersByTimeAsync(3000) })
      expect(progressCalls()).toBe(afterDone)
    } finally {
      vi.useRealTimers()
    }
  })
})

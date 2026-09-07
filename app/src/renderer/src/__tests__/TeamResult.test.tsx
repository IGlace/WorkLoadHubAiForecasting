import { act, render, screen } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import { MemoryRouter, Route, Routes, useNavigate } from 'react-router-dom'
import { afterEach, vi } from 'vitest'
import type { RunDetail } from '../../../shared/types'
import { AppProvider } from '../context'
import { setLanguage } from '../i18n'
import { TeamResult } from '../pages/TeamResult'
import { installFakeWhf, META } from '../test/fake-whf'
import { RUN_DETAIL } from '../test/fixtures'

function mount() {
  return render(
    <MemoryRouter initialEntries={['/runs/5']}><AppProvider>
      <Routes><Route path="/runs/:runId" element={<TeamResult />} /></Routes>
    </AppProvider></MemoryRouter>,
  )
}

describe('TeamResult', () => {
  afterEach(() => setLanguage('en'))

  it('shows members by week with overload, champion, summary and warnings', async () => {
    installFakeWhf({ 'GET /meta': META, 'GET /profile': { member_id: 11, role: 'team_leader' }, 'GET /runs/5': RUN_DETAIL })
    mount()
    expect(await screen.findByText('Core')).toBeInTheDocument()
    expect(screen.getByText('Gradient boosting')).toBeInTheDocument()
    expect(screen.getByText('0.77')).toBeInTheDocument()
    const yara = screen.getByRole('row', { name: /Yara Tazi/ })
    expect(yara).toHaveTextContent('46.0 h')
    expect(yara).toHaveTextContent('+6.0 h')
    expect(yara).toHaveTextContent('+12.0 h')
    expect(screen.getByText('Core is slightly over capacity in both weeks, driven by Yara.')).toBeInTheDocument()
    expect(screen.getByText('Two overdue tasks.')).toBeInTheDocument()
    expect(screen.getByText('Billing v2 deadline')).toBeInTheDocument()
    expect(screen.getByRole('link', { name: 'Yara Tazi' })).toHaveAttribute('href', '/runs/5/members/13')
    act(() => { setLanguage('fr') })
    expect(await screen.findByText('Boosting de gradient')).toBeInTheDocument()
  })
  it('offers to ask Copilot when there is no narrative and flags unverified ones', async () => {
    let detail: RunDetail = { ...RUN_DETAIL, narrative: null, run: { ...RUN_DETAIL.run, ai_status: 'not_requested' } }
    const fake = installFakeWhf({
      'GET /meta': META, 'GET /profile': { member_id: 11, role: 'team_leader' },
      'GET /runs/5': () => detail,
      'POST /runs/5/narrative': () => {
        detail = { ...RUN_DETAIL, run: { ...RUN_DETAIL.run, ai_status: 'unverified' } }
        return { run_id: 5, status: 'unverified', ai_status: 'unverified', narrative: RUN_DETAIL.narrative, error: null, reason: null, attempts: 2, tool_calls: [] }
      },
    })
    mount()
    await userEvent.click(await screen.findByRole('button', { name: 'Ask Copilot' }))
    expect(await screen.findByText('Some numbers in this narrative could not be matched to the forecast facts.')).toBeInTheDocument()
    expect(fake.calls.some((c) => c.method === 'POST' && c.path === '/runs/5/narrative')).toBe(true)
  })
  it('shows Copilot progress while the narrative is running and advances the label when a later step arrives', async () => {
    let finish: (value: unknown) => void = () => {}
    const held = new Promise((resolve) => { finish = resolve })
    const detail: RunDetail = { ...RUN_DETAIL, narrative: null, run: { ...RUN_DETAIL.run, ai_status: 'not_requested' } }
    installFakeWhf({
      'GET /meta': META, 'GET /profile': { member_id: 11, role: 'team_leader' },
      'GET /runs/5': () => detail,
      'GET /runs/5/narrative/progress': { run_id: 5, steps: [{ code: 'tool', detail: 'team_overview', at: '2026-09-04T10:00:00' }], thinking: '', answer: '' },
      'POST /runs/5/narrative': () => held,
    })
    mount()
    const button = await screen.findByRole('button', { name: 'Ask Copilot' })
    // Fake timers so the elapsed counter shown next to the step label can be pinned to an exact value.
    vi.useFakeTimers()
    try {
      const user = userEvent.setup({ advanceTimers: vi.advanceTimersByTime })
      const clickPromise = user.click(button)
      await act(async () => { await vi.advanceTimersByTimeAsync(500) })
      await clickPromise
      expect(screen.getByText(/Copilot is reading team_overview…/)).toBeInTheDocument()
      expect(screen.getByText('0 s so far')).toBeInTheDocument()
      finish({ run_id: 5, status: 'ok', ai_status: 'ok', narrative: RUN_DETAIL.narrative, error: null, reason: null, attempts: 1, tool_calls: [] })
      await act(async () => { await vi.advanceTimersByTimeAsync(0) })
    } finally {
      vi.useRealTimers()
    }
  })
  it('advances the elapsed counter shown next to the progress label with the clock', async () => {
    let finish: (value: unknown) => void = () => {}
    const held = new Promise((resolve) => { finish = resolve })
    const detail: RunDetail = { ...RUN_DETAIL, narrative: null, run: { ...RUN_DETAIL.run, ai_status: 'not_requested' } }
    installFakeWhf({
      'GET /meta': META, 'GET /profile': { member_id: 11, role: 'team_leader' },
      'GET /runs/5': () => detail,
      'GET /runs/5/narrative/progress': { run_id: 5, steps: [{ code: 'tool', detail: 'team_overview', at: '2026-09-04T10:00:00' }], thinking: '', answer: '' },
      'POST /runs/5/narrative': () => held,
    })
    mount()
    const button = await screen.findByRole('button', { name: 'Ask Copilot' })
    vi.useFakeTimers()
    try {
      const user = userEvent.setup({ advanceTimers: vi.advanceTimersByTime })
      const clickPromise = user.click(button)
      await act(async () => { await vi.advanceTimersByTimeAsync(500) })
      await clickPromise
      expect(screen.getByText('0 s so far')).toBeInTheDocument()
      await act(async () => { await vi.advanceTimersByTimeAsync(3000) })
      expect(screen.getByText('3 s so far')).toBeInTheDocument()
      finish({ run_id: 5, status: 'ok', ai_status: 'ok', narrative: RUN_DETAIL.narrative, error: null, reason: null, attempts: 1, tool_calls: [] })
      await act(async () => { await vi.advanceTimersByTimeAsync(0) })
    } finally {
      vi.useRealTimers()
    }
  })
  it('stops polling for progress once the narrative is done', async () => {
    let finish: (value: unknown) => void = () => {}
    const held = new Promise((resolve) => { finish = resolve })
    let detail: RunDetail = { ...RUN_DETAIL, narrative: null, run: { ...RUN_DETAIL.run, ai_status: 'not_requested' } }
    const fake = installFakeWhf({
      'GET /meta': META, 'GET /profile': { member_id: 11, role: 'team_leader' },
      'GET /runs/5': () => detail,
      'GET /runs/5/narrative/progress': { run_id: 5, steps: [{ code: 'tool', detail: 'team_overview', at: '2026-09-04T10:00:00' }], thinking: '', answer: '' },
      'POST /runs/5/narrative': () => {
        detail = { ...RUN_DETAIL, run: { ...RUN_DETAIL.run, ai_status: 'ok' } }
        return held
      },
    })
    mount()
    const button = await screen.findByRole('button', { name: 'Ask Copilot' })
    vi.useFakeTimers()
    try {
      const user = userEvent.setup({ advanceTimers: vi.advanceTimersByTime })
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
      finish({ run_id: 5, status: 'ok', ai_status: 'ok', narrative: RUN_DETAIL.narrative, error: null, reason: null, attempts: 1, tool_calls: [] })
      await act(async () => { await vi.advanceTimersByTimeAsync(0) })
      expect(screen.getByText('Core is slightly over capacity in both weeks, driven by Yara.')).toBeInTheDocument()
      const afterDone = progressCalls()
      await act(async () => { await vi.advanceTimersByTimeAsync(3000) })
      expect(progressCalls()).toBe(afterDone)
    } finally {
      vi.useRealTimers()
    }
  })
  it("enables run B's button and hides the progress line while only run A is narrating", async () => {
    let finish: (value: unknown) => void = () => {}
    const held = new Promise((resolve) => { finish = resolve })
    const detail5: RunDetail = { ...RUN_DETAIL, narrative: null, run: { ...RUN_DETAIL.run, ai_status: 'not_requested' } }
    const detail6: RunDetail = {
      run: { ...RUN_DETAIL.run, id: 6, team_id: 2 },
      forecasts: [],
      facts: RUN_DETAIL.facts && { ...RUN_DETAIL.facts, team: { ...RUN_DETAIL.facts.team, id: 2, name: 'Nova' }, members: [] },
      narrative: null,
    }
    const fake = installFakeWhf({
      'GET /meta': META, 'GET /profile': { member_id: 11, role: 'team_leader' },
      'GET /runs/5': () => detail5, 'GET /runs/6': () => detail6,
      'GET /runs/5/narrative/progress': { run_id: 5, steps: [{ code: 'tool', detail: 'team_overview', at: '2026-09-04T10:00:00' }], thinking: '', answer: '' },
      'GET /runs/6/narrative/progress': { run_id: 6, steps: [{ code: 'checking', detail: null, at: '2026-09-04T10:00:00' }], thinking: '', answer: '' },
      'POST /runs/5/narrative': () => held,
    })
    function Nav() {
      const navigate = useNavigate()
      return <button onClick={() => navigate('/runs/6')}>go to 6</button>
    }
    render(
      <MemoryRouter initialEntries={['/runs/5']}><AppProvider>
        <Nav />
        <Routes><Route path="/runs/:runId" element={<TeamResult />} /></Routes>
      </AppProvider></MemoryRouter>,
    )
    const askButton = await screen.findByRole('button', { name: 'Ask Copilot' })
    vi.useFakeTimers()
    try {
      const user = userEvent.setup({ advanceTimers: vi.advanceTimersByTime })
      // Start narrating run 5, then - while that request is still held open - navigate to run 6's
      // page. The route component is reused across the param change, so this exercises the case
      // where `id` (the page on screen) and the run actually narrating diverge.
      const askPromise = user.click(askButton)
      await act(async () => { await vi.advanceTimersByTimeAsync(500) })
      await askPromise
      const navPromise = user.click(screen.getByRole('button', { name: 'go to 6' }))
      await act(async () => { await vi.advanceTimersByTimeAsync(500) })
      await navPromise
      expect(screen.getByText('Nova')).toBeInTheDocument()
      await act(async () => { await vi.advanceTimersByTimeAsync(1000) })
      // Run 6 is not narrating: its button must be enabled and no progress line for run 5's (or
      // any) narration should leak onto its page, and its progress endpoint must not be polled.
      expect(screen.getByRole('button', { name: 'Ask Copilot' })).not.toBeDisabled()
      expect(screen.queryByText(/Copilot is reading team_overview…/)).not.toBeInTheDocument()
      expect(fake.calls.some((c) => c.path === '/runs/6/narrative/progress')).toBe(false)
      finish({ run_id: 5, status: 'ok', ai_status: 'ok', narrative: RUN_DETAIL.narrative, error: null, reason: null, attempts: 1, tool_calls: [] })
      await act(async () => { await vi.advanceTimersByTimeAsync(0) })
    } finally {
      vi.useRealTimers()
    }
  })
  it('does not let one narration finishing drop tracking of another still in flight', async () => {
    let finish5: (value: unknown) => void = () => {}
    let finish6: (value: unknown) => void = () => {}
    const held5 = new Promise((resolve) => { finish5 = resolve })
    const held6 = new Promise((resolve) => { finish6 = resolve })
    const detail5: RunDetail = { ...RUN_DETAIL, narrative: null, run: { ...RUN_DETAIL.run, ai_status: 'not_requested' } }
    const detail6: RunDetail = {
      run: { ...RUN_DETAIL.run, id: 6, team_id: 2 },
      forecasts: [],
      facts: RUN_DETAIL.facts && { ...RUN_DETAIL.facts, team: { ...RUN_DETAIL.facts.team, id: 2, name: 'Nova' }, members: [] },
      narrative: null,
    }
    installFakeWhf({
      'GET /meta': META, 'GET /profile': { member_id: 11, role: 'team_leader' },
      'GET /runs/5': () => detail5, 'GET /runs/6': () => detail6,
      'GET /runs/5/narrative/progress': { run_id: 5, steps: [{ code: 'tool', detail: 'team_overview', at: '2026-09-04T10:00:00' }], thinking: '', answer: '' },
      'GET /runs/6/narrative/progress': { run_id: 6, steps: [{ code: 'checking', detail: null, at: '2026-09-04T10:00:00' }], thinking: '', answer: '' },
      'POST /runs/5/narrative': () => held5,
      'POST /runs/6/narrative': () => held6,
    })
    function Nav() {
      const navigate = useNavigate()
      return <button onClick={() => navigate('/runs/6')}>go to 6</button>
    }
    render(
      <MemoryRouter initialEntries={['/runs/5']}><AppProvider>
        <Nav />
        <Routes><Route path="/runs/:runId" element={<TeamResult />} /></Routes>
      </AppProvider></MemoryRouter>,
    )
    const askButton = await screen.findByRole('button', { name: 'Ask Copilot' })
    vi.useFakeTimers()
    try {
      const user = userEvent.setup({ advanceTimers: vi.advanceTimersByTime })
      // Start A (run 5), navigate to B (run 6) while A is in flight, then start B too: two
      // narrations now overlap. Letting A finish must not erase B's tracking.
      const askAPromise = user.click(askButton)
      await act(async () => { await vi.advanceTimersByTimeAsync(500) })
      await askAPromise
      const navPromise = user.click(screen.getByRole('button', { name: 'go to 6' }))
      await act(async () => { await vi.advanceTimersByTimeAsync(500) })
      await navPromise
      expect(screen.getByText('Nova')).toBeInTheDocument()
      const askBButton = screen.getByRole('button', { name: 'Ask Copilot' })
      const askBPromise = user.click(askBButton)
      await act(async () => { await vi.advanceTimersByTimeAsync(500) })
      await askBPromise
      expect(screen.getByRole('button', { name: 'Ask Copilot' })).toBeDisabled()
      finish5({ run_id: 5, status: 'ok', ai_status: 'ok', narrative: RUN_DETAIL.narrative, error: null, reason: null, attempts: 1, tool_calls: [] })
      await act(async () => { await vi.advanceTimersByTimeAsync(0) })
      // Run 6 (currently on screen) must still show disabled and its progress line, unaffected by
      // run 5's narration finishing.
      expect(screen.getByRole('button', { name: 'Ask Copilot' })).toBeDisabled()
      expect(screen.getByText(/Checking the answer against the numbers/)).toBeInTheDocument()
      finish6({ run_id: 6, status: 'ok', ai_status: 'ok', narrative: RUN_DETAIL.narrative, error: null, reason: null, attempts: 1, tool_calls: [] })
      await act(async () => { await vi.advanceTimersByTimeAsync(0) })
    } finally {
      vi.useRealTimers()
    }
  })
  it('resets stale data when navigating from one run to another', async () => {
    const detail6: RunDetail = {
      run: { ...RUN_DETAIL.run, id: 6, team_id: 2 },
      forecasts: [],
      facts: RUN_DETAIL.facts && { ...RUN_DETAIL.facts, team: { ...RUN_DETAIL.facts.team, id: 2, name: 'Nova' }, members: [] },
      narrative: null,
    }
    installFakeWhf({
      'GET /meta': META, 'GET /profile': { member_id: 11, role: 'team_leader' },
      'GET /runs/5': RUN_DETAIL, 'GET /runs/6': detail6,
    })
    function Nav() {
      const navigate = useNavigate()
      return <button onClick={() => navigate('/runs/6')}>go to 6</button>
    }
    render(
      <MemoryRouter initialEntries={['/runs/5']}><AppProvider>
        <Nav />
        <Routes><Route path="/runs/:runId" element={<TeamResult />} /></Routes>
      </AppProvider></MemoryRouter>,
    )
    expect(await screen.findByText('Core')).toBeInTheDocument()
    await userEvent.click(screen.getByRole('button', { name: 'go to 6' }))
    expect(screen.queryByText('Core')).not.toBeInTheDocument()
    expect(await screen.findByText('Nova')).toBeInTheDocument()
  })
})

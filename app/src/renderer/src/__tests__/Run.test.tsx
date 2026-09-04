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
    await userEvent.click(await screen.findByRole('button', { name: 'Run forecast' }))
    expect(await screen.findByText(/Copilot is reading team_overview…/)).toBeInTheDocument()
    finish({ run_id: 5, status: 'ok', ai_status: 'ok', narrative: null, error: null, reason: null, attempts: 1, tool_calls: [] })
    expect(await screen.findByText('Forecast complete')).toBeInTheDocument()
  })
  it('stops polling for progress once the narrative is done', async () => {
    const fake = installFakeWhf({
      'GET /meta': META, 'GET /profile': { member_id: 11, role: 'team_leader' }, 'GET /copilot/status': ready,
      'POST /runs': RUN_CREATED,
      'GET /runs/5/narrative/progress': { run_id: 5, steps: [{ code: 'tool', detail: 'team_overview', at: '2026-09-04T10:00:00' }] },
      'POST /runs/5/narrative': { run_id: 5, status: 'ok', ai_status: 'ok', narrative: null, error: null, reason: null, attempts: 1, tool_calls: [] },
    })
    render(<MemoryRouter initialEntries={['/run?team=1']}><AppProvider><Run /></AppProvider></MemoryRouter>)
    await userEvent.click(await screen.findByRole('button', { name: 'Run forecast' }))
    expect(await screen.findByText('Forecast complete')).toBeInTheDocument()
    const progressCalls = (): number => fake.calls.filter((c) => c.path === '/runs/5/narrative/progress').length
    const before = progressCalls()
    vi.useFakeTimers()
    try {
      await act(async () => { await vi.advanceTimersByTimeAsync(2000) })
      expect(progressCalls()).toBe(before)
    } finally {
      vi.useRealTimers()
    }
  })
})

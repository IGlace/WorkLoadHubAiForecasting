import { render, screen } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import { MemoryRouter, Route, Routes, useNavigate } from 'react-router-dom'
import type { RunDetail } from '../../../shared/types'
import { AppProvider } from '../context'
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
  it('shows members by week with overload, champion, summary and warnings', async () => {
    installFakeWhf({ 'GET /meta': META, 'GET /profile': { member_id: 11, role: 'team_leader' }, 'GET /runs/5': RUN_DETAIL })
    mount()
    expect(await screen.findByText('Core')).toBeInTheDocument()
    expect(screen.getByText('gbm')).toBeInTheDocument()
    expect(screen.getByText('0.77')).toBeInTheDocument()
    const yara = screen.getByRole('row', { name: /Yara Tazi/ })
    expect(yara).toHaveTextContent('46.0 h')
    expect(yara).toHaveTextContent('+6.0 h')
    expect(yara).toHaveTextContent('+12.0 h')
    expect(screen.getByText('Core is slightly over capacity in both weeks, driven by Yara.')).toBeInTheDocument()
    expect(screen.getByText('Two overdue tasks.')).toBeInTheDocument()
    expect(screen.getByText('Billing v2 deadline')).toBeInTheDocument()
    expect(screen.getByRole('link', { name: 'Yara Tazi' })).toHaveAttribute('href', '/runs/5/members/13')
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

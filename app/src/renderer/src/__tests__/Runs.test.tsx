import { render, screen } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import { MemoryRouter } from 'react-router-dom'
import { AppProvider } from '../context'
import { Runs } from '../pages/Runs'
import { installFakeWhf, META } from '../test/fake-whf'

const runs = [
  { id: 6, team_id: 2, as_of: '2026-09-04', requested_by: 10, status: 'ok', champion_model: 'tsb', backtest_mase: 0.81, started_at: '2026-09-04T10:00:00', finished_at: '2026-09-04T10:00:04', ai_status: 'ok' },
  { id: 5, team_id: 1, as_of: '2026-09-04', requested_by: 11, status: 'ok', champion_model: 'gbm', backtest_mase: 0.77, started_at: '2026-09-04T09:00:00', finished_at: '2026-09-04T09:00:05', ai_status: 'failed:timeout' },
]

describe('Runs', () => {
  it('lists runs of visible teams newest first and filters by team', async () => {
    installFakeWhf({ 'GET /meta': META, 'GET /profile': { member_id: 10, role: 'skill_team_leader' }, 'GET /runs': runs })
    render(<MemoryRouter><AppProvider><Runs /></AppProvider></MemoryRouter>)
    const rows = await screen.findAllByRole('row')
    expect(rows).toHaveLength(3)
    expect(rows[1]).toHaveTextContent('Data')
    expect(rows[2]).toHaveTextContent('failed:timeout')
    await userEvent.selectOptions(screen.getByLabelText('Team'), '1')
    expect(screen.getAllByRole('row')).toHaveLength(2)
  })
  it('hides runs of other teams from a team leader', async () => {
    installFakeWhf({ 'GET /meta': META, 'GET /profile': { member_id: 11, role: 'team_leader' }, 'GET /runs': runs })
    render(<MemoryRouter><AppProvider><Runs /></AppProvider></MemoryRouter>)
    expect(await screen.findAllByRole('row')).toHaveLength(2)
  })
})

import { render, screen } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import { MemoryRouter } from 'react-router-dom'
import { AppProvider } from '../context'
import { Rebalancing } from '../pages/Rebalancing'
import { installFakeWhf, META } from '../test/fake-whf'
import { RUN_DETAIL } from '../test/fixtures'

describe('Rebalancing', () => {
  it('shows candidates and suggested moves for the latest run of the team', async () => {
    installFakeWhf({
      'GET /meta': META, 'GET /profile': { member_id: 11, role: 'team_leader' },
      'GET /runs?team_id=1': [{ ...RUN_DETAIL.run, id: 4, status: 'failed' }, RUN_DETAIL.run],
      'GET /runs/5': RUN_DETAIL,
    })
    render(<MemoryRouter><AppProvider><Rebalancing /></AppProvider></MemoryRouter>)
    expect(await screen.findByText('18.0 h over')).toBeInTheDocument()
    expect(screen.getByText('6.0 h spare')).toBeInTheDocument()
    const move = screen.getByRole('row', { name: /Yara Tazi/ })
    expect(move).toHaveTextContent('Ali Benjelloun')
    expect(move).toHaveTextContent('4.0 h')
    expect(move).toHaveTextContent('Mon 07 Sep')
    expect(move).toHaveTextContent('medium')
  })
  it('shows the loading text while the team fetch is in flight, then the data', async () => {
    let resolveRuns: (v: unknown) => void = () => {}
    const runsPromise = new Promise((resolve) => { resolveRuns = resolve })
    installFakeWhf({
      'GET /meta': META, 'GET /profile': { member_id: 11, role: 'team_leader' },
      'GET /runs?team_id=1': () => runsPromise,
      'GET /runs/5': RUN_DETAIL,
    })
    render(<MemoryRouter><AppProvider><Rebalancing /></AppProvider></MemoryRouter>)
    expect(await screen.findByText('Loading…')).toBeInTheDocument()
    resolveRuns([RUN_DETAIL.run])
    expect(await screen.findByText('18.0 h over')).toBeInTheDocument()
    expect(screen.queryByText('Loading…')).not.toBeInTheDocument()
  })
  it('says so when no run exists', async () => {
    installFakeWhf({ 'GET /meta': META, 'GET /profile': { member_id: 11, role: 'team_leader' }, 'GET /runs?team_id=1': [] })
    render(<MemoryRouter><AppProvider><Rebalancing /></AppProvider></MemoryRouter>)
    expect(await screen.findByText('No forecast yet')).toBeInTheDocument()
  })
  it('clears the error when switching from a team whose fetch fails to one that succeeds', async () => {
    installFakeWhf({
      'GET /meta': META, 'GET /profile': { member_id: 10, role: 'skill_team_leader' },
      'GET /runs?team_id=1': new Error('team 1 failed'), 'GET /runs?team_id=2': [RUN_DETAIL.run], 'GET /runs/5': RUN_DETAIL,
    })
    render(<MemoryRouter><AppProvider><Rebalancing /></AppProvider></MemoryRouter>)
    expect(await screen.findByRole('alert')).toHaveTextContent('team 1 failed')
    await userEvent.selectOptions(screen.getByLabelText('Team'), '2')
    expect(await screen.findByText('18.0 h over')).toBeInTheDocument()
    expect(screen.queryByRole('alert')).not.toBeInTheDocument()
  })
})

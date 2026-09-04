import { render, screen } from '@testing-library/react'
import { MemoryRouter } from 'react-router-dom'
import { AppProvider } from '../context'
import { Dashboard } from '../pages/Dashboard'
import { installFakeWhf, META } from '../test/fake-whf'

const overview = {
  department_id: 1,
  teams: [
    { team_id: 1, team_name: 'Core', run_id: 5, as_of: '2026-09-04', finished_at: '2026-09-04T10:00:00', due: false,
      weeks: [{ week: '2026-09-07', demand: 152.5, capacity: 160, overload: 4 }, { week: '2026-09-14', demand: 170, capacity: 160, overload: 12 }],
      overloaded: [{ member_id: 13, name: 'Yara Tazi', overload_hours: 16 }] },
    { team_id: 2, team_name: 'Data', run_id: null, as_of: null, finished_at: null, due: true, weeks: [], overloaded: [] },
  ],
}

describe('Dashboard', () => {
  it('shows every visible team with due state, weekly totals and overloaded members', async () => {
    installFakeWhf({ 'GET /meta': META, 'GET /profile': { member_id: 10, role: 'skill_team_leader' }, 'GET /departments/1/overview': overview })
    render(<MemoryRouter><AppProvider><Dashboard /></AppProvider></MemoryRouter>)
    expect(await screen.findByText('Core')).toBeInTheDocument()
    expect(screen.getByText('Data')).toBeInTheDocument()
    expect(screen.getAllByText('Forecast due')).toHaveLength(1)
    expect(screen.getByText('152.5 h')).toBeInTheDocument()
    expect(screen.getByText('Yara Tazi')).toBeInTheDocument()
    expect(screen.getByRole('link', { name: 'Open result' })).toHaveAttribute('href', '/runs/5')
  })
  it('shows only the own team for a team leader', async () => {
    installFakeWhf({ 'GET /meta': META, 'GET /profile': { member_id: 11, role: 'team_leader' }, 'GET /departments/1/overview': overview })
    render(<MemoryRouter><AppProvider><Dashboard /></AppProvider></MemoryRouter>)
    expect(await screen.findByText('Core')).toBeInTheDocument()
    expect(screen.queryByText('Data')).not.toBeInTheDocument()
  })
  it('shows an error banner on a failed fetch, then loads cleanly with no banner once the fetch succeeds', async () => {
    let call = 0
    installFakeWhf({
      'GET /meta': META,
      'GET /profile': { member_id: 10, role: 'skill_team_leader' },
      'GET /departments/1/overview': () => { call += 1; return call === 1 ? new Error('boom') : overview },
    })
    const { unmount } = render(<MemoryRouter><AppProvider><Dashboard /></AppProvider></MemoryRouter>)
    expect(await screen.findByText('Something went wrong: boom')).toBeInTheDocument()
    unmount()
    render(<MemoryRouter><AppProvider><Dashboard /></AppProvider></MemoryRouter>)
    expect(await screen.findByText('Core')).toBeInTheDocument()
    expect(screen.queryByText(/Something went wrong/)).not.toBeInTheDocument()
  })
})

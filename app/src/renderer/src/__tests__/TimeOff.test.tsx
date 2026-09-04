import { render, screen, waitFor } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import { MemoryRouter } from 'react-router-dom'
import { AppProvider } from '../context'
import { TimeOff } from '../pages/TimeOff'
import { installFakeWhf, META } from '../test/fake-whf'

describe('TimeOff', () => {
  it('wraps a fetch failure in the common error message', async () => {
    installFakeWhf({
      'GET /meta': META, 'GET /profile': { member_id: 11, role: 'team_leader' },
      'GET /holidays?year=*': () => { throw new Error('boom') },
      'GET /vacations': [],
    })
    render(<MemoryRouter><AppProvider><TimeOff /></AppProvider></MemoryRouter>)
    expect(await screen.findByText('Something went wrong: boom')).toBeInTheDocument()
  })
  it('lists holidays and vacations, validates and adds a vacation', async () => {
    let vacations = [{ id: 2, member_id: 13, start_date: '2026-09-21', end_date: '2026-09-25', type: 'vacation' }]
    const fake = installFakeWhf({
      'GET /meta': META, 'GET /profile': { member_id: 11, role: 'team_leader' },
      'GET /holidays?year=*': [{ date: '2026-11-06', name: 'Green March', country: 'MA' }],
      'GET /vacations': () => vacations,
      'POST /vacations': (b: unknown) => { vacations = [...vacations, { id: 3, ...(b as { member_id: number; start_date: string; end_date: string; type: string }) }]; return { id: 3 } },
      'DELETE /vacations/2': () => { vacations = vacations.filter((v) => v.id !== 2); return { deleted: true } },
    })
    render(<MemoryRouter><AppProvider><TimeOff /></AppProvider></MemoryRouter>)
    expect(await screen.findByText('Green March')).toBeInTheDocument()
    expect(screen.getByText('2026-09-21')).toBeInTheDocument()
    await userEvent.selectOptions(screen.getByLabelText('Member'), '11')
    await userEvent.type(screen.getByLabelText('From'), '2026-10-12')
    await userEvent.type(screen.getByLabelText('To'), '2026-10-09')
    await userEvent.click(screen.getByRole('button', { name: 'Add vacation' }))
    expect(await screen.findByText('Something went wrong: The end date must not be before the start date.')).toBeInTheDocument()
    await userEvent.clear(screen.getByLabelText('To'))
    await userEvent.type(screen.getByLabelText('To'), '2026-10-16')
    await userEvent.click(screen.getByRole('button', { name: 'Add vacation' }))
    expect(await screen.findByText('2026-10-12')).toBeInTheDocument()
    await userEvent.click(screen.getAllByRole('button', { name: 'Remove' })[0]!)
    await waitFor(() => expect(fake.calls.some((c) => c.method === 'DELETE' && c.path === '/vacations/2')).toBe(true))
  })
})

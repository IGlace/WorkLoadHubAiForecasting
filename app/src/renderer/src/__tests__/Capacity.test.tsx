import { render, screen, waitFor } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import { MemoryRouter } from 'react-router-dom'
import { AppProvider } from '../context'
import { Capacity } from '../pages/Capacity'
import { installFakeWhf, META } from '../test/fake-whf'

describe('Capacity', () => {
  it('shows the default and overrides, adds and removes an override', async () => {
    let overrides: { id: number; member_id: number; week_start: string | null; weekly_hours: number; reason: string | null }[] =
      [{ id: 7, member_id: 13, week_start: '2026-09-14', weekly_hours: 32, reason: 'training' }]
    const fake = installFakeWhf({
      'GET /meta': META, 'GET /profile': { member_id: 11, role: 'team_leader' },
      'GET /capacity': () => ({ default_weekly_hours: 40, overrides }),
      'PUT /capacity/default': (b: unknown) => b,
      'PUT /capacity/overrides': (b: unknown) => { const o = b as { member_id: number; weekly_hours: number; week_start: string | null; reason: string | null }; overrides = [...overrides, { id: 8, ...o }]; return o },
      'DELETE /capacity/overrides/7': () => { overrides = overrides.filter((o) => o.id !== 7); return { deleted: true } },
    })
    render(<MemoryRouter><AppProvider><Capacity /></AppProvider></MemoryRouter>)
    expect(await screen.findByDisplayValue('40')).toBeInTheDocument()
    expect(screen.getByText('training')).toBeInTheDocument()
    await userEvent.selectOptions(screen.getByLabelText('Member'), '13')
    await userEvent.type(screen.getByLabelText('Weekly hours'), '20')
    await userEvent.type(screen.getByLabelText('Reason'), 'internal project')
    await userEvent.click(screen.getByRole('button', { name: 'Add override' }))
    expect(await screen.findByText('internal project')).toBeInTheDocument()
    expect(screen.getByText('permanent')).toBeInTheDocument()
    await userEvent.click(screen.getAllByRole('button', { name: 'Remove' })[0]!)
    await waitFor(() => expect(screen.queryByText('training')).not.toBeInTheDocument())
    expect(fake.calls.some((c) => c.method === 'DELETE' && c.path === '/capacity/overrides/7')).toBe(true)
  })
})

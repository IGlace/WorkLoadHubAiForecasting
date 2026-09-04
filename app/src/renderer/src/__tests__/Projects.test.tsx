import { render, screen, waitFor } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import { MemoryRouter } from 'react-router-dom'
import { AppProvider } from '../context'
import { Projects } from '../pages/Projects'
import { installFakeWhf, META } from '../test/fake-whf'

const existing = [{ id: 3, name: 'Billing v2', department_id: 1, start_date: '2026-08-03', deadline: '2026-09-18', type: 'delivery', status: 'active', created_by: 11, team_ids: [1] }]

const two = [
  { id: 3, name: 'Billing v2', department_id: 1, start_date: '2026-08-03', deadline: '2026-09-18', type: 'delivery', status: 'active', created_by: 11, team_ids: [1] },
  { id: 4, name: 'Search Engine', department_id: 1, start_date: '2026-08-10', deadline: '2026-09-20', type: 'internal', status: 'planned', created_by: 10, team_ids: [2] },
]

describe('Projects', () => {
  it('creates a project after validating dates and teams', async () => {
    const fake = installFakeWhf({ 'GET /meta': META, 'GET /profile': { member_id: 10, role: 'skill_team_leader' }, 'GET /projects': existing, 'POST /projects': { id: 4 } })
    render(<MemoryRouter><AppProvider><Projects /></AppProvider></MemoryRouter>)
    expect(await screen.findByText('Billing v2')).toBeInTheDocument()
    await userEvent.click(screen.getByRole('button', { name: 'New project' }))
    await userEvent.type(screen.getByLabelText('Name'), 'Search')
    await userEvent.type(screen.getByLabelText('Start date'), '2026-10-05')
    await userEvent.type(screen.getByLabelText('Deadline'), '2026-10-05')
    await userEvent.click(screen.getByRole('button', { name: 'Save' }))
    expect(await screen.findByText('The deadline must be after the start date.')).toBeInTheDocument()
    await userEvent.clear(screen.getByLabelText('Deadline'))
    await userEvent.type(screen.getByLabelText('Deadline'), '2026-11-27')
    await userEvent.click(screen.getByRole('button', { name: 'Save' }))
    expect(await screen.findByText('Pick at least one team.')).toBeInTheDocument()
    await userEvent.click(screen.getByLabelText('Data'))
    await userEvent.click(screen.getByRole('button', { name: 'Save' }))
    await waitFor(() => expect(fake.calls.some((c) => c.method === 'POST' && c.path === '/projects')).toBe(true))
    const body = fake.calls.find((c) => c.method === 'POST')!.body as { name: string; team_ids: number[]; department_id: number }
    expect(body).toMatchObject({ name: 'Search', team_ids: [2], department_id: 1 })
  })
  it('edits status and deadline of an existing project', async () => {
    const fake = installFakeWhf({ 'GET /meta': META, 'GET /profile': { member_id: 11, role: 'team_leader' }, 'GET /projects': existing, 'PUT /projects/3': (b: unknown) => ({ id: 3, ...(b as object) }) })
    render(<MemoryRouter><AppProvider><Projects /></AppProvider></MemoryRouter>)
    await userEvent.click(await screen.findByRole('button', { name: 'Edit' }))
    await userEvent.selectOptions(screen.getByLabelText('Status'), 'done')
    await userEvent.click(screen.getByRole('button', { name: 'Save' }))
    await waitFor(() => expect(fake.calls.some((c) => c.method === 'PUT' && c.path === '/projects/3')).toBe(true))
    expect((fake.calls.find((c) => c.method === 'PUT')!.body as { status: string }).status).toBe('done')
  })
  it('switches the edit form draft when Edit is clicked on a different project', async () => {
    installFakeWhf({ 'GET /meta': META, 'GET /profile': { member_id: 10, role: 'skill_team_leader' }, 'GET /projects': two })
    render(<MemoryRouter><AppProvider><Projects /></AppProvider></MemoryRouter>)
    const editButtons = await screen.findAllByRole('button', { name: 'Edit' })
    expect(editButtons).toHaveLength(2)
    await userEvent.click(editButtons[0]!)
    expect(screen.getByLabelText('Name')).toHaveValue('Billing v2')
    await userEvent.click(screen.getAllByRole('button', { name: 'Edit' })[1]!)
    expect(screen.getByLabelText('Name')).toHaveValue('Search Engine')
  })
})

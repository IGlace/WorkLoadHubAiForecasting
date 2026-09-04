import { render, screen, waitFor } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import { MemoryRouter } from 'react-router-dom'
import { AppProvider } from '../context'
import { Settings } from '../pages/Settings'
import { installFakeWhf, META } from '../test/fake-whf'

function mount() {
  return render(<MemoryRouter><AppProvider><Settings /></AppProvider></MemoryRouter>)
}

describe('Settings', () => {
  it('lets the user pick a profile and shows Copilot status', async () => {
    let profile = { member_id: null as number | null, role: null as string | null }
    const fake = installFakeWhf({
      'GET /meta': META,
      'GET /profile': () => profile,
      'PUT /profile': (body: unknown) => { const b = body as { member_id: number }; profile = { member_id: b.member_id, role: 'team_leader' }; return profile },
      'GET /copilot/status': { cli_path: 'C:\\copilot.exe', cli_source: 'path', authenticated: false, login: null, message: 'Not signed in', ready: false },
    })
    mount()
    const select = await screen.findByLabelText('I am')
    await userEvent.selectOptions(select, '11')
    await waitFor(() => expect(fake.calls.some((c) => c.method === 'PUT' && c.path === '/profile')).toBe(true))
    expect(await screen.findByText('Not signed in')).toBeInTheDocument()
    await userEvent.click(screen.getByRole('button', { name: 'Sign in to GitHub Copilot' }))
    expect(await screen.findByText('opened')).toBeInTheDocument()
  })
  it('saves language, model and launch at login', async () => {
    const fake = installFakeWhf({ 'GET /meta': META, 'GET /profile': { member_id: 11, role: 'team_leader' }, 'GET /copilot/status': { cli_path: null, cli_source: 'none', authenticated: null, login: null, message: 'no cli', ready: false } })
    mount()
    await userEvent.selectOptions(await screen.findByLabelText('Language'), 'fr')
    await waitFor(() => expect(fake.settings.language).toBe('fr'))
    await userEvent.type(screen.getByLabelText(/Mod/), 'gpt-5')
    await userEvent.tab()
    await waitFor(() => expect(fake.settings.model).toBe('gpt-5'))
    await userEvent.click(screen.getByLabelText(/Windows/))
    await waitFor(() => expect(fake.settings.launchAtLogin).toBe(true))
  })
})

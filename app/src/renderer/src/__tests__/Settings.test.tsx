import { render, screen, waitFor } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import { MemoryRouter } from 'react-router-dom'
import { AppProvider } from '../context'
import { Settings } from '../pages/Settings'
import { installFakeWhf, META } from '../test/fake-whf'

function mount(props: { pollMs?: number; maxPollMs?: number } = {}) {
  return render(<MemoryRouter><AppProvider><Settings {...props} /></AppProvider></MemoryRouter>)
}

describe('Settings', () => {
  it('lets the user pick a profile and shows Copilot status', async () => {
    let profile = { member_id: null as number | null, role: null as string | null }
    const fake = installFakeWhf({
      'GET /meta': META,
      'GET /profile': () => profile,
      'PUT /profile': (body: unknown) => { const b = body as { member_id: number }; profile = { member_id: b.member_id, role: 'team_leader' }; return profile },
      'GET /copilot/status': { cli_path: 'C:\\copilot.exe', cli_source: 'path', authenticated: false, login: null, message: 'Not signed in', code: 'not_signed_in', ready: false, quota: null },
    })
    mount()
    const select = await screen.findByLabelText('I am')
    await userEvent.selectOptions(select, '11')
    await waitFor(() => expect(fake.calls.some((c) => c.method === 'PUT' && c.path === '/profile')).toBe(true))
    expect(await screen.findByText('Not signed in to GitHub Copilot yet.')).toBeInTheDocument()
    await userEvent.click(screen.getByRole('button', { name: 'Sign in to GitHub Copilot' }))
    // With the default (slow) poll interval, the first tick that would replace this with the
    // waiting message is still far off, so the login-started sentence is what's on screen.
    expect(await screen.findByText(/A terminal window opened/)).toBeInTheDocument()
  })
  it('shows how much of the monthly quota is left, and skips the unlimited ones', async () => {
    installFakeWhf({
      'GET /meta': META, 'GET /profile': { member_id: 11, role: 'team_leader' },
      'GET /copilot/status': {
        cli_path: 'C:\\copilot.exe', cli_source: 'path', authenticated: true, login: 'octocat', message: 'Signed in',
        code: 'signed_in', ready: true,
        quota: {
          premium_interactions: { used: 112, entitlement: 300, unlimited: false, remaining_percentage: 62.5, overage: 0, reset_date: '2026-10-01T00:00:00Z' },
          chat: { used: 9, entitlement: -1, unlimited: true, remaining_percentage: 100, overage: 0, reset_date: null },
          copilot_labs: { used: 1, entitlement: 10, unlimited: false, remaining_percentage: 90, overage: 0, reset_date: null },
        },
      },
    })
    mount()
    expect(await screen.findByText('Premium requests: 63% remaining, resets on 2026-10-01')).toBeInTheDocument()
    // An unlimited quota has nothing to run out of, so it says nothing.
    expect(screen.queryByText(/Chat:/)).not.toBeInTheDocument()
    // A quota type this version has no wording for still shows, under its raw name.
    expect(screen.getByText('copilot_labs: 90% remaining, resets on no reset date')).toBeInTheDocument()
  })
  it('shows no quota line when Copilot reports none', async () => {
    installFakeWhf({
      'GET /meta': META, 'GET /profile': { member_id: 11, role: 'team_leader' },
      'GET /copilot/status': { cli_path: 'C:\\copilot.exe', cli_source: 'path', authenticated: true, login: 'octocat', message: 'Signed in', code: 'signed_in', ready: true, quota: null },
    })
    mount()
    expect(await screen.findByText((_, node) => node?.textContent === 'Signed in as octocat')).toBeInTheDocument()
    expect(screen.queryByText(/remaining/)).not.toBeInTheDocument()
  })
  it('saves language, model and launch at login', async () => {
    const fake = installFakeWhf({ 'GET /meta': META, 'GET /profile': { member_id: 11, role: 'team_leader' }, 'GET /copilot/status': { cli_path: null, cli_source: 'none', authenticated: null, login: null, message: 'no cli', code: 'start_failed', ready: false, quota: null } })
    mount()
    await userEvent.selectOptions(await screen.findByLabelText('Language'), 'fr')
    await waitFor(() => expect(fake.settings.language).toBe('fr'))
    await userEvent.type(screen.getByLabelText(/Mod/), 'gpt-5')
    await userEvent.tab()
    await waitFor(() => expect(fake.settings.model).toBe('gpt-5'))
    await userEvent.click(screen.getByLabelText(/Windows/))
    await waitFor(() => expect(fake.settings.launchAtLogin).toBe(true))
  })
  it('shows an error when Copilot sign-in rejects', async () => {
    installFakeWhf({ 'GET /meta': META, 'GET /profile': { member_id: 11, role: 'team_leader' }, 'GET /copilot/status': { cli_path: null, cli_source: 'none', authenticated: null, login: null, message: 'no cli', code: 'start_failed', ready: false, quota: null } })
    window.whf.copilotLogin = () => Promise.reject(new Error('login failed'))
    mount()
    await userEvent.click(await screen.findByRole('button', { name: 'Sign in to GitHub Copilot' }))
    expect(await screen.findByText('Something went wrong: login failed')).toBeInTheDocument()
  })
  it('shows the signed-in login and disables the sign-in button, with no Check again button', async () => {
    installFakeWhf({
      'GET /meta': META, 'GET /profile': { member_id: 11, role: 'team_leader' },
      'GET /copilot/status': { cli_path: 'C:\\copilot.exe', cli_source: 'path', authenticated: true, login: 'octocat', message: 'Signed in', code: 'signed_in', ready: true, quota: null },
    })
    mount()
    // The login name is rendered in its own <strong>, so the sentence is split across elements;
    // match on the paragraph's full text rather than a single text node.
    expect(await screen.findByText((_, node) => node?.textContent === 'Signed in as octocat')).toBeInTheDocument()
    const signIn = screen.getByRole('button', { name: 'Sign in to GitHub Copilot' })
    expect(signIn).toBeDisabled()
    expect(signIn).toHaveAttribute('aria-disabled', 'true')
    expect(screen.getByText('You are signed in; no further sign-in is needed.')).toBeInTheDocument()
    expect(screen.queryByRole('button', { name: 'Check again' })).not.toBeInTheDocument()
  })
  it('shows the CLI-not-found message and keeps the sign-in button enabled when the login does not start', async () => {
    installFakeWhf({
      'GET /meta': META, 'GET /profile': { member_id: 11, role: 'team_leader' },
      'GET /copilot/status': { cli_path: null, cli_source: 'none', authenticated: null, login: null, message: 'no cli', code: 'start_failed', ready: false, quota: null },
    })
    window.whf.copilotLogin = () => Promise.resolve({ started: false, code: 'copilot.login.noCli' })
    mount()
    await userEvent.click(await screen.findByRole('button', { name: 'Sign in to GitHub Copilot' }))
    expect(await screen.findByText('The Copilot CLI could not be found.')).toBeInTheDocument()
    expect(screen.getByRole('button', { name: 'Sign in to GitHub Copilot' })).not.toBeDisabled()
  })
  it('polls after sign-in until the status becomes ready, then shows the login and a disabled button', async () => {
    let calls = 0
    installFakeWhf({
      'GET /meta': META, 'GET /profile': { member_id: 11, role: 'team_leader' },
      'GET /copilot/status': () => {
        calls += 1
        if (calls <= 2) return { cli_path: 'C:\\copilot.exe', cli_source: 'path', authenticated: false, login: null, message: 'Not signed in', code: 'not_signed_in', ready: false, quota: null }
        return { cli_path: 'C:\\copilot.exe', cli_source: 'path', authenticated: true, login: 'octocat', message: 'Signed in', code: 'signed_in', ready: true, quota: null }
      },
    })
    mount({ pollMs: 10 })
    await screen.findByText('Not signed in to GitHub Copilot yet.')
    await userEvent.click(screen.getByRole('button', { name: 'Sign in to GitHub Copilot' }))
    // The login-started sentence stays up while polling (it is only cleared once ready), so this
    // is what should be on screen through the retries, not the generic "waiting" fallback.
    expect(await screen.findByText(/A terminal window opened/)).toBeInTheDocument()
    // The login name is rendered in its own <strong>, so the sentence is split across elements;
    // match on the paragraph's full text rather than a single text node.
    expect(await screen.findByText((_, node) => node?.textContent === 'Signed in as octocat')).toBeInTheDocument()
    expect(screen.queryByText(/A terminal window opened/)).not.toBeInTheDocument()
    await waitFor(() => expect(screen.getByRole('button', { name: 'Sign in to GitHub Copilot' })).toBeDisabled())
  })
  it('clears a stale timeout once a later refetch reports ready', async () => {
    let ready = false
    installFakeWhf({
      'GET /meta': META, 'GET /profile': { member_id: 11, role: 'team_leader' },
      'GET /copilot/status': () => ready
        ? { cli_path: 'C:\\copilot.exe', cli_source: 'path', authenticated: true, login: 'octocat', message: 'Signed in', code: 'signed_in', ready: true, quota: null }
        : { cli_path: 'C:\\copilot.exe', cli_source: 'path', authenticated: false, login: null, message: 'Not signed in', code: 'not_signed_in', ready: false, quota: null },
    })
    mount({ pollMs: 10, maxPollMs: 1 })
    await screen.findByText('Not signed in to GitHub Copilot yet.')
    await userEvent.click(screen.getByRole('button', { name: 'Sign in to GitHub Copilot' }))
    expect(await screen.findByText('Still not signed in after ten minutes. Start the sign-in again.')).toBeInTheDocument()
    ready = true
    window.dispatchEvent(new Event('focus'))
    // The login name is rendered in its own <strong>, so the sentence is split across elements;
    // match on the paragraph's full text rather than a single text node.
    expect(await screen.findByText((_, node) => node?.textContent === 'Signed in as octocat')).toBeInTheDocument()
    expect(screen.queryByText('Still not signed in after ten minutes. Start the sign-in again.')).not.toBeInTheDocument()
  })
  it('refetches the status when the window regains focus', async () => {
    const fake = installFakeWhf({
      'GET /meta': META, 'GET /profile': { member_id: 11, role: 'team_leader' },
      'GET /copilot/status': { cli_path: 'C:\\copilot.exe', cli_source: 'path', authenticated: false, login: null, message: 'Not signed in', code: 'not_signed_in', ready: false, quota: null },
    })
    mount()
    await screen.findByText('Not signed in to GitHub Copilot yet.')
    const before = fake.calls.filter((c) => c.method === 'GET' && c.path === '/copilot/status').length
    window.dispatchEvent(new Event('focus'))
    await waitFor(() => {
      const after = fake.calls.filter((c) => c.method === 'GET' && c.path === '/copilot/status').length
      expect(after).toBeGreaterThan(before)
    })
  })
})

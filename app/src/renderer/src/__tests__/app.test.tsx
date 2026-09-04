import { render, screen } from '@testing-library/react'
import { MemoryRouter } from 'react-router-dom'
import { App } from '../app'
import { installFakeWhf, META } from '../test/fake-whf'

describe('App shell', () => {
  it('shows the navigation and the service state banner when the service failed', async () => {
    installFakeWhf({ 'GET /meta': META, 'GET /profile': { member_id: null, role: null } }, { state: { service: 'failed', serviceMessage: 'boom' } })
    render(<MemoryRouter initialEntries={['/']}><App /></MemoryRouter>)
    expect(await screen.findByText('boom')).toBeInTheDocument()
    for (const label of ['Dashboard', 'Run', 'Rebalancing', 'Projects', 'Capacity', 'Time off', 'Runs', 'Settings']) {
      expect(screen.getByRole('link', { name: label })).toBeInTheDocument()
    }
  })
  it('asks for a profile when none is set', async () => {
    installFakeWhf({ 'GET /meta': META, 'GET /profile': { member_id: null, role: null } })
    render(<MemoryRouter initialEntries={['/']}><App /></MemoryRouter>)
    expect(await screen.findByText('Choose who you are in Settings to see your teams.')).toBeInTheDocument()
  })
  it('shows the error banner when getState rejects', async () => {
    installFakeWhf({ 'GET /meta': META, 'GET /profile': { member_id: null, role: null } })
    window.whf.getState = () => Promise.reject(new Error('state unavailable'))
    render(<MemoryRouter initialEntries={['/']}><App /></MemoryRouter>)
    expect(await screen.findByText('Something went wrong: state unavailable')).toBeInTheDocument()
  })
})

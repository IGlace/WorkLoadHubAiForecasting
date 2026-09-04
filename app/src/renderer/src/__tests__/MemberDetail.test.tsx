import { render, screen } from '@testing-library/react'
import { MemoryRouter, Route, Routes } from 'react-router-dom'
import { AppProvider } from '../context'
import { MemberDetail } from '../pages/MemberDetail'
import { installFakeWhf, META } from '../test/fake-whf'
import { RUN_DETAIL } from '../test/fixtures'

describe('MemberDetail', () => {
  it('shows forecast, patterns, open tasks and narrative for one member', async () => {
    installFakeWhf({ 'GET /meta': META, 'GET /profile': { member_id: 11, role: 'team_leader' }, 'GET /runs/5': RUN_DETAIL })
    render(
      <MemoryRouter initialEntries={['/runs/5/members/11']}><AppProvider>
        <Routes><Route path="/runs/:runId/members/:memberId" element={<MemberDetail />} /></Routes>
      </AppProvider></MemoryRouter>,
    )
    expect(await screen.findByRole('heading', { name: /Ali Benjelloun/ })).toBeInTheDocument()
    const w1 = screen.getByRole('row', { name: /Mon 07 Sep/ })
    expect(w1).toHaveTextContent('36.0 h')
    expect(w1).toHaveTextContent('33.0 – 39.0 h')
    expect(screen.getByText(/Most tasks arrive on Monday/)).toBeInTheDocument()
    expect(screen.getByText(/estimate ratio/i)).toHaveTextContent('1.10')
    expect(screen.getByText('Fix login')).toBeInTheDocument()
    expect(screen.getByText('overdue')).toBeInTheDocument()
    expect(screen.getByText('Steady load around 36.0 h.')).toBeInTheDocument()
  })
})

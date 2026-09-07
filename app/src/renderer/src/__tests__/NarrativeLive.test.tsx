import { render, screen } from '@testing-library/react'
import type { NarrativeProgressStep } from '../../../shared/types'
import { NarrativeLive } from '../components/NarrativeLive'
import { setLanguage } from '../i18n'

const step: NarrativeProgressStep = { code: 'tool', detail: 'team_overview', at: '2026-09-04T10:00:00' }

function mount(over: { thinking?: string; answer?: string; tools?: { name: string; done: boolean }[] } = {}) {
  return render(
    <NarrativeLive step={step} elapsed={3} thinking={over.thinking ?? ''} answer={over.answer ?? ''} tools={over.tools ?? []} />,
  )
}

describe('NarrativeLive', () => {
  beforeEach(() => setLanguage('en'))

  it('keeps showing the step and how long it has been running', () => {
    mount()
    expect(screen.getByText(/Copilot is reading team_overview…/)).toBeInTheDocument()
    expect(screen.getByText('3 s so far')).toBeInTheDocument()
  })

  it('shows what Copilot is thinking', () => {
    mount({ thinking: 'Yara is over capacity in week two' })
    expect(screen.getByText('What Copilot is thinking')).toBeInTheDocument()
    expect(screen.getByText(/Yara is over capacity in week two/)).toBeInTheDocument()
  })

  it('says so rather than showing an empty box before the first chunk arrives', () => {
    mount()
    expect(screen.getByText('Copilot has not sent anything yet.')).toBeInTheDocument()
  })

  it('hides the answer until Copilot starts writing it', () => {
    const { rerender } = mount({ thinking: 'thinking' })
    expect(screen.queryByText('The answer as it is written')).not.toBeInTheDocument()
    rerender(<NarrativeLive step={step} elapsed={3} thinking="thinking" answer='{"run_summary"' tools={[]} />)
    expect(screen.getByText('The answer as it is written')).toBeInTheDocument()
    expect(screen.getByText(/"run_summary"/)).toBeInTheDocument()
  })

  it('lists the facts read and ticks the ones Copilot has finished with', () => {
    mount({ tools: [{ name: 'team_overview', done: true }, { name: 'member_history', done: false }] })
    expect(screen.getByText(/Facts read/)).toHaveTextContent('team_overview ✓')
    expect(screen.getByText(/Facts read/)).toHaveTextContent('member_history')
  })

  it('says nothing about facts before the first tool call', () => {
    mount()
    expect(screen.queryByText(/Facts read/)).not.toBeInTheDocument()
  })
})

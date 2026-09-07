import { act, render, screen } from '@testing-library/react'
import { afterEach } from 'vitest'
import type { NarrativeUsage } from '../../../shared/types'
import { CopilotCost } from '../components/CopilotCost'
import { setLanguage } from '../i18n'
import { USAGE } from '../test/fixtures'

function usage(patch: Partial<NarrativeUsage>): NarrativeUsage {
  return { ...USAGE, ...patch }
}

describe('CopilotCost', () => {
  afterEach(() => act(() => { setLanguage('en') }))

  it('says nothing when the narration never reached a session', () => {
    const { container } = render(<CopilotCost usage={usage({ source: 'none' })} />)
    expect(container).toBeEmptyDOMElement()
  })

  it('says nothing when there is no usage at all', () => {
    const { container } = render(<CopilotCost usage={null} />)
    expect(container).toBeEmptyDOMElement()
  })

  it('shows the credits, the money, the tokens and the requests', () => {
    render(<CopilotCost usage={USAGE} />)
    expect(screen.getByText('Copilot usage: 12.50 AI credits (about $0.13) · 12,345 tokens in, 678 out · 3 requests'))
      .toBeInTheDocument()
  })

  it('shows the tokens without inventing a price when only the events were readable', () => {
    render(<CopilotCost usage={usage({ source: 'events', ai_credits: null, usd: null, premium_requests: null, input_tokens: 1000, output_tokens: 20, requests: 1 })} />)
    expect(screen.getByText('Copilot usage: 1,000 tokens in, 20 out · 1 request')).toBeInTheDocument()
  })

  it('falls back to the premium requests when the account is not billed in credits', () => {
    render(<CopilotCost usage={usage({ ai_credits: null, usd: null, input_tokens: null, output_tokens: null, premium_requests: 2.5, requests: 2 })} />)
    expect(screen.getByText('Copilot usage: 2 requests · 2.5 premium requests')).toBeInTheDocument()
  })

  it('says nothing about money when the service reported credits without a price', () => {
    // The service computes the money (whf/ai/usage.py); the view never does that arithmetic itself.
    render(<CopilotCost usage={usage({ usd: null, premium_requests: null })} />)
    const line = screen.getByText(/Copilot usage:/)
    expect(line).not.toHaveTextContent('AI credits')
    expect(line).toHaveTextContent('12,345 tokens in, 678 out · 3 requests')
  })

  it('says one request in the singular, in both languages', () => {
    render(<CopilotCost usage={usage({ ai_credits: null, usd: null, input_tokens: null, output_tokens: null, premium_requests: null, requests: 1 })} />)
    expect(screen.getByText('Copilot usage: 1 request')).toBeInTheDocument()
    act(() => { setLanguage('fr') })
    expect(screen.getByText('Utilisation de Copilot : 1 requête')).toBeInTheDocument()
  })

  it('formats the premium requests with the decimal mark of the reader', () => {
    render(<CopilotCost usage={usage({ ai_credits: null, usd: null, input_tokens: null, output_tokens: null, premium_requests: 2.5, requests: 2 })} />)
    act(() => { setLanguage('fr') })
    expect(screen.getByText('Utilisation de Copilot : 2 requêtes · 2,5 requêtes premium')).toBeInTheDocument()
  })

  it('reads in French, thousands grouped the French way', () => {
    render(<CopilotCost usage={USAGE} />)
    act(() => { setLanguage('fr') })
    const line = screen.getByText(/crédits IA/)
    expect(line).toHaveTextContent('Utilisation de Copilot : 12,50 crédits IA (environ 0,13 $)')
    expect(line).toHaveTextContent('3 requêtes')
    // 12345 is grouped for a French reader, so the English comma must be gone.
    expect(line.textContent).toContain((12345).toLocaleString('fr-FR'))
    expect(line.textContent).not.toContain('12,345')
  })
})

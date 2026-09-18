import { describe, expect, it } from 'vitest'
import { segments } from './highlight'

describe('highlight', () => {
  it('flags the unverified numbers and nothing else', () => {
    expect(segments('Demand 31.2 h against 44.0 h capacity.', ['31.2 h'])).toEqual([
      { text: 'Demand ', flagged: false }, { text: '31.2 h', flagged: true }, { text: ' against 44.0 h capacity.', flagged: false },
    ])
  })
  it('leaves a whole sentence plain when nothing is unverified', () => {
    expect(segments('All within capacity.', [])).toEqual([{ text: 'All within capacity.', flagged: false }])
    expect(segments('', ['1'])).toEqual([{ text: '', flagged: false }])
  })
  it('matches whole numbers only, longest spelling first', () => {
    expect(segments('112.5 h and 12.5 h', ['12.5 h'])).toEqual([{ text: '112.5 h and ', flagged: false }, { text: '12.5 h', flagged: true }])
    expect(segments('12.5 h then 12.5', ['12.5', '12.5 h']).map((s) => s.flagged)).toEqual([true, false, true])
  })
})

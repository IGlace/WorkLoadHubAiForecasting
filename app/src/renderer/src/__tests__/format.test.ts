import { hours, pct, weekLabel } from '../format'

describe('format', () => {
  it('formats hours with one decimal', () => {
    expect(hours(12.34)).toBe('12.3 h'); expect(hours(0)).toBe('0.0 h'); expect(hours(null)).toBe('–')
  })
  it('labels a Monday', () => { expect(weekLabel('2026-09-07')).toBe('Mon 07 Sep') })
  it('formats shares as percentages', () => { expect(pct(0.256)).toBe('26%') })
})

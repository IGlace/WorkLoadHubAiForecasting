import { describe, expect, it } from 'vitest'
import { addDays, dayLabel, hours, isWeekend, pct, shortId, signed } from './format'

describe('format', () => {
  it('prints hours with one decimal and a dash for nothing', () => {
    expect(hours(12.34)).toBe('12.3 h')
    expect(hours(0)).toBe('0.0 h')
    expect(hours(null)).toBe('–')
    expect(hours(Number.NaN)).toBe('–')
    expect(hours('NaN')).toBe('–')
    expect(hours('Infinity')).toBe('–')
    expect(hours('12.25')).toBe('12.3 h')
  })
  it('signs a bias and rounds a rate', () => {
    expect(signed(1.5)).toBe('+1.50')
    expect(signed(-0.25)).toBe('-0.25')
    expect(pct(0.756)).toBe('76 %')
    expect(pct(null)).toBe('–')
    expect(pct('NaN')).toBe('–')
    expect(signed('NaN')).toBe('–')
  })
  it('labels calendar days without a time zone', () => {
    expect(dayLabel('2026-09-07')).toBe('Mon 07 Sep')
    expect(dayLabel('2026-01-01')).toBe('Thu 01 Jan')
    expect(isWeekend('2026-09-05')).toBe(true)
    expect(isWeekend('2026-09-07')).toBe(false)
    expect(addDays('2026-09-06', 1)).toBe('2026-09-07')
    expect(addDays('2026-08-31', -7)).toBe('2026-08-24')
    expect(shortId('d16d94cb-2e0c-3308-8c10-faa3cdc00210')).toBe('d16d94cb')
  })
})

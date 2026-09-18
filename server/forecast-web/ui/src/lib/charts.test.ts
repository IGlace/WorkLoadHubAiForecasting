import { describe, expect, it } from 'vitest'
import { loadStep, niceMax, scale, ticks } from './charts'

describe('charts', () => {
  it('rounds the axis up to a clean maximum', () => {
    expect(niceMax([44, 31.2])).toBe(50)
    expect(niceMax([8])).toBe(10)
    expect(niceMax([0, 0])).toBe(10)
    expect(niceMax([120])).toBe(200)
    expect(niceMax([Number.NaN, 3])).toBe(5)
  })
  it('scales into the range and clamps', () => {
    const s = scale(50, 200)
    expect(s(25)).toBe(100)
    expect(s(-3)).toBe(0)
    expect(s(500)).toBe(200)
    expect(scale(0, 200)(10)).toBe(0)
    expect(ticks(50, 5)).toEqual([0, 10, 20, 30, 40, 50])
  })
  it('steps the load ratio on six levels and never shades overload darker', () => {
    expect(loadStep(0, 8.8)).toBe(0)
    expect(loadStep(4, 0)).toBe(0)
    expect(loadStep(1, 8.8)).toBe(1)
    expect(loadStep(4, 8.8)).toBe(2)
    expect(loadStep(6, 8.8)).toBe(3)
    expect(loadStep(8, 8.8)).toBe(4)
    expect(loadStep(8.8, 8.8)).toBe(5)
    expect(loadStep(20, 8.8)).toBe(5)
  })
})

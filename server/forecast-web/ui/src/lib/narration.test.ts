import { describe, expect, it } from 'vitest'
import { NARRATION_START_TIMEOUT_MS, pollAction } from './narration'

describe('the narration poll', () => {
  it('keeps waiting while the run still reports its own phase', () => {
    // The 202 comes back before a worker picks the narration up, so the first polls read the run's DONE.
    expect(pollAction('DONE', false)).toBe('wait')
    expect(pollAction('FAILED', false)).toBe('wait')
    expect(pollAction('PERSIST', false)).toBe('wait')
    expect(pollAction(null, false)).toBe('wait')
  })
  it('reports the narration once a worker has it, and settles when it ends', () => {
    expect(pollAction('NARRATING', false)).toBe('running')
    expect(pollAction('NARRATED', false)).toBe('settled')
    expect(pollAction('NARRATION_FAILED', false)).toBe('settled')
    expect(pollAction('NARRATED', true)).toBe('settled')
  })
  it('settles on any other phase once the narration has been seen running', () => {
    expect(pollAction('DONE', true)).toBe('settled')
    expect(pollAction('LOADING', true)).toBe('settled')
  })
  it('gives up when no worker takes it within the timeout', () => {
    expect(pollAction('DONE', false, NARRATION_START_TIMEOUT_MS - 1)).toBe('wait')
    expect(pollAction('DONE', false, NARRATION_START_TIMEOUT_MS)).toBe('gave-up')
    expect(pollAction('NARRATING', false, NARRATION_START_TIMEOUT_MS * 10)).toBe('running')
  })
})

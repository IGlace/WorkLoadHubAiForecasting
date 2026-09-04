import { setLanguage } from '../i18n'
import { progressLabel } from '../narrative-progress'

const step = (code: string, detail: string | null = null) => ({ code, detail, at: '2026-09-04T10:00:00' }) as never

describe('progressLabel', () => {
  beforeEach(() => setLanguage('en'))

  it('falls back to the generic line before the first step arrives', () => {
    expect(progressLabel(null)).toBe('Asking Copilot to explain the forecast…')
  })
  it('phrases each step', () => {
    expect(progressLabel(step('starting'))).toBe('Starting Copilot…')
    expect(progressLabel(step('session'))).toBe('Opening a Copilot session…')
    expect(progressLabel(step('checking'))).toBe('Checking the answer against the numbers…')
  })
  it('names the tool Copilot is reading', () => {
    expect(progressLabel(step('tool', 'team_overview'))).toBe('Copilot is reading team_overview…')
  })
  it('says nothing about attempts on the first attempt, and says so on a retry', () => {
    expect(progressLabel(step('asking', '1'))).toBe('Copilot is writing the explanation…')
    expect(progressLabel(step('asking', '2'))).toBe('Copilot is trying again (attempt 2)…')
  })
  it('phrases them in French too', () => {
    setLanguage('fr')
    expect(progressLabel(step('starting'))).toBe('Démarrage de Copilot…')
    expect(progressLabel(step('tool', 'team_overview'))).toBe('Copilot consulte team_overview…')
  })
  it('falls back to the generic line for a code it does not know', () => {
    // The service may add a step before the app is updated; an unknown code must not blank the line.
    expect(progressLabel(step('something_new'))).toBe('Asking Copilot to explain the forecast…')
  })
})

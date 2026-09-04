import { getLanguage, setLanguage, t } from '../i18n'

describe('i18n', () => {
  it('translates known keys and interpolates', () => {
    setLanguage('en')
    expect(t('nav.dashboard')).toBe('Dashboard')
    expect(t('run.progress.forecasting', { team: 'Core' })).toBe('Forecasting Core…')
  })
  it('falls back to English for missing French keys and to the key itself when unknown', () => {
    setLanguage('fr')
    expect(getLanguage()).toBe('fr')
    expect(t('nav.dashboard')).toBe('Tableau de bord')
    expect(t('run.progress.forecasting', { team: 'Core' })).toBe('Forecasting Core…')
    expect(t('no.such.key')).toBe('no.such.key')
    setLanguage('en')
  })
})

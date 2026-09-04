import { getLanguage, setLanguage, t, untranslatedKeys } from '../i18n'

describe('i18n', () => {
  it('translates known keys and interpolates', () => {
    setLanguage('en')
    expect(t('nav.dashboard')).toBe('Dashboard')
    expect(t('run.progress.forecasting', { team: 'Core' })).toBe('Forecasting Core…')
  })
  it('translates and interpolates in French too', () => {
    setLanguage('fr')
    expect(getLanguage()).toBe('fr')
    expect(t('nav.dashboard')).toBe('Tableau de bord')
    expect(t('run.progress.forecasting', { team: 'Core' })).toBe('Prévision pour Core…')
    setLanguage('en')
  })
  it('falls back to the key itself when it is unknown in every language', () => {
    setLanguage('fr')
    expect(t('no.such.key')).toBe('no.such.key')
    setLanguage('en')
  })
  it('is complete in both languages, so nothing silently shows in the wrong one', () => {
    expect(untranslatedKeys('fr')).toEqual([])
    expect(untranslatedKeys('en')).toEqual([])
  })
})

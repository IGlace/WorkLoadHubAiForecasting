// A manual end-to-end check of the showcase UI against a running forecast-web (not part of the gate):
//
//   npm i -D playwright && npx playwright install chromium     # once
//   BASE=http://localhost:8080 OUT=/tmp/shots node e2e/smoke.mjs
//
// It picks the seed's admin, acts as a team leader, starts a run and waits for it, opens the run, stores a
// token (a classic ghp_ one is refused, a gho_ one accepted) and reads the seat, checks a permission, moves
// the demo clock as the admin, reads the accuracy page, runs again, tries a route on the docs page and
// visits a run that does not exist. It exits non-zero on any page error. The only console entries it
// tolerates are the refused calls it provokes on purpose (a 404 and a 400).
import { chromium } from 'playwright'
const BASE = process.env.BASE ?? 'http://localhost:8080'
const OUT = process.env.OUT ?? '.'
const browser = await chromium.launch({ executablePath: process.env.CHROMIUM || undefined, headless: true })
const page = await browser.newPage({ viewport: { width: 1360, height: 900 } })
const errors = []
page.on('pageerror', (e) => errors.push('pageerror: ' + e.message))
page.on('console', (m) => { if (m.type() === 'error' && !/status of (400|404)/.test(m.text())) errors.push('console: ' + m.text()) })
const shot = (n) => page.screenshot({ path: `${OUT}/${n}.png`, fullPage: true })
const log = (...a) => console.log(new Date().toISOString().slice(11, 19), ...a)

await page.goto(BASE + '/teams')
await page.waitForSelector('text=Acting as nobody yet')
await shot('01-nobody')
await page.click("text=act as the seed's admin")
await page.waitForSelector('select[aria-label="acting user"]')
await page.waitForSelector('text=ADMIN: views every team, runs every team')
await shot('02-teams-admin')

// Act as a TEAM_LEADER: pick the first option of that group.
const leaderValue = await page.$eval('select[aria-label="acting user"] optgroup[label="TEAM_LEADER"] option', (o) => o.value)
await page.selectOption('select[aria-label="acting user"]', leaderValue)
await page.waitForSelector('text=/TEAM_LEADER: views/')
await shot('03-teams-leader')
// open the first team the leader can run
const runnableRow = page.locator('tr', { has: page.locator('span.badge.ok', { hasText: 'run' }) }).first()
const teamName = await runnableRow.locator('td a').first().innerText()
log('team', teamName)
await runnableRow.locator('td a').first().click()
await page.waitForSelector('h1:has-text("' + teamName + '")')
await page.waitForSelector('h2:has-text("Runs")')
await shot('04-team-before-run')
await page.click('button:has-text("Start a forecast")')
await page.waitForSelector('text=/Run [0-9a-f]{8}/')
await shot('05-team-running')
await page.waitForSelector('text=Open the run', { timeout: 300000 })
await page.waitForSelector('table.grid')
await shot('06-team-after-run')
await page.click('text=Open the run')
await page.waitForSelector('h1:has-text("Run")')
await page.waitForSelector('text=Window 1')
await shot('07-run')
await page.click('text=show the')
await shot('08-run-days')

// settings: store a token as the leader, then read the seat (no network, so authenticated may be false; that is fine)
await page.goto(BASE + '/settings')
await page.waitForSelector('text=/a token is stored|no token stored/')
if (await page.isVisible('text=a token is stored')) { await page.click('button:has-text("Clear")'); await page.waitForSelector('text=no token stored') }
await page.fill('input[type=password]', 'ghp_classic')
await page.click('button:has-text("Save")')
await page.waitForSelector('text=INVALID_REQUEST')
await page.fill('input[type=password]', 'gho_showcase_token')
await page.click('button:has-text("Save")')
await page.waitForSelector('text=a token is stored')
await shot('09-settings-token')
await page.click('button:has-text("Check the seat")')
await page.waitForSelector('text=/authenticated|COPILOT_UNAVAILABLE|TOKEN/', { timeout: 120000 })
await shot('10-settings-seat')

// narration button now enabled on the run page; do not click (it would call Copilot with a fake token) -- the seat check already tells the story.
await page.goBack(); await page.goBack()

// permissions explorer
await page.goto(BASE + '/permissions')
await page.waitForSelector('text=Check any user on any team')
await page.waitForSelector('span.badge.accent')
const memberValue = await page.$eval('select >> nth=1 >> option:has-text("MEMBER")', (o) => o.value).catch(() => null)
if (memberValue) {
  await page.selectOption('select >> nth=1', memberValue)
  const firstTeam = await page.$eval('select >> nth=2 >> option >> nth=1', (o) => o.value)
  await page.selectOption('select >> nth=2', firstTeam)
  await page.click('button:has-text("Check")')
  await page.waitForSelector('text=may run')
}
await shot('11-permissions')

// clock as a member -> refused; as admin -> forward 4 weeks, accuracy fills, another run
await page.goto(BASE + '/clock')
await page.waitForSelector('text=Only an ADMIN may move the clock')
await shot('12-clock-not-admin')
await page.waitForSelector('select[aria-label="acting user"] optgroup[label="ADMIN"] option', { state: 'attached' })
const adminValue = await page.$eval('select[aria-label="acting user"] optgroup[label="ADMIN"] option', (o) => o.value)
await page.selectOption('select[aria-label="acting user"]', adminValue)
await page.waitForSelector('span.badge.accent:has-text("ADMIN")')
await page.waitForSelector('button:has-text("forward 4 weeks"):not([disabled])')
await page.click('button:has-text("forward 4 weeks")')
await page.waitForSelector('.stat .value:has-text("2026-07-26")')
await shot('13-clock-moved')
await page.goto(BASE + '/teams')
await page.waitForSelector('span.badge.accent')
await page.click('text=' + teamName)
await page.click('text=Accuracy of past forecasts')
await page.waitForSelector('text=Rows (', { timeout: 60000 })
await shot('14-accuracy')
await page.goto(BASE + '/teams')
await page.waitForSelector('span.badge.accent')
await page.click('text=' + teamName)
await page.waitForSelector('h1:has-text("' + teamName + '")')
await page.waitForSelector('button:has-text("Start a forecast"):not([disabled])')
await page.click('button:has-text("Start a forecast")')
await page.waitForSelector('text=Open the run', { timeout: 300000 })
await shot('14b-team-second-run')

await page.goto(BASE + '/docs')
await page.waitForSelector('text=API reference')
await page.selectOption('label:has-text("team") select', { index: 1 })
await page.locator('.endpoint', { hasText: '/api/directory/teams/{id}' }).locator('button:has-text("try it")').click()
await page.waitForSelector('text=managerName')
await shot('15-docs')
await page.goto(BASE + '/runs/00000000-0000-0000-0000-000000000000')
await page.waitForSelector('text=RUN_NOT_FOUND')
await shot('16-run-not-found')
log('errors:', errors.length ? errors : 'none')
await browser.close()
if (errors.length) process.exit(1)

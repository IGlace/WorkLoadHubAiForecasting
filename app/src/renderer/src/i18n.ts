import type { Language } from '../../shared/ipc'

const en: Record<string, string> = {
  'app.title': 'WorkloadHub Forecast',
  'nav.dashboard': 'Dashboard', 'nav.run': 'Run', 'nav.rebalancing': 'Rebalancing', 'nav.projects': 'Projects',
  'nav.capacity': 'Capacity', 'nav.timeoff': 'Time off', 'nav.runs': 'Runs', 'nav.settings': 'Settings',
  'profile.none': 'Choose who you are in Settings to see your teams.',
  'service.starting': 'Starting the forecast service…',
  'settings.title': 'Settings', 'settings.profile': 'Profile', 'settings.iam': 'I am', 'settings.nobody': 'Nobody selected',
  'settings.copilot': 'GitHub Copilot', 'settings.signin': 'Sign in to GitHub Copilot', 'settings.recheck': 'Check again',
  'settings.language': 'Language', 'settings.model': 'Model (blank uses your Copilot default)',
  'settings.launch': 'Start with Windows (hidden in the tray)', 'settings.tray': 'Keep running in the tray when the window is closed',
  'settings.ready': 'Signed in as {login}', 'settings.saved': 'Saved',
  'run.title': 'Run a forecast', 'run.team': 'Team', 'run.asof': 'As of', 'run.start': 'Run forecast', 'run.withai': 'Ask Copilot for the narrative',
  'run.progress.forecasting': 'Forecasting {team}…', 'run.progress.narrating': 'Asking Copilot to explain the forecast…',
  'run.done': 'Forecast complete', 'run.open': 'Open the result', 'run.aiFailed': 'Copilot narrative failed: {reason}',
  'run.onBehalf': 'You are running this forecast on behalf of {leader}.',
  'dashboard.title': 'Dashboard', 'dashboard.due': 'Forecast due', 'dashboard.lastRun': 'Last run {date}', 'dashboard.noRun': 'No forecast yet',
  'dashboard.overloaded': 'Overloaded', 'dashboard.demand': 'Demand', 'dashboard.capacity': 'Capacity', 'dashboard.overload': 'Overload',
  'dashboard.open': 'Open result',
  'team.title': 'Team result', 'team.member': 'Member', 'team.champion': 'Champion model', 'team.mase': 'Backtest MASE',
  'team.summary': 'AI summary', 'team.warnings': 'Warnings', 'team.risks': 'Team risks', 'team.narrate': 'Ask Copilot',
  'team.narrativeStatus': 'Narrative status: {status}', 'team.unverified': 'Some numbers in this narrative could not be matched to the forecast facts.',
  'team.notes': 'Model notes', 'team.interval': 'Interval', 'team.total': 'Total',
  'member.title': 'Member detail', 'member.history': 'Arrivals in the last 13 weeks', 'member.forecast': 'Forecast', 'member.patterns': 'Patterns',
  'member.open': 'Open tasks', 'member.narrative': 'Narrative', 'member.week': 'Week', 'member.demand': 'Demand', 'member.range': 'Range',
  'member.capacity': 'Capacity', 'member.overload': 'Overload', 'member.openHours': 'From open tasks', 'member.newHours': 'From new tasks',
  'rebalancing.title': 'Rebalancing', 'rebalancing.overloaded': 'Overloaded', 'rebalancing.underloaded': 'Under-loaded', 'rebalancing.moves': 'Suggested moves',
  'rebalancing.none': 'No moves suggested for this run.', 'rebalancing.spare': '{hours} spare', 'rebalancing.over': '{hours} over',
  'rebalancing.adjustments': 'Suggested forecast adjustments (not applied)',
  'rebalancing.from': 'From', 'rebalancing.to': 'To', 'rebalancing.hours': 'Hours', 'rebalancing.reason': 'Reason', 'rebalancing.confidence': 'Confidence',
  'projects.title': 'Projects', 'projects.new': 'New project', 'projects.name': 'Name', 'projects.start': 'Start date', 'projects.deadline': 'Deadline',
  'projects.teams': 'Teams', 'projects.type': 'Type', 'projects.status': 'Status', 'projects.save': 'Save', 'projects.edit': 'Edit', 'projects.cancel': 'Cancel',
  'projects.deadlineError': 'The deadline must be after the start date.', 'projects.teamsError': 'Pick at least one team.',
  'projects.type.delivery': 'delivery', 'projects.type.maintenance': 'maintenance', 'projects.type.internal': 'internal',
  'projects.status.planned': 'planned', 'projects.status.active': 'active', 'projects.status.done': 'done',
  'capacity.title': 'Capacity', 'capacity.default': 'Default weekly hours', 'capacity.overrides': 'Overrides', 'capacity.member': 'Member',
  'capacity.week': 'Week (blank = permanent)', 'capacity.hours': 'Weekly hours', 'capacity.reason': 'Reason', 'capacity.add': 'Add override',
  'capacity.remove': 'Remove', 'capacity.permanent': 'permanent',
  'timeoff.title': 'Time off', 'timeoff.holidays': 'Public holidays', 'timeoff.vacations': 'Vacations', 'timeoff.year': 'Year',
  'timeoff.member': 'Member', 'timeoff.from': 'From', 'timeoff.to': 'To', 'timeoff.type': 'Type', 'timeoff.add': 'Add vacation', 'timeoff.remove': 'Remove',
  'timeoff.rangeError': 'The end date must not be before the start date.',
  'timeoff.type.vacation': 'vacation', 'timeoff.type.sick': 'sick', 'timeoff.type.other': 'other',
  'runs.title': 'Runs', 'runs.id': 'Run', 'runs.team': 'Team', 'runs.asof': 'As of', 'runs.status': 'Status', 'runs.ai': 'AI', 'runs.champion': 'Champion',
  'runs.open': 'Open', 'runs.empty': 'No runs yet.',
  'common.loading': 'Loading…', 'common.error': 'Something went wrong: {message}', 'common.week': 'Week of {date}', 'common.all': 'All teams',
}

const fr: Record<string, string> = {
  'app.title': 'WorkloadHub Forecast',
  'nav.dashboard': 'Tableau de bord', 'nav.run': 'Lancer', 'nav.rebalancing': 'Rééquilibrage', 'nav.projects': 'Projets',
  'nav.capacity': 'Capacité', 'nav.timeoff': 'Absences', 'nav.runs': 'Historique', 'nav.settings': 'Paramètres',
  'profile.none': 'Choisissez qui vous êtes dans Paramètres pour voir vos équipes.',
  'common.loading': 'Chargement…',
}

const dictionaries: Record<Language, Record<string, string>> = { en, fr }
let current: Language = 'en'

export function setLanguage(lang: Language): void { current = lang }
export function getLanguage(): Language { return current }
export function t(key: string, vars: Record<string, string | number> = {}): string {
  const template = dictionaries[current][key] ?? en[key] ?? key
  return template.replace(/\{(\w+)\}/g, (_, name: string) => String(vars[name] ?? `{${name}}`))
}

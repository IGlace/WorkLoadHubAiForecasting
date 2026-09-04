import { useSyncExternalStore } from 'react'
import type { Language } from '../../shared/ipc'

const en: Record<string, string> = {
  'app.title': 'WorkloadHub Forecast',
  'nav.dashboard': 'Dashboard', 'nav.run': 'Run', 'nav.rebalancing': 'Rebalancing', 'nav.projects': 'Projects',
  'nav.capacity': 'Capacity', 'nav.timeoff': 'Time off', 'nav.runs': 'Runs', 'nav.settings': 'Settings',
  'profile.none': 'Choose who you are in Settings to see your teams.',
  'service.starting': 'Starting the forecast service…',
  'service.failed': 'The forecast service stopped. Restart the application.',
  'copilot.signed_in': 'Signed in to GitHub Copilot.', 'copilot.not_signed_in': 'Not signed in to GitHub Copilot yet.',
  'copilot.start_failed': 'The Copilot CLI could not start.',
  'copilot.login.started': 'A terminal window opened with the GitHub device-login flow. Return here when it says you are signed in.',
  'copilot.login.noCli': 'The Copilot CLI could not be found.',
  'copilot.login.failed': 'The sign-in window could not be opened.',
  'settings.title': 'Settings', 'settings.profile': 'Profile', 'settings.iam': 'I am', 'settings.nobody': 'Nobody selected',
  'role.skill_team_leader': 'department leader', 'role.team_leader': 'team leader', 'role.member': 'member',
  'settings.copilot': 'GitHub Copilot', 'settings.signin': 'Sign in to GitHub Copilot', 'settings.recheck': 'Check again',
  'settings.language': 'Language', 'settings.model': 'Model (blank uses your Copilot default)',
  'settings.launch': 'Start with Windows (hidden in the tray)', 'settings.tray': 'Keep running in the tray when the window is closed',
  'settings.ready': 'Signed in as {login}', 'settings.saved': 'Saved',
  'run.title': 'Run a forecast', 'run.team': 'Team', 'run.asof': 'As of', 'run.start': 'Run forecast', 'run.withai': 'Ask Copilot for the narrative',
  'run.progress.forecasting': 'Forecasting {team}…', 'run.progress.narrating': 'Asking Copilot to explain the forecast…',
  'run.progress.ai.starting': 'Starting Copilot…',
  'run.progress.ai.session': 'Opening a Copilot session…',
  'run.progress.ai.asking': 'Copilot is writing the explanation…',
  'run.progress.ai.retry': 'Copilot is trying again (attempt {attempt})…',
  'run.progress.ai.tool': 'Copilot is reading {tool}…',
  'run.progress.ai.checking': 'Checking the answer against the numbers…',
  'run.progress.elapsed': '{seconds} s so far',
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
  'projects.nameError': 'Enter a project name.',
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

// Keep this in the same order as `en`; `untranslatedKeys` proves nothing is missing.
// Glossary, so the two languages stay consistent with each other and with the narrative:
// demand = charge, capacity = capacité, overload = surcharge, run = prévision, member = membre.
const fr: Record<string, string> = {
  'app.title': 'WorkloadHub Forecast',
  'nav.dashboard': 'Tableau de bord', 'nav.run': 'Lancer', 'nav.rebalancing': 'Rééquilibrage', 'nav.projects': 'Projets',
  'nav.capacity': 'Capacité', 'nav.timeoff': 'Absences', 'nav.runs': 'Historique', 'nav.settings': 'Paramètres',
  'profile.none': 'Choisissez qui vous êtes dans Paramètres pour voir vos équipes.',
  'service.starting': 'Démarrage du service de prévision…',
  'service.failed': 'Le service de prévision s’est arrêté. Redémarrez l’application.',
  'copilot.signed_in': 'Connecté à GitHub Copilot.', 'copilot.not_signed_in': 'Pas encore connecté à GitHub Copilot.',
  'copilot.start_failed': 'Le CLI Copilot n’a pas pu démarrer.',
  'copilot.login.started': 'Une fenêtre de terminal s’est ouverte avec la connexion par code GitHub. Revenez ici lorsqu’elle indique que vous êtes connecté.',
  'copilot.login.noCli': 'Le CLI Copilot est introuvable.',
  'copilot.login.failed': 'La fenêtre de connexion n’a pas pu être ouverte.',
  'settings.title': 'Paramètres', 'settings.profile': 'Profil', 'settings.iam': 'Je suis', 'settings.nobody': 'Personne de sélectionné',
  'role.skill_team_leader': 'responsable de département', 'role.team_leader': 'responsable d’équipe', 'role.member': 'membre',
  'settings.copilot': 'GitHub Copilot', 'settings.signin': 'Se connecter à GitHub Copilot', 'settings.recheck': 'Vérifier à nouveau',
  'settings.language': 'Langue', 'settings.model': 'Modèle (vide = votre modèle Copilot par défaut)',
  'settings.launch': 'Démarrer avec Windows (masqué dans la zone de notification)',
  'settings.tray': 'Continuer en arrière-plan quand la fenêtre est fermée',
  'settings.ready': 'Connecté en tant que {login}', 'settings.saved': 'Enregistré',
  'run.title': 'Lancer une prévision', 'run.team': 'Équipe', 'run.asof': 'À la date du', 'run.start': 'Lancer la prévision',
  'run.withai': 'Demander l’analyse à Copilot',
  'run.progress.forecasting': 'Prévision pour {team}…', 'run.progress.narrating': 'Copilot rédige l’explication de la prévision…',
  'run.progress.ai.starting': 'Démarrage de Copilot…',
  'run.progress.ai.session': 'Ouverture d’une session Copilot…',
  'run.progress.ai.asking': 'Copilot rédige l’explication…',
  'run.progress.ai.retry': 'Copilot réessaie (tentative {attempt})…',
  'run.progress.ai.tool': 'Copilot consulte {tool}…',
  'run.progress.ai.checking': 'Vérification de la réponse par rapport aux chiffres…',
  'run.progress.elapsed': '{seconds} s écoulées',
  'run.done': 'Prévision terminée', 'run.open': 'Ouvrir le résultat', 'run.aiFailed': 'L’analyse Copilot a échoué : {reason}',
  'run.onBehalf': 'Vous lancez cette prévision pour le compte de {leader}.',
  'dashboard.title': 'Tableau de bord', 'dashboard.due': 'Prévision à faire', 'dashboard.lastRun': 'Dernière prévision {date}',
  'dashboard.noRun': 'Aucune prévision pour l’instant',
  'dashboard.overloaded': 'En surcharge', 'dashboard.demand': 'Charge', 'dashboard.capacity': 'Capacité', 'dashboard.overload': 'Surcharge',
  'dashboard.open': 'Ouvrir le résultat',
  'team.title': 'Résultat de l’équipe', 'team.member': 'Membre', 'team.champion': 'Modèle retenu', 'team.mase': 'MASE du backtest',
  'team.summary': 'Synthèse IA', 'team.warnings': 'Avertissements', 'team.risks': 'Risques pour l’équipe', 'team.narrate': 'Demander à Copilot',
  'team.narrativeStatus': 'État de l’analyse : {status}',
  'team.unverified': 'Certains chiffres de cette analyse n’ont pas pu être rattachés aux données de la prévision.',
  'team.notes': 'Notes sur le modèle', 'team.interval': 'Intervalle', 'team.total': 'Total',
  'member.title': 'Détail du membre', 'member.history': 'Arrivées des 13 dernières semaines', 'member.forecast': 'Prévision',
  'member.patterns': 'Tendances',
  'member.open': 'Tâches en cours', 'member.narrative': 'Analyse', 'member.week': 'Semaine', 'member.demand': 'Charge', 'member.range': 'Fourchette',
  'member.capacity': 'Capacité', 'member.overload': 'Surcharge', 'member.openHours': 'Depuis les tâches en cours',
  'member.newHours': 'Depuis les nouvelles tâches',
  'rebalancing.title': 'Rééquilibrage', 'rebalancing.overloaded': 'En surcharge', 'rebalancing.underloaded': 'Sous-chargé',
  'rebalancing.moves': 'Transferts proposés',
  'rebalancing.none': 'Aucun transfert proposé pour cette prévision.', 'rebalancing.spare': '{hours} disponibles',
  'rebalancing.over': '{hours} en trop',
  'rebalancing.adjustments': 'Ajustements proposés (non appliqués)',
  'rebalancing.from': 'De', 'rebalancing.to': 'Vers', 'rebalancing.hours': 'Heures', 'rebalancing.reason': 'Motif',
  'rebalancing.confidence': 'Confiance',
  'projects.title': 'Projets', 'projects.new': 'Nouveau projet', 'projects.name': 'Nom', 'projects.start': 'Date de début',
  'projects.deadline': 'Échéance',
  'projects.teams': 'Équipes', 'projects.type': 'Type', 'projects.status': 'Statut', 'projects.save': 'Enregistrer',
  'projects.edit': 'Modifier', 'projects.cancel': 'Annuler',
  'projects.nameError': 'Saisissez un nom de projet.',
  'projects.deadlineError': 'L’échéance doit être postérieure à la date de début.',
  'projects.teamsError': 'Choisissez au moins une équipe.',
  'projects.type.delivery': 'livraison', 'projects.type.maintenance': 'maintenance', 'projects.type.internal': 'interne',
  'projects.status.planned': 'planifié', 'projects.status.active': 'actif', 'projects.status.done': 'terminé',
  'capacity.title': 'Capacité', 'capacity.default': 'Heures hebdomadaires par défaut', 'capacity.overrides': 'Exceptions',
  'capacity.member': 'Membre',
  'capacity.week': 'Semaine (vide = permanent)', 'capacity.hours': 'Heures hebdomadaires', 'capacity.reason': 'Motif',
  'capacity.add': 'Ajouter une exception',
  'capacity.remove': 'Supprimer', 'capacity.permanent': 'permanent',
  'timeoff.title': 'Absences', 'timeoff.holidays': 'Jours fériés', 'timeoff.vacations': 'Congés', 'timeoff.year': 'Année',
  'timeoff.member': 'Membre', 'timeoff.from': 'Du', 'timeoff.to': 'Au', 'timeoff.type': 'Type', 'timeoff.add': 'Ajouter un congé',
  'timeoff.remove': 'Supprimer',
  'timeoff.rangeError': 'La date de fin ne doit pas être antérieure à la date de début.',
  'timeoff.type.vacation': 'congé', 'timeoff.type.sick': 'maladie', 'timeoff.type.other': 'autre',
  'runs.title': 'Historique', 'runs.id': 'Prévision', 'runs.team': 'Équipe', 'runs.asof': 'À la date du', 'runs.status': 'Statut',
  'runs.ai': 'IA', 'runs.champion': 'Modèle',
  'runs.open': 'Ouvrir', 'runs.empty': 'Aucune prévision pour l’instant.',
  'common.loading': 'Chargement…', 'common.error': 'Une erreur est survenue : {message}', 'common.week': 'Semaine du {date}',
  'common.all': 'Toutes les équipes',
}

const dictionaries: Record<Language, Record<string, string>> = { en, fr }
let current: Language = 'en'
const listeners = new Set<() => void>()

export function setLanguage(lang: Language): void {
  if (lang === current) return
  current = lang
  for (const listener of listeners) listener()
}
export function getLanguage(): Language { return current }

/**
 * The keys `lang` has no translation for, and would therefore silently show in English.
 * English and French must both be complete, so a test asserts this is empty.
 */
export function untranslatedKeys(lang: Language): string[] {
  return Object.keys(en).filter((key) => !dictionaries[lang][key])
}
export function t(key: string, vars: Record<string, string | number> = {}): string {
  const template = dictionaries[current][key] ?? en[key] ?? key
  return template.replace(/\{(\w+)\}/g, (_, name: string) => String(vars[name] ?? `{${name}}`))
}

function subscribe(listener: () => void): () => void {
  listeners.add(listener)
  return () => listeners.delete(listener)
}

/**
 * Subscribes the calling component to language changes so it re-renders on the next
 * `setLanguage` call even if it never reads the app context (e.g. `MemberDetail`, which
 * is reached without going through `useApp()`).
 */
export function useT(): typeof t {
  useSyncExternalStore(subscribe, getLanguage, getLanguage)
  return t
}

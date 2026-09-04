export const IPC = {
  apiRequest: 'api:request',
  settingsGet: 'settings:get',
  settingsSet: 'settings:set',
  copilotLogin: 'copilot:login',
  appState: 'app:state',
  appStateChanged: 'app:state-changed',
} as const

export type HttpMethod = 'GET' | 'POST' | 'PUT' | 'DELETE'

export interface ApiRequest { method: HttpMethod; path: string; body?: unknown }
export type ApiResponse =
  | { ok: true; status: number; data: unknown }
  | { ok: false; status: number; error: string }

export type Language = 'en' | 'fr'
export interface Settings { language: Language; model: string | null; launchAtLogin: boolean; closeToTray: boolean }
export const DEFAULT_SETTINGS: Settings = { language: 'en', model: null, launchAtLogin: false, closeToTray: true }

export type ServicePhase = 'starting' | 'ready' | 'failed' | 'stopped'
export interface AppState { service: ServicePhase; serviceMessage: string; version: string; platform: string }

/**
 * What the sign-in attempt did, as a renderer dictionary key rather than a sentence: the main
 * process starts the login but the window that reports it may be in either language.
 */
export type LoginCode = 'copilot.login.started' | 'copilot.login.noCli' | 'copilot.login.failed'
export interface LoginResult {
  started: boolean
  code: LoginCode
  /** Technical detail from the service, in English, shown beside the translated sentence. */
  detail?: string
}

export interface WhfBridge {
  request(req: ApiRequest): Promise<ApiResponse>
  getSettings(): Promise<Settings>
  setSettings(patch: Partial<Settings>): Promise<Settings>
  copilotLogin(): Promise<LoginResult>
  getState(): Promise<AppState>
  onStateChanged(listener: (state: AppState) => void): () => void
}

declare global {
  interface Window { whf: WhfBridge }
}

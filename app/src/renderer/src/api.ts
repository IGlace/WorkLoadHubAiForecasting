import type { HttpMethod } from '../../shared/ipc'
import type {
  Capacity, CapacityOverride, CopilotStatus, DepartmentOverview, Holiday, Meta, NarrativeOutcome, Profile, Project,
  ProjectInput, ProjectUpdate, RunCreated, RunDetail, RunSummary, TeamDue, Vacation,
} from '../../shared/types'

export class ApiError extends Error {
  constructor(message: string, readonly status: number) { super(message); this.name = 'ApiError' }
}

export async function call<T>(method: HttpMethod, path: string, body?: unknown): Promise<T> {
  const res = await window.whf.request({ method, path, body })
  if (!res.ok) throw new ApiError(res.error, res.status)
  return res.data as T
}

const q = (params: Record<string, string | number | undefined>): string => {
  const parts = Object.entries(params).filter(([, v]) => v !== undefined).map(([k, v]) => `${k}=${encodeURIComponent(String(v))}`)
  return parts.length ? `?${parts.join('&')}` : ''
}

export const getMeta = () => call<Meta>('GET', '/meta')
export const getProfile = () => call<Profile>('GET', '/profile')
export const setProfile = (member_id: number | null) => call<Profile>('PUT', '/profile', { member_id })
export const getRuns = (team_id?: number) => call<RunSummary[]>('GET', `/runs${q({ team_id })}`)
export const getRun = (id: number) => call<RunDetail>('GET', `/runs/${id}`)
export const createRun = (team_id: number, as_of?: string, requested_by?: number | null) =>
  call<RunCreated>('POST', '/runs', { team_id, as_of, requested_by })
export const createNarrative = (run_id: number, model: string | null) => call<NarrativeOutcome>('POST', `/runs/${run_id}/narrative`, { model })
export const getCopilotStatus = () => call<CopilotStatus>('GET', '/copilot/status')
export const getProjects = () => call<Project[]>('GET', '/projects')
export const createProject = (input: ProjectInput) => call<{ id: number }>('POST', '/projects', input)
export const updateProject = (id: number, input: ProjectUpdate) => call<Project>('PUT', `/projects/${id}`, input)
export const getCapacity = () => call<Capacity>('GET', '/capacity')
export const setCapacityDefault = (weekly_hours: number) => call<{ default_weekly_hours: number }>('PUT', '/capacity/default', { weekly_hours })
export const setCapacityOverride = (o: Omit<CapacityOverride, 'id'>) => call<Omit<CapacityOverride, 'id'>>('PUT', '/capacity/overrides', o)
export const deleteCapacityOverride = (id: number) => call<{ deleted: boolean }>('DELETE', `/capacity/overrides/${id}`)
export const getHolidays = (year?: number) => call<Holiday[]>('GET', `/holidays${q({ year })}`)
export const getVacations = (member_id?: number) => call<Vacation[]>('GET', `/vacations${q({ member_id })}`)
export const createVacation = (v: Omit<Vacation, 'id'>) => call<{ id: number }>('POST', '/vacations', v)
export const deleteVacation = (id: number) => call<{ deleted: boolean }>('DELETE', `/vacations/${id}`)
export const getTeamDue = (team_id: number) => call<TeamDue>('GET', `/teams/${team_id}/due`)
export const getDepartmentOverview = (department_id: number) => call<DepartmentOverview>('GET', `/departments/${department_id}/overview`)

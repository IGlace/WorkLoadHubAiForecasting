import type { ReactNode } from 'react'
import { ApiError } from '../api'

export function Notice({ kind = 'info', children }: { kind?: 'info' | 'error' | 'warn' | 'good'; children: ReactNode }) {
  return <div className={'notice' + (kind === 'info' ? '' : ' ' + kind)}>{children}</div>
}

/** An API error as the pages print it: the code the developer maps, then the message the person reads. */
export function ErrorNotice({ error }: { error: unknown }) {
  if (!error) return null
  if (error instanceof ApiError) {
    return <Notice kind="error"><b>{error.status ? error.status + ' ' : ''}{error.code}</b> {error.message}</Notice>
  }
  return <Notice kind="error">{error instanceof Error ? error.message : String(error)}</Notice>
}

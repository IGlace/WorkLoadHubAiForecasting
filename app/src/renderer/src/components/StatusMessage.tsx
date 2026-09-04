import type React from 'react'

export function StatusMessage({ kind, children }: { kind: 'info' | 'error' | 'success'; children: React.ReactNode }): React.JSX.Element {
  return <div role={kind === 'error' ? 'alert' : 'status'} className={`status ${kind}`}>{children}</div>
}

import type React from 'react'
import { useId } from 'react'

export function Field({ label, children }: { label: string; children: (id: string) => React.ReactNode }): React.JSX.Element {
  const id = useId()
  return (
    <div className="field">
      <label htmlFor={id}>{label}</label>
      {children(id)}
    </div>
  )
}

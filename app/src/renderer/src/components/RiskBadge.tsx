import type React from 'react'
import type { RiskLevel } from '../../../shared/types'

export function RiskBadge({ level }: { level: RiskLevel }): React.JSX.Element {
  return <span className={`badge ${level}`}>{level}</span>
}

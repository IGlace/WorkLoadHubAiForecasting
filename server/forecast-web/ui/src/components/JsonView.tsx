/** A collapsible JSON tree: objects and arrays open to `depth`, leaves inline. Text is rendered as text, never as markup. */
export function JsonView({ value, depth = 1, name }: { value: unknown; depth?: number; name?: string }) {
  return <div className="json"><Node value={value} open={depth} name={name} /></div>
}

function Node({ value, open, name }: { value: unknown; open: number; name?: string }) {
  const label = name !== undefined ? <><span className="k">{name}</span>: </> : null
  if (value === null || value === undefined) return <div className="leaf">{label}<span className="muted">null</span></div>
  if (typeof value === 'number') return <div className="leaf">{label}<span className="n">{String(value)}</span></div>
  if (typeof value === 'boolean') return <div className="leaf">{label}<span className="n">{String(value)}</span></div>
  if (typeof value === 'string') return <div className="leaf">{label}<span className="s">"{value}"</span></div>
  if (Array.isArray(value)) {
    return (
      <details open={open > 0}>
        <summary>{label}[{value.length}]</summary>
        {value.map((v, i) => <Node key={i} value={v} open={open - 1} name={String(i)} />)}
      </details>
    )
  }
  const entries = Object.entries(value as Record<string, unknown>)
  return (
    <details open={open > 0}>
      <summary>{label}{'{'}{entries.length}{'}'}</summary>
      {entries.map(([k, v]) => <Node key={k} value={v} open={open - 1} name={k} />)}
    </details>
  )
}

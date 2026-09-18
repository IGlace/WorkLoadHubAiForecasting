import { useMemo, useState, useSyncExternalStore } from 'react'
import { callLog, type CallRecord } from '../api'
import { JsonView } from './JsonView'

/**
 * "Behind this page": every call made since the page opened, with its status and time, and the response on
 * demand. This is how the developer reads the contract while using the feature.
 */
export function Behind() {
  const [since] = useState(() => callLog.seq())
  const calls = useSyncExternalStore(callLog.subscribe, callLog.snapshot)
  const mine = useMemo(() => calls.filter((c) => c.seq > since), [calls, since])
  const [open, setOpen] = useState<number | null>(null)
  return (
    <details className="behind">
      <summary>Behind this page: {mine.length} call{mine.length === 1 ? '' : 's'} to the REST surface</summary>
      <table>
        <tbody>
          {mine.map((c: CallRecord) => (
            <tr key={c.seq}>
              <td className="m">{c.method}</td>
              <td className="mono">{c.path}</td>
              <td className={'mono st-' + String(c.status ?? 0)[0]}>{c.status ?? 'network'}</td>
              <td className="num muted">{c.ms} ms</td>
              <td className="muted">{c.error ?? ''}</td>
              <td><button className="small" onClick={() => setOpen(open === c.seq ? null : c.seq)}>{open === c.seq ? 'hide' : 'response'}</button></td>
            </tr>
          ))}
        </tbody>
      </table>
      {open !== null && mine.find((c) => c.seq === open) && (
        <div className="card tight"><JsonView value={mine.find((c) => c.seq === open)!.response} depth={1} /></div>
      )}
    </details>
  )
}

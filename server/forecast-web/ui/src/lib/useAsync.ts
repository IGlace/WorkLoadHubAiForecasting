import { useCallback, useEffect, useRef, useState, type DependencyList } from 'react'

/** Loads once per dependency change; `reload` fetches again without clearing what is shown. */
export function useAsync<T>(load: () => Promise<T>, deps: DependencyList) {
  const [data, setData] = useState<T | null>(null)
  const [error, setError] = useState<unknown>(null)
  const [loading, setLoading] = useState(true)
  const gen = useRef(0)
  const run = useCallback(async () => {
    const mine = ++gen.current
    setLoading(true)
    try {
      const d = await load()
      if (mine === gen.current) { setData(d); setError(null) }
    } catch (e) {
      if (mine === gen.current) setError(e)
    } finally {
      if (mine === gen.current) setLoading(false)
    }
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, deps)
  useEffect(() => { void run() }, [run])
  return { data, error, loading, reload: run, setData }
}

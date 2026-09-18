/**
 * Splits a narrative sentence into plain and flagged segments: a flagged segment is one of the numbers the
 * verifier could not find in the facts (the verification's `unverified` list, as the verifier spelled them).
 * The longest spellings match first so "12.5" inside "112.5" is never flagged on its own.
 */
export interface Segment { text: string; flagged: boolean }

export function segments(text: string, unverified: string[]): Segment[] {
  const needles = Array.from(new Set(unverified.map((u) => u.trim()).filter((u) => u.length > 0))).sort((a, b) => b.length - a.length)
  if (!text || needles.length === 0) return [{ text, flagged: false }]
  const out: Segment[] = []
  let i = 0
  let plain = ''
  while (i < text.length) {
    const hit = needles.find((n) => text.startsWith(n, i) && boundary(text, i, n.length))
    if (hit) {
      if (plain) { out.push({ text: plain, flagged: false }); plain = '' }
      out.push({ text: hit, flagged: true })
      i += hit.length
    } else {
      plain += text[i]
      i++
    }
  }
  if (plain) out.push({ text: plain, flagged: false })
  return out
}

/** A number is a whole token: not glued to another digit on either side. */
function boundary(text: string, start: number, length: number): boolean {
  const before = start > 0 ? text[start - 1] : ''
  const after = start + length < text.length ? text[start + length] : ''
  return !/[0-9.]/.test(before) && !/[0-9]/.test(after)
}


export type AnnotationTone = 'ok' | 'wait' | 'alert' | 'neutral'

export interface PresentedAnnotation {
  key: string
  label: string
  kind: 'score' | 'text'
  value: string
  tone: AnnotationTone
}

const labels: Record<string, string> = {
  fit: 'Fit',
  score: 'Score',
  reason: 'Why',
  concern: 'Watch out',
  summary: 'Summary',
  urgency: 'Urgency',
}

const cautionKeys = new Set(['concern', 'risk', 'blocker', 'caveat', 'warning'])

/** Scores are 0–10 by convention; larger values are shown untoned. */
const SCORE_MAX = 10

function toneForScore(value: number): AnnotationTone {
  if (value > SCORE_MAX) return 'neutral'
  if (value >= 7) return 'ok'
  if (value >= 4) return 'wait'
  return 'alert'
}

function titleCase(key: string): string {
  const spaced = key.replace(/[_-]+/g, ' ').replace(/([a-z])([A-Z])/g, '$1 $2')
  return spaced.charAt(0).toUpperCase() + spaced.slice(1)
}

export function presentAnnotations(
  annotations: Record<string, string | number | boolean> | null | undefined,
): PresentedAnnotation[] {
  if (!annotations) return []

  const out: PresentedAnnotation[] = []
  for (const [key, raw] of Object.entries(annotations)) {
    if (raw === null || raw === undefined || raw === '') continue

    const label = labels[key.toLowerCase()] ?? titleCase(key)
    if (typeof raw === 'number') {
      out.push({ key, label, kind: 'score', value: String(raw), tone: toneForScore(raw) })
    } else {
      const value = String(raw).trim()
      if (!value) continue
      out.push({
        key,
        label,
        kind: 'text',
        value,
        tone: cautionKeys.has(key.toLowerCase()) ? 'wait' : 'neutral',
      })
    }
  }

  // Map order does not survive the JSON and Mongo round-trip, so order explicitly.
  const rank = (a: PresentedAnnotation) =>
    a.kind === 'score' ? 0 : a.tone === 'wait' ? 2 : 1
  return out.sort((a, b) => rank(a) - rank(b))
}

export const annotationToneVars: Record<AnnotationTone, { fg: string; bg: string }> = {
  ok: { fg: 'var(--tone-ok)', bg: 'var(--tone-ok-bg)' },
  wait: { fg: 'var(--tone-wait)', bg: 'var(--tone-wait-bg)' },
  alert: { fg: 'var(--tone-alert)', bg: 'var(--tone-alert-bg)' },
  neutral: { fg: 'var(--tone-neutral)', bg: 'var(--tone-neutral-bg)' },
}

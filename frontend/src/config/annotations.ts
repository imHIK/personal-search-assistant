/**
 * How a task's per-item output is presented.
 *
 * A run item's `annotations` is an open map: the keys come from whatever the task asked the model to
 * record, and tasks are written by users. So no component may branch on a key — this file is the
 * lookup, and like `presentation.ts` it has a neutral fallback, meaning a task nobody anticipated
 * still renders as labelled values rather than crashing or showing raw JSON.
 */

export type AnnotationTone = 'ok' | 'wait' | 'alert' | 'neutral'

export interface PresentedAnnotation {
  key: string
  label: string
  /** Numbers render as a badge; text renders as a line under the title. */
  kind: 'score' | 'text'
  value: string
  tone: AnnotationTone
}

/**
 * Keys worth labelling specially. Everything else falls back to the key itself, title-cased — which
 * reads acceptably because task authors name their own fields ("fit", "urgency", "next_step").
 */
const labels: Record<string, string> = {
  fit: 'Fit',
  score: 'Score',
  reason: 'Why',
  concern: 'Watch out',
  summary: 'Summary',
  urgency: 'Urgency',
}

/**
 * Keys whose text is a caveat rather than a recommendation, so it can be toned differently. Kept as
 * a list rather than inferred: guessing from wording would mislabel far more often than it helps.
 */
const cautionKeys = new Set(['concern', 'risk', 'blocker', 'caveat', 'warning'])

/** Scores are conventionally 0–10 here; anything larger is shown plainly rather than mis-toned. */
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

/**
 * Turn one item's annotations into things to render, scores first so a badge leads the row.
 * Empty and null-ish values are dropped: a task that had nothing to say about an item should leave
 * no trace on it rather than an empty label.
 */
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

  // Scores lead, then the explanation, then the caveat. Map order cannot be relied on — annotations
  // round-trip through JSON and Mongo — and a "watch out" line reads as a footnote to the reason,
  // not as the headline.
  const rank = (a: PresentedAnnotation) =>
    a.kind === 'score' ? 0 : a.tone === 'wait' ? 2 : 1
  return out.sort((a, b) => rank(a) - rank(b))
}

/**
 * CSS variables for a tone — never a hex literal in a component. These are the same four state tones
 * the rest of the console uses, so a fit score reads like every other status in the app.
 */
export const annotationToneVars: Record<AnnotationTone, { fg: string; bg: string }> = {
  ok: { fg: 'var(--tone-ok)', bg: 'var(--tone-ok-bg)' },
  wait: { fg: 'var(--tone-wait)', bg: 'var(--tone-wait-bg)' },
  alert: { fg: 'var(--tone-alert)', bg: 'var(--tone-alert-bg)' },
  neutral: { fg: 'var(--tone-neutral)', bg: 'var(--tone-neutral-bg)' },
}

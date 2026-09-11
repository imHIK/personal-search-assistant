import { AlertTriangle, ExternalLink } from 'lucide-react'
import type { Digest, DigestRun, DigestRunItem } from '@/api/types'
import { Technical, TechnicalPanel } from '@/components/TechnicalDetails'
import { annotationToneVars, presentAnnotations } from '@/config/annotations'
import { labels } from '@/config/labels'

export interface RunOutcome {
  text: string
  /** Why the run came out that way, for the cases where the headline alone leaves people stuck. */
  hint?: string
  failed: boolean
  /** True when the look-back window, not the query, is what emptied the run. */
  windowed?: boolean
}

/**
 * How a run turned out, in one line.
 *
 * Four of these used to render identically as "no results", which made a digest that was working
 * perfectly indistinguishable from one whose query had stopped matching anything. The run's
 * `candidates`, `suppressed` and `outsideWindow` counters exist for exactly this sentence.
 *
 * Takes the digest because the wording depends on it: a digest with `onlyNew` off is not reporting
 * *new* anything, and calling its results new — which every non-job-search digest used to be told —
 * is just untrue.
 */
export function runOutcome(run: DigestRun, digest?: Pick<Digest, 'onlyNew'>): RunOutcome {
  const onlyNew = digest?.onlyNew ?? true
  if (run.error) return { text: labels.digests.outcomeFailed, failed: true }
  if (run.items.length > 0) {
    return {
      text: onlyNew
        ? labels.digests.resultCount(run.items.length)
        : labels.digests.resultCountPlain(run.items.length),
      failed: false,
    }
  }
  if (run.candidates > 0) {
    return {
      text: labels.digests.outcomeAllSeen(run.candidates),
      hint: labels.digests.outcomeAllSeenHint,
      failed: false,
    }
  }
  // The window filters on when something was last indexed. A source that finished ingesting a week
  // ago leaves a one-day window and never re-enters it, so every run afterwards is empty for a
  // reason no amount of rewriting the query will fix.
  if (run.outsideWindow > 0) {
    return {
      text: labels.digests.outcomeOutsideWindow(run.outsideWindow),
      hint: labels.digests.outcomeOutsideWindowHint,
      failed: false,
      windowed: true,
    }
  }
  return {
    text: labels.digests.outcomeNothingMatched,
    hint: labels.digests.outcomeNothingMatchedHint,
    failed: false,
  }
}

/** The failure, the task's summary, and the items — everything a run has to show. */
export function RunBody({ run, outcome }: { run: DigestRun; outcome?: RunOutcome }) {
  if (run.error) {
    return (
      <div className="flex gap-2">
        <AlertTriangle
          className="mt-0.5 size-3.5 shrink-0 text-[var(--tone-alert)]"
          aria-hidden
        />
        <div className="min-w-0">
          <p className="text-xs text-[var(--text-muted)]">{labels.digests.runFailed}</p>
          <p className="text-[11px] leading-relaxed text-[var(--text-subtle)]">{run.error}</p>
        </div>
      </div>
    )
  }

  // Only shown when nothing was annotated: once the task's reply has been read onto the items, the
  // same text twice — once as prose, once as badges — is noise rather than detail.
  const annotated = run.items.some((item) => Object.keys(item.annotations ?? {}).length > 0)

  return (
    <div className="space-y-3">
      {run.taskOutput && !annotated && (
        <p className="whitespace-pre-wrap rounded-lg bg-[var(--surface-sunken)] p-3 text-[12px] leading-relaxed text-[var(--text-muted)]">
          {run.taskOutput}
        </p>
      )}

      {/* An empty run has nothing below this point, so the reason it is empty is the body. */}
      {run.items.length === 0 && !run.error && outcome?.hint && (
        <p className="text-[11px] leading-relaxed text-[var(--text-subtle)]">{outcome.hint}</p>
      )}

      {run.items.length > 0 && (
        <ol className="space-y-1.5">
          {run.items.map((item, index) => (
            <RunItem key={item.chunkId} item={item} rank={index + 1} />
          ))}
        </ol>
      )}

      <Technical>
        <TechnicalPanel
          rows={[
            ['run id', run.id],
            ['candidates', String(run.candidates)],
            ['suppressed as already seen', String(run.suppressed)],
            ['matches outside the window', String(run.outsideWindow)],
            ...(run.taskOutput ? ([[labels.digests.rawOutput, run.taskOutput]] as [string, string][]) : []),
          ]}
        />
      </Technical>
    </div>
  )
}

/**
 * One result. Boxed rather than laid straight onto the panel: ten passages of running prose with no
 * rule between them read as one long quotation, which is what the list looked like whenever several
 * results came from the same document and the titles repeated.
 */
function RunItem({ item, rank }: { item: DigestRunItem; rank: number }) {
  const annotations = presentAnnotations(item.annotations)
  const scores = annotations.filter((a) => a.kind === 'score')
  const notes = annotations.filter((a) => a.kind === 'text')

  return (
    <li className="flex gap-2.5 rounded-lg border border-[var(--border)] bg-[var(--surface)] px-3 py-2">
      <span className="mt-0.5 w-4 shrink-0 text-right text-[11px] tabular-nums text-[var(--text-subtle)]">
        {rank}
      </span>
      {scores.map((score) => (
        <span
          key={score.key}
          title={score.label}
          className="mt-0.5 flex h-5 min-w-5 shrink-0 items-center justify-center rounded px-1 text-[11px] font-semibold tabular-nums"
          style={{
            color: annotationToneVars[score.tone].fg,
            backgroundColor: annotationToneVars[score.tone].bg,
          }}
        >
          {score.value}
        </span>
      ))}

      <div className="min-w-0 flex-1">
        <p className="truncate text-xs">
          {item.uri ? (
            <a
              href={item.uri}
              target="_blank"
              rel="noreferrer"
              className="inline-flex items-center gap-1 text-[var(--accent)] hover:underline"
            >
              {item.title || item.uri}
              <ExternalLink className="size-3 shrink-0" aria-hidden />
            </a>
          ) : (
            <span>{item.title || item.entityId}</span>
          )}
        </p>

        {notes.map((note) => (
          <p
            key={note.key}
            className="mt-0.5 text-[11px] leading-relaxed"
            style={{ color: annotationToneVars[note.tone].fg }}
          >
            {note.value}
          </p>
        ))}

        {/* Only worth the room when the task said nothing about this item. */}
        {notes.length === 0 && item.snippet && (
          <p className="mt-0.5 line-clamp-2 text-[11px] leading-relaxed text-[var(--text-subtle)]">
            {item.snippet}
          </p>
        )}

        <Technical>
          <span className="text-[10px] text-[var(--text-subtle)]">
            {item.entityId} · {item.score.toFixed(3)}
          </span>
        </Technical>
      </div>
    </li>
  )
}

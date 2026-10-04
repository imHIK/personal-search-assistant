import { AlertTriangle, ExternalLink, Sparkles } from 'lucide-react'
import { forwardRef } from 'react'
import type { Digest, DigestRun, DigestRunItem, TaskSourceText } from '@/api/types'
import { Markdown } from '@/components/Markdown'
import { Technical, TechnicalPanel } from '@/components/TechnicalDetails'
import { annotationToneVars, presentAnnotations } from '@/config/annotations'
import { labels } from '@/config/labels'
import { useCitationJump } from '@/hooks/useCitationJump'
import { cn, displayName } from '@/lib/utils'

export interface RunOutcome {
  text: string
  hint?: string
  failed: boolean
  windowed?: boolean
}

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

/**
 * Source n is the n-th item, except under an `ENTITY` task: SourceTexts collapses one document's
 * chunks first, so n is the n-th distinct entity (the backend joins annotations the same way).
 * Built-in tasks report no sourceText; the uncollapsed mapping is the default.
 */
function citationRanks(items: DigestRunItem[], sourceText: TaskSourceText | null): number[] {
  if (sourceText !== 'ENTITY') return items.map((_, index) => index + 1)
  const seen = new Set<string>()
  const ranks: number[] = []
  items.forEach((item, index) => {
    if (item.entityId && seen.has(item.entityId)) return
    if (item.entityId) seen.add(item.entityId)
    ranks.push(index + 1)
  })
  return ranks
}

export function RunBody({
  run,
  outcome,
  taskSourceText = null,
}: {
  run: DigestRun
  outcome?: RunOutcome
  taskSourceText?: TaskSourceText | null
}) {
  const { citedRank, jumpTo, register } = useCitationJump<HTMLLIElement>()
  const ranks = citationRanks(run.items, taskSourceText)

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

  const annotated = run.items.some((item) => Object.keys(item.annotations ?? {}).length > 0)

  return (
    <div className="space-y-3">
      {run.taskError && (
        <div className="flex gap-2">
          <AlertTriangle className="mt-0.5 size-3.5 shrink-0 text-[var(--tone-wait)]" aria-hidden />
          <div className="min-w-0">
            <p className="text-xs text-[var(--text-muted)]">{labels.digests.taskFailed}</p>
            <p className="text-[11px] leading-relaxed text-[var(--text-subtle)]">{run.taskError}</p>
          </div>
        </div>
      )}

      {run.taskOutput && !annotated && (
        <section className="rounded-lg border border-[var(--accent)]/25 bg-[var(--accent-subtle)]/40 p-3">
          <p className="mb-2 flex items-center gap-2 text-[11px] font-medium uppercase tracking-wide text-[var(--accent)]">
            <Sparkles className="size-3.5" aria-hidden />
            {labels.digests.summaryHeading}
          </p>
          <Markdown
            text={run.taskOutput}
            className="text-[13px] text-[var(--text)]"
            citation={(index) => {
              const rank = ranks[index - 1]
              const item = rank ? run.items[rank - 1] : undefined
              if (!item) return null
              return {
                label: `${labels.digests.citation(rank)}: ${displayName(item)}`,
                onClick: () => jumpTo(rank),
              }
            }}
          />
        </section>
      )}

      {run.items.length === 0 && !run.error && outcome?.hint && (
        <p className="text-[11px] leading-relaxed text-[var(--text-subtle)]">{outcome.hint}</p>
      )}

      {run.items.length > 0 && (
        <ol className="space-y-1.5">
          {run.items.map((item, index) => (
            <RunItem
              key={item.chunkId}
              item={item}
              rank={index + 1}
              highlighted={citedRank === index + 1}
              ref={register(index + 1)}
            />
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

const RunItem = forwardRef<HTMLLIElement, {
  item: DigestRunItem
  rank: number
  highlighted?: boolean
}>(function RunItem({ item, rank, highlighted }, ref) {
  const annotations = presentAnnotations(item.annotations)
  const scores = annotations.filter((a) => a.kind === 'score')
  const notes = annotations.filter((a) => a.kind === 'text')

  return (
    <li
      ref={ref}
      tabIndex={-1}
      className={cn(
        'flex scroll-mt-24 gap-2.5 rounded-lg border border-[var(--border)] bg-[var(--surface)] px-3 py-2 outline-none transition-shadow duration-300',
        highlighted && 'ring-2 ring-[var(--accent)]',
      )}
    >
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
})

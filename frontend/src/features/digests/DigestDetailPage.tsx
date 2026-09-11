import {
  AlertTriangle,
  CalendarClock,
  ChevronDown,
  ChevronRight,
  Clock,
  FileText,
  FolderOpen,
  History,
  Play,
  RotateCcw,
  Sparkles,
  Trash2,
} from 'lucide-react'
import { useState } from 'react'
import { useNavigate, useParams, useSearchParams } from 'react-router-dom'
import { toast } from 'sonner'
import type { Digest, DigestRun } from '@/api/types'
import { Technical } from '@/components/TechnicalDetails'
import { Button } from '@/components/ui/Button'
import { Card } from '@/components/ui/Card'
import { ConfirmDialog } from '@/components/ui/Dialog'
import { PageHeader } from '@/components/ui/PageHeader'
import { EmptyState, ErrorState, SkeletonList } from '@/components/ui/States'
import { Toggle } from '@/components/ui/Toggle'
import { formatDigestInterval, formatDigestWindow } from '@/config/constants'
import { friendlyError } from '@/config/errors'
import { labels } from '@/config/labels'
import {
  useDigest,
  useDigestActions,
  useDigestRuns,
  useEntity,
  useKnowledgeList,
  useTasks,
} from '@/hooks/queries'
import { cn, relativeTime } from '@/lib/utils'
import { DigestForm } from './DigestForm'
import { RunBody, runOutcome, type RunOutcome } from './RunView'

const PAGE = 20
const tabs = [
  { id: 'runs', label: labels.digests.tabRuns },
  { id: 'settings', label: labels.digests.tabSettings },
] as const

type TabId = (typeof tabs)[number]['id']

/**
 * One digest, its history, and its settings.
 *
 * The list page previously fetched twenty runs and rendered one, so "what did this send me on
 * Tuesday" had no answer anywhere in the console. Structured like `SourceDetailPage` — tabs in the
 * query string, so a particular view survives a refresh and can be linked to.
 */
export function DigestDetailPage() {
  const { id } = useParams<{ id: string }>()
  const navigate = useNavigate()
  const [params, setParams] = useSearchParams()
  const [pendingRemoval, setPendingRemoval] = useState(false)
  const [pendingReset, setPendingReset] = useState(false)
  const [page, setPage] = useState(0)

  const { data: digest, isLoading, error, refetch } = useDigest(id)
  const { data: runs, isLoading: runsLoading } = useDigestRuns(id, PAGE * (page + 1), 0)
  const { setEnabled, remove, run, update, resetHistory } = useDigestActions()

  const activeTab = (params.get('tab') as TabId | null) ?? 'runs'
  const setTab = (tab: TabId) => {
    const next = new URLSearchParams(params)
    next.set('tab', tab)
    if (tab !== 'runs') next.delete('run')
    setParams(next, { replace: true })
  }

  if (isLoading) return <SkeletonList rows={3} />
  if (error) return <ErrorState error={error} onRetry={() => void refetch()} />
  if (!digest || !id) return null

  const hasMore = (runs?.length ?? 0) >= PAGE * (page + 1)

  return (
    <>
      <PageHeader
        title={digest.name}
        subtitle={describe(digest)}
        backTo="/digests"
        backLabel={labels.digests.back}
        actions={
          <div className="flex items-center gap-2">
            <Toggle
              checked={digest.enabled}
              onCheckedChange={(enabled) => setEnabled.mutate({ id, enabled })}
              label={digest.enabled ? labels.digests.pause : labels.digests.resume}
            />
            <Button
              variant="secondary"
              size="sm"
              loading={run.isPending}
              onClick={() => run.mutate(id)}
            >
              <Play />
              {run.isPending ? labels.digests.running : labels.digests.runNow}
            </Button>
          </div>
        }
      />

      <SummaryStrip digest={digest} />

      <div className="mb-4 flex gap-1 border-b border-[var(--border)]">
        {tabs.map((tab) => (
          <button
            key={tab.id}
            type="button"
            onClick={() => setTab(tab.id)}
            className={cn(
              '-mb-px border-b-2 px-3 py-2 text-[13px] font-medium transition-colors',
              activeTab === tab.id
                ? 'border-[var(--accent)] text-[var(--text)]'
                : 'border-transparent text-[var(--text-muted)] hover:text-[var(--text)]',
            )}
          >
            {tab.label}
          </button>
        ))}
      </div>

      {activeTab === 'runs' ? (
        runsLoading ? (
          <SkeletonList rows={3} />
        ) : !runs || runs.length === 0 ? (
          <EmptyState
            icon={Play}
            title={labels.digests.runsEmpty}
            description={labels.digests.runsEmptyHint}
            action={
              <Button variant="primary" loading={run.isPending} onClick={() => run.mutate(id)}>
                <Play />
                {labels.digests.runNow}
              </Button>
            }
          />
        ) : (
          <div className="space-y-2">
            {groupRuns(runs, digest).map((group, index) =>
              group.runs.length === 1 ? (
                <RunRow
                  key={group.runs[0].id}
                  run={group.runs[0]}
                  outcome={group.outcome}
                  // The newest run is the one being looked for nine times out of ten.
                  defaultOpen={index === 0}
                  openedByLink={params.get('run') === group.runs[0].id}
                  onOpen={() => {
                    const next = new URLSearchParams(params)
                    next.set('run', group.runs[0].id)
                    setParams(next, { replace: true })
                  }}
                  onWiden={() => setTab('settings')}
                />
              ) : (
                <QuietRunRow
                  key={group.runs[0].id}
                  group={group}
                  onWiden={() => setTab('settings')}
                />
              ),
            )}
            {hasMore && (
              <Button variant="ghost" size="sm" onClick={() => setPage(page + 1)}>
                {labels.digests.loadMore}
              </Button>
            )}
          </div>
        )
      ) : (
        <div className="space-y-4">
          <DigestForm
            initial={digest}
            pending={update.isPending}
            submitLabel={labels.digests.save}
            onCancel={() => setTab('runs')}
            onSubmit={(body) =>
              update.mutate(
                { id, body },
                {
                  onSuccess: () => toast.success(labels.digests.saved),
                  onError: (cause) =>
                    toast.error(labels.digests.saveFailed, {
                      description: friendlyError(cause).detail,
                    }),
                },
              )
            }
          />

          <Card className="space-y-3 p-5">
            <div>
              <h3 className="text-sm font-medium">{labels.digests.resetHistory}</h3>
              <p className="mt-1 text-xs leading-relaxed text-[var(--text-muted)]">
                {labels.digests.resetHistoryBody}
              </p>
              {digest.historyResetAt && (
                <p className="mt-1 text-[11px] text-[var(--text-subtle)]">
                  {labels.digests.historyResetAt} {relativeTime(digest.historyResetAt)}
                </p>
              )}
            </div>
            <div className="flex gap-2">
              <Button variant="secondary" size="sm" onClick={() => setPendingReset(true)}>
                <RotateCcw />
                {labels.digests.resetHistory}
              </Button>
              <Button variant="dangerGhost" size="sm" onClick={() => setPendingRemoval(true)}>
                <Trash2 />
                {labels.digests.remove}
              </Button>
            </div>
          </Card>
        </div>
      )}

      <ConfirmDialog
        open={pendingReset}
        onOpenChange={setPendingReset}
        title={labels.digests.resetHistoryConfirm}
        description={labels.digests.resetHistoryBody}
        confirmLabel={labels.digests.resetHistory}
        loading={resetHistory.isPending}
        onConfirm={() => {
          resetHistory.mutate(id, {
            onSuccess: () => toast.success(labels.digests.resetHistoryDone),
          })
          setPendingReset(false)
        }}
      />

      <ConfirmDialog
        open={pendingRemoval}
        onOpenChange={setPendingRemoval}
        title={labels.digests.removeConfirm}
        description={labels.digests.removeBody}
        confirmLabel={labels.digests.remove}
        loading={remove.isPending}
        onConfirm={() => {
          remove.mutate(id, { onSuccess: () => navigate('/digests') })
          setPendingRemoval(false)
        }}
      />
    </>
  )
}

/** One line under the title: what this digest is, without ids or jargon. */
function describe(digest: Digest): string {
  const what = digest.sourceEntityId
    ? labels.digests.searchesLike.toLowerCase()
    : `${labels.digests.searchesFor.toLowerCase()} “${digest.query ?? ''}”`
  const cadence = formatDigestInterval(digest.interval)
  return cadence ? `${what} · ${cadence.toLowerCase()}` : what
}

/**
 * What this digest does, in plain terms. Deliberately resolves ids to names — the source document's
 * title, the sources' names, the task's name — because the point of the strip is to be readable by
 * someone who did not set the digest up.
 *
 * A wrapped row of labelled facts rather than a grid of uppercase headings: the six-cell grid it
 * replaced gave a one-word value ("Every hour") the same weight as the page title, and pushed the
 * history — the thing people come here for — below the fold. What the digest searches for is not
 * repeated here; it is already the page subtitle.
 */
function SummaryStrip({ digest }: { digest: Digest }) {
  const { data: sources } = useKnowledgeList()
  const { data: tasks } = useTasks()
  const { data: document } = useEntity(digest.sourceEntityId)

  const scoped = (sources ?? []).filter((source) => digest.knowledgeIds.includes(source.id))
  const task = (tasks ?? []).find((candidate) => candidate.id === digest.taskId)

  const facts: { icon: typeof FolderOpen; label: string; value: string }[] = [
    // Only for a digest that searches by a document: the subtitle can only say "finds things like",
    // and "like what" is the one thing that digest's page must not leave as an id.
    ...(digest.sourceEntityId
      ? [
          {
            icon: FileText,
            label: labels.digests.searchesLike,
            value: document?.title ?? digest.sourceEntityId,
          },
        ]
      : []),
    {
      icon: FolderOpen,
      label: labels.digests.looksIn,
      value:
        scoped.length > 0
          ? scoped.map((source) => source.name).join(', ')
          : labels.digests.looksInAll,
    },
    {
      icon: History,
      label: labels.digests.lookBack,
      value: formatDigestWindow(digest.window) ?? labels.digests.lookBackNone,
    },
    {
      icon: CalendarClock,
      label: labels.digests.runsEvery,
      value: digest.cron ?? formatDigestInterval(digest.interval) ?? labels.digests.notScheduled,
    },
    {
      icon: Sparkles,
      label: labels.digests.taskLabel,
      value: digest.taskId
        ? (task?.name ?? labels.digests.taskMissing)
        : labels.digests.taskNone,
    },
    {
      icon: Clock,
      label: labels.digests.nextRun,
      value: !digest.enabled
        ? labels.digests.paused
        : // A null nextRunAt means "on the next tick", which reads as broken if shown as a blank.
          (relativeTime(digest.nextRunAt) ?? labels.digests.dueNow),
    },
  ]

  return (
    <Card className="mb-4 flex flex-wrap items-center gap-x-5 gap-y-2 px-4 py-3">
      {facts.map((fact) => (
        <span key={fact.label} className="flex min-w-0 items-center gap-1.5 text-xs">
          <fact.icon className="size-3.5 shrink-0 text-[var(--text-subtle)]" aria-hidden />
          <span className="text-[var(--text-muted)]">{fact.label}</span>
          <span className="truncate font-medium text-[var(--text)]" title={fact.value}>
            {fact.value}
          </span>
        </span>
      ))}
      <span className="shrink-0 rounded-full bg-[var(--tone-neutral-bg)] px-2 py-0.5 text-[11px] text-[var(--tone-neutral)]">
        {digest.onlyNew ? labels.digests.showsOnlyNew : labels.digests.showsEverything}
      </span>
    </Card>
  )
}

/**
 * Consecutive runs that came out the same way and have nothing to open.
 *
 * An hourly digest whose window is empty writes one of these every hour, and each used to be a
 * full-height expandable card saying "Nothing matched" — a page of identical boxes hiding the last
 * run that actually found something. Collapsing a streak into one line is the whole point.
 */
interface RunGroup {
  runs: DigestRun[]
  outcome: RunOutcome
}

/** A run is worth its own row when there is something inside it to look at. */
function hasBody(run: DigestRun): boolean {
  return run.items.length > 0 || Boolean(run.error) || Boolean(run.taskOutput)
}

function groupRuns(runs: DigestRun[], digest: Digest): RunGroup[] {
  const groups: RunGroup[] = []
  for (const run of runs) {
    const outcome = runOutcome(run, digest)
    const previous = groups[groups.length - 1]
    if (
      previous &&
      !hasBody(run) &&
      !hasBody(previous.runs[previous.runs.length - 1]) &&
      previous.outcome.text === outcome.text
    ) {
      previous.runs.push(run)
      continue
    }
    groups.push({ runs: [run], outcome })
  }
  return groups
}

function QuietRunRow({ group, onWiden }: { group: RunGroup; onWiden: () => void }) {
  const { runs, outcome } = group
  const times = runs.map((run) => new Date(run.ranAt).toLocaleString()).join('\n')

  return (
    <div
      className="flex flex-wrap items-center gap-x-2 gap-y-1 rounded-xl border border-dashed border-[var(--border)] px-4 py-2 text-xs"
      title={times}
    >
      <span className="text-[var(--text-muted)]">{relativeTime(runs[0].ranAt)}</span>
      <span className="text-[var(--text-subtle)]">·</span>
      <span className="text-[var(--text-muted)]">{outcome.text}</span>
      {runs.length > 1 && (
        <span className="rounded-full bg-[var(--tone-neutral-bg)] px-1.5 text-[11px] text-[var(--tone-neutral)]">
          {labels.digests.runStreak(runs.length)}
        </span>
      )}
      {outcome.windowed && (
        <button
          type="button"
          onClick={onWiden}
          className="ml-auto text-[11px] text-[var(--accent)] hover:underline"
        >
          {labels.digests.widenWindow}
        </button>
      )}
    </div>
  )
}

function RunRow({
  run,
  outcome,
  defaultOpen,
  openedByLink,
  onOpen,
  onWiden,
}: {
  run: DigestRun
  outcome: RunOutcome
  defaultOpen: boolean
  openedByLink: boolean
  onOpen: () => void
  onWiden: () => void
}) {
  const [open, setOpen] = useState(defaultOpen || openedByLink)

  return (
    <Card className="overflow-hidden">
      <button
        type="button"
        onClick={() => {
          if (!open) onOpen()
          setOpen(!open)
        }}
        className="flex w-full items-center gap-2 px-4 py-3 text-left hover:bg-[var(--surface-hover)]"
      >
        {open ? (
          <ChevronDown className="size-3.5 shrink-0 text-[var(--text-subtle)]" aria-hidden />
        ) : (
          <ChevronRight className="size-3.5 shrink-0 text-[var(--text-subtle)]" aria-hidden />
        )}
        <span className="text-xs text-[var(--text-muted)]" title={run.ranAt}>
          {relativeTime(run.ranAt)}
        </span>
        <span className="mx-1 text-[var(--text-subtle)]">·</span>
        <span
          className={cn(
            'flex items-center gap-1 text-xs',
            outcome.failed ? 'text-[var(--tone-alert)]' : 'text-[var(--text)]',
          )}
        >
          {outcome.failed && <AlertTriangle className="size-3.5" aria-hidden />}
          {outcome.text}
        </span>
        <Technical>
          <span className="ml-auto text-[10px] text-[var(--text-subtle)]">{run.id}</span>
        </Technical>
      </button>

      {open && (
        <div className="space-y-2 border-t border-[var(--border)] px-4 py-3">
          <RunBody run={run} outcome={outcome} />
          {outcome.windowed && (
            <button
              type="button"
              onClick={onWiden}
              className="text-[11px] text-[var(--accent)] hover:underline"
            >
              {labels.digests.widenWindow}
            </button>
          )}
        </div>
      )}
    </Card>
  )
}

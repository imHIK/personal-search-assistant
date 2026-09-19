import {
  AlertTriangle,
  ChevronDown,
  ChevronRight,
  ExternalLink,
  FileQuestion,
  RefreshCw,
  Trash2,
} from 'lucide-react'
import { useMemo, useState } from 'react'
import { useSearchParams } from 'react-router-dom'
import { toast } from 'sonner'
import type { CursorInfo, EntityItem } from '@/api/types'
import { Technical, TechnicalPanel, useTechnicalDetails } from '@/components/TechnicalDetails'
import { Button } from '@/components/ui/Button'
import { Card } from '@/components/ui/Card'
import { FilterBar } from '@/components/ui/FilterBar'
import { StateBadge } from '@/components/ui/StateBadge'
import { EmptyState, ErrorState, SkeletonList } from '@/components/ui/States'
import { Tooltip } from '@/components/ui/Tooltip'
import { PAGE_SIZE } from '@/config/constants'
import { friendlyError, friendlyLastError } from '@/config/errors'
import type { ConnectorDescriptor } from '@/config/connectors'
import { labels } from '@/config/labels'
import {
  entityFilters,
  hasActiveFilters,
  withOptions,
  type ListFilterValues,
} from '@/config/listFilters'
import { groupName, presentEntityType, presentItem } from '@/config/presentation'
import { useCursors, useEntities, useEntityActions } from '@/hooks/queries'
import { absoluteTime, displayName, formatNumber, relativeTime } from '@/lib/utils'

/**
 * The item browser. Backed by `GET /api/knowledge/{id}/entities`, which returns projections —
 * so this never has the item's text, only enough to identify and act on it.
 */
export function ItemsTab({
  knowledgeId,
  descriptor,
}: {
  knowledgeId: string
  descriptor: ConnectorDescriptor
}) {
  const [params, setParams] = useSearchParams()
  const [offset, setOffset] = useState(0)
  // Same query key as the page's own, so this is a cache read, not a second request.
  const { data: cursors } = useCursors(knowledgeId)
  const noun = descriptor.groupNoun ?? { one: labels.detail.groups, many: labels.detail.groups }

  // One option per group, named exactly as the Groups tab names it.
  const specs = useMemo(() => {
    const ids = [...new Set((cursors ?? []).map((cursor) => cursor.iterableId))]
    const options = ids
      .map((id) => ({ value: id, label: groupName(id, cursors, labels.groups.everything) }))
      .sort((a, b) => a.label.localeCompare(b.label))
    // A single-group source (one folder, "everything") has nothing to narrow by.
    return withOptions(entityFilters, 'group', options.length > 1 ? options : [], {
      label: noun.one,
      placeholder: labels.filters.all(noun.many),
    })
  }, [cursors, noun.one, noun.many])

  // Filter values live in the URL, so a filtered list survives a reload and can be linked to.
  const values: ListFilterValues = useMemo(() => {
    const next: ListFilterValues = {}
    for (const spec of specs) {
      const value = params.get(spec.id)
      if (value) next[spec.id] = value
    }
    return next
  }, [params, specs])

  const { data, isLoading, error, refetch } = useEntities(knowledgeId, values, offset, PAGE_SIZE)

  const filtering = hasActiveFilters(specs, values)

  const setValue = (id: string, value: string) => {
    const next = new URLSearchParams(params)
    next.set('tab', 'items')
    if (value) next.set(id, value)
    else next.delete(id)
    setParams(next, { replace: true })
    // Page 3 of the old filter is rarely page 3 of the new one, and an out-of-range offset renders
    // an empty list over a non-zero total.
    setOffset(0)
  }

  const clearFilters = () => {
    const next = new URLSearchParams(params)
    for (const spec of specs) next.delete(spec.id)
    setParams(next, { replace: true })
    setOffset(0)
  }

  const total = data?.total ?? 0
  const from = total === 0 ? 0 : offset + 1
  const to = Math.min(offset + PAGE_SIZE, total)

  return (
    <div className="space-y-4">
      <FilterBar
        specs={specs}
        values={values}
        onChange={setValue}
        onClear={clearFilters}
      />

      {isLoading && !data ? (
        <SkeletonList rows={4} />
      ) : error ? (
        <ErrorState error={error} onRetry={() => void refetch()} />
      ) : !data || data.items.length === 0 ? (
        <EmptyState
          icon={FileQuestion}
          title={filtering ? labels.items.emptyFiltered : labels.items.empty}
          description={filtering ? undefined : labels.items.emptyHint}
        />
      ) : (
        <>
          {/* No overflow-hidden: it would clip an item row's error tooltip. */}
          <Card className="divide-y divide-[var(--border)]">
            {data.items.map((item) => (
              <ItemRow
                key={item.id}
                item={item}
                knowledgeId={knowledgeId}
                cursors={cursors}
                onShowGroup={(iterableId) => setValue('group', iterableId)}
              />
            ))}
          </Card>

          <div className="flex items-center justify-between gap-3">
            <p className="text-xs text-[var(--text-muted)]" aria-live="polite">
              {labels.items.page(from, to, total)}
            </p>
            <div className="flex gap-2">
              <Button
                size="sm"
                variant="secondary"
                disabled={offset === 0}
                onClick={() => setOffset(Math.max(0, offset - PAGE_SIZE))}
              >
                {labels.items.previous}
              </Button>
              <Button
                size="sm"
                variant="secondary"
                disabled={to >= total}
                onClick={() => setOffset(offset + PAGE_SIZE)}
              >
                {labels.items.next}
              </Button>
            </div>
          </div>
        </>
      )}
    </div>
  )
}

/**
 * An item's processing error, folded behind an icon.
 *
 * Inline it dominated the row: three wrapped lines of exception text above the one thing the row is
 * actually about, its name — and repeated verbatim down the whole list when a batch fails the same
 * way, which is the common case. The icon keeps the list scannable while still marking which items
 * carry an error, and the wording stays one hover away.
 */
function ItemError({ error }: { error: string }) {
  const technical = useTechnicalDetails()
  const friendly = friendlyLastError(error)

  return (
    <Tooltip
      content={
        <>
          <p className="text-xs font-medium text-[var(--text)]">{friendly.title}</p>
          <p className="mt-1 text-xs leading-relaxed text-[var(--text-muted)]">{friendly.detail}</p>
          {technical && friendly.raw && friendly.raw !== friendly.detail && (
            <p className="mt-2 break-all font-mono text-[10px] leading-relaxed text-[var(--text-subtle)]">
              {friendly.raw}
            </p>
          )}
        </>
      }
    >
      <button
        type="button"
        aria-label={`${labels.items.errorHint}: ${friendly.title}`}
        className="rounded p-1 text-[var(--tone-alert)] transition-colors hover:bg-[var(--tone-alert-bg)]"
      >
        <AlertTriangle className="size-4" aria-hidden />
      </button>
    </Tooltip>
  )
}

function ItemRow({
  item,
  knowledgeId,
  cursors,
  onShowGroup,
}: {
  item: EntityItem
  knowledgeId: string
  cursors: CursorInfo[] | undefined
  onShowGroup: (iterableId: string) => void
}) {
  const [expanded, setExpanded] = useState(false)
  const technical = useTechnicalDetails()
  const { reindex, remove } = useEntityActions(knowledgeId)

  const name = displayName(item)
  const presented = presentItem(item)
  // createdAt, not updatedAt: updatedAt is the row's last write, and almost every one of those is the
  // indexing stage's own bookkeeping — a claim, a retry, a deferral. An item that has never changed
  // reads as "added 2 minutes ago" all day if you show it here.
  const added = relativeTime(item.createdAt)
  const group = item.iterableId ? groupName(item.iterableId, cursors, labels.groups.everything) : null

  const act = (
    mutation: {
      mutate: (
        id: string,
        options?: { onSuccess?: () => void; onError?: (error: unknown) => void },
      ) => void
    },
    message: string,
  ) =>
    mutation.mutate(item.id, {
      onSuccess: () => toast.success(message),
      onError: (error: unknown) => {
        const friendly = friendlyError(error)
        toast.error(friendly.title, { description: friendly.detail })
      },
    })

  return (
    <div className="px-4 py-3">
      <div className="flex items-start gap-3">
        <button
          type="button"
          onClick={() => setExpanded((value) => !value)}
          aria-expanded={expanded}
          aria-label={expanded ? labels.common.showLess : labels.common.showMore}
          className="mt-0.5 rounded text-[var(--text-subtle)] transition-colors hover:text-[var(--text)]"
        >
          {expanded ? <ChevronDown className="size-4" /> : <ChevronRight className="size-4" />}
        </button>

        <div className="min-w-0 flex-1">
          <p className="truncate text-sm font-medium text-[var(--text)]" title={name}>
            {name}
          </p>
          <p className="mt-0.5 text-xs text-[var(--text-muted)]">
            {presentEntityType(item.entityType)}
            {group && item.iterableId && (
              <>
                {' · '}
                <button
                  type="button"
                  onClick={() => onShowGroup(item.iterableId!)}
                  title={labels.items.showOnlyGroup(group)}
                  className="rounded text-[var(--text-muted)] underline-offset-2 hover:text-[var(--accent)] hover:underline"
                >
                  {group}
                </button>
              </>
            )}
            {added && ` · ${labels.items.added.toLowerCase()} ${added}`}
            {item.chunkCount > 0 && technical && ` · ${formatNumber(item.chunkCount)} chunks`}
          </p>
        </div>

        <div className="flex shrink-0 items-center gap-1.5">
          {item.error && <ItemError error={item.error} />}
          <StateBadge state={presented} size="sm" />
          <Button
            variant="ghost"
            size="iconSm"
            title={labels.items.refreshHint}
            aria-label={`${labels.items.refresh} — ${name}`}
            loading={reindex.isPending}
            onClick={() => act(reindex, `Reprocessing "${name}"`)}
          >
            <RefreshCw />
          </Button>
          <Button
            variant="dangerGhost"
            size="iconSm"
            title={labels.items.removeHint}
            aria-label={`${labels.items.remove} — ${name}`}
            loading={remove.isPending}
            onClick={() => act(remove, `Removed "${name}" from search`)}
          >
            <Trash2 />
          </Button>
        </div>
      </div>

      {expanded && (
        <div className="mt-3 space-y-3 pl-7">
          {item.uri && (
            <a
              href={item.uri}
              target="_blank"
              rel="noreferrer"
              className="inline-flex items-center gap-1.5 break-all text-xs text-[var(--accent)] hover:underline"
            >
              <ExternalLink className="size-3.5 shrink-0" aria-hidden />
              {item.uri}
            </a>
          )}
          <Technical>
            <TechnicalPanel
              rows={[
                ['id', item.id],
                ['iterableId', item.iterableId ?? '—'],
                ['externalId', item.externalId],
                ['status', item.status ?? '—'],
                ['entityType', item.entityType ?? '—'],
                ['checksum', item.checksum ?? '—'],
                ['chunkCount', formatNumber(item.chunkCount)],
                ['embeddingModel', item.embeddingModel ?? '—'],
                ['indexedAt', absoluteTime(item.indexedAt) ?? '—'],
                ['retryCount', String(item.retryCount)],
                ['needsReindex', String(item.needsReindex)],
                ['createdAt', absoluteTime(item.createdAt) ?? '—'],
                ['updatedAt', absoluteTime(item.updatedAt) ?? '—'],
                ['error', item.error ?? '—'],
              ]}
            />
          </Technical>
        </div>
      )}
    </div>
  )
}

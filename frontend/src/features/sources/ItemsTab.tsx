import { ChevronDown, ChevronRight, ExternalLink, FileQuestion, RefreshCw, Trash2 } from 'lucide-react'
import { useState } from 'react'
import { useSearchParams } from 'react-router-dom'
import { toast } from 'sonner'
import type { EntityItem } from '@/api/types'
import { Technical, TechnicalPanel, useTechnicalDetails } from '@/components/TechnicalDetails'
import { Button } from '@/components/ui/Button'
import { Card } from '@/components/ui/Card'
import { StateBadge } from '@/components/ui/StateBadge'
import { EmptyState, ErrorState, SkeletonList } from '@/components/ui/States'
import { SegmentedControl } from '@/components/ui/Toggle'
import { PAGE_SIZE } from '@/config/constants'
import { friendlyError, friendlyLastError } from '@/config/errors'
import { labels } from '@/config/labels'
import { itemFilters, presentEntityType, presentItem } from '@/config/presentation'
import { useEntities, useEntityActions } from '@/hooks/queries'
import { absoluteTime, displayName, formatNumber, relativeTime } from '@/lib/utils'

/**
 * The item browser. Backed by `GET /api/knowledge/{id}/entities`, which returns projections —
 * so this never has the item's text, only enough to identify and act on it.
 */
export function ItemsTab({ knowledgeId }: { knowledgeId: string }) {
  const [params, setParams] = useSearchParams()
  const [offset, setOffset] = useState(0)

  const filterId = params.get('filter') ?? 'all'
  const filter = itemFilters.find((f) => f.id === filterId) ?? itemFilters[0]

  const { data, isLoading, error, refetch } = useEntities(
    knowledgeId,
    filter.status,
    offset,
    PAGE_SIZE,
  )

  const setFilter = (id: string) => {
    const next = new URLSearchParams(params)
    next.set('tab', 'items')
    if (id === 'all') next.delete('filter')
    else next.set('filter', id)
    setParams(next, { replace: true })
    setOffset(0)
  }

  const total = data?.total ?? 0
  const from = total === 0 ? 0 : offset + 1
  const to = Math.min(offset + PAGE_SIZE, total)

  return (
    <div className="space-y-4">
      <SegmentedControl
        value={filter.id}
        onChange={setFilter}
        ariaLabel="Filter items"
        options={itemFilters.map((f) => ({ value: f.id, label: f.label }))}
      />

      {isLoading && !data ? (
        <SkeletonList rows={4} />
      ) : error ? (
        <ErrorState error={error} onRetry={() => void refetch()} />
      ) : !data || data.items.length === 0 ? (
        <EmptyState
          icon={FileQuestion}
          title={filter.status ? labels.items.emptyFiltered : labels.items.empty}
          description={filter.status ? undefined : labels.items.emptyHint}
        />
      ) : (
        <>
          <Card className="divide-y divide-[var(--border)] overflow-hidden">
            {data.items.map((item) => (
              <ItemRow key={item.id} item={item} knowledgeId={knowledgeId} />
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

function ItemRow({ item, knowledgeId }: { item: EntityItem; knowledgeId: string }) {
  const [expanded, setExpanded] = useState(false)
  const technical = useTechnicalDetails()
  const { reindex, remove } = useEntityActions(knowledgeId)

  const name = displayName(item)
  const presented = presentItem(item)
  const added = relativeTime(item.updatedAt)

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
            {added && ` · ${labels.items.added.toLowerCase()} ${added}`}
            {item.chunkCount > 0 && technical && ` · ${formatNumber(item.chunkCount)} chunks`}
          </p>
          {item.error && (
            <p className="mt-1 text-xs leading-relaxed text-[var(--tone-alert)]">
              {friendlyLastError(item.error).detail}
            </p>
          )}
        </div>

        <div className="flex shrink-0 items-center gap-1.5">
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
                ['externalId', item.externalId],
                ['status', item.status ?? '—'],
                ['entityType', item.entityType ?? '—'],
                ['checksum', item.checksum ?? '—'],
                ['chunkCount', formatNumber(item.chunkCount)],
                ['embeddingModel', item.embeddingModel ?? '—'],
                ['indexedAt', absoluteTime(item.indexedAt) ?? '—'],
                ['retryCount', String(item.retryCount)],
                ['needsReindex', String(item.needsReindex)],
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

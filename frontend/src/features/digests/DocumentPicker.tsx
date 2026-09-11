import { FileText, Search } from 'lucide-react'
import { useState } from 'react'
import { Button } from '@/components/ui/Button'
import { Dialog } from '@/components/ui/Dialog'
import { Input, Select } from '@/components/ui/Input'
import { EmptyState, SkeletonList } from '@/components/ui/States'
import { labels } from '@/config/labels'
import { useEntities, useKnowledgeList } from '@/hooks/queries'

interface Props {
  open: boolean
  onOpenChange: (open: boolean) => void
  onPick: (id: string, title: string) => void
}

const PAGE = 25

/**
 * Choose an indexed document to search *by* — a CV, a brief.
 *
 * Replaces pasting a raw `ent_…` id, which meant the only way to set up the feature the digest was
 * built for was to turn on technical details, find an item, and copy an opaque string. Filtering is
 * client-side over the current page because the entities endpoint takes no search term; that is
 * honest for browsing a source you know, and the page control is right there when it is not enough.
 */
export function DocumentPicker({ open, onOpenChange, onPick }: Props) {
  const { data: sources } = useKnowledgeList()
  const [knowledgeId, setKnowledgeId] = useState<string>('')
  const [offset, setOffset] = useState(0)
  const [filter, setFilter] = useState('')

  const active = (sources ?? []).filter((source) => source.status !== 'DELETED')
  const selected = knowledgeId || active[0]?.id || ''
  const { data, isLoading } = useEntities(selected || undefined, null, offset, PAGE)

  const needle = filter.trim().toLowerCase()
  const items = (data?.items ?? []).filter(
    (item) => !needle || (item.title ?? '').toLowerCase().includes(needle),
  )

  const pick = (id: string, title: string | null) => {
    onPick(id, title ?? id)
    onOpenChange(false)
  }

  return (
    <Dialog
      open={open}
      onOpenChange={onOpenChange}
      title={labels.digests.pickDocument}
      description={labels.digests.sourceEntityHint}
      className="max-w-2xl"
    >
      <div className="space-y-3">
        <div className="flex gap-2">
          <Select
            value={selected}
            onChange={(event) => {
              setKnowledgeId(event.target.value)
              setOffset(0)
            }}
            className="h-9 w-56 text-[13px]"
          >
            {active.map((source) => (
              <option key={source.id} value={source.id}>
                {source.name}
              </option>
            ))}
          </Select>
          <div className="relative flex-1">
            <Search
              className="pointer-events-none absolute left-2.5 top-1/2 size-3.5 -translate-y-1/2 text-[var(--text-subtle)]"
              aria-hidden
            />
            <Input
              value={filter}
              onChange={(event) => setFilter(event.target.value)}
              placeholder={labels.common.search}
              className="h-9 pl-8 text-[13px]"
            />
          </div>
        </div>

        {isLoading ? (
          <SkeletonList rows={4} />
        ) : items.length === 0 ? (
          <EmptyState icon={FileText} title={labels.digests.sourcesNone} />
        ) : (
          <ul className="max-h-80 divide-y divide-[var(--border)] overflow-auto rounded-lg border border-[var(--border)]">
            {items.map((item) => (
              <li key={item.id}>
                <button
                  type="button"
                  onClick={() => pick(item.id, item.title)}
                  className="flex w-full items-center gap-2 px-3 py-2 text-left text-xs hover:bg-[var(--surface-hover)]"
                >
                  <FileText className="size-3.5 shrink-0 text-[var(--text-subtle)]" aria-hidden />
                  <span className="truncate">{item.title || item.id}</span>
                </button>
              </li>
            ))}
          </ul>
        )}

        {data && data.total > PAGE && (
          <div className="flex items-center justify-between text-[11px] text-[var(--text-subtle)]">
            <span>
              {offset + 1}–{Math.min(offset + PAGE, data.total)} of {data.total}
            </span>
            <div className="flex gap-1">
              <Button
                type="button"
                variant="ghost"
                size="sm"
                disabled={offset === 0}
                onClick={() => setOffset(Math.max(offset - PAGE, 0))}
              >
                {labels.common.previous}
              </Button>
              <Button
                type="button"
                variant="ghost"
                size="sm"
                disabled={offset + PAGE >= data.total}
                onClick={() => setOffset(offset + PAGE)}
              >
                {labels.common.next}
              </Button>
            </div>
          </div>
        )}
      </div>
    </Dialog>
  )
}

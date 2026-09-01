import { useState } from 'react'
import type { CreateDigestBody } from '@/api/types'
import { useKnowledgeList } from '@/hooks/queries'
import { Button } from '@/components/ui/Button'
import { Card } from '@/components/ui/Card'
import { Input, Select } from '@/components/ui/Input'
import { Toggle } from '@/components/ui/Toggle'
import { labels } from '@/config/labels'

interface Props {
  onCreate: (body: CreateDigestBody) => void
  onCancel: () => void
  pending: boolean
}

/** Look-back windows and cadences, as the durations the backend's Durations parser accepts. */
const WINDOWS = [
  { value: '1d', label: 'Last day' },
  { value: '7d', label: 'Last week' },
  { value: '30d', label: 'Last month' },
  { value: '', label: 'No time limit' },
]

const INTERVALS = [
  { value: '1d', label: 'Day' },
  { value: '6h', label: '6 hours' },
  { value: '1h', label: 'Hour' },
  { value: '7d', label: 'Week' },
]

export function NewDigestForm({ onCreate, onCancel, pending }: Props) {
  const { data: sources } = useKnowledgeList()
  const [knowledgeIds, setKnowledgeIds] = useState<string[]>([])
  const [name, setName] = useState('')
  const [query, setQuery] = useState('')
  const [sourceEntityId, setSourceEntityId] = useState('')
  const [window, setWindow] = useState('1d')
  const [interval, setInterval] = useState('1d')
  const [onlyNew, setOnlyNew] = useState(true)

  // The backend rejects a digest with neither; checking here keeps that a disabled button rather
  // than a round trip that comes back 400.
  const valid = name.trim() !== '' && (query.trim() !== '' || sourceEntityId.trim() !== '')
  const activeSources = (sources ?? []).filter((source) => source.status !== 'DELETED')

  const toggleSource = (id: string) =>
    setKnowledgeIds((current) =>
      current.includes(id) ? current.filter((s) => s !== id) : [...current, id],
    )

  const submit = (event: React.FormEvent) => {
    event.preventDefault()
    if (!valid) return
    onCreate({
      name: name.trim(),
      query: query.trim() || null,
      sourceEntityId: sourceEntityId.trim() || null,
      // Empty means every source, which is the API's own default. Scoping matters here more than in
      // a one-off search: a digest runs unattended, so an unscoped one quietly starts reporting
      // whatever else happens to be indexed.
      knowledgeIds,
      window: window || null,
      interval,
      onlyNew,
      collapseDuplicates: true,
    })
  }

  return (
    <Card className="p-5">
      <form onSubmit={submit} className="space-y-4">
        <label className="block space-y-1">
          <span className="text-xs font-medium text-[var(--text-muted)]">{labels.digests.name}</span>
          <Input
            value={name}
            onChange={(event) => setName(event.target.value)}
            placeholder={labels.digests.namePlaceholder}
            autoFocus
          />
        </label>

        <label className="block space-y-1">
          <span className="text-xs font-medium text-[var(--text-muted)]">
            {labels.digests.queryLabel}
          </span>
          <Input
            value={query}
            onChange={(event) => setQuery(event.target.value)}
            placeholder={labels.digests.queryPlaceholder}
          />
          <span className="block text-[11px] text-[var(--text-subtle)]">
            {labels.digests.queryHint}
          </span>
        </label>

        <label className="block space-y-1">
          <span className="text-xs font-medium text-[var(--text-muted)]">
            {labels.digests.sourceEntity}
          </span>
          <Input
            value={sourceEntityId}
            onChange={(event) => setSourceEntityId(event.target.value)}
            placeholder={labels.digests.sourceEntityPlaceholder}
          />
          <span className="block text-[11px] text-[var(--text-subtle)]">
            {labels.digests.sourceEntityHint}
          </span>
        </label>

        <fieldset className="space-y-1">
          <legend className="text-xs font-medium text-[var(--text-muted)]">
            {labels.digests.sourcesLabel}
          </legend>
          <span className="block text-[11px] text-[var(--text-subtle)]">
            {knowledgeIds.length === 0
              ? labels.digests.sourcesAllHint
              : labels.digests.sourcesSomeHint(knowledgeIds.length)}
          </span>
          <div className="flex flex-wrap gap-x-4 gap-y-1.5 pt-1">
            {activeSources.map((source) => (
              <label key={source.id} className="flex items-center gap-1.5 text-xs">
                <input
                  type="checkbox"
                  checked={knowledgeIds.includes(source.id)}
                  onChange={() => toggleSource(source.id)}
                  className="size-3.5 accent-[var(--accent)]"
                />
                {source.name}
              </label>
            ))}
            {activeSources.length === 0 && (
              <span className="text-xs text-[var(--text-subtle)]">{labels.digests.sourcesNone}</span>
            )}
          </div>
        </fieldset>

        <div className="flex flex-wrap items-end gap-4">
          <label className="space-y-1">
            <span className="block text-xs font-medium text-[var(--text-muted)]">
              {labels.digests.windowLabel}
            </span>
            <Select
              value={window}
              onChange={(event) => setWindow(event.target.value)}
              className="h-9 w-auto min-w-36 text-[13px]"
            >
              {WINDOWS.map((option) => (
                <option key={option.value} value={option.value}>
                  {option.label}
                </option>
              ))}
            </Select>
          </label>

          <label className="space-y-1">
            <span className="block text-xs font-medium text-[var(--text-muted)]">
              {labels.digests.scheduleLabel}
            </span>
            <Select
              value={interval}
              onChange={(event) => setInterval(event.target.value)}
              className="h-9 w-auto min-w-36 text-[13px]"
            >
              {INTERVALS.map((option) => (
                <option key={option.value} value={option.value}>
                  {option.label}
                </option>
              ))}
            </Select>
          </label>

          <div className="mb-1.5">
            <Toggle checked={onlyNew} onCheckedChange={setOnlyNew} label={labels.digests.onlyNew} />
          </div>
        </div>

        <div className="flex gap-2 pt-1">
          <Button type="submit" variant="primary" loading={pending} disabled={!valid}>
            {labels.digests.create}
          </Button>
          <Button type="button" variant="ghost" onClick={onCancel}>
            Cancel
          </Button>
        </div>
      </form>
    </Card>
  )
}

import { useMemo, useState } from 'react'
import { Link } from 'react-router-dom'
import type { CreateDigestBody, Digest } from '@/api/types'
import type { SearchFilterSpec } from '@/config/searchFilters'
import { Button } from '@/components/ui/Button'
import { Card } from '@/components/ui/Card'
import { Field, Input, Select } from '@/components/ui/Input'
import { Toggle } from '@/components/ui/Toggle'
import {
  digestIntervals,
  digestIntervalValue,
  digestWindows,
  TOP_K_OPTIONS,
} from '@/config/constants'
import { labels } from '@/config/labels'
import { buildFilters, filtersForSources, searchFilters } from '@/config/searchFilters'
import { useChannels, useKnowledgeList, useTasks } from '@/hooks/queries'
import { SearchFilters } from '@/features/search/SearchFilters'

interface Props {
  initial?: Digest
  onSubmit: (body: CreateDigestBody) => void
  onCancel: () => void
  pending: boolean
  submitLabel: string
}

export function DigestForm({ initial, onSubmit, onCancel, pending, submitLabel }: Props) {
  const { data: sources } = useKnowledgeList()
  const { data: tasks } = useTasks()
  const { data: channels } = useChannels()

  const [name, setName] = useState(initial?.name ?? '')
  const [query, setQuery] = useState(initial?.query ?? '')
  const [knowledgeIds, setKnowledgeIds] = useState<string[]>(initial?.knowledgeIds ?? [])
  const [channelIds, setChannelIds] = useState<string[]>(initial?.channelIds ?? [])
  const [window, setWindow] = useState(initial?.window ?? '')
  const [interval, setInterval] = useState(digestIntervalValue(initial?.interval ?? null))
  const [taskId, setTaskId] = useState(initial?.taskId ?? '')
  const [useLlm, setUseLlm] = useState(initial?.useLlm ?? true)
  const [topK, setTopK] = useState(initial?.topK ?? 10)
  const [onePerDocument, setOnePerDocument] = useState((initial?.maxChunksPerEntity ?? 1) === 1)
  const [collapseDuplicates, setCollapseDuplicates] = useState(initial?.collapseDuplicates ?? true)
  const [onlyNew, setOnlyNew] = useState(initial?.onlyNew ?? true)
  const [filterValues, setFilterValues] = useState<Record<string, string>>(() =>
    initialFilterValues(initial),
  )

  const activeSources = (sources ?? []).filter((source) => source.status !== 'DELETED')
  const selectedTypes = useMemo(
    () =>
      activeSources
        .filter((source) => knowledgeIds.length === 0 || knowledgeIds.includes(source.id))
        .map((source) => source.connectorDetails.type),
    [activeSources, knowledgeIds],
  )
  const filterSpecs = filtersForSources(selectedTypes)

  const offerableTasks = (tasks ?? []).filter((task) => task.usableInDigest)
  const chosenTask = offerableTasks.find((task) => task.id === taskId)

  const valid = name.trim() !== '' && query.trim() !== ''

  const toggleSource = (id: string) =>
    setKnowledgeIds((current) =>
      current.includes(id) ? current.filter((s) => s !== id) : [...current, id],
    )

  const toggleChannel = (id: string) =>
    setChannelIds((current) =>
      current.includes(id) ? current.filter((c) => c !== id) : [...current, id],
    )

  const setFilter = (id: string, value: string | null) =>
    setFilterValues((current) => {
      const next = { ...current }
      if (value === null || value === '') delete next[id]
      else next[id] = value
      return next
    })

  const submit = (event: React.FormEvent) => {
    event.preventDefault()
    if (!valid) return
    onSubmit({
      name: name.trim(),
      query: query.trim(),
      knowledgeIds,
      filters: mergedFilters(initial, filterSpecs, filterValues),
      window: window || null,
      interval,
      taskId: taskId || null,
      useLlm,
      topK,
      maxChunksPerEntity: onePerDocument ? 1 : null,
      collapseDuplicates,
      onlyNew,
      channelIds,
    })
  }

  return (
    <Card className="p-5">
      <form onSubmit={submit} className="space-y-5">
        <Field label={labels.digests.name} required htmlFor="digest-name">
          <Input
            id="digest-name"
            value={name}
            onChange={(event) => setName(event.target.value)}
            placeholder={labels.digests.namePlaceholder}
            autoFocus
          />
        </Field>

        <Field label={labels.digests.queryLabel} required htmlFor="digest-query">
          <Input
            id="digest-query"
            value={query}
            onChange={(event) => setQuery(event.target.value)}
            placeholder={labels.digests.queryPlaceholder}
          />
        </Field>

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

        {filterSpecs.length > 0 && (
          <SearchFilters
            specs={filterSpecs}
            values={filterValues}
            onChange={setFilter}
            onClear={() => setFilterValues({})}
          />
        )}

        <Field
          label={labels.digests.taskField}
          hint={chosenTask?.description || labels.digests.taskFieldHint}
          htmlFor="digest-task"
        >
          <div className="flex items-center gap-2">
            <Select
              id="digest-task"
              value={taskId}
              onChange={(event) => setTaskId(event.target.value)}
              disabled={!useLlm}
              className="h-9 flex-1 text-[13px]"
            >
              <option value="">{labels.digests.taskFieldNone}</option>
              {offerableTasks.map((task) => (
                <option key={task.id} value={task.id}>
                  {task.name}
                </option>
              ))}
            </Select>
            <Link
              to="/tasks"
              className="shrink-0 text-[11px] text-[var(--accent)] hover:underline"
            >
              {labels.digests.taskManage}
            </Link>
          </div>
        </Field>

        <Toggle checked={useLlm} onCheckedChange={setUseLlm} label={labels.digests.useLlm} className="-mt-2" />

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
              {digestWindows.map((option) => (
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
              {digestIntervals.map((option) => (
                <option key={option.value} value={option.value}>
                  {option.label}
                </option>
              ))}
            </Select>
          </label>

          <label className="space-y-1">
            <span className="block text-xs font-medium text-[var(--text-muted)]">
              {labels.digests.topK}
            </span>
            <Select
              value={String(topK)}
              onChange={(event) => setTopK(Number(event.target.value))}
              className="h-9 w-auto min-w-24 text-[13px]"
            >
              {TOP_K_OPTIONS.map((option) => (
                <option key={option} value={option}>
                  {option}
                </option>
              ))}
            </Select>
          </label>
        </div>

        {window !== '' && (
          <p className="-mt-3 max-w-prose text-[11px] leading-relaxed text-[var(--text-subtle)]">
            {labels.digests.windowHint}
          </p>
        )}

        <div className="grid gap-3 sm:grid-cols-2">
          <Toggle
            checked={onlyNew}
            onCheckedChange={setOnlyNew}
            label={labels.digests.onlyNew}
            hint={onlyNew ? labels.digests.onlyNewHint : labels.digests.onlyNewOffHint}
          />
          <Toggle
            checked={onePerDocument}
            onCheckedChange={setOnePerDocument}
            label={labels.digests.onePerDocument}
            hint={labels.digests.onePerDocumentHint}
          />
          <Toggle
            checked={collapseDuplicates}
            onCheckedChange={setCollapseDuplicates}
            label={labels.digests.groupDuplicates}
          />
        </div>

        <fieldset className="space-y-1">
          <legend className="text-xs font-medium text-[var(--text-muted)]">
            {labels.digests.sendToLabel}
          </legend>
          <span className="block text-[11px] text-[var(--text-subtle)]">{labels.digests.sendToHint}</span>
          <div className="flex flex-wrap gap-x-4 gap-y-1.5 pt-1">
            {(channels ?? []).map((channel) => (
              <label key={channel.id} className="flex items-center gap-1.5 text-xs">
                <input
                  type="checkbox"
                  checked={channelIds.includes(channel.id)}
                  onChange={() => toggleChannel(channel.id)}
                  className="size-3.5 accent-[var(--accent)]"
                />
                {channel.name}
              </label>
            ))}
            {channels && channels.length === 0 && (
              <span className="text-xs text-[var(--text-subtle)]">
                {labels.digests.sendToNone}{' '}
                <Link to="/channels/new" className="text-[var(--accent)] hover:underline">
                  {labels.digests.sendToAdd}
                </Link>
              </span>
            )}
          </div>
        </fieldset>

        <div className="flex gap-2 pt-1">
          <Button type="submit" variant="primary" loading={pending} disabled={!valid}>
            {submitLabel}
          </Button>
          <Button type="button" variant="ghost" onClick={onCancel}>
            {labels.common.cancel}
          </Button>
        </div>
      </form>
    </Card>
  )
}

/**
 * Only kinds that round-trip losslessly: a `sinceDays` filter is stored as a timestamp, and reading
 * it back as "within N days" would move the window on every save.
 */
function initialFilterValues(digest: Digest | undefined): Record<string, string> {
  if (!digest) return {}
  const out: Record<string, string> = {}
  for (const spec of searchFilters) {
    const stored = digest.filters?.[spec.field]
    if (stored === undefined || stored === null) continue
    if (typeof stored === 'boolean') {
      if (stored) out[spec.id] = '1'
    } else if (typeof stored === 'string' || typeof stored === 'number') {
      out[spec.id] = String(stored)
    } else if (spec.kind === 'min' && typeof stored === 'object' && 'gte' in stored) {
      out[spec.id] = String((stored as { gte: unknown }).gte)
    }
  }
  return out
}

/**
 * Merged over stored filters the form cannot represent, so an edit keeps filters set through the
 * API.
 */
function mergedFilters(
  initial: Digest | undefined,
  specs: SearchFilterSpec[],
  values: Record<string, string>,
): Record<string, unknown> {
  const owned = new Set(specs.map((spec) => spec.field))
  const kept: Record<string, unknown> = {}
  for (const [field, value] of Object.entries(initial?.filters ?? {})) {
    if (!owned.has(field)) kept[field] = value
  }
  return { ...kept, ...buildFilters(specs, values) }
}

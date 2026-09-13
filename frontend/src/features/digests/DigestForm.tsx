import { FileText, X } from 'lucide-react'
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
import { useChannels, useEntity, useKnowledgeList, useTasks } from '@/hooks/queries'
import { SearchFilters } from '@/features/search/SearchFilters'
import { DocumentPicker } from './DocumentPicker'

interface Props {
  /** Present when editing; absent when creating. */
  initial?: Digest
  onSubmit: (body: CreateDigestBody) => void
  onCancel: () => void
  pending: boolean
  submitLabel: string
}

/**
 * Create and edit share this form. They used to differ absolutely: creating offered eight fields and
 * editing did not exist, so the case the whole feature was built for — a CV-scored job digest — could
 * not be set up in the console at all.
 *
 * Filters reuse the search page's own descriptors rather than defining a second set, so a filter added
 * to `searchFilters.ts` appears in both places and neither renders a control this file knows about.
 */
export function DigestForm({ initial, onSubmit, onCancel, pending, submitLabel }: Props) {
  const { data: sources } = useKnowledgeList()
  const { data: tasks } = useTasks()
  const { data: channels } = useChannels()

  const [name, setName] = useState(initial?.name ?? '')
  const [query, setQuery] = useState(initial?.query ?? '')
  const [sourceEntityId, setSourceEntityId] = useState(initial?.sourceEntityId ?? '')
  const [knowledgeIds, setKnowledgeIds] = useState<string[]>(initial?.knowledgeIds ?? [])
  const [channelIds, setChannelIds] = useState<string[]>(initial?.channelIds ?? [])
  // No time limit by default. The window filters on when something was last indexed, so a source
  // that is ingested once and then left alone falls out of a short window and stays out — a new
  // digest defaulted to "Last day" over a settled source was empty on every run, with nothing on
  // screen to say why. `onlyNew` is what keeps a digest from repeating itself.
  const [window, setWindow] = useState(initial?.window ?? '')
  const [interval, setInterval] = useState(digestIntervalValue(initial?.interval ?? null))
  const [taskId, setTaskId] = useState(initial?.taskId ?? '')
  const [topK, setTopK] = useState(initial?.topK ?? 10)
  const [onePerDocument, setOnePerDocument] = useState((initial?.maxChunksPerEntity ?? 1) === 1)
  const [collapseDuplicates, setCollapseDuplicates] = useState(initial?.collapseDuplicates ?? true)
  const [onlyNew, setOnlyNew] = useState(initial?.onlyNew ?? true)
  const [filterValues, setFilterValues] = useState<Record<string, string>>(() =>
    initialFilterValues(initial),
  )
  const [picking, setPicking] = useState(false)

  // Resolves the title of a document chosen in an earlier session, so editing a digest does not show
  // the raw id the picker exists to avoid.
  const { data: pickedEntity } = useEntity(sourceEntityId || null)
  const [pickedTitle, setPickedTitle] = useState<string | null>(null)
  const documentLabel = pickedTitle ?? pickedEntity?.title ?? sourceEntityId

  const activeSources = (sources ?? []).filter((source) => source.status !== 'DELETED')
  const selectedTypes = useMemo(
    () =>
      activeSources
        .filter((source) => knowledgeIds.length === 0 || knowledgeIds.includes(source.id))
        .map((source) => source.connectorDetails.type),
    [activeSources, knowledgeIds],
  )
  const filterSpecs = filtersForSources(selectedTypes)

  // Only tasks a digest may actually be pointed at: the answering machinery would run and produce
  // nothing useful.
  const offerableTasks = (tasks ?? []).filter((task) => task.usableInDigest)
  const chosenTask = offerableTasks.find((task) => task.id === taskId)

  // The backend rejects a digest with neither; checking here keeps that a disabled button rather
  // than a round trip that comes back 400.
  const valid = name.trim() !== '' && (query.trim() !== '' || sourceEntityId.trim() !== '')

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
      query: query.trim() || null,
      sourceEntityId: sourceEntityId.trim() || null,
      // Empty means every source, which is the API's own default. Scoping matters here more than in
      // a one-off search: a digest runs unattended, so an unscoped one quietly starts reporting
      // whatever else happens to be indexed.
      knowledgeIds,
      filters: mergedFilters(initial, filterSpecs, filterValues),
      window: window || null,
      interval,
      taskId: taskId || null,
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

        <Field
          label={labels.digests.queryLabel}
          hint={labels.digests.queryHint}
          htmlFor="digest-query"
        >
          <Input
            id="digest-query"
            value={query}
            onChange={(event) => setQuery(event.target.value)}
            placeholder={labels.digests.queryPlaceholder}
          />
        </Field>

        <Field label={labels.digests.sourceEntity} hint={labels.digests.sourceEntityHint}>
          {sourceEntityId ? (
            <div className="flex items-center gap-2 rounded-lg border border-[var(--border)] bg-[var(--surface-sunken)] px-3 py-2">
              <FileText className="size-3.5 shrink-0 text-[var(--text-subtle)]" aria-hidden />
              <span className="min-w-0 flex-1 truncate text-xs">{documentLabel}</span>
              <Button type="button" variant="ghost" size="sm" onClick={() => setPicking(true)}>
                {labels.digests.changeDocument}
              </Button>
              <Button
                type="button"
                variant="ghost"
                size="iconSm"
                aria-label={labels.digests.clearDocument}
                onClick={() => {
                  setSourceEntityId('')
                  setPickedTitle(null)
                }}
              >
                <X />
              </Button>
            </div>
          ) : (
            <Button type="button" variant="secondary" size="sm" onClick={() => setPicking(true)}>
              <FileText />
              {labels.digests.pickDocument}
            </Button>
          )}
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

        {/* Not behind the technical toggle. What "look back" counts from, and what turning newness
            off actually does, are the two things people get wrong here — and both produce a digest
            that looks broken rather than one that looks misconfigured. */}
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

      <DocumentPicker
        open={picking}
        onOpenChange={setPicking}
        onPick={(id, title) => {
          setSourceEntityId(id)
          setPickedTitle(title)
        }}
      />
    </Card>
  )
}

/**
 * Filter control values back out of a stored digest, so editing shows what is actually set.
 *
 * Only the kinds that round-trip losslessly are read back. A `sinceDays` filter is stored as an
 * absolute timestamp, and turning that back into "within N days" would silently move the window
 * every time the digest was saved.
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
 * What the form set, merged over anything stored that the form cannot represent.
 *
 * Without the merge, editing a digest whose filters were set through the API would quietly drop them:
 * the form would rebuild the map from its own controls alone and send a narrower digest than the one
 * the user opened.
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

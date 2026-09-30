import {
  FileText,
  Info,
  Search as SearchIcon,
  SearchX,
  SlidersHorizontal,
  Sparkles,
  X,
} from 'lucide-react'
import { useEffect, useRef, useState } from 'react'
import { useSearchParams } from 'react-router-dom'
import type { SearchBody, SearchHit, SearchMode } from '@/api/types'
import { Technical, TechnicalInline } from '@/components/TechnicalDetails'
import { Button } from '@/components/ui/Button'
import { Input, Select } from '@/components/ui/Input'
import { EmptyState, ErrorState, SkeletonList } from '@/components/ui/States'
import { SegmentedControl, Toggle } from '@/components/ui/Toggle'
import {
  DEFAULT_SEARCH_MODE,
  DEFAULT_TOP_K,
  MATCHES_PER_RESULT_OPTIONS,
  TOP_K_OPTIONS,
  searchModes,
} from '@/config/constants'
import { labels } from '@/config/labels'
import { buildFilters, filtersFor, searchFilters } from '@/config/searchFilters'
import { DocumentPicker } from '@/features/digests/DocumentPicker'
import { useEntity, useKnowledgeList } from '@/hooks/queries'
import { useCitationJump } from '@/hooks/useCitationJump'
import { displayName, formatSeconds } from '@/lib/utils'
import { AnswerCard } from './AnswerCard'
import { ResultCard } from './ResultCard'
import { SearchFilters } from './SearchFilters'
import { useSearch } from './useSearch'

/**
 * The landing screen. All state lives in the URL, so a search is shareable and the back button
 * works the way a user expects.
 */
export function SearchPage() {
  const [params, setParams] = useSearchParams()
  const { data: sources } = useKnowledgeList()
  const search = useSearch()
  // Ranks only mean anything within one result set, so the highlight is cleared whenever a new
  // search runs.
  const { citedRank, jumpTo, register, clear: clearCitation } = useCitationJump()

  const urlQuery = params.get('q') ?? ''
  const mode = (params.get('mode') as SearchMode | null) ?? DEFAULT_SEARCH_MODE
  const topK = Number(params.get('topK')) || DEFAULT_TOP_K
  // Absent means "the server's default"; 0 is a real choice (every match), so it cannot use `||`.
  const matchesParam = params.get('matches')
  const matches = matchesParam !== null && Number.isInteger(Number(matchesParam)) ? Number(matchesParam) : null
  const scope = params.get('scope') ?? ''
  // An item to search *by* rather than for. An id in the URL like the rest of the state; its title is
  // looked up for display, so the id itself is never shown.
  const doc = params.get('doc') ?? ''
  // Opt-out, not opt-in: the summary is the default reading of a result set, so a bare `?q=` URL
  // produces one and only an explicit `answer=0` suppresses it.
  const wantsAnswer = params.get('answer') !== '0'
  // Opt-out too: the same item reached by two routes (one role on two boards, a forwarded mail) is
  // noise in nearly every search, so only an explicit `group=0` shows both.
  const groupDuplicates = params.get('group') !== '0'

  const activeSources = (sources ?? []).filter((source) => source.status !== 'DELETED')
  // Source-specific filters are offered only once that source is picked. Across everything, a posting
  // facet such as "Remote only" would quietly drop every mail and file from the results, which reads as
  // a broken search rather than a narrowed one — so "All sources" offers only the universal specs.
  const scopedSource = activeSources.find((source) => source.id === scope)
  const filterSpecs = filtersFor(scopedSource?.connectorDetails.type ?? null)
  const filterValues = Object.fromEntries(
    filterSpecs.map((spec) => [spec.id, params.get(spec.id) ?? '']),
  )
  const activeFilterCount = filterSpecs.filter((spec) => filterValues[spec.id]).length
  // Serialised so the effect below re-runs when a filter changes without depending on a fresh
  // object identity every render.
  const filterKey = JSON.stringify(filterValues)

  const [draft, setDraft] = useState(urlQuery)
  useEffect(() => setDraft(urlQuery), [urlQuery])
  const [filtersOpen, setFiltersOpen] = useState(false)
  const [picking, setPicking] = useState(false)
  const { data: document } = useEntity(doc || null)

  const hasSearch = urlQuery.trim() !== '' || doc !== ''

  // Re-run whenever the URL changes, so back/forward replays the search rather than showing a
  // stale result set.
  const runRef = useRef(search.mutate)
  runRef.current = search.mutate
  useEffect(() => {
    if (!urlQuery.trim() && !doc) return
    const filters = buildFilters(filterSpecs, JSON.parse(filterKey) as Record<string, string>)
    const body: SearchBody = {
      query: urlQuery,
      mode,
      topK,
      // A document search has no question to summarise against unless one was typed alongside it.
      answer: wantsAnswer && urlQuery.trim() !== '',
      knowledgeIds: scope ? [scope] : [],
      ...(Object.keys(filters).length > 0 ? { filters } : {}),
      ...(groupDuplicates ? { collapseDuplicates: true } : {}),
      ...(doc ? { sourceEntityId: doc } : {}),
      ...(matches !== null ? { maxChunksPerEntity: matches } : {}),
    }
    clearCitation()
    runRef.current(body)
    // filterSpecs is derived from scope and sources, and filterKey already captures what it changes.
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [urlQuery, doc, mode, topK, matches, wantsAnswer, scope, filterKey, groupDuplicates])

  const update = (next: Record<string, string | null>) => {
    const merged = new URLSearchParams(params)
    for (const [key, value] of Object.entries(next)) {
      if (value === null || value === '') merged.delete(key)
      else merged.set(key, value)
    }
    setParams(merged)
  }

  const submit = (event: React.FormEvent) => {
    event.preventDefault()
    update({ q: draft.trim() || null })
  }

  // "More like this": the result becomes the document searched by, and the typed query is dropped so it
  // does not steer the comparison toward what the previous search was about.
  const findSimilar = (hit: SearchHit) => {
    update({ doc: hit.entityId, q: null })
    window.scrollTo({ top: 0, behavior: 'smooth' })
  }

  const result = search.data

  return (
    <div className="mx-auto max-w-3xl">
      <form onSubmit={submit} className="space-y-4">
        <div className="relative">
          <SearchIcon
            className="pointer-events-none absolute left-3.5 top-1/2 size-4 -translate-y-1/2 text-[var(--text-subtle)]"
            aria-hidden
          />
          <Input
            value={draft}
            onChange={(event) => setDraft(event.target.value)}
            placeholder={doc ? labels.search.placeholderWithDocument : labels.search.placeholder}
            aria-label={labels.search.title}
            className="h-12 pl-10 pr-24 text-[15px]"
            autoFocus
          />
          <Button
            type="submit"
            variant="primary"
            size="sm"
            className="absolute right-2 top-1/2 -translate-y-1/2"
            loading={search.isPending}
          >
            {labels.search.submit}
          </Button>
        </div>

        {doc && (
          <div className="flex items-center gap-2 rounded-lg border border-[var(--border)] bg-[var(--surface-sunken)] px-3 py-2">
            <FileText className="size-3.5 shrink-0 text-[var(--text-subtle)]" aria-hidden />
            <span className="shrink-0 text-xs text-[var(--text-muted)]">{labels.search.similarTo}</span>
            <span className="min-w-0 flex-1 truncate text-xs font-medium text-[var(--text)]">
              {document ? displayName(document) : labels.common.loading}
            </span>
            <Button type="button" variant="ghost" size="sm" onClick={() => setPicking(true)}>
              {labels.search.changeDocument}
            </Button>
            <Button
              type="button"
              variant="ghost"
              size="iconSm"
              aria-label={labels.search.clearDocument}
              title={labels.search.clearDocument}
              onClick={() => update({ doc: null })}
            >
              <X />
            </Button>
          </div>
        )}

        <div className="flex flex-wrap items-center gap-3">
          <SegmentedControl
            value={mode}
            onChange={(value) => update({ mode: value })}
            ariaLabel={labels.search.mode}
            options={searchModes.map((m) => ({ value: m.value, label: m.label, hint: m.hint }))}
          />

          <Select
            value={scope}
            // Changing source drops the previous source's filter values, so none linger in the URL
            // unseen and come back into force when that source is picked again.
            onChange={(event) =>
              update({
                scope: event.target.value || null,
                ...Object.fromEntries(
                  searchFilters.filter((spec) => spec.sourceTypes).map((spec) => [spec.id, null]),
                ),
              })
            }
            aria-label={labels.search.scope}
            className="h-8 w-auto min-w-40 text-[13px]"
          >
            <option value="">{labels.search.scopeAll}</option>
            {activeSources.map((source) => (
              <option key={source.id} value={source.id}>
                {source.name}
              </option>
            ))}
          </Select>

          <Technical>
            <Select
              value={String(topK)}
              onChange={(event) => update({ topK: event.target.value })}
              aria-label={labels.search.topK}
              className="h-8 w-auto text-[13px]"
            >
              {TOP_K_OPTIONS.map((option) => (
                <option key={option} value={option}>
                  Top {option}
                </option>
              ))}
            </Select>
          </Technical>

          <Technical>
            <Select
              value={matches === null ? '' : String(matches)}
              onChange={(event) => update({ matches: event.target.value === '' ? null : event.target.value })}
              aria-label={labels.search.matchesPerResult}
              title={labels.search.matchesPerResult}
              className="h-8 w-auto text-[13px]"
            >
              <option value="">{labels.search.matchesDefault}</option>
              {MATCHES_PER_RESULT_OPTIONS.map((option) => (
                <option key={option} value={option}>
                  {labels.search.matchesOption(option)}
                </option>
              ))}
            </Select>
          </Technical>

          {filterSpecs.length > 0 && (
            <Button
              type="button"
              variant={activeFilterCount > 0 ? 'secondary' : 'ghost'}
              size="sm"
              aria-expanded={filtersOpen}
              onClick={() => setFiltersOpen((open) => !open)}
            >
              <SlidersHorizontal />
              {activeFilterCount > 0 ? labels.search.filtersActive(activeFilterCount) : labels.search.filters}
            </Button>
          )}

          {!doc && (
            <Button type="button" variant="ghost" size="sm" onClick={() => setPicking(true)}>
              <FileText />
              {labels.search.searchByDocument}
            </Button>
          )}

          {/* The two toggles sit together rather than one being pinned right: split across the
              row they wrap onto separate lines at this container width. */}
          <div className="ml-auto flex items-center gap-4">
            <Toggle
              checked={groupDuplicates}
              onCheckedChange={(checked) => update({ group: checked ? null : '0' })}
              label={labels.search.groupDuplicates}
            />
            <Toggle
              checked={wantsAnswer}
              onCheckedChange={(checked) => update({ answer: checked ? null : '0' })}
              label={labels.search.answerToggle}
            />
          </div>
        </div>

        {filtersOpen && filterSpecs.length > 0 && (
          <SearchFilters
            specs={filterSpecs}
            values={filterValues}
            onChange={(id, value) => update({ [id]: value })}
            onClear={() => update(Object.fromEntries(filterSpecs.map((spec) => [spec.id, null])))}
          />
        )}
      </form>

      <DocumentPicker
        open={picking}
        onOpenChange={setPicking}
        status="INDEXED"
        title={labels.search.searchByDocument}
        description={labels.search.searchByDocumentHint}
        onPick={(id) => update({ doc: id })}
      />

      <div className="mt-7">
        {search.isPending ? (
          <SkeletonList rows={4} />
        ) : search.error ? (
          <ErrorState error={search.error} onRetry={() => update({ q: urlQuery })} />
        ) : !hasSearch ? (
          <EmptyState
            icon={Sparkles}
            title={labels.search.idle}
            description={labels.search.idleHint}
          />
        ) : !result || result.hits.length === 0 ? (
          <EmptyState icon={SearchX} title={labels.search.empty} description={labels.search.emptyHint} />
        ) : (
          <div className="space-y-4">
            {result.vectorError && (
              <div
                role="status"
                className="flex gap-2.5 rounded-xl border border-[var(--tone-wait)]/35 bg-[var(--tone-wait-bg)] px-4 py-3"
              >
                <Info className="mt-0.5 size-4 shrink-0 text-[var(--tone-wait)]" aria-hidden />
                <p className="text-xs leading-relaxed text-[var(--text-muted)]">
                  {labels.search.vectorUnavailable}
                  <TechnicalInline> {result.vectorError}</TechnicalInline>
                </p>
              </div>
            )}

            {search.answerUnavailable && (
              <div
                role="status"
                className="flex gap-2.5 rounded-xl border border-[var(--tone-wait)]/35 bg-[var(--tone-wait-bg)] px-4 py-3"
              >
                <Info className="mt-0.5 size-4 shrink-0 text-[var(--tone-wait)]" aria-hidden />
                <p className="text-xs leading-relaxed text-[var(--text-muted)]">
                  {labels.search.answerUnavailable}
                </p>
              </div>
            )}

            {result.answer && (
              <AnswerCard
                answer={result.answer}
                hits={result.hits}
                onCitationClick={jumpTo}
              />
            )}

            <p className="text-xs text-[var(--text-muted)]" aria-live="polite">
              {labels.search.resultCount(result.hits.length, formatSeconds(result.tookMs))}
            </p>

            <div className="space-y-3">
              {result.hits.map((hit, index) => (
                <ResultCard
                  key={hit.chunkId || `${hit.entityId}-${index}`}
                  hit={hit}
                  rank={index + 1}
                  query={urlQuery}
                  topScore={result.hits[0]?.score ?? 1}
                  highlighted={citedRank === index + 1}
                  onFindSimilar={findSimilar}
                  ref={register(index + 1)}
                />
              ))}
            </div>
          </div>
        )}
      </div>
    </div>
  )
}

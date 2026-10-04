import { Info, Search as SearchIcon, SearchX, SlidersHorizontal, Sparkles } from 'lucide-react'
import { useEffect, useState } from 'react'
import { useSearchParams } from 'react-router-dom'
import type { SearchBody, SearchMode } from '@/api/types'
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
import { useKnowledgeList } from '@/hooks/queries'
import { useCitationJump } from '@/hooks/useCitationJump'
import { formatSeconds } from '@/lib/utils'
import { AnswerCard } from './AnswerCard'
import { ResultCard } from './ResultCard'
import { SearchFilters } from './SearchFilters'
import { rememberSearch } from './lastSearch'
import { useSearch } from './useSearch'

export function SearchPage() {
  const [params, setParams] = useSearchParams()
  const { data: sources } = useKnowledgeList()
  const { citedRank, jumpTo, register, clear: clearCitation } = useCitationJump()

  const urlQuery = params.get('q') ?? ''
  const mode = (params.get('mode') as SearchMode | null) ?? DEFAULT_SEARCH_MODE
  const topK = Number(params.get('topK')) || DEFAULT_TOP_K
  // Absent means "the server's default"; 0 is a real choice (every match), so it cannot use `||`.
  const matchesParam = params.get('matches')
  const matches = matchesParam !== null && Number.isInteger(Number(matchesParam)) ? Number(matchesParam) : null
  const scope = params.get('scope') ?? ''
  // Opt-in: every answer is an LLM call.
  const wantsAnswer = params.get('answer') === '1'
  const groupDuplicates = params.get('group') !== '0'

  const activeSources = (sources ?? []).filter((source) => source.status !== 'DELETED')
  const scopedSource = activeSources.find((source) => source.id === scope)
  const filterSpecs = filtersFor(scopedSource?.connectorDetails.type ?? null)
  const filterValues = Object.fromEntries(
    filterSpecs.map((spec) => [spec.id, params.get(spec.id) ?? '']),
  )
  const activeFilterCount = filterSpecs.filter((spec) => filterValues[spec.id]).length

  const [draft, setDraft] = useState(urlQuery)
  useEffect(() => setDraft(urlQuery), [urlQuery])
  const [filtersOpen, setFiltersOpen] = useState(false)

  const hasSearch = urlQuery.trim() !== ''
  // A scoped search waits for the source list, since the source type decides its filters.
  const ready = !scope || sources !== undefined

  const filters = buildFilters(filterSpecs, filterValues)
  const body: SearchBody | null =
    hasSearch && ready
      ? {
          query: urlQuery,
          mode,
          topK,
          answer: wantsAnswer,
          knowledgeIds: scope ? [scope] : [],
          ...(Object.keys(filters).length > 0 ? { filters } : {}),
          ...(groupDuplicates ? { collapseDuplicates: true } : {}),
          ...(matches !== null ? { maxChunksPerEntity: matches } : {}),
        }
      : null
  const search = useSearch(body)

  const bodyKey = JSON.stringify(body)
  // Ranks only mean anything within one result set.
  // eslint-disable-next-line react-hooks/exhaustive-deps
  useEffect(() => clearCitation(), [bodyKey])

  const queryString = params.toString()
  useEffect(() => rememberSearch(hasSearch ? queryString : ''), [hasSearch, queryString])

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
    const next = draft.trim()
    if (next !== '' && next === urlQuery.trim()) {
      void search.refetch()
    } else {
      update({ q: next || null })
    }
  }

  const result = search.data?.result

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
            placeholder={labels.search.placeholder}
            aria-label={labels.search.title}
            className="h-12 pl-10 pr-24 text-[15px]"
            autoFocus
          />
          <Button
            type="submit"
            variant="primary"
            size="sm"
            className="absolute right-2 top-1/2 -translate-y-1/2"
            loading={search.isFetching}
          >
            {labels.search.submit}
          </Button>
        </div>

        <div className="flex flex-wrap items-center gap-3">
          <SegmentedControl
            value={mode}
            onChange={(value) => update({ mode: value })}
            ariaLabel={labels.search.mode}
            options={searchModes.map((m) => ({ value: m.value, label: m.label, hint: m.hint }))}
          />

          <Select
            value={scope}
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

          <div className="ml-auto flex items-center gap-4">
            <Toggle
              checked={groupDuplicates}
              onCheckedChange={(checked) => update({ group: checked ? null : '0' })}
              label={labels.search.groupDuplicates}
            />
            <Toggle
              checked={wantsAnswer}
              onCheckedChange={(checked) => update({ answer: checked ? '1' : null })}
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

      <div className="mt-7">
        {search.isFetching ? (
          <SkeletonList rows={4} />
        ) : search.error ? (
          <ErrorState error={search.error} onRetry={() => void search.refetch()} />
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

            {search.data?.answerUnavailable && (
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

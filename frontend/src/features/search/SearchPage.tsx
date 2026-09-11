import { Info, Search as SearchIcon, SearchX, Sparkles } from 'lucide-react'
import { useEffect, useRef, useState } from 'react'
import { useSearchParams } from 'react-router-dom'
import type { SearchBody, SearchMode } from '@/api/types'
import { Technical } from '@/components/TechnicalDetails'
import { Button } from '@/components/ui/Button'
import { Input, Select } from '@/components/ui/Input'
import { EmptyState, ErrorState, SkeletonList } from '@/components/ui/States'
import { SegmentedControl, Toggle } from '@/components/ui/Toggle'
import {
  DEFAULT_SEARCH_MODE,
  DEFAULT_TOP_K,
  TOP_K_OPTIONS,
  searchModes,
} from '@/config/constants'
import { labels } from '@/config/labels'
import { buildFilters, filtersFor } from '@/config/searchFilters'
import { useKnowledgeList } from '@/hooks/queries'
import { formatSeconds } from '@/lib/utils'
import { AnswerCard } from './AnswerCard'
import { ResultCard } from './ResultCard'
import { SearchFilters } from './SearchFilters'
import { useSearch } from './useSearch'

/** How long a cited result keeps its ring — long enough to notice, short enough not to linger. */
const HIGHLIGHT_MS = 1200

/**
 * The landing screen. All state lives in the URL, so a search is shareable and the back button
 * works the way a user expects.
 */
export function SearchPage() {
  const [params, setParams] = useSearchParams()
  const { data: sources } = useKnowledgeList()
  const search = useSearch()
  const resultRefs = useRef<Record<number, HTMLElement | null>>({})
  // The rank a citation just jumped to. Ranks only mean anything within one result set, so this is
  // cleared whenever a new search runs.
  const [citedRank, setCitedRank] = useState<number | null>(null)
  const citedTimer = useRef<number | null>(null)

  const urlQuery = params.get('q') ?? ''
  const mode = (params.get('mode') as SearchMode | null) ?? DEFAULT_SEARCH_MODE
  const topK = Number(params.get('topK')) || DEFAULT_TOP_K
  const scope = params.get('scope') ?? ''
  // Opt-out, not opt-in: the summary is the default reading of a result set, so a bare `?q=` URL
  // produces one and only an explicit `answer=0` suppresses it.
  const wantsAnswer = params.get('answer') !== '0'
  const groupDuplicates = params.get('group') === '1'

  // Filters are offered per source, so they follow the scope rather than the query. A scope of
  // "everything" has no single source type and therefore offers only the universal specs.
  const scopedSource = (sources ?? []).find((source) => source.id === scope)
  const filterSpecs = filtersFor(scopedSource?.connectorDetails.type ?? null)
  const filterValues = Object.fromEntries(
    filterSpecs.map((spec) => [spec.id, params.get(spec.id) ?? '']),
  )
  // Serialised so the effect below re-runs when a filter changes without depending on a fresh
  // object identity every render.
  const filterKey = JSON.stringify(filterValues)

  const [draft, setDraft] = useState(urlQuery)
  useEffect(() => setDraft(urlQuery), [urlQuery])

  // The highlight is a timer, so it has to be cancelled on unmount — otherwise a navigation during
  // the flash sets state on a component that is gone.
  useEffect(() => () => {
    if (citedTimer.current !== null) window.clearTimeout(citedTimer.current)
  }, [])

  /** Scroll to a cited result, move focus there, and flash it so the jump is visible. */
  const jumpToCitation = (rank: number) => {
    const element = resultRefs.current[rank]
    if (!element) return
    // Focus first: focusing during a smooth scroll cancels it, even with preventScroll, so the page
    // would stop partway to the result.
    element.focus({ preventScroll: true })
    element.scrollIntoView({ behavior: 'smooth', block: 'center' })
    setCitedRank(rank)
    if (citedTimer.current !== null) window.clearTimeout(citedTimer.current)
    citedTimer.current = window.setTimeout(() => setCitedRank(null), HIGHLIGHT_MS)
  }

  // Re-run whenever the URL changes, so back/forward replays the search rather than showing a
  // stale result set.
  const runRef = useRef(search.mutate)
  runRef.current = search.mutate
  useEffect(() => {
    if (!urlQuery.trim()) return
    const filters = buildFilters(filterSpecs, JSON.parse(filterKey) as Record<string, string>)
    const body: SearchBody = {
      query: urlQuery,
      mode,
      topK,
      answer: wantsAnswer,
      knowledgeIds: scope ? [scope] : [],
      ...(Object.keys(filters).length > 0 ? { filters } : {}),
      ...(groupDuplicates ? { collapseDuplicates: true } : {}),
    }
    setCitedRank(null)
    runRef.current(body)
    // filterSpecs is derived from scope, which is already a dependency.
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [urlQuery, mode, topK, wantsAnswer, scope, filterKey, groupDuplicates])

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

  const result = search.data
  const activeSources = (sources ?? []).filter((source) => source.status !== 'DELETED')

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
            loading={search.isPending}
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
            onChange={(event) => update({ scope: event.target.value || null })}
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

          {/* The two toggles sit together rather than one being pinned right: split across the
              row they wrap onto separate lines at this container width. */}
          <div className="ml-auto flex items-center gap-4">
            <Toggle
              checked={groupDuplicates}
              onCheckedChange={(checked) => update({ group: checked ? '1' : null })}
              label={labels.search.groupDuplicates}
            />
            <Toggle
              checked={wantsAnswer}
              onCheckedChange={(checked) => update({ answer: checked ? null : '0' })}
              label={labels.search.answerToggle}
            />
          </div>
        </div>

        <SearchFilters
          specs={filterSpecs}
          values={filterValues}
          onChange={(id, value) => update({ [id]: value })}
          onClear={() => update(Object.fromEntries(filterSpecs.map((spec) => [spec.id, null])))}
        />
      </form>

      <div className="mt-7">
        {search.isPending ? (
          <SkeletonList rows={4} />
        ) : search.error ? (
          <ErrorState error={search.error} onRetry={() => update({ q: urlQuery })} />
        ) : !urlQuery.trim() ? (
          <EmptyState
            icon={Sparkles}
            title={labels.search.idle}
            description={labels.search.idleHint}
          />
        ) : !result || result.hits.length === 0 ? (
          <EmptyState icon={SearchX} title={labels.search.empty} description={labels.search.emptyHint} />
        ) : (
          <div className="space-y-4">
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
                onCitationClick={jumpToCitation}
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
                  ref={(element) => {
                    resultRefs.current[index + 1] = element
                  }}
                />
              ))}
            </div>
          </div>
        )}
      </div>
    </div>
  )
}

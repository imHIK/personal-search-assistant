import { Briefcase, Wrench } from 'lucide-react'
import { useMemo } from 'react'
import { useSearchParams } from 'react-router-dom'
import { toast } from 'sonner'
import { useTechnicalDetailsToggle } from '@/components/TechnicalDetails'
import { ThemeToggle } from '@/components/ThemeToggle'
import { Button } from '@/components/ui/Button'
import { EmptyState, ErrorState, SkeletonList } from '@/components/ui/States'
import { friendlyError } from '@/config/errors'
import {
  buildJobQuery,
  enrichedFilters,
  facetPaths,
  jobDashboard,
  jobKnowledges,
  type JobParams,
} from '@/config/jobDashboard'
import { labels } from '@/config/labels'
import {
  useEntityCustom,
  useEntityFacets,
  useEntityQuery,
  useKnowledgeList,
  useTasks,
} from '@/hooks/queries'
import { JobFilters } from './JobFilters'
import { JobRow } from './JobRow'
import { useMySkills, useNewSince } from './jobPrefs'

/** Standalone: no console chrome and no nav entry; reached at /jobs. */
export function JobsDashboardPage() {
  const [params, setParams] = useSearchParams()
  const values: JobParams = useMemo(() => Object.fromEntries(params.entries()), [params])
  const page = Math.max(0, Number(values.page ?? '0') || 0)

  const { data: knowledgeList, isLoading: sourcesLoading } = useKnowledgeList()
  const { data: tasks } = useTasks()
  const sources = useMemo(() => jobKnowledges(knowledgeList), [knowledgeList])
  const sourceIds = useMemo(() => sources.map((k) => k.id), [sources])
  const enriched = useMemo(() => enrichedFilters(sources, tasks), [sources, tasks])
  const specs = useMemo(() => [...jobDashboard.filters, ...enriched], [enriched])

  // Rounded to the minute: a "within N days" bound built from the raw clock would change the query
  // key on every render and refetch forever.
  const minute = Math.floor(Date.now() / 60_000)
  const body = useMemo(
    () => buildJobQuery(specs, values, sourceIds, page * jobDashboard.pageSize),
    // eslint-disable-next-line react-hooks/exhaustive-deps
    [specs, values, sourceIds, page, minute],
  )
  const result = useEntityQuery(body, sourceIds.length > 0)
  const facets = useEntityFacets([jobDashboard.entityType], sourceIds, facetPaths(specs))
  const mark = useEntityCustom()
  const newSince = useNewSince()
  const mySkills = useMySkills()

  const setValue = (id: string, value: string) => {
    const next = new URLSearchParams(params)
    if (value) next.set(id, value)
    else next.delete(id)
    next.delete('page')
    setParams(next, { replace: true })
  }
  const setPage = (n: number) => {
    const next = new URLSearchParams(params)
    if (n > 0) next.set('page', String(n))
    else next.delete('page')
    setParams(next)
    window.scrollTo({ top: 0 })
  }

  const data = result.data
  const from = data && data.total > 0 ? data.offset + 1 : 0
  const to = data ? data.offset + data.items.length : 0

  return (
    <div className="min-h-screen bg-[var(--bg)] text-[var(--text)]">
      <Header total={data?.total} />

      <main className="mx-auto grid max-w-[1400px] gap-6 px-4 py-6 sm:px-6 lg:grid-cols-[260px_minmax(0,1fr)] lg:px-10">
        <aside className="lg:sticky lg:top-20 lg:max-h-[calc(100vh-6rem)] lg:overflow-y-auto">
          <JobFilters
            specs={specs}
            values={values}
            facets={facets.data}
            sources={sources}
            onChange={setValue}
            onClear={() => setParams(new URLSearchParams(), { replace: true })}
            mySkills={mySkills.raw}
            onMySkillsChange={mySkills.setRaw}
          />
        </aside>

        <section className="min-w-0 space-y-3">
          {sourcesLoading ? (
            <SkeletonList rows={4} />
          ) : sources.length === 0 ? (
            <EmptyState
              icon={Briefcase}
              title={labels.jobs.noSources}
              description={labels.jobs.noSourcesHint}
            />
          ) : result.error ? (
            <ErrorState error={result.error} onRetry={() => void result.refetch()} />
          ) : !data ? (
            <SkeletonList rows={4} />
          ) : data.items.length === 0 ? (
            <EmptyState
              icon={Briefcase}
              title={labels.jobs.empty}
              description={labels.jobs.emptyHint}
            />
          ) : (
            <>
              {data.items.map((item) => (
                <JobRow
                  key={item.id}
                  item={item}
                  enrichedSpecs={enriched}
                  newSince={newSince}
                  mySkills={mySkills.skills}
                  pending={mark.isPending && mark.variables?.id === item.id}
                  onMark={(values) =>
                    mark.mutate(
                      { id: item.id, values },
                      {
                        onError: (cause) =>
                          toast.error(labels.jobs.markFailed, { description: friendlyError(cause).detail }),
                      },
                    )
                  }
                />
              ))}
              <div className="flex items-center justify-between pt-2">
                <p className="text-xs tabular-nums text-[var(--text-muted)]">
                  {labels.items.page(from, to, data.total)}
                </p>
                <div className="flex gap-2">
                  <Button variant="secondary" size="sm" disabled={page === 0} onClick={() => setPage(page - 1)}>
                    {labels.items.previous}
                  </Button>
                  <Button
                    variant="secondary"
                    size="sm"
                    disabled={to >= data.total}
                    onClick={() => setPage(page + 1)}
                  >
                    {labels.items.next}
                  </Button>
                </div>
              </div>
            </>
          )}
        </section>
      </main>
    </div>
  )
}

function Header({ total }: { total: number | undefined }) {
  const { enabled, toggle } = useTechnicalDetailsToggle()
  return (
    <header className="sticky top-0 z-20 flex h-14 items-center justify-between gap-4 border-b border-[var(--border)] bg-[var(--bg)]/85 px-4 backdrop-blur sm:px-6 lg:px-10">
      <div className="flex min-w-0 items-center gap-2.5">
        <Briefcase className="size-4 text-[var(--accent)]" aria-hidden />
        <h1 className="truncate text-sm font-semibold">{labels.jobs.title}</h1>
        {total !== undefined && (
          <span className="text-xs tabular-nums text-[var(--text-muted)]">{labels.jobs.count(total)}</span>
        )}
      </div>
      <div className="flex items-center gap-1.5">
        <Button
          variant={enabled ? 'secondary' : 'ghost'}
          size="sm"
          onClick={toggle}
          aria-pressed={enabled}
          title={labels.nav.technicalDetailsHint}
        >
          <Wrench />
          <span className="hidden md:inline">{labels.nav.technicalDetails}</span>
        </Button>
        <ThemeToggle />
      </div>
    </header>
  )
}

import { Building2, Download, Plus, Search, X } from 'lucide-react'
import { useMemo, useState } from 'react'
import { useSearchParams } from 'react-router-dom'
import { toast } from 'sonner'
import type { CompanyTier, WatchedCompany } from '@/api/types'
import { Button } from '@/components/ui/Button'
import { Input } from '@/components/ui/Input'
import { EmptyState, ErrorState, SkeletonList } from '@/components/ui/States'
import { Toggle } from '@/components/ui/Toggle'
import { friendlyError } from '@/config/errors'
import { jobDashboard } from '@/config/jobDashboard'
import { labels } from '@/config/labels'
import { companyTierOrder, knownTier, presentCompanyTier } from '@/config/presentation'
import { useWatchedCompanies, useWatchedCompanyMutations } from '@/hooks/queries'
import { Chips, Group } from './JobFilters'
import { JobsHeader } from './JobsHeader'
import { useDueDays } from './jobPrefs'
import { WatchedCompanyDialog } from './WatchedCompanyDialog'
import { WatchedCompanyRow } from './WatchedCompanyRow'

const DAY_MS = 86_400_000
const t = labels.unsupported
/** The `?tier=` value for a company with no (known) tier. */
const NO_TIER = 'none'

/** Standalone like the postings tab; reached at /jobs/unsupported. */
export function UnsupportedCompaniesPage() {
  const [params, setParams] = useSearchParams()
  const query = params.get('q') ?? ''
  const platforms = split(params.get('platform'))
  const tiers = split(params.get('tier'))
  const dueOnly = params.get('due') === '1'
  const [dueDays, setTierDays] = useDueDays()
  const [editing, setEditing] = useState<WatchedCompany | 'new' | null>(null)

  const { data, error, refetch } = useWatchedCompanies()
  const mutations = useWatchedCompanyMutations()

  // Rounded to the minute so "due" does not flicker between renders.
  const now = Math.floor(Date.now() / 60_000) * 60_000
  const daysFor = (tier: CompanyTier | null) => dueDays[tier ?? jobDashboard.defaultTier]
  const isDue = (c: WatchedCompany) =>
    !c.lastCheckedAt || now - Date.parse(c.lastCheckedAt) >= daysFor(knownTier(c.tier)) * DAY_MS

  const all = data ?? []
  const dueCount = all.filter(isDue).length
  const platformOptions = useMemo(() => {
    const counts = new Map<string, number>()
    for (const c of data ?? []) {
      if (c.platform) counts.set(c.platform, (counts.get(c.platform) ?? 0) + 1)
    }
    return [...counts.entries()]
      .sort((a, b) => b[1] - a[1] || a[0].localeCompare(b[0]))
      .map(([value, count]) => ({ value, label: value, count }))
  }, [data])
  const tierOptions = [...companyTierOrder, null]
    .map((tier) => ({
      value: tier ?? NO_TIER,
      label: presentCompanyTier(tier).label,
      count: all.filter((c) => knownTier(c.tier) === tier).length,
    }))
    .filter((o) => o.count > 0)

  const needle = query.trim().toLowerCase()
  const shown = all
    .filter((c) => !dueOnly || isDue(c))
    .filter((c) => platforms.length === 0 || (c.platform !== null && platforms.includes(c.platform)))
    .filter((c) => tiers.length === 0 || tiers.includes(knownTier(c.tier) ?? NO_TIER))
    .filter(
      (c) =>
        !needle ||
        [c.name, c.platform, c.reason, c.note].some((v) => v?.toLowerCase().includes(needle)),
    )
    .sort(byLeastRecentlyChecked)
  const sections = [...companyTierOrder, null]
    .map((tier) => ({ tier, companies: shown.filter((c) => knownTier(c.tier) === tier) }))
    .filter((s) => s.companies.length > 0)

  const set = (key: string, value: string) => {
    const next = new URLSearchParams(params)
    if (value) next.set(key, value)
    else next.delete(key)
    setParams(next, { replace: true })
  }
  const filtered = Boolean(needle || platforms.length || tiers.length || dueOnly)

  const importBundled = () =>
    mutations.importBundled.mutate(undefined, {
      onSuccess: (result) => toast.success(t.imported(result.added, result.skipped, result.tiered)),
      onError: (cause) => toast.error(t.importFailed, { description: friendlyError(cause).detail }),
    })

  return (
    <div className="min-h-screen bg-[var(--bg)] text-[var(--text)]">
      <JobsHeader count={data ? `${t.count(all.length)} · ${t.due(dueCount)}` : undefined} />

      <main className="mx-auto grid max-w-[1400px] gap-6 px-4 py-6 sm:px-6 lg:grid-cols-[260px_minmax(0,1fr)] lg:px-10">
        <aside className="space-y-5 lg:sticky lg:top-20 lg:max-h-[calc(100vh-6rem)] lg:overflow-y-auto">
          <div className="flex items-center justify-between">
            <h2 className="text-xs font-semibold uppercase tracking-wide text-[var(--text-subtle)]">
              {labels.jobs.filters}
            </h2>
            {filtered && (
              <Button
                variant="ghost"
                size="sm"
                className="h-7 gap-1 px-2 text-[11px]"
                onClick={() => setParams(new URLSearchParams(), { replace: true })}
              >
                <X className="size-3" aria-hidden />
                {labels.jobs.clear}
              </Button>
            )}
          </div>

          <div className="relative">
            <Search
              className="pointer-events-none absolute left-2.5 top-1/2 size-3.5 -translate-y-1/2 text-[var(--text-subtle)]"
              aria-hidden
            />
            <Input
              className="h-8 pl-8 text-[13px]"
              placeholder={t.search}
              aria-label={t.search}
              value={query}
              onChange={(e) => set('q', e.target.value)}
            />
          </div>

          <Toggle
            checked={dueOnly}
            onCheckedChange={(on) => set('due', on ? '1' : '')}
            label={t.dueOnly}
            hint={t.dueOnlyHint}
          />

          {tierOptions.length > 0 && (
            <Group label={t.tier}>
              <Chips options={tierOptions} selected={tiers} onChange={(next) => set('tier', next.join(','))} />
            </Group>
          )}

          {platformOptions.length > 0 && (
            <Group label={t.platform}>
              <Chips
                options={platformOptions}
                selected={platforms}
                onChange={(next) => set('platform', next.join(','))}
              />
            </Group>
          )}

          <Group label={t.dueDays}>
            {companyTierOrder.map((tier) => (
              <label key={tier} className="flex items-center justify-between gap-3 text-[13px]">
                <span className="text-[var(--text-muted)]">{presentCompanyTier(tier).label}</span>
                <Input
                  type="number"
                  min={1}
                  className="h-8 w-20 text-[13px]"
                  value={dueDays[tier]}
                  onChange={(e) => {
                    const n = Number(e.target.value)
                    if (Number.isInteger(n) && n > 0) setTierDays(tier, n)
                  }}
                />
              </label>
            ))}
            <p className="text-[11px] text-[var(--text-subtle)]">{t.dueDaysHint}</p>
          </Group>
        </aside>

        <section className="min-w-0 space-y-3">
          <div className="flex flex-wrap justify-end gap-2">
            <Button
              variant="secondary"
              size="sm"
              title={t.importHint}
              loading={mutations.importBundled.isPending}
              onClick={importBundled}
            >
              <Download />
              {t.importBundled}
            </Button>
            <Button variant="primary" size="sm" onClick={() => setEditing('new')}>
              <Plus />
              {t.add}
            </Button>
          </div>

          {error ? (
            <ErrorState error={error} onRetry={() => void refetch()} />
          ) : !data ? (
            <SkeletonList rows={6} />
          ) : all.length === 0 ? (
            <EmptyState
              icon={Building2}
              title={t.empty}
              description={t.emptyHint}
              action={
                <Button variant="primary" size="sm" loading={mutations.importBundled.isPending} onClick={importBundled}>
                  <Download />
                  {t.importBundled}
                </Button>
              }
            />
          ) : shown.length === 0 ? (
            <EmptyState icon={Building2} title={t.noMatch} />
          ) : (
            sections.map(({ tier, companies }) => (
              <div key={tier ?? NO_TIER} className="space-y-3 pt-2 first:pt-0">
                <h3 className="flex flex-wrap items-baseline gap-x-2 text-xs font-semibold uppercase tracking-wide text-[var(--text-subtle)]">
                  {presentCompanyTier(tier).label}
                  <span className="font-normal normal-case tracking-normal">
                    {t.count(companies.length)} · {t.due(companies.filter(isDue).length)} · {t.every(daysFor(tier))}
                  </span>
                </h3>
                {companies.map((company) => (
                  <WatchedCompanyRow
                    key={company.id}
                    company={company}
                    due={isDue(company)}
                    dueDays={daysFor(knownTier(company.tier))}
                    checking={mutations.markChecked.isPending && mutations.markChecked.variables === company.id}
                    onMarkChecked={() =>
                      mutations.markChecked.mutate(company.id, {
                        onError: (cause) =>
                          toast.error(t.saveFailed, { description: friendlyError(cause).detail }),
                      })
                    }
                    onEdit={() => setEditing(company)}
                  />
                ))}
              </div>
            ))
          )}
        </section>
      </main>

      <WatchedCompanyDialog
        company={editing === 'new' ? null : editing}
        open={editing !== null}
        onOpenChange={(open) => !open && setEditing(null)}
      />
    </div>
  )
}

/** Never checked first, then the longest since a check; ties by name. */
function byLeastRecentlyChecked(a: WatchedCompany, b: WatchedCompany): number {
  const at = a.lastCheckedAt ? Date.parse(a.lastCheckedAt) : -Infinity
  const bt = b.lastCheckedAt ? Date.parse(b.lastCheckedAt) : -Infinity
  return at === bt ? a.name.localeCompare(b.name) : at - bt
}

function split(value: string | null): string[] {
  return value ? value.split(',').filter(Boolean) : []
}

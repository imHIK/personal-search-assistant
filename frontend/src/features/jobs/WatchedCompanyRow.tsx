import { useMutation } from '@tanstack/react-query'
import { Check, ExternalLink, Pencil, Radar } from 'lucide-react'
import { toast } from 'sonner'
import { jobBoardsApi } from '@/api/jobBoards'
import type { WatchedCompany } from '@/api/types'
import { CompanyIcon } from '@/components/CompanyIcon'
import { TechnicalPanel } from '@/components/TechnicalDetails'
import { Button } from '@/components/ui/Button'
import { Card } from '@/components/ui/Card'
import { friendlyError } from '@/config/errors'
import { labels } from '@/config/labels'
import { absoluteTime, cn, relativeTime } from '@/lib/utils'

const t = labels.unsupported

export function WatchedCompanyRow({
  company,
  due,
  dueDays,
  checking,
  onMarkChecked,
  onEdit,
}: {
  company: WatchedCompany
  due: boolean
  dueDays: number
  checking: boolean
  onMarkChecked: () => void
  onEdit: () => void
}) {
  const lookup = useMutation({ mutationFn: (handle: string) => jobBoardsApi.lookup([handle]) })
  const checked = relativeTime(company.lastCheckedAt)

  const runLookup = (handle: string) =>
    lookup.mutate(handle, {
      onSuccess: ([result]) =>
        result?.found
          ? toast.success(t.lookupFound(result.postings, result.platform ?? ''), {
              description: t.lookupFoundHint,
            })
          : toast(t.lookupMissing),
      onError: (cause) => toast.error(t.lookupFailed, { description: friendlyError(cause).detail }),
    })

  return (
    <Card className={cn('px-5 py-4', due && 'border-l-[3px] border-l-[var(--tone-wait)]')}>
      <div className="flex gap-4">
        <CompanyIcon
          name={company.name}
          domain={company.domain}
          className="size-11 rounded-xl [&_img]:size-7"
        />

        <div className="min-w-0 flex-1 space-y-1.5">
          <div className="flex flex-wrap items-start justify-between gap-3">
            <div className="min-w-0 space-y-0.5">
              <p className="flex flex-wrap items-center gap-x-2 gap-y-1">
                <span className="text-[15px] font-semibold">{company.name}</span>
                {company.platform && (
                  <span className="rounded-full bg-[var(--bg-subtle)] px-2 py-0.5 text-[11px] text-[var(--text-muted)]">
                    {company.platform}
                  </span>
                )}
                {due && (
                  <span
                    className="rounded-full bg-[var(--tone-wait-bg)] px-2 py-0.5 text-[11px] font-semibold text-[var(--tone-wait)]"
                    title={t.dueHint(dueDays)}
                  >
                    {t.dueBadge}
                  </span>
                )}
              </p>
              <p
                className="text-[13px] text-[var(--text-muted)]"
                title={absoluteTime(company.lastCheckedAt) ?? undefined}
              >
                {checked ? t.checked(checked) : t.neverChecked}
              </p>
            </div>

            <div className="flex flex-wrap items-center gap-1.5">
              {company.lookupHandle && (
                <Button
                  variant="ghost"
                  size="sm"
                  title={t.lookupHint}
                  loading={lookup.isPending}
                  onClick={() => runLookup(company.lookupHandle!)}
                >
                  <Radar />
                  {t.lookup}
                </Button>
              )}
              {company.careersUrl ? (
                <Button asChild variant="secondary" size="sm">
                  <a href={company.careersUrl} target="_blank" rel="noopener noreferrer">
                    <ExternalLink />
                    {t.openCareers}
                  </a>
                </Button>
              ) : (
                <span className="px-2 text-[12px] text-[var(--text-subtle)]">{t.noCareersUrl}</span>
              )}
              <Button
                variant={due ? 'primary' : 'secondary'}
                size="sm"
                title={t.markCheckedHint}
                loading={checking}
                onClick={onMarkChecked}
              >
                <Check />
                {t.markChecked}
              </Button>
              <Button variant="ghost" size="iconSm" title={t.edit} aria-label={t.edit} onClick={onEdit}>
                <Pencil />
              </Button>
            </div>
          </div>

          {company.reason && <p className="text-[13px] text-[var(--text-muted)]">{company.reason}</p>}
          {company.note && (
            <p className="whitespace-pre-line border-l-2 border-[var(--border)] pl-2.5 text-[13px] text-[var(--text)]">
              {company.note}
            </p>
          )}

          <TechnicalPanel
            rows={[
              ['id', company.id],
              ['tier', company.tier ?? '—'],
              ...(company.lookupHandle ? [['lookupHandle', company.lookupHandle] as [string, string]] : []),
              ['lastCheckedAt', company.lastCheckedAt ?? '—'],
            ]}
          />
        </div>
      </div>
    </Card>
  )
}

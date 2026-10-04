import { Check, ExternalLink, EyeOff, Eye, MapPin } from 'lucide-react'
import { useState } from 'react'
import type { Blob, EntityRecord } from '@/api/types'
import { CompanyIcon } from '@/components/CompanyIcon'
import { Technical, TechnicalPanel } from '@/components/TechnicalDetails'
import { Button } from '@/components/ui/Button'
import { Card } from '@/components/ui/Card'
import { companyFor } from '@/config/companies'
import { connectorFor } from '@/config/connectors'
import { jobDashboard, statusFor, type EnrichedDisplay, type JobFilterSpec } from '@/config/jobDashboard'
import { labels } from '@/config/labels'
import { resultFacts } from '@/lib/resultFacts'
import { absoluteTime, cn, relativeTime } from '@/lib/utils'
import { skillKey } from './jobPrefs'
import { StatusMenu } from './StatusMenu'

const { status: STATUS, statusAt: STATUS_AT, hidden: HIDDEN } = jobDashboard.marks

const EDGE_TONES: Record<string, string> = {
  ok: 'border-l-[var(--tone-ok)]',
  busy: 'border-l-[var(--tone-busy)]',
  wait: 'border-l-[var(--tone-wait)]',
  alert: 'border-l-[var(--tone-alert)]',
}

// Company, location and posting date each have their own spot on the card; the rest become chips.
const fields = connectorFor(jobDashboard.sourceType)?.resultFields ?? []
const chipFields = fields.filter((f) => !['company', 'location', 'postedAt'].includes(f.key))
const postedField = fields.filter((f) => f.key === 'postedAt')

const DESCRIPTION_MIN_CHARS = 80

export function JobRow({
  item,
  enrichedSpecs,
  newSince,
  mySkills,
  pending,
  onMark,
}: {
  item: EntityRecord
  enrichedSpecs: JobFilterSpec[]
  newSince: number | null
  mySkills: Set<string>
  pending: boolean
  onMark: (values: Blob) => void
}) {
  const href = text(item.metadata.applyUrl) ?? item.uri ?? undefined
  const rawCompany = text(item.metadata.company)
  const company = companyFor(rawCompany)?.label ?? rawCompany
  const location = text(item.metadata.location)
  const posted = resultFacts(postedField, item.metadata)[0]
  const status = statusFor(item.custom[STATUS])
  const hidden = item.custom[HIDDEN] === true
  const added = relativeTime(item.createdAt)
  const isNew = newSince !== null && Date.parse(item.createdAt) > newSince
  const { facts, tags, descriptions } = placeEnriched(item.enriched, enrichedSpecs)
  const chips = [...resultFacts(chipFields, item.metadata), ...facts]
  // A board that files a role under "Remote" would otherwise show it twice, next to the Remote flag.
  const place = location ? shortLocation(location) : null
  const showPlace = place && !chips.some((c) => c.toLowerCase() === place.toLowerCase())

  return (
    <Card
      className={cn(
        'px-5 py-4',
        status && cn('border-l-[3px]', EDGE_TONES[status.tone]),
        hidden && 'opacity-60',
      )}
    >
      <div className="flex gap-4">
        {company && <CompanyIcon name={company} className="size-11 rounded-xl [&_img]:size-7" />}

        <div className="min-w-0 flex-1 space-y-2.5">
          <div className="flex items-start justify-between gap-3">
            <div className="min-w-0 space-y-0.5">
              <a
                href={href}
                target="_blank"
                rel="noopener noreferrer"
                className="group inline-flex max-w-full items-center gap-1.5 text-[15px] font-semibold text-[var(--text)] hover:text-[var(--accent)]"
                title={labels.jobs.open}
              >
                <span className="truncate">{item.title ?? item.externalId}</span>
                <ExternalLink
                  className="size-3.5 shrink-0 text-[var(--text-subtle)] group-hover:text-[var(--accent)]"
                  aria-hidden
                />
              </a>
              <p className="flex flex-wrap items-center gap-x-1.5 text-[13px] text-[var(--text-muted)]">
                {company && <span className="font-medium text-[var(--text)]">{company}</span>}
                {company && posted && <span aria-hidden>·</span>}
                {posted && <span>{posted}</span>}
                {isNew && (
                  <span
                    className="inline-flex items-center gap-1 text-[11px] font-semibold text-[var(--accent)]"
                    title={labels.jobs.newHint}
                  >
                    <span className="size-1.5 rounded-full bg-[var(--accent)]" aria-hidden />
                    {labels.jobs.isNew}
                  </span>
                )}
              </p>
            </div>

            <div className="flex shrink-0 items-center gap-1">
              <StatusMenu
                value={item.custom[STATUS]}
                disabled={pending}
                onChange={(next) => onMark({ [STATUS]: next, [STATUS_AT]: next ? new Date().toISOString() : null })}
              />
              <Button
                variant="ghost"
                size="iconSm"
                disabled={pending}
                title={hidden ? labels.jobs.unhide : labels.jobs.hide}
                aria-label={hidden ? labels.jobs.unhide : labels.jobs.hide}
                onClick={() => onMark({ [HIDDEN]: hidden ? null : true })}
              >
                {hidden ? <Eye /> : <EyeOff />}
              </Button>
            </div>
          </div>

          {(showPlace || chips.length > 0) && (
            <div className="flex flex-wrap gap-1">
              {showPlace && (
                <Pill title={location!}>
                  <MapPin className="size-3 shrink-0 text-[var(--text-subtle)]" aria-hidden />
                  {place}
                </Pill>
              )}
              {chips.map((chip) => (
                <Pill key={chip}>{chip}</Pill>
              ))}
            </div>
          )}

          {descriptions.map((value) => (
            <p key={value} className="line-clamp-2 text-[13px] text-[var(--text-muted)]" title={value}>
              {value}
            </p>
          ))}

          {tags.map(([name, values]) => (
            <Tags key={name} values={values} mySkills={mySkills} />
          ))}

          {added && <p className="text-[11px] text-[var(--text-subtle)]">{labels.jobs.firstSeen(added)}</p>}
        </div>
      </div>

      <Technical className="mt-3">
        <TechnicalPanel
          rows={[
            ['id', item.id],
            ['knowledgeId', item.knowledgeId],
            ['iterableId', item.iterableId ?? '—'],
            ['externalId', item.externalId],
            ['platform', text(item.metadata.platform) ?? '—'],
            ['board', text(item.metadata.board) ?? '—'],
            ['location', location ?? '—'],
            ['createdAt', absoluteTime(item.createdAt)],
            ['enriched', JSON.stringify(item.enriched)],
            ['custom', JSON.stringify(item.custom)],
            ['enrichmentError', item.enrichmentError ?? '—'],
          ]}
        />
      </Technical>
    </Card>
  )
}

function Pill({ title, children }: { title?: string; children: React.ReactNode }) {
  return (
    <span
      title={title}
      className="inline-flex max-w-full items-center gap-1 truncate rounded-full bg-[var(--bg-subtle)] px-2 py-0.5 text-[11px] text-[var(--text-muted)]"
    >
      {children}
    </span>
  )
}

/** Your skills sort first so they survive the collapse; with none set, every tag is accent-tinted. */
function Tags({ values, mySkills }: { values: string[]; mySkills: Set<string> }) {
  const [open, setOpen] = useState(false)
  const matched = (value: string) => mySkills.has(skillKey(value))
  const sorted = mySkills.size > 0 ? [...values].sort((a, b) => Number(matched(b)) - Number(matched(a))) : values
  const limit = jobDashboard.tagLimit
  const shown = open ? sorted : sorted.slice(0, limit)
  const rest = sorted.length - limit

  return (
    <div className="flex flex-wrap gap-1">
      {shown.map((value) => {
        const match = matched(value)
        return (
          <span
            key={value}
            title={match ? labels.jobs.skillMatch : undefined}
            className={cn(
              'inline-flex items-center gap-1 rounded-full px-2 py-0.5 text-[11px] font-medium',
              match
                ? 'bg-[var(--tone-ok-bg)] text-[var(--tone-ok)]'
                : mySkills.size > 0
                  ? 'bg-[var(--bg-subtle)] text-[var(--text-muted)]'
                  : 'bg-[var(--accent-subtle)] text-[var(--accent)]',
            )}
          >
            {match && <Check className="size-2.5" aria-hidden />}
            {value}
          </span>
        )
      })}
      {rest > 0 && (
        <button
          type="button"
          onClick={() => setOpen(!open)}
          aria-expanded={open}
          className="rounded-full border border-[var(--border)] px-2 py-0.5 text-[11px] font-medium text-[var(--text-muted)] hover:text-[var(--text)]"
        >
          {open ? labels.jobs.less : labels.jobs.more(rest)}
        </button>
      )}
    </div>
  )
}

function placeEnriched(enriched: Blob, specs: JobFilterSpec[]) {
  const facts: string[] = []
  const tags: [string, string[]][] = []
  const descriptions: string[] = []
  for (const spec of specs) {
    const value = enriched[spec.label]
    if (value === null || value === undefined || value === '' || value === false) continue
    const display: EnrichedDisplay =
      jobDashboard.enrichedDisplay[spec.label] ??
      (Array.isArray(value)
        ? { as: 'tags' }
        : typeof value === 'string' && value.length >= DESCRIPTION_MIN_CHARS
          ? { as: 'description' }
          : { as: 'fact' })
    switch (display.as) {
      case 'tags': {
        const values = (Array.isArray(value) ? value : [value]).map(String).filter(Boolean)
        if (values.length) tags.push([spec.label, [...new Set(values)]])
        break
      }
      case 'description':
        descriptions.push(String(value))
        break
      case 'fact': {
        const shown = display.format ? display.format(value) : factText(value, spec)
        if (shown) facts.push(shown)
        break
      }
    }
  }
  return { facts, tags, descriptions }
}

function factText(value: unknown, spec: JobFilterSpec): string | null {
  if (Array.isArray(value)) return value.length ? `${spec.label} ${value.join(', ')}` : null
  if (value === true) return spec.label
  return `${spec.label} ${String(value)}`
}

/** "San Jose, California, US; Austin, Texas, US; …" → "San Jose, Austin +n"; the full text is the tooltip. */
function shortLocation(location: string): string {
  const places = [...new Set(location.split(/[;|]/).map((p) => p.split(',')[0].trim()).filter(Boolean))]
  if (places.length <= 2) return places.join(', ')
  return `${places.slice(0, 2).join(', ')} ${labels.jobs.more(places.length - 2)}`
}

function text(value: unknown): string | null {
  return typeof value === 'string' && value !== '' ? value : null
}

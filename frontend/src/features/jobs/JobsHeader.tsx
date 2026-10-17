import { Briefcase, Wrench } from 'lucide-react'
import { NavLink } from 'react-router-dom'
import { useTechnicalDetailsToggle } from '@/components/TechnicalDetails'
import { ThemeToggle } from '@/components/ThemeToggle'
import { Button } from '@/components/ui/Button'
import { labels } from '@/config/labels'
import { cn } from '@/lib/utils'
import { useTabSearch } from './jobPrefs'

const TABS = [
  { to: '/jobs', label: labels.jobs.tabs.postings },
  { to: '/jobs/unsupported', label: labels.jobs.tabs.unsupported },
]

export function JobsHeader({ count }: { count?: string }) {
  const { enabled, toggle } = useTechnicalDetailsToggle()
  const searchFor = useTabSearch()
  return (
    <header className="sticky top-0 z-20 flex h-14 items-center justify-between gap-4 border-b border-[var(--border)] bg-[var(--bg)]/85 px-4 backdrop-blur sm:px-6 lg:px-10">
      <div className="flex min-w-0 items-center gap-2.5">
        <Briefcase className="size-4 shrink-0 text-[var(--accent)]" aria-hidden />
        <h1 className="hidden truncate text-sm font-semibold sm:block">{labels.jobs.title}</h1>
        <nav className="inline-flex items-center gap-0.5 rounded-lg border border-[var(--border)] bg-[var(--bg-subtle)] p-0.5">
          {TABS.map((tab) => (
            <NavLink
              key={tab.to}
              to={{ pathname: tab.to, search: searchFor(tab.to) }}
              end
              className={({ isActive }) =>
                cn(
                  'rounded-[6px] px-3 py-1 text-[13px] font-medium transition-colors',
                  isActive
                    ? 'bg-[var(--surface)] text-[var(--text)] shadow-[var(--shadow-sm)]'
                    : 'text-[var(--text-muted)] hover:text-[var(--text)]',
                )
              }
            >
              {tab.label}
            </NavLink>
          ))}
        </nav>
        {count && <span className="truncate text-xs tabular-nums text-[var(--text-muted)]">{count}</span>}
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

import { Plug, Search, Sparkles, Library, Wrench } from 'lucide-react'
import { NavLink, Outlet } from 'react-router-dom'
import { ThemeToggle } from '@/components/ThemeToggle'
import { useTechnicalDetailsToggle } from '@/components/TechnicalDetails'
import { Button } from '@/components/ui/Button'
import { labels } from '@/config/labels'
import { useHealth } from '@/hooks/queries'
import { cn } from '@/lib/utils'

const navItems = [
  { to: '/', label: labels.nav.search, icon: Search, end: true },
  { to: '/knowledge', label: labels.nav.sources, icon: Library, end: false },
  { to: '/connections', label: labels.nav.accounts, icon: Plug, end: false },
]

export function Layout() {
  return (
    <div className="flex min-h-screen">
      <Sidebar />
      <div className="flex min-w-0 flex-1 flex-col">
        <TopBar />
        <main className="flex-1 px-6 py-7 lg:px-10">
          <div className="mx-auto w-full max-w-[1400px]">
            <Outlet />
          </div>
        </main>
      </div>
    </div>
  )
}

function Sidebar() {
  return (
    <aside className="hidden w-56 shrink-0 border-r border-[var(--border)] bg-[var(--bg-subtle)] sm:flex sm:flex-col">
      <div className="flex items-center gap-2.5 px-5 py-5">
        <div className="rounded-lg bg-[var(--accent)] p-1.5">
          <Sparkles className="size-4 text-[var(--accent-fg)]" aria-hidden />
        </div>
        <span className="text-sm font-semibold tracking-tight">{labels.app.short}</span>
      </div>

      <nav className="flex flex-col gap-0.5 px-3" aria-label="Main">
        {navItems.map(({ to, label, icon: Icon, end }) => (
          <NavLink
            key={to}
            to={to}
            end={end}
            className={({ isActive }) =>
              cn(
                'flex items-center gap-2.5 rounded-lg px-3 py-2 text-sm font-medium transition-colors',
                isActive
                  ? 'bg-[var(--accent-subtle)] text-[var(--accent)]'
                  : 'text-[var(--text-muted)] hover:bg-[var(--surface-hover)] hover:text-[var(--text)]',
              )
            }
          >
            <Icon className="size-4" aria-hidden />
            {label}
          </NavLink>
        ))}
      </nav>
    </aside>
  )
}

function TopBar() {
  const { enabled, toggle } = useTechnicalDetailsToggle()

  return (
    <header className="sticky top-0 z-20 flex h-14 items-center justify-between gap-4 border-b border-[var(--border)] bg-[var(--bg)]/85 px-6 backdrop-blur lg:px-10">
      <MobileNav />
      <div className="ml-auto flex items-center gap-1.5">
        <HealthIndicator />
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

/** The sidebar is hidden below `sm`, so the nav collapses into the top bar there. */
function MobileNav() {
  return (
    <nav className="flex items-center gap-1 sm:hidden" aria-label="Main">
      {navItems.map(({ to, label, icon: Icon, end }) => (
        <NavLink
          key={to}
          to={to}
          end={end}
          className={({ isActive }) =>
            cn(
              'flex items-center gap-1.5 rounded-lg px-2.5 py-1.5 text-[13px] font-medium',
              isActive
                ? 'bg-[var(--accent-subtle)] text-[var(--accent)]'
                : 'text-[var(--text-muted)]',
            )
          }
        >
          <Icon className="size-4" aria-hidden />
          <span className="sr-only sm:not-sr-only">{label}</span>
        </NavLink>
      ))}
    </nav>
  )
}

function HealthIndicator() {
  const { isError, isLoading } = useHealth()

  const state = isLoading
    ? { color: 'var(--tone-neutral)', text: labels.nav.serverChecking }
    : isError
      ? { color: 'var(--tone-alert)', text: labels.nav.serverOffline }
      : { color: 'var(--tone-ok)', text: labels.nav.serverOnline }

  return (
    <span
      className="mr-1 flex items-center gap-1.5 text-xs text-[var(--text-subtle)]"
      title={state.text}
    >
      <span
        className={cn('size-1.5 rounded-full', isLoading && 'animate-pulse-dot')}
        style={{ backgroundColor: state.color }}
      />
      <span className={cn('hidden lg:inline', isError && 'text-[var(--tone-alert)]')}>
        {state.text}
      </span>
    </span>
  )
}

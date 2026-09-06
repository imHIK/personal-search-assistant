import { Check, Search, X } from 'lucide-react'
import { useState } from 'react'
import { jobBoardsApi } from '@/api/jobBoards'
import type { CompanyLookup as Lookup } from '@/api/types'
import { Button } from '@/components/ui/Button'
import { Textarea } from '@/components/ui/Input'
import { ErrorState } from '@/components/ui/States'
import { labels } from '@/config/labels'

interface Props {
  /** Companies already in the form, so a resolved name can be appended rather than replacing them. */
  current: string[]
  onAdd: (companies: string[]) => void
}

/**
 * Check which platform hosts each of a list of companies before committing them.
 *
 * Reach here is a function of how many companies are named, and finding out whether one is reachable
 * otherwise means creating a source and seeing what happens. This answers it for a whole list at once,
 * and adds only the ones that resolved.
 */
export function CompanyLookup({ current, onAdd }: Props) {
  const [draft, setDraft] = useState('')
  const [results, setResults] = useState<Lookup[] | null>(null)
  const [error, setError] = useState<unknown>(null)
  const [pending, setPending] = useState(false)

  const names = draft
    .split(/[\n,]/)
    .map((n) => n.trim())
    .filter(Boolean)

  const check = async () => {
    if (names.length === 0) return
    setPending(true)
    setError(null)
    try {
      setResults(await jobBoardsApi.lookup(names))
    } catch (cause) {
      setError(cause)
      setResults(null)
    } finally {
      setPending(false)
    }
  }

  const found = (results ?? []).filter((r) => r.found)
  const missing = (results ?? []).filter((r) => !r.found)
  // Only names not already listed, so adding twice is harmless.
  const toAdd = found.map((r) => r.company).filter((c) => !current.includes(c))

  return (
    <div className="rounded-xl border border-[var(--border)] bg-[var(--surface-sunken)] p-4">
      <p className="mb-1 text-xs font-medium text-[var(--text-muted)]">
        {labels.jobBoards.lookupTitle}
      </p>
      <p className="mb-2.5 text-[11px] leading-relaxed text-[var(--text-subtle)]">
        {labels.jobBoards.lookupHint}
      </p>

      <Textarea
        value={draft}
        onChange={(event) => setDraft(event.target.value)}
        placeholder={labels.jobBoards.lookupPlaceholder}
        rows={3}
        className="text-[13px]"
      />

      <div className="mt-2.5 flex items-center gap-2">
        <Button
          type="button"
          variant="secondary"
          size="sm"
          onClick={() => void check()}
          loading={pending}
          disabled={names.length === 0}
        >
          <Search className="size-3.5" aria-hidden />
          {pending ? labels.jobBoards.checking : labels.jobBoards.check(names.length)}
        </Button>
        {toAdd.length > 0 && (
          <Button
            type="button"
            variant="primary"
            size="sm"
            onClick={() => {
              onAdd([...current, ...toAdd])
              setDraft('')
              setResults(null)
            }}
          >
            {labels.jobBoards.addFound(toAdd.length)}
          </Button>
        )}
      </div>

      {/* The shared display, not a bare line: a lookup failure needs the same cause-and-fix
          translation as every other one, and --tone-bad never existed so this rendered uncoloured. */}
      {error ? <ErrorState error={error} compact className="mt-2" /> : null}

      {results && (
        <ul className="mt-3 space-y-1">
          {found.map((r) => (
            <li key={r.company} className="flex items-center gap-2 text-xs">
              <Check className="size-3.5 shrink-0 text-[var(--tone-ok)]" aria-hidden />
              <span className="font-medium">{r.company}</span>
              <span className="text-[var(--text-subtle)]">
                {r.platform} · {labels.jobBoards.postings(r.postings)}
              </span>
            </li>
          ))}
          {missing.map((r) => (
            <li key={r.company} className="flex items-center gap-2 text-xs">
              <X className="size-3.5 shrink-0 text-[var(--text-subtle)]" aria-hidden />
              <span className="text-[var(--text-muted)]">{r.company}</span>
              <span className="text-[var(--text-subtle)]">{labels.jobBoards.notFound}</span>
            </li>
          ))}
        </ul>
      )}
    </div>
  )
}

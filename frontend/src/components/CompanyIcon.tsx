import { useState } from 'react'
import { companyFor, companyLogoUrl } from '@/config/companies'
import { cn } from '@/lib/utils'

// Alert is left out: a red badge would read as an error on the row.
const MONOGRAM_TONES = ['ok', 'busy', 'wait', 'neutral'] as const

function monogram(name: string): string {
  const words = name.replace(/[^\p{L}\p{N} ]/gu, ' ').split(/\s+/).filter(Boolean)
  if (words.length === 0) return '?'
  if (words.length === 1) return words[0].slice(0, 2).toUpperCase()
  return (words[0][0] + words[1][0]).toUpperCase()
}

function toneFor(name: string): (typeof MONOGRAM_TONES)[number] {
  let hash = 0
  for (const ch of name) hash = (hash * 31 + ch.charCodeAt(0)) | 0
  return MONOGRAM_TONES[Math.abs(hash) % MONOGRAM_TONES.length]
}

/** The company's logo when its domain is known and the icon loads; a coloured monogram otherwise. */
export function CompanyIcon({ name, className }: { name: string; className?: string }) {
  const domain = companyFor(name)?.domain
  const [failed, setFailed] = useState(false)
  const box = cn(
    'flex size-9 shrink-0 items-center justify-center overflow-hidden rounded-lg border border-[var(--border)]',
    className,
  )

  if (domain && !failed) {
    return (
      <span className={cn(box, 'bg-[var(--surface)]')}>
        <img
          src={companyLogoUrl(domain)}
          alt=""
          loading="lazy"
          referrerPolicy="no-referrer"
          className="size-6 object-contain"
          onError={() => setFailed(true)}
        />
      </span>
    )
  }

  const tone = toneFor(name)
  return (
    <span
      aria-hidden
      className={cn(box, 'text-[11px] font-semibold')}
      style={{ background: `var(--tone-${tone}-bg)`, color: `var(--tone-${tone})` }}
    >
      {monogram(name)}
    </span>
  )
}

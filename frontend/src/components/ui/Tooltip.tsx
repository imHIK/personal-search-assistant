import * as React from 'react'
import { cn } from '@/lib/utils'

export function Tooltip({
  content,
  children,
  className,
}: {
  content: React.ReactNode
  /** Must be focusable for keyboard users to reach the content. */
  children: React.ReactNode
  className?: string
}) {
  return (
    <span className="group/tooltip relative inline-flex">
      {children}
      <span
        role="tooltip"
        className={cn(
          'pointer-events-none invisible absolute right-0 top-full z-30 mt-1.5 w-72 rounded-lg',
          'border border-[var(--border)] bg-[var(--surface)] p-3 text-left opacity-0',
          'shadow-[var(--shadow-lg)] transition-opacity',
          'group-hover/tooltip:visible group-hover/tooltip:opacity-100',
          'group-focus-within/tooltip:visible group-focus-within/tooltip:opacity-100',
          className,
        )}
      >
        {content}
      </span>
    </span>
  )
}

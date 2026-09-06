import * as React from 'react'
import { cn } from '@/lib/utils'

/**
 * Hover/focus popover for detail that would be noise if it were always on screen.
 *
 * CSS-only (`group-hover` / `group-focus-within`) rather than stateful or portalled: every use is a
 * small trigger sitting inside a row, and a portal would buy correct positioning at the price of a
 * positioning library. The one consequence to know about is that an ancestor with `overflow-hidden`
 * clips it — containers that host a tooltip drop that class.
 */
export function Tooltip({
  content,
  children,
  className,
}: {
  /** Rendered inside the popover. Kept as a node so callers can structure it. */
  content: React.ReactNode
  /** The trigger. Must be focusable for keyboard users to reach the content. */
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

import * as RadixDialog from '@radix-ui/react-dialog'
import { X } from 'lucide-react'
import * as React from 'react'
import { Button } from './Button'
import { Input } from './Input'
import { cn } from '@/lib/utils'
import { labels } from '@/config/labels'

export function Dialog({
  open,
  onOpenChange,
  title,
  description,
  children,
  footer,
  className,
}: {
  open: boolean
  onOpenChange: (open: boolean) => void
  title: string
  description?: React.ReactNode
  children?: React.ReactNode
  footer?: React.ReactNode
  className?: string
}) {
  return (
    <RadixDialog.Root open={open} onOpenChange={onOpenChange}>
      <RadixDialog.Portal>
        <RadixDialog.Overlay className="fixed inset-0 z-50 bg-black/40 backdrop-blur-[2px] data-[state=open]:animate-in data-[state=open]:fade-in" />
        <RadixDialog.Content
          className={cn(
            'fixed left-1/2 top-1/2 z-50 w-[calc(100vw-2rem)] max-w-lg -translate-x-1/2 -translate-y-1/2 rounded-xl border border-[var(--border)] bg-[var(--surface)] shadow-[var(--shadow-lg)]',
            className,
          )}
        >
          <div className="flex items-start justify-between gap-4 border-b border-[var(--border)] px-5 py-4">
            <div className="space-y-1">
              <RadixDialog.Title className="text-base font-semibold text-[var(--text)]">
                {title}
              </RadixDialog.Title>
              {description && (
                <RadixDialog.Description className="text-sm leading-relaxed text-[var(--text-muted)]">
                  {description}
                </RadixDialog.Description>
              )}
            </div>
            <RadixDialog.Close asChild>
              <Button variant="ghost" size="iconSm" aria-label={labels.common.close}>
                <X />
              </Button>
            </RadixDialog.Close>
          </div>
          {children && <div className="px-5 py-4">{children}</div>}
          {footer && (
            <div className="flex justify-end gap-2 border-t border-[var(--border)] px-5 py-3.5">
              {footer}
            </div>
          )}
        </RadixDialog.Content>
      </RadixDialog.Portal>
    </RadixDialog.Root>
  )
}

/**
 * Destructive confirmation. When `confirmText` is given the action stays disabled until the user
 * types it — reserved for things that delete indexed data, so it can't happen on a stray click.
 */
export function ConfirmDialog({
  open,
  onOpenChange,
  title,
  description,
  confirmLabel = labels.common.confirm,
  confirmText,
  confirmHint,
  onConfirm,
  loading,
}: {
  open: boolean
  onOpenChange: (open: boolean) => void
  title: string
  description?: React.ReactNode
  confirmLabel?: string
  confirmText?: string
  confirmHint?: string
  onConfirm: () => void
  loading?: boolean
}) {
  const [typed, setTyped] = React.useState('')

  React.useEffect(() => {
    if (!open) setTyped('')
  }, [open])

  const armed = !confirmText || typed.trim() === confirmText.trim()

  return (
    <Dialog
      open={open}
      onOpenChange={onOpenChange}
      title={title}
      description={description}
      footer={
        <>
          <Button variant="ghost" onClick={() => onOpenChange(false)}>
            {labels.common.cancel}
          </Button>
          <Button variant="danger" onClick={onConfirm} disabled={!armed} loading={loading}>
            {confirmLabel}
          </Button>
        </>
      }
    >
      {confirmText && (
        <div className="space-y-1.5">
          <label htmlFor="confirm-text" className="block text-sm text-[var(--text-muted)]">
            {confirmHint}
          </label>
          <Input
            id="confirm-text"
            value={typed}
            onChange={(event) => setTyped(event.target.value)}
            autoComplete="off"
            autoFocus
          />
        </div>
      )}
    </Dialog>
  )
}

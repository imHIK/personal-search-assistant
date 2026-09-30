import { AlertTriangle } from 'lucide-react'
import { Link } from 'react-router-dom'
import { labels } from '@/config/labels'
import { useConnections } from '@/hooks/queries'

export function ReconnectBanner() {
  const { data } = useConnections()
  const broken = (data ?? []).filter((connection) => connection.status === 'ERROR')
  if (broken.length === 0) return null

  const target = broken.length === 1 ? `/connections/${broken[0].id}` : '/connections'

  return (
    <div
      role="status"
      className="flex flex-wrap items-center gap-x-3 gap-y-1.5 border-b border-[var(--border)] bg-[var(--tone-alert-bg)] px-6 py-2.5 text-sm text-[var(--tone-alert)] lg:px-10"
    >
      <AlertTriangle className="size-4 shrink-0" aria-hidden />
      <span className="min-w-0">
        {broken.length === 1
          ? labels.reconnectBanner.one(broken[0].name)
          : labels.reconnectBanner.many(broken.length)}
      </span>
      <Link
        to={target}
        className="ml-auto shrink-0 font-medium underline underline-offset-2 hover:no-underline"
      >
        {labels.reconnectBanner.action}
      </Link>
    </div>
  )
}

import { Plug, Plus, Star, Trash2 } from 'lucide-react'
import { useState } from 'react'
import { Link, useNavigate } from 'react-router-dom'
import { toast } from 'sonner'
import { isDefaultConnection } from '@/api/connections'
import type { Connection } from '@/api/types'
import { Technical, TechnicalPanel } from '@/components/TechnicalDetails'
import { Button } from '@/components/ui/Button'
import { Card } from '@/components/ui/Card'
import { ConfirmDialog } from '@/components/ui/Dialog'
import { PageHeader } from '@/components/ui/PageHeader'
import { StateBadge } from '@/components/ui/StateBadge'
import { EmptyState, ErrorState, SkeletonList } from '@/components/ui/States'
import { connectorFor, connectorsNeedingAccounts } from '@/config/connectors'
import { friendlyError, friendlyLastError } from '@/config/errors'
import { labels } from '@/config/labels'
import { presentConnection } from '@/config/presentation'
import { useConnectionMutations, useConnections } from '@/hooks/queries'
import { absoluteTime, relativeTime } from '@/lib/utils'

export function AccountsPage() {
  const navigate = useNavigate()
  const { data, isLoading, error, refetch } = useConnections()
  const { makeDefault, remove } = useConnectionMutations()
  const [pendingRemoval, setPendingRemoval] = useState<Connection | null>(null)

  const needsAccounts = connectorsNeedingAccounts()

  return (
    <>
      <PageHeader
        title={labels.accounts.title}
        subtitle={labels.accounts.subtitle}
        actions={
          <Button variant="primary" onClick={() => navigate('/connections/new')}>
            <Plus />
            {labels.accounts.add}
          </Button>
        }
      />

      {isLoading ? (
        <SkeletonList rows={2} />
      ) : error ? (
        <ErrorState error={error} onRetry={() => void refetch()} />
      ) : !data || data.length === 0 ? (
        <EmptyState
          icon={Plug}
          title={labels.accounts.empty}
          description={
            needsAccounts.length > 0
              ? `${needsAccounts.map((c) => c.label).join(' and ')} need an account before they can be connected.`
              : labels.accounts.emptyHint
          }
          action={
            <Button variant="primary" onClick={() => navigate('/connections/new')}>
              <Plus />
              {labels.accounts.add}
            </Button>
          }
        />
      ) : (
        <div className="space-y-3">
          {data.map((connection) => (
            <AccountRow
              key={connection.id}
              connection={connection}
              onMakeDefault={() => {
                makeDefault.mutate(connection.id, {
                  onSuccess: () => toast.success(`"${connection.name}" is now the default`),
                  onError: (mutationError) =>
                    toast.error(friendlyError(mutationError).title, {
                      description: friendlyError(mutationError).detail,
                    }),
                })
              }}
              onRemove={() => setPendingRemoval(connection)}
            />
          ))}
        </div>
      )}

      <ConfirmDialog
        open={pendingRemoval !== null}
        onOpenChange={(open) => !open && setPendingRemoval(null)}
        title={pendingRemoval ? labels.accounts.removeTitle(pendingRemoval.name) : ''}
        description={labels.accounts.removeBody}
        confirmLabel={labels.accounts.remove}
        loading={remove.isPending}
        onConfirm={() => {
          if (!pendingRemoval) return
          remove.mutate(pendingRemoval.id, {
            onSuccess: () => {
              toast.success(`Removed "${pendingRemoval.name}"`)
              setPendingRemoval(null)
            },
            onError: (mutationError) => {
              const friendly = friendlyError(mutationError)
              // A 409 here always means a source still binds this account — say that, rather
              // than showing the raw IllegalStateException text.
              toast.error(friendly.title, {
                description:
                  mutationError instanceof Error && mutationError.name === 'ApiError'
                    ? labels.accounts.inUse
                    : friendly.detail,
              })
              setPendingRemoval(null)
            },
          })
        }}
      />
    </>
  )
}

function AccountRow({
  connection,
  onMakeDefault,
  onRemove,
}: {
  connection: Connection
  onMakeDefault: () => void
  onRemove: () => void
}) {
  const descriptor = connectorFor(connection.type)
  const Icon = descriptor.icon
  const isDefault = isDefaultConnection(connection)
  const created = relativeTime(connection.createdAt)

  return (
    <Card className="px-5 py-4">
      <div className="flex flex-wrap items-start justify-between gap-4">
        <div className="flex min-w-0 gap-3.5">
          <div className="mt-0.5 rounded-lg bg-[var(--bg-subtle)] p-2">
            <Icon className="size-4 text-[var(--text-muted)]" aria-hidden />
          </div>
          <div className="min-w-0 space-y-1">
            <div className="flex flex-wrap items-center gap-2">
              <Link
                to={`/connections/${connection.id}`}
                className="truncate text-sm font-medium text-[var(--text)] hover:text-[var(--accent)]"
              >
                {connection.name}
              </Link>
              {isDefault && (
                <span
                  className="inline-flex items-center gap-1 rounded-full bg-[var(--accent-subtle)] px-2 py-0.5 text-[11px] font-medium text-[var(--accent)]"
                  title={labels.accounts.defaultHint(descriptor.label)}
                >
                  <Star className="size-3 fill-current" aria-hidden />
                  {labels.accounts.isDefault}
                </span>
              )}
            </div>
            <p className="text-xs text-[var(--text-muted)]">
              {descriptor.label}
              {created && ` · added ${created}`}
            </p>
            {connection.lastError && (
              <p className="pt-0.5 text-xs leading-relaxed text-[var(--tone-alert)]">
                {friendlyLastError(connection.lastError).detail}
              </p>
            )}
          </div>
        </div>

        <div className="flex shrink-0 items-center gap-2">
          <StateBadge state={presentConnection(connection.status)} size="sm" />
          {!isDefault && (
            <Button variant="ghost" size="sm" onClick={onMakeDefault}>
              {labels.accounts.makeDefault}
            </Button>
          )}
          <Button asChild variant="secondary" size="sm">
            <Link to={`/connections/${connection.id}`}>{labels.accounts.edit}</Link>
          </Button>
          <Button
            variant="dangerGhost"
            size="iconSm"
            onClick={onRemove}
            aria-label={`${labels.accounts.remove} ${connection.name}`}
          >
            <Trash2 />
          </Button>
        </div>
      </div>

      <Technical className="mt-3.5">
        <TechnicalPanel
          rows={[
            ['id', connection.id],
            ['type', connection.type],
            ['status', connection.status],
            ['auth keys', Object.keys(connection.auth ?? {}).join(', ') || '—'],
            ['config keys', Object.keys(connection.config ?? {}).join(', ') || '—'],
            ['createdAt', absoluteTime(connection.createdAt)],
            ['updatedAt', absoluteTime(connection.updatedAt)],
            ['lastError', connection.lastError ?? '—'],
          ]}
        />
      </Technical>
    </Card>
  )
}

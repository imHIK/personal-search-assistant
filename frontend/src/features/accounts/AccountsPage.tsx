import { Plug, Plus, Star, Trash2 } from 'lucide-react'
import { useEffect, useState } from 'react'
import { Link, useLocation, useNavigate } from 'react-router-dom'
import { toast } from 'sonner'
import { isDefaultConnection } from '@/api/connections'
import { readOAuthOutcome } from '@/api/oauth'
import type { Connection } from '@/api/types'
import { Technical, TechnicalPanel } from '@/components/TechnicalDetails'
import { Button } from '@/components/ui/Button'
import { Card } from '@/components/ui/Card'
import { ConfirmDialog } from '@/components/ui/Dialog'
import { PageHeader } from '@/components/ui/PageHeader'
import { StateBadge } from '@/components/ui/StateBadge'
import { EmptyState, ErrorState, SkeletonList } from '@/components/ui/States'
import { accountFor, accountTypes } from '@/config/accounts'
import { friendlyError, friendlyLastError } from '@/config/errors'
import { labels } from '@/config/labels'
import { presentConnection } from '@/config/presentation'
import { useConnectionMutations, useConnections } from '@/hooks/queries'
import { absoluteTime, relativeTime } from '@/lib/utils'

export function AccountsPage() {
  const navigate = useNavigate()
  const location = useLocation()
  const { data, isLoading, error, refetch } = useConnections()
  const { makeDefault, test, remove } = useConnectionMutations()
  const [pendingRemoval, setPendingRemoval] = useState<Connection | null>(null)

  const needsAccounts = accountTypes()

  // This page doubles as the landing spot for an OAuth callback, which arrives as a plain redirect
  // carrying its result in the query string. Report it once, then strip the parameters so a reload
  // or a back-navigation doesn't replay a stale outcome.
  //
  // The timeout is load-bearing, not defensive padding. An OAuth callback is a *fresh page load*, so
  // this effect runs during the first commit — and React runs effects child-first, so it fires before
  // <Toaster/> (a sibling of the router, mounted in main.tsx) has subscribed to sonner's store. A
  // toast emitted in that gap is published to nobody and silently lost, which is exactly the message
  // the user needs most: whether their reconnect actually worked. Deferring by a macrotask puts it
  // after every effect in the commit, Toaster's included.
  useEffect(() => {
    const outcome = readOAuthOutcome(location.search)
    if (!outcome) return
    //
    // Deliberately not cleaned up on unmount: the `navigate` below changes `location.search`, which
    // re-runs this effect — and a cleanup would cancel the very toast the previous run scheduled. A
    // toast is global state in sonner rather than this component's, so letting it land is correct.
    window.setTimeout(() => {
      if (outcome.status === 'ok') {
        toast.success(labels.accounts.connectOk)
      } else {
        toast.error(labels.accounts.connectFailed, { description: outcome.reason })
      }
    }, 0)
    if (outcome.status === 'ok') void refetch()
    navigate('/connections', { replace: true })
  }, [location.search, navigate, refetch])

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
              testing={test.isPending && test.variables === connection.id}
              onTest={() =>
                test.mutate(connection.id, {
                  // The endpoint always resolves; a broken account comes back as ERROR on the body,
                  // so the outcome is read from the result rather than caught.
                  onSuccess: (checked) =>
                    checked.status === 'ERROR'
                      ? toast.error(labels.accounts.testFailed(checked.name), {
                          description: checked.lastError
                            ? friendlyLastError(checked.lastError).detail
                            : undefined,
                        })
                      : toast.success(labels.accounts.testOk(checked.name)),
                  onError: (mutationError) =>
                    toast.error(friendlyError(mutationError).title, {
                      description: friendlyError(mutationError).detail,
                    }),
                })
              }
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
  testing,
  onTest,
  onMakeDefault,
  onRemove,
}: {
  connection: Connection
  testing: boolean
  onTest: () => void
  onMakeDefault: () => void
  onRemove: () => void
}) {
  const descriptor = accountFor(connection.type)
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
          <Button
            variant="ghost"
            size="sm"
            onClick={onTest}
            loading={testing}
            title={labels.accounts.testHint}
          >
            {testing ? labels.accounts.testing : labels.accounts.test}
          </Button>
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

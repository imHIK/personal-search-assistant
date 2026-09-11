import { Copy, Lock, Plus, Sparkles, Trash2 } from 'lucide-react'
import { useState } from 'react'
import { Link, useNavigate } from 'react-router-dom'
import { toast } from 'sonner'
import { tasksApi } from '@/api/tasks'
import type { Task } from '@/api/types'
import { Button } from '@/components/ui/Button'
import { Card } from '@/components/ui/Card'
import { ConfirmDialog } from '@/components/ui/Dialog'
import { PageHeader } from '@/components/ui/PageHeader'
import { EmptyState, ErrorState, SkeletonList } from '@/components/ui/States'
import { friendlyError } from '@/config/errors'
import { labels } from '@/config/labels'
import { useTaskActions, useTasks } from '@/hooks/queries'

/**
 * The task library.
 *
 * Built-in tasks are listed but read-only: two of them are what search itself runs, so an edit would
 * degrade every answer with nothing on screen to say why. Duplicate is the way in — and duplicating
 * the shipped scorer is the most useful starting point there is, because it is a working example.
 */
export function TasksPage() {
  const navigate = useNavigate()
  const { data, isLoading, error, refetch } = useTasks()
  const { remove } = useTaskActions()
  const [pendingRemoval, setPendingRemoval] = useState<Task | null>(null)
  const [duplicating, setDuplicating] = useState<string | null>(null)

  const builtIn = (data ?? []).filter((task) => task.builtIn)
  const mine = (data ?? []).filter((task) => !task.builtIn)

  /**
   * Duplicating returns an unsaved copy, so it opens the editor rather than adding a row. An
   * abandoned duplicate then leaves nothing behind.
   */
  const duplicate = async (id: string) => {
    setDuplicating(id)
    try {
      const copy = await tasksApi.duplicate(id)
      navigate('/tasks/new', { state: { draft: copy } })
    } catch (cause) {
      toast.error(labels.tasks.saveFailed, { description: friendlyError(cause).detail })
    } finally {
      setDuplicating(null)
    }
  }

  return (
    <>
      <PageHeader
        title={labels.tasks.title}
        subtitle={labels.tasks.subtitle}
        actions={
          <Button variant="primary" asChild>
            <Link to="/tasks/new">
              <Plus />
              {labels.tasks.add}
            </Link>
          </Button>
        }
      />

      {isLoading ? (
        <SkeletonList rows={3} />
      ) : error ? (
        <ErrorState error={error} onRetry={() => void refetch()} />
      ) : (
        <div className="space-y-6">
          <section className="space-y-2">
            <h2 className="flex items-center gap-1.5 text-xs font-medium uppercase tracking-wide text-[var(--text-subtle)]">
              <Lock className="size-3" aria-hidden />
              {labels.tasks.builtIn}
            </h2>
            <p className="text-xs text-[var(--text-muted)]">{labels.tasks.builtInHint}</p>
            {builtIn.map((task) => (
              <Card key={task.id} className="flex flex-wrap items-center gap-3 p-4">
                <div className="min-w-0 flex-1">
                  <h3 className="flex items-center gap-2 truncate text-sm font-medium">
                    {task.name}
                    {task.usedBy.length > 0 && (
                      <span className="shrink-0 rounded bg-[var(--tone-busy-bg)] px-1.5 py-0.5 text-[10px] font-medium text-[var(--tone-busy)]">
                        {labels.tasks.usedBySearch}
                      </span>
                    )}
                  </h3>
                  <p className="mt-0.5 text-xs leading-relaxed text-[var(--text-muted)]">
                    {task.description}
                  </p>
                </div>
                <Button
                  variant="secondary"
                  size="sm"
                  loading={duplicating === task.id}
                  onClick={() => void duplicate(task.id)}
                >
                  <Copy />
                  {labels.tasks.duplicate}
                </Button>
              </Card>
            ))}
          </section>

          <section className="space-y-2">
            <h2 className="text-xs font-medium uppercase tracking-wide text-[var(--text-subtle)]">
              {labels.tasks.yours}
            </h2>
            {mine.length === 0 ? (
              <EmptyState
                icon={Sparkles}
                title={labels.tasks.empty}
                description={labels.tasks.emptyHint}
                action={
                  <Button variant="primary" asChild>
                    <Link to="/tasks/new">
                      <Plus />
                      {labels.tasks.add}
                    </Link>
                  </Button>
                }
              />
            ) : (
              mine.map((task) => (
                <Card key={task.id} className="flex flex-wrap items-center gap-3 p-4">
                  <Link to={`/tasks/${task.id}`} className="group min-w-0 flex-1">
                    <h3 className="truncate text-sm font-medium group-hover:text-[var(--accent)]">
                      {task.name}
                    </h3>
                    <p className="mt-0.5 truncate text-xs text-[var(--text-muted)]">
                      {task.description || task.instruction}
                    </p>
                  </Link>
                  <Button variant="ghost" size="sm" asChild>
                    <Link to={`/tasks/${task.id}`}>{labels.tasks.edit}</Link>
                  </Button>
                  <Button
                    variant="ghost"
                    size="sm"
                    loading={duplicating === task.id}
                    onClick={() => void duplicate(task.id)}
                    aria-label={labels.tasks.duplicate}
                  >
                    <Copy />
                  </Button>
                  <Button
                    variant="ghost"
                    size="sm"
                    onClick={() => setPendingRemoval(task)}
                    aria-label={labels.tasks.remove}
                  >
                    <Trash2 />
                  </Button>
                </Card>
              ))
            )}
          </section>
        </div>
      )}

      <ConfirmDialog
        open={pendingRemoval !== null}
        onOpenChange={(open) => !open && setPendingRemoval(null)}
        title={labels.tasks.removeConfirm}
        description={labels.tasks.removeBody}
        confirmLabel={labels.tasks.remove}
        loading={remove.isPending}
        onConfirm={() => {
          if (pendingRemoval) {
            remove.mutate(pendingRemoval.id, {
              // The API refuses while a digest still points at it, and names which — that message is
              // the useful part, so it is shown rather than a generic failure.
              onError: (cause) =>
                toast.error(labels.tasks.removeFailed, {
                  description: friendlyError(cause).detail,
                }),
            })
          }
          setPendingRemoval(null)
        }}
      />
    </>
  )
}

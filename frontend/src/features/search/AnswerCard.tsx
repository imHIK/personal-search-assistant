import { Sparkles } from 'lucide-react'
import type { SearchHit } from '@/api/types'
import { Markdown } from '@/components/Markdown'
import { Card, CardBody } from '@/components/ui/Card'
import { labels } from '@/config/labels'
import { displayName } from '@/lib/utils'

/**
 * The grounded answer.
 *
 * The backend returns a plain string — there is no structured citation object and no HTML. It is
 * prompted to use a fixed Markdown subset (headings, bold, lists, tables) and to cite sources as `[n]`,
 * grouping several into one marker as `[1,3]`. Both are handled by `<Markdown>`: the Markdown so a table
 * of source data renders as a table instead of a wall of pipes, and the markers so each citation becomes
 * a button that scrolls to the result it refers to — the difference between a citation a user can verify
 * and one they have to take on faith.
 *
 * See `lib/answerMarkdown.ts` for why the subset is parsed by hand rather than with a Markdown library.
 */
export function AnswerCard({
  answer,
  hits,
  onCitationClick,
}: {
  answer: string
  hits: SearchHit[]
  onCitationClick: (index: number) => void
}) {
  return (
    <Card className="border-[var(--accent)]/25 bg-[var(--accent-subtle)]/40">
      <CardBody className="space-y-2.5">
        <p className="flex items-center gap-2 text-xs font-medium uppercase tracking-wide text-[var(--accent)]">
          <Sparkles className="size-3.5" aria-hidden />
          {labels.search.answerHeading}
        </p>
        <Markdown
          text={answer}
          className="text-[15px] text-[var(--text)]"
          citation={(index) => {
            const hit = hits[index - 1]
            if (!hit) return null
            return {
              label: `${labels.search.citation(index)}: ${displayName(hit)}`,
              onClick: () => onCitationClick(index),
            }
          }}
        />
      </CardBody>
    </Card>
  )
}

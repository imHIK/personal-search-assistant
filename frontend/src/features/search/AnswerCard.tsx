import { Sparkles } from 'lucide-react'
import type { SearchHit } from '@/api/types'
import { Markdown } from '@/components/Markdown'
import { Card, CardBody } from '@/components/ui/Card'
import { labels } from '@/config/labels'
import { displayName } from '@/lib/utils'

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

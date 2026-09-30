import { Link, useRouteError } from 'react-router-dom'
import { Button } from '@/components/ui/Button'
import { ErrorState } from '@/components/ui/States'
import { labels } from '@/config/labels'

export function RouteError() {
  const error = useRouteError()

  return (
    <div className="mx-auto flex min-h-screen max-w-lg flex-col items-center justify-center gap-5 px-6">
      <ErrorState error={error} className="w-full" />
      <Button asChild variant="secondary">
        <Link to="/">{labels.nav.search}</Link>
      </Button>
    </div>
  )
}

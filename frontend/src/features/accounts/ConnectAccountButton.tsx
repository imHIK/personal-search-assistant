import { ExternalLink } from 'lucide-react'
import { useState } from 'react'
import { toast } from 'sonner'
import { oauthApi } from '@/api/oauth'
import { Button } from '@/components/ui/Button'
import type { AccountDescriptor } from '@/config/accounts'
import { friendlyError } from '@/config/errors'
import { labels } from '@/config/labels'

/**
 * Hands the sign-in to the provider instead of asking the user to fetch a token by hand.
 *
 * Rendered whenever a descriptor carries `oauth` — never on a check against a particular connector —
 * so a new OAuth application appears here by adding that one field to its descriptor.
 *
 * A full-page navigation rather than a popup: the callback redirects the browser back to
 * `/connections`, which is where the result belongs anyway, and a popup would need message-passing
 * to tell the opener anything.
 */
export function ConnectAccountButton({
  descriptor,
  type,
  connectionId,
  name,
  disabled,
}: {
  descriptor: AccountDescriptor
  /** The connection type being connected — a SourceType name or e.g. `GMAIL_SEND`. */
  type: string
  /** Present when re-credentialing an existing account rather than creating one. */
  connectionId?: string
  name?: string
  disabled?: boolean
}) {
  const [starting, setStarting] = useState(false)
  const provider = descriptor.oauth?.provider
  if (!provider) return null

  const service = provider.charAt(0).toUpperCase() + provider.slice(1)

  const start = async () => {
    setStarting(true)
    try {
      const { authorizeUrl } = await oauthApi.start(provider, { type, connectionId, name })
      window.location.assign(authorizeUrl)
    } catch (error) {
      setStarting(false)
      const friendly = friendlyError(error)
      toast.error(friendly.title, { description: friendly.detail })
    }
  }

  return (
    <div className="space-y-2">
      <Button
        type="button"
        variant="primary"
        onClick={() => void start()}
        loading={starting}
        disabled={disabled}
      >
        <ExternalLink />
        {connectionId ? labels.accounts.reconnect(service) : labels.accounts.connect(service)}
      </Button>
      <p className="text-xs leading-relaxed text-[var(--text-muted)]">
        {labels.accounts.connectHint}
      </p>
    </div>
  )
}

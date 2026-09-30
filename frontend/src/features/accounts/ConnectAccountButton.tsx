import { ExternalLink } from 'lucide-react'
import { useState } from 'react'
import { toast } from 'sonner'
import { oauthApi } from '@/api/oauth'
import { Button } from '@/components/ui/Button'
import type { AccountDescriptor } from '@/config/accounts'
import { friendlyError } from '@/config/errors'
import { labels } from '@/config/labels'

export function ConnectAccountButton({
  descriptor,
  type,
  connectionId,
  name,
  disabled,
}: {
  descriptor: AccountDescriptor
  type: string
  /** Present when re-credentialing an existing account. */
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

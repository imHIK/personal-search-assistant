import { toast } from 'sonner'
import type { Channel } from '@/api/types'
import { friendlyError, friendlyLastError } from '@/config/errors'
import { labels } from '@/config/labels'

/**
 * The test endpoint always resolves; a channel that could not send comes back as ERROR on the body,
 * so the outcome is read from the result rather than caught. Shared by the list and the form.
 */
export function notifyTestResult(channel: Channel) {
  if (channel.status === 'ERROR') {
    toast.error(labels.channels.testFailed(channel.name), {
      description: channel.lastError ? friendlyLastError(channel.lastError).detail : undefined,
    })
  } else {
    toast.success(labels.channels.testOk(channel.name))
  }
}

export function notifyMutationError(error: unknown) {
  const friendly = friendlyError(error)
  toast.error(friendly.title, { description: friendly.detail })
}

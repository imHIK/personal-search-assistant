import { toast } from 'sonner'
import type { Channel } from '@/api/types'
import { friendlyError, friendlyLastError } from '@/config/errors'
import { labels } from '@/config/labels'

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

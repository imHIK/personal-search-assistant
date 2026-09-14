import { http } from './http'
import type {
  Channel,
  CreateChannelBody,
  Delivery,
  PatchChannelBody,
  PublishMessage,
} from './types'

export const channelsApi = {
  list: () => http<Channel[]>('/api/channels'),

  get: (id: string) => http<Channel>(`/api/channels/${encodeURIComponent(id)}`),

  /** The channel's publisher validates `target`; a refusal comes back as a 400 with the reason. */
  create: (body: CreateChannelBody) => http<Channel>('/api/channels', { method: 'POST', body }),

  /** A new `target` is re-validated, so this can also 400. */
  patch: (id: string, body: PatchChannelBody) =>
    http<Channel>(`/api/channels/${encodeURIComponent(id)}`, { method: 'PATCH', body }),

  /**
   * Send a sample message now. Always 200 with the refreshed channel — read `status` and
   * `lastError` rather than catching. A failed send is a result to display, not a failure.
   */
  test: (id: string) =>
    http<Channel>(`/api/channels/${encodeURIComponent(id)}/test`, { method: 'POST' }),

  /** 202: queued, not sent. Poll the returned delivery for the outcome. */
  publish: (id: string, message: PublishMessage) =>
    http<Delivery>(`/api/channels/${encodeURIComponent(id)}/publish`, {
      method: 'POST',
      body: message,
    }),

  /** Deletes the channel's deliveries with it. */
  remove: (id: string) =>
    http<null>(`/api/channels/${encodeURIComponent(id)}`, { method: 'DELETE' }),
}

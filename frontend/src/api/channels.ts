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

  create: (body: CreateChannelBody) => http<Channel>('/api/channels', { method: 'POST', body }),

  patch: (id: string, body: PatchChannelBody) =>
    http<Channel>(`/api/channels/${encodeURIComponent(id)}`, { method: 'PATCH', body }),

  /** Always 200: a failed send shows as `status` and `lastError` on the returned channel. */
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

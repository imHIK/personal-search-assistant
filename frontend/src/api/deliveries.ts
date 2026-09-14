import { http, query } from './http'
import type { Delivery, DeliveryStatus } from './types'

export interface DeliveryListParams {
  channelId?: string
  /** What one producer queued — a digest run's id. */
  refId?: string
  status?: DeliveryStatus | null
  /** Capped at 100 server-side. */
  limit?: number
  offset?: number
}

export const deliveriesApi = {
  /** Newest first. */
  list: (params: DeliveryListParams = {}) =>
    http<Delivery[]>(
      '/api/deliveries' +
        query({
          channelId: params.channelId,
          refId: params.refId,
          status: params.status ?? undefined,
          limit: params.limit,
          offset: params.offset,
        }),
    ),

  get: (id: string) => http<Delivery>(`/api/deliveries/${encodeURIComponent(id)}`),

  /** FAILED → PENDING with attempts reset. 409 for any other status. */
  retry: (id: string) =>
    http<Delivery>(`/api/deliveries/${encodeURIComponent(id)}/retry`, { method: 'POST' }),
}

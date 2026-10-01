import { http, query } from './http'
import type { Blob, EntityList, EntityQueryBody, EntityRecord, EntityType, FacetValue } from './types'

export const entitiesApi = {
  query: (body: EntityQueryBody) =>
    http<EntityList>('/api/entities/query', { method: 'POST', body }),

  /** Counted over the type and knowledges only, so picking a value never hides the others. */
  facets: (params: { entityTypes: EntityType[]; knowledgeIds: string[]; fields: string[]; limit?: number }) =>
    http<Record<string, FacetValue[]>>(
      '/api/entities/facets' +
        query({
          entityTypes: params.entityTypes.join(','),
          knowledgeIds: params.knowledgeIds.join(','),
          fields: params.fields.join(','),
          limit: params.limit,
        }),
    ),

  /** Sets each key, or removes it when null; other keys are left alone. */
  mergeCustom: (id: string, values: Blob) =>
    http<EntityRecord>(`/api/entities/${encodeURIComponent(id)}/custom`, {
      method: 'PATCH',
      body: values,
    }),
}

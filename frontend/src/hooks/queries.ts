import { useMutation, useQuery, useQueryClient } from '@tanstack/react-query'
import { useCallback, useEffect, useRef, useState } from 'react'
import { channelsApi } from '@/api/channels'
import { connectionsApi } from '@/api/connections'
import { deliveriesApi } from '@/api/deliveries'
import { digestsApi } from '@/api/digests'
import { healthApi, indexingApi } from '@/api/indexing'
import { knowledgeApi } from '@/api/knowledge'
import { tasksApi } from '@/api/tasks'
import type {
  Connection,
  CreateChannelBody,
  CreateConnectionBody,
  CreateDigestBody,
  CreateKnowledgeBody,
  CursorInfo,
  DeliveryStatus,
  EntityStatus,
  Knowledge,
  PatchChannelBody,
  PatchConnectionBody,
  PatchDigestBody,
  PatchKnowledgeBody,
  TaskBody,
} from '@/api/types'
import {
  HEALTH_INTERVAL_MS,
  POLL_AFTER_MUTATION_MS,
  POLL_INTERVAL_MS,
} from '@/config/constants'

/**
 * Query keys in one place, so a mutation can invalidate precisely what it affected.
 */
export const keys = {
  knowledge: ['knowledge'] as const,
  knowledgeOne: (id: string) => ['knowledge', id] as const,
  entities: (id: string, status: EntityStatus | null, offset: number, limit: number) =>
    ['knowledge', id, 'entities', status ?? 'all', offset, limit] as const,
  cursors: (id: string) => ['knowledge', id, 'cursors'] as const,
  digests: ['digests'] as const,
  digestOne: (id: string) => ['digests', id] as const,
  digestRuns: (id: string, limit: number, offset: number) =>
    ['digests', id, 'runs', limit, offset] as const,
  /** Everything under one digest's runs, for invalidating every page at once. */
  digestRunsAll: (id: string) => ['digests', id, 'runs'] as const,
  tasks: ['tasks'] as const,
  taskOne: (id: string) => ['tasks', id] as const,
  llmProfiles: ['llm-profiles'] as const,
  entity: (id: string) => ['entities', id] as const,
  connections: ['connections'] as const,
  connectionsOfType: (type?: string) => ['connections', type ?? 'all'] as const,
  health: ['health'] as const,
  channels: ['channels'] as const,
  channelOne: (id: string) => ['channels', id] as const,
  deliveries: (channelId: string, status: DeliveryStatus | null, limit: number, offset: number) =>
    ['deliveries', channelId, status ?? 'all', limit, offset] as const,
  deliveriesAll: ['deliveries'] as const,
  runDeliveries: (runId: string) => ['deliveries', 'run', runId] as const,
}

/**
 * The backend does all real work on background pollers and exposes no job status or SSE, so the
 * only way to show progress is to re-read. Polling every 5s forever would be wasteful, so each
 * live query decides for itself whether work is still in flight.
 *
 * `hasWorkInFlight` is deliberately generous: a mutation also arms polling for a short window,
 * because a freshly-triggered sync hasn't produced any observable "in flight" signal yet.
 */
function knowledgeHasWork(knowledge: Knowledge): boolean {
  if (knowledge.status === 'DRAFT') return true
  if (knowledge.status !== 'ACTIVE') return false
  const { entities, indexed, failed } = knowledge.stats
  return entities === 0 || indexed + failed < entities
}

function cursorsHaveWork(cursors: CursorInfo[]): boolean {
  return cursors.some(
    (cursor) =>
      cursor.status === 'IN_PROGRESS' ||
      cursor.status === 'AVAILABLE' ||
      (cursor.direction === 'BACKWARD' && cursor.status !== 'EXHAUSTED' && cursor.status !== 'RETIRED'),
  )
}

/**
 * Arms polling for a fixed window after a mutation, so server-side effects that take a tick or
 * two to appear (a sync arming cursors, an entity being re-claimed) show up without a refresh.
 */
function useRecentlyMutated() {
  const [until, setUntil] = useState(0)
  const arm = useCallback(() => setUntil(Date.now() + POLL_AFTER_MUTATION_MS), [])
  // Re-render once the window closes so the interval actually drops back to false.
  const [, force] = useState(0)
  useEffect(() => {
    if (until <= Date.now()) return
    const timer = setTimeout(() => force((n) => n + 1), until - Date.now() + 50)
    return () => clearTimeout(timer)
  }, [until])
  return { armed: Date.now() < until, arm }
}

// ---- Knowledge ------------------------------------------------------------------------------

export function useKnowledgeList() {
  const { armed, arm } = useRecentlyMutated()
  const query = useQuery({
    queryKey: keys.knowledge,
    queryFn: knowledgeApi.list,
    refetchInterval: (q) => {
      const data = q.state.data
      if (armed) return POLL_INTERVAL_MS
      return data?.some(knowledgeHasWork) ? POLL_INTERVAL_MS : false
    },
  })
  return { ...query, armPolling: arm }
}

export function useKnowledge(id: string | undefined) {
  const { armed, arm } = useRecentlyMutated()
  const query = useQuery({
    queryKey: keys.knowledgeOne(id!),
    queryFn: () => knowledgeApi.get(id!),
    enabled: Boolean(id),
    refetchInterval: (q) => {
      const data = q.state.data
      if (armed) return POLL_INTERVAL_MS
      return data && knowledgeHasWork(data) ? POLL_INTERVAL_MS : false
    },
  })
  return { ...query, armPolling: arm }
}

export function useEntities(
  id: string | undefined,
  status: EntityStatus | null,
  offset: number,
  limit: number,
) {
  return useQuery({
    queryKey: keys.entities(id!, status, offset, limit),
    queryFn: () => knowledgeApi.entities(id!, { status, offset, limit }),
    enabled: Boolean(id),
    placeholderData: (previous) => previous, // keeps the table steady while paging
    refetchInterval: (q) => {
      const data = q.state.data
      if (!data) return false
      const working = data.items.some((item) => item.status !== 'INDEXED' || item.needsReindex)
      return working ? POLL_INTERVAL_MS : false
    },
  })
}

export function useCursors(id: string | undefined, enabled = true) {
  return useQuery({
    queryKey: keys.cursors(id!),
    queryFn: () => knowledgeApi.cursors(id!),
    enabled: Boolean(id) && enabled,
    refetchInterval: (q) => (q.state.data && cursorsHaveWork(q.state.data) ? POLL_INTERVAL_MS : false),
  })
}

/** Every knowledge mutation invalidates the same set, so callers never have to remember. */
function useKnowledgeInvalidation() {
  const client = useQueryClient()
  return useCallback(
    (id?: string) => {
      void client.invalidateQueries({ queryKey: keys.knowledge })
      if (id) {
        void client.invalidateQueries({ queryKey: keys.knowledgeOne(id) })
        void client.invalidateQueries({ queryKey: keys.cursors(id) })
      }
    },
    [client],
  )
}

export function useCreateKnowledge() {
  const invalidate = useKnowledgeInvalidation()
  return useMutation({
    mutationFn: (body: CreateKnowledgeBody) => knowledgeApi.create(body),
    onSuccess: (knowledge) => invalidate(knowledge.id),
  })
}

export function usePatchKnowledge(id: string) {
  const invalidate = useKnowledgeInvalidation()
  return useMutation({
    mutationFn: (body: PatchKnowledgeBody) => knowledgeApi.patch(id, body),
    onSuccess: () => invalidate(id),
  })
}

export function useKnowledgeLifecycle(id: string) {
  const invalidate = useKnowledgeInvalidation()
  const client = useQueryClient()

  const pause = useMutation({
    mutationFn: () => knowledgeApi.pause(id),
    onSuccess: () => invalidate(id),
  })
  const resume = useMutation({
    mutationFn: () => knowledgeApi.resume(id),
    onSuccess: () => invalidate(id),
  })
  const remove = useMutation({
    mutationFn: () => knowledgeApi.remove(id),
    onSuccess: () => {
      client.removeQueries({ queryKey: keys.knowledgeOne(id) })
      invalidate()
    },
  })
  const sync = useMutation({
    mutationFn: () => indexingApi.sync(id),
    onSuccess: () => invalidate(id),
  })

  return { pause, resume, remove, sync }
}

// ---- Entities -------------------------------------------------------------------------------

export function useEntityActions(knowledgeId: string) {
  const client = useQueryClient()
  const invalidateEntities = useCallback(() => {
    void client.invalidateQueries({ queryKey: ['knowledge', knowledgeId, 'entities'] })
    void client.invalidateQueries({ queryKey: keys.knowledgeOne(knowledgeId) })
  }, [client, knowledgeId])

  const reindex = useMutation({
    mutationFn: (entityId: string) => indexingApi.reindexEntity(entityId),
    onSuccess: invalidateEntities,
  })
  const remove = useMutation({
    mutationFn: (entityId: string) => indexingApi.removeEntity(entityId),
    onSuccess: invalidateEntities,
  })

  return { reindex, remove }
}

// ---- Connections ----------------------------------------------------------------------------

export function useConnections(type?: string) {
  return useQuery({
    queryKey: keys.connectionsOfType(type),
    queryFn: () => connectionsApi.list(type),
  })
}

export function useConnection(id: string | undefined) {
  return useQuery({
    queryKey: ['connections', 'one', id!],
    queryFn: () => connectionsApi.get(id!),
    enabled: Boolean(id),
  })
}

function useConnectionInvalidation() {
  const client = useQueryClient()
  return useCallback(() => {
    void client.invalidateQueries({ queryKey: keys.connections })
    // A connection change can flip a knowledge out of ERROR, so re-read those too.
    void client.invalidateQueries({ queryKey: keys.knowledge })
  }, [client])
}

export function useConnectionMutations() {
  const invalidate = useConnectionInvalidation()

  const create = useMutation({
    mutationFn: (body: CreateConnectionBody) => connectionsApi.create(body),
    onSuccess: invalidate,
  })
  const patch = useMutation({
    mutationFn: ({ id, body }: { id: string; body: PatchConnectionBody }) =>
      connectionsApi.patch(id, body),
    onSuccess: invalidate,
  })
  const makeDefault = useMutation({
    mutationFn: (id: string) => connectionsApi.makeDefault(id),
    onSuccess: invalidate,
  })
  const test = useMutation({
    mutationFn: (id: string) => connectionsApi.test(id),
    onSuccess: invalidate,
  })
  const remove = useMutation({
    mutationFn: (id: string) => connectionsApi.remove(id),
    onSuccess: invalidate,
  })

  return { create, patch, makeDefault, test, remove }
}

/** Connections indexed by id, for showing an account name next to a source. */
export function useConnectionsById(): Map<string, Connection> {
  const { data } = useConnections()
  const map = useRef(new Map<string, Connection>())
  map.current = new Map((data ?? []).map((connection) => [connection.id, connection]))
  return map.current
}

// ---- Publishing channels ----------------------------------------------------------------------

export function useChannels() {
  return useQuery({ queryKey: keys.channels, queryFn: channelsApi.list })
}

export function useChannel(id: string | undefined) {
  return useQuery({
    queryKey: keys.channelOne(id!),
    queryFn: () => channelsApi.get(id!),
    enabled: Boolean(id),
  })
}

export function useChannelMutations() {
  const client = useQueryClient()
  const invalidate = useCallback(() => {
    void client.invalidateQueries({ queryKey: keys.channels })
    void client.invalidateQueries({ queryKey: keys.deliveriesAll })
  }, [client])

  const create = useMutation({
    mutationFn: (body: CreateChannelBody) => channelsApi.create(body),
    onSuccess: invalidate,
  })
  const patch = useMutation({
    mutationFn: ({ id, body }: { id: string; body: PatchChannelBody }) => channelsApi.patch(id, body),
    onSuccess: invalidate,
  })
  const test = useMutation({
    mutationFn: (id: string) => channelsApi.test(id),
    onSuccess: invalidate,
  })
  const remove = useMutation({
    mutationFn: (id: string) => channelsApi.remove(id),
    onSuccess: invalidate,
  })
  return { create, patch, test, remove }
}

/**
 * A channel's recent deliveries. Polls while anything is still queued, since the worker sends in the
 * background and there is no push channel to say it has.
 */
export function useDeliveries(channelId: string | undefined, limit = 20) {
  return useQuery({
    queryKey: keys.deliveries(channelId!, null, limit, 0),
    queryFn: () => deliveriesApi.list({ channelId, limit }),
    enabled: Boolean(channelId),
    refetchInterval: (q) =>
      q.state.data?.some((delivery) => delivery.status === 'PENDING') ? POLL_INTERVAL_MS : false,
  })
}

export function useRetryDelivery() {
  const client = useQueryClient()
  return useMutation({
    mutationFn: (id: string) => deliveriesApi.retry(id),
    onSuccess: () => void client.invalidateQueries({ queryKey: keys.deliveriesAll }),
  })
}

/** What a digest run was sent to. Polls while any of it is still queued. */
export function useRunDeliveries(runId: string | undefined) {
  return useQuery({
    queryKey: keys.runDeliveries(runId!),
    queryFn: () => deliveriesApi.list({ refId: runId, limit: 20 }),
    enabled: Boolean(runId),
    refetchInterval: (q) =>
      q.state.data?.some((delivery) => delivery.status === 'PENDING') ? POLL_INTERVAL_MS : false,
  })
}

// ---- Health ---------------------------------------------------------------------------------

export function useHealth() {
  return useQuery({
    queryKey: keys.health,
    queryFn: healthApi.check,
    refetchInterval: HEALTH_INTERVAL_MS,
    retry: false,
    staleTime: 0,
  })
}

// ---- Digests --------------------------------------------------------------------------------

export function useDigests() {
  return useQuery({ queryKey: keys.digests, queryFn: digestsApi.list })
}

export function useDigest(id: string | undefined) {
  return useQuery({
    queryKey: keys.digestOne(id!),
    queryFn: () => digestsApi.get(id!),
    enabled: Boolean(id),
  })
}

export function useDigestRuns(id: string | undefined, limit = 20, offset = 0) {
  return useQuery({
    queryKey: keys.digestRuns(id!, limit, offset),
    queryFn: () => digestsApi.runs(id!, limit, offset),
    enabled: Boolean(id),
    // A page of history stays valid while paging back and forth; only a run makes it stale.
    placeholderData: (previous) => previous,
  })
}

/**
 * The task library. Rarely changes and is read on the digest form as well as its own page, so it is
 * worth keeping around between visits.
 */
export function useTasks() {
  return useQuery({ queryKey: keys.tasks, queryFn: tasksApi.list, staleTime: 60_000 })
}

export function useTask(id: string | undefined) {
  return useQuery({
    queryKey: keys.taskOne(id!),
    queryFn: () => tasksApi.get(id!),
    enabled: Boolean(id),
  })
}

/** Configured models. Fixed for the life of the process, so it never needs refetching. */
export function useLlmProfiles() {
  return useQuery({ queryKey: keys.llmProfiles, queryFn: tasksApi.llmProfiles, staleTime: Infinity })
}

/**
 * One indexed item, for showing a title where only an entity id is held — a digest that searches by
 * a document, say. Failures are not retried: a deleted document is a normal outcome here, and the
 * caller falls back to showing the id.
 */
export function useEntity(id: string | null | undefined) {
  return useQuery({
    queryKey: keys.entity(id!),
    queryFn: () => tasksApi.entity(id!),
    enabled: Boolean(id),
    retry: false,
    staleTime: 300_000,
  })
}

export function useTaskActions() {
  const client = useQueryClient()
  const invalidate = () => void client.invalidateQueries({ queryKey: keys.tasks })

  const create = useMutation({
    mutationFn: (body: TaskBody) => tasksApi.create(body),
    onSuccess: invalidate,
  })
  const update = useMutation({
    mutationFn: ({ id, body }: { id: string; body: TaskBody }) => tasksApi.update(id, body),
    onSuccess: (_data, { id }) => {
      void client.invalidateQueries({ queryKey: keys.taskOne(id) })
      invalidate()
    },
  })
  const remove = useMutation({
    mutationFn: (id: string) => tasksApi.remove(id),
    onSuccess: invalidate,
  })

  return { create, update, remove }
}

/** Create / enable / delete / run-now, each invalidating exactly what it affected. */
export function useDigestActions() {
  const client = useQueryClient()
  const invalidate = () => void client.invalidateQueries({ queryKey: keys.digests })

  const create = useMutation({
    mutationFn: (body: CreateDigestBody) => digestsApi.create(body),
    onSuccess: invalidate,
  })
  const setEnabled = useMutation({
    mutationFn: ({ id, enabled }: { id: string; enabled: boolean }) =>
      digestsApi.setEnabled(id, enabled),
    onSuccess: invalidate,
  })
  const remove = useMutation({
    mutationFn: (id: string) => digestsApi.remove(id),
    onSuccess: (_data, id) => {
      client.removeQueries({ queryKey: keys.digestRunsAll(id) })
      client.removeQueries({ queryKey: keys.digestOne(id) })
      invalidate()
    },
  })
  const run = useMutation({
    mutationFn: (id: string) => digestsApi.run(id),
    onSuccess: (_data, id) => {
      void client.invalidateQueries({ queryKey: keys.digestRunsAll(id) })
      void client.invalidateQueries({ queryKey: keys.digestOne(id) })
      invalidate()
    },
  })
  const update = useMutation({
    mutationFn: ({ id, body }: { id: string; body: PatchDigestBody }) =>
      digestsApi.update(id, body),
    onSuccess: (_data, { id }) => {
      void client.invalidateQueries({ queryKey: keys.digestOne(id) })
      invalidate()
    },
  })
  /** Clears the seen-set only — the runs stay, so the history view is unaffected. */
  const resetHistory = useMutation({
    mutationFn: (id: string) => digestsApi.resetHistory(id),
    onSuccess: (_data, id) => {
      void client.invalidateQueries({ queryKey: keys.digestOne(id) })
      invalidate()
    },
  })

  return { create, setEnabled, remove, run, update, resetHistory }
}

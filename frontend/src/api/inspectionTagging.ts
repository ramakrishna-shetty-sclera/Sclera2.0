import { apiFetch } from './client'

export type TargetType = 'ASSET' | 'LOCATION'

export interface Target {
  id: string
  targetType: TargetType
  targetId: string
  targetName: string
  condition: string | null
  assigneeEmail: string | null
}

export interface TaggedProcedure {
  id: string
  procedureId: string
  procedureName: string
  targets: Target[]
}

export interface OuterCondition {
  id: string
  description: string
  emailAlert: boolean
  createWorkOrder: boolean
}

export interface Tagging {
  configId: string
  taggedProcedures: TaggedProcedure[]
  outerConditions: OuterCondition[]
}

const base = (configId: string) => `/api/v1/inspection-configs/${configId}`

export function getTagging(configId: string): Promise<Tagging> {
  return apiFetch<Tagging>(`${base(configId)}/tagging`).then((r) => r.data)
}

export function tagProcedure(
  configId: string,
  body: { procedureId: string; procedureName: string },
): Promise<TaggedProcedure> {
  return apiFetch<TaggedProcedure>(`${base(configId)}/tagged-procedures`, {
    method: 'POST',
    body,
  }).then((r) => r.data)
}

export function untagProcedure(configId: string, tpId: string): Promise<void> {
  return apiFetch<void>(`${base(configId)}/tagged-procedures/${tpId}`, { method: 'DELETE' }).then(
    () => undefined,
  )
}

export function addTarget(
  configId: string,
  tpId: string,
  body: {
    targetType: TargetType
    targetId: string
    targetName: string
    condition?: string
    assigneeEmail?: string
  },
): Promise<Target> {
  return apiFetch<Target>(`${base(configId)}/tagged-procedures/${tpId}/targets`, {
    method: 'POST',
    body,
  }).then((r) => r.data)
}

export function updateTarget(
  configId: string,
  tpId: string,
  targetId: string,
  body: { condition?: string; assigneeEmail?: string },
): Promise<Target> {
  return apiFetch<Target>(`${base(configId)}/tagged-procedures/${tpId}/targets/${targetId}`, {
    method: 'PUT',
    body,
  }).then((r) => r.data)
}

export function removeTarget(configId: string, tpId: string, targetId: string): Promise<void> {
  return apiFetch<void>(`${base(configId)}/tagged-procedures/${tpId}/targets/${targetId}`, {
    method: 'DELETE',
  }).then(() => undefined)
}

export function addCondition(
  configId: string,
  body: { description: string; emailAlert: boolean; createWorkOrder: boolean },
): Promise<OuterCondition> {
  return apiFetch<OuterCondition>(`${base(configId)}/conditions`, { method: 'POST', body }).then(
    (r) => r.data,
  )
}

export function removeCondition(configId: string, conditionId: string): Promise<void> {
  return apiFetch<void>(`${base(configId)}/conditions/${conditionId}`, { method: 'DELETE' }).then(
    () => undefined,
  )
}

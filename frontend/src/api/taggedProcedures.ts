import { apiFetch } from './client'
import type { Checklist } from './checklists'
import type { TargetType } from './inspectionTagging'

export interface TaggedProcedureLink {
  id: string
  orgId: string
  procedureId: string
  procedureName: string
  targetType: TargetType
  targetId: string
  targetName: string
  createdBy: string
  createdAt: string
}

const BASE = '/api/v1/tagged-procedures'

export function listTaggedProcedures(
  params: { targetType?: TargetType; targetId?: string } = {},
): Promise<TaggedProcedureLink[]> {
  return apiFetch<TaggedProcedureLink[]>(BASE, { query: params }).then((r) => r.data)
}

/** Tag at Procedure level — persistent reusable template. */
export function createTaggedProcedure(body: {
  procedureId: string
  procedureName: string
  targetType: TargetType
  targetId: string
  targetName: string
}): Promise<TaggedProcedureLink> {
  return apiFetch<TaggedProcedureLink>(BASE, { method: 'POST', body }).then((r) => r.data)
}

/** Spawn a fresh checklist from the template (the template stays). */
export function fillTaggedProcedure(
  id: string,
  body: { assigneeEmail?: string; dueDate?: string } = {},
): Promise<Checklist> {
  return apiFetch<Checklist>(`${BASE}/${id}/fill`, { method: 'POST', body }).then((r) => r.data)
}

/** One-time "Add Procedure": creates a single checklist, stores no template. */
export function addOneTimeProcedure(body: {
  procedureId: string
  procedureName: string
  targetType: TargetType
  targetId: string
  targetName: string
  assigneeEmail?: string
  dueDate?: string
}): Promise<Checklist> {
  return apiFetch<Checklist>(`${BASE}/one-time`, { method: 'POST', body }).then((r) => r.data)
}

export function deleteTaggedProcedure(id: string): Promise<void> {
  return apiFetch<void>(`${BASE}/${id}`, { method: 'DELETE' }).then(() => undefined)
}

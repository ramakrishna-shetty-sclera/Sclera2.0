import { apiFetch } from './client'
import type { TargetType } from './inspectionTagging'

export type ChecklistStatus = 'TODO' | 'COMPLETE' | 'FAILED' | 'EXCEPTION' | 'INCOMPLETE'

export interface ChecklistAnswer {
  questionId: string
  value: string | null
  failed: boolean
  comment: string | null
}

export interface ChecklistHistory {
  at: string
  action: string
  detail: string | null
}

export interface Checklist {
  id: string
  orgId: string
  configId: string
  configName: string
  taggedProcedureId: string
  procedureId: string
  procedureName: string
  targetType: TargetType | null
  targetId: string | null
  targetName: string | null
  assigneeEmail: string
  status: ChecklistStatus
  dueDate: string | null
  checkInRequired: boolean
  checkInAt: string | null
  checkOutAt: string | null
  exceptionReason: string | null
  answers: ChecklistAnswer[]
  history: ChecklistHistory[]
  workOrderIds: string[]
  createdAt: string
  updatedAt: string
}

export interface AnswerInput {
  questionId: string
  value?: string
  failed: boolean
  comment?: string
}

const BASE = '/api/v1/checklists'

export function generateChecklists(configId: string, dueDate?: string): Promise<Checklist[]> {
  return apiFetch<Checklist[]>(`${BASE}/generate`, {
    method: 'POST',
    body: { configId, dueDate },
  }).then((r) => r.data)
}

export function listChecklists(
  params: { configId?: string; status?: ChecklistStatus } = {},
): Promise<Checklist[]> {
  return apiFetch<Checklist[]>(BASE, { query: params }).then((r) => r.data)
}

export function getChecklist(id: string): Promise<Checklist> {
  return apiFetch<Checklist>(`${BASE}/${id}`).then((r) => r.data)
}

export function checkInChecklist(id: string): Promise<Checklist> {
  return apiFetch<Checklist>(`${BASE}/${id}/check-in`, { method: 'POST' }).then((r) => r.data)
}

export function saveChecklist(id: string, answers: AnswerInput[], checkout: boolean): Promise<Checklist> {
  return apiFetch<Checklist>(`${BASE}/${id}/answers`, {
    method: 'PUT',
    body: { answers, checkout },
  }).then((r) => r.data)
}

export function submitChecklist(id: string, answers: AnswerInput[]): Promise<Checklist> {
  return apiFetch<Checklist>(`${BASE}/${id}/submit`, {
    method: 'POST',
    body: { answers, checkout: true },
  }).then((r) => r.data)
}

export function exceptionChecklist(id: string, reason: string): Promise<Checklist> {
  return apiFetch<Checklist>(`${BASE}/${id}/exception`, {
    method: 'POST',
    body: { reason },
  }).then((r) => r.data)
}

export function reopenChecklist(id: string): Promise<Checklist> {
  return apiFetch<Checklist>(`${BASE}/${id}/reopen`, { method: 'POST' }).then((r) => r.data)
}

export function markIncompleteChecklist(id: string): Promise<Checklist> {
  return apiFetch<Checklist>(`${BASE}/${id}/mark-incomplete`, { method: 'POST' }).then((r) => r.data)
}

export function updateChecklistAssignee(id: string, assigneeEmail: string): Promise<Checklist> {
  return apiFetch<Checklist>(`${BASE}/${id}/assignee`, {
    method: 'PUT',
    body: { assigneeEmail },
  }).then((r) => r.data)
}

export function createChecklistWorkOrder(id: string, note?: string): Promise<Checklist> {
  return apiFetch<Checklist>(`${BASE}/${id}/work-order`, {
    method: 'POST',
    body: { note },
  }).then((r) => r.data)
}

export function deleteChecklist(id: string): Promise<void> {
  return apiFetch<void>(`${BASE}/${id}`, { method: 'DELETE' }).then(() => undefined)
}

export const CHECKLIST_STATUS_CLASS: Record<ChecklistStatus, string> = {
  TODO: 'badge-gray',
  COMPLETE: 'badge-green',
  FAILED: 'badge-red',
  EXCEPTION: 'badge-amber',
  INCOMPLETE: 'badge-blue',
}

export function checklistStatusLabel(s: ChecklistStatus): string {
  return s === 'TODO' ? 'To-Do' : s.charAt(0) + s.slice(1).toLowerCase()
}

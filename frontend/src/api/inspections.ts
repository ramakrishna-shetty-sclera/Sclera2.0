import { apiFetch, type ApiResult } from './client'
import type { AnswerSubmission, CreateInspectionRequest, Inspection, InspectionStatus } from './types'

const BASE = '/api/v1/inspections'

export function listInspections(params: {
  status?: InspectionStatus
  templateId?: string
  page?: number
  size?: number
}): Promise<ApiResult<Inspection[]>> {
  return apiFetch<Inspection[]>(BASE, { query: params })
}

export function getInspection(id: string): Promise<Inspection> {
  return apiFetch<Inspection>(`${BASE}/${id}`).then((r) => r.data)
}

export function createInspection(body: CreateInspectionRequest): Promise<Inspection> {
  return apiFetch<Inspection>(BASE, { method: 'POST', body }).then((r) => r.data)
}

export function startInspection(id: string): Promise<Inspection> {
  return apiFetch<Inspection>(`${BASE}/${id}/start`, { method: 'POST' }).then((r) => r.data)
}

export function submitAnswers(id: string, answers: AnswerSubmission[]): Promise<Inspection> {
  return apiFetch<Inspection>(`${BASE}/${id}/answers`, { method: 'PUT', body: { answers } }).then(
    (r) => r.data,
  )
}

export function completeInspection(id: string): Promise<Inspection> {
  return apiFetch<Inspection>(`${BASE}/${id}/complete`, { method: 'POST' }).then((r) => r.data)
}

export function cancelInspection(id: string): Promise<Inspection> {
  return apiFetch<Inspection>(`${BASE}/${id}/cancel`, { method: 'POST' }).then((r) => r.data)
}

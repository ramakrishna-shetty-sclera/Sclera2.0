import { apiFetch, type ApiResult } from './client'
import type { QuestionTemplate, TemplateRequest, TemplateStatus } from './types'

const BASE = '/api/v1/question-templates'

export function listTemplates(params: {
  status?: TemplateStatus
  page?: number
  size?: number
}): Promise<ApiResult<QuestionTemplate[]>> {
  return apiFetch<QuestionTemplate[]>(BASE, { query: params })
}

export function getTemplate(id: string): Promise<QuestionTemplate> {
  return apiFetch<QuestionTemplate>(`${BASE}/${id}`).then((r) => r.data)
}

export function createTemplate(body: TemplateRequest): Promise<QuestionTemplate> {
  return apiFetch<QuestionTemplate>(BASE, { method: 'POST', body }).then((r) => r.data)
}

export function updateTemplate(id: string, body: TemplateRequest): Promise<QuestionTemplate> {
  return apiFetch<QuestionTemplate>(`${BASE}/${id}`, { method: 'PUT', body }).then((r) => r.data)
}

export function publishTemplate(id: string): Promise<QuestionTemplate> {
  return apiFetch<QuestionTemplate>(`${BASE}/${id}/publish`, { method: 'POST' }).then((r) => r.data)
}

export function archiveTemplate(id: string): Promise<QuestionTemplate> {
  return apiFetch<QuestionTemplate>(`${BASE}/${id}`, { method: 'DELETE' }).then((r) => r.data)
}

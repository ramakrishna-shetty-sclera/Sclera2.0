import { apiFetch, type ApiResult } from './client'
import type { QuestionTemplate, TemplateRequest, TemplateStatus } from './types'
import type {
  CloneProcedureRequest,
  CreateProcedureRequest,
  NewDraftRequest,
  ProcedureDiff,
  ProcedureStatus,
  ProcedureTemplate,
  PublishResult,
  SaveDraftRequest,
  TemplateVersion,
  TemplateVersionSummary,
  UpdateProcedureRequest,
} from './types'

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

// ── Procedures: the versioned model ──────────────────────────────────────────
// Everything above this line talks to /api/v1/question-templates, which no
// longer exists. It is kept only until its last caller has moved, then deleted.
//
// The gateway must route /api/v1/procedure-templates/** to the procedure
// service (see the route table in the root README), or these 404 before
// reaching it.

const PROCEDURES = '/api/v1/procedure-templates'

/** Identity only; the questions are on the versions. `status` filters ACTIVE vs ARCHIVED. */
export function listProcedures(params: {
  status?: ProcedureStatus
  page?: number
  size?: number
} = {}): Promise<ApiResult<ProcedureTemplate[]>> {
  return apiFetch<ProcedureTemplate[]>(PROCEDURES, { query: params })
}

export function getProcedure(id: string): Promise<ProcedureTemplate> {
  return apiFetch<ProcedureTemplate>(`${PROCEDURES}/${id}`).then((r) => r.data)
}

/** Also opens draft v1, with or without a starting document. */
export function createProcedure(body: CreateProcedureRequest): Promise<ProcedureTemplate> {
  return apiFetch<ProcedureTemplate>(PROCEDURES, { method: 'POST', body }).then((r) => r.data)
}

/** Name and description only — they are identity, not version content. */
export function updateProcedure(id: string, body: UpdateProcedureRequest): Promise<ProcedureTemplate> {
  return apiFetch<ProcedureTemplate>(`${PROCEDURES}/${id}`, { method: 'PUT', body }).then((r) => r.data)
}

export function archiveProcedure(id: string): Promise<ProcedureTemplate> {
  return apiFetch<ProcedureTemplate>(`${PROCEDURES}/${id}/archive`, { method: 'POST' }).then((r) => r.data)
}

/** Copies a version's document into a brand-new procedure with its own draft v1. */
export function cloneProcedure(id: string, body: CloneProcedureRequest): Promise<ProcedureTemplate> {
  return apiFetch<ProcedureTemplate>(`${PROCEDURES}/${id}/clone`, { method: 'POST', body }).then((r) => r.data)
}

// --- the draft: the only editable version ---

/** 404s when no draft is open — that is a normal state, not an error to shout about. */
export function getDraft(id: string): Promise<TemplateVersion> {
  return apiFetch<TemplateVersion>(`${PROCEDURES}/${id}/draft`).then((r) => r.data)
}

/** Replaces the whole document. A stale `rowVersion` comes back 409 rather than overwriting. */
export function saveDraft(id: string, body: SaveDraftRequest): Promise<TemplateVersion> {
  return apiFetch<TemplateVersion>(`${PROCEDURES}/${id}/draft`, { method: 'PUT', body }).then((r) => r.data)
}

/** Opens a new draft from a published version — how you edit after publishing. */
export function createDraft(id: string, body: NewDraftRequest): Promise<TemplateVersion> {
  return apiFetch<TemplateVersion>(`${PROCEDURES}/${id}/draft`, { method: 'POST', body }).then((r) => r.data)
}

export function discardDraft(id: string): Promise<void> {
  return apiFetch<void>(`${PROCEDURES}/${id}/draft`, { method: 'DELETE' }).then(() => undefined)
}

/** Freezes the draft. Pass rowVersion to refuse a draft that changed since you read it. */
export function publishProcedure(id: string, rowVersion?: number): Promise<PublishResult> {
  return apiFetch<PublishResult>(`${PROCEDURES}/${id}/publish`, {
    method: 'POST',
    body: { rowVersion },
  }).then((r) => r.data)
}

// --- history ---

/** Newest first, draft included when there is one. */
export function listVersions(id: string): Promise<TemplateVersionSummary[]> {
  return apiFetch<TemplateVersionSummary[]>(`${PROCEDURES}/${id}/versions`).then((r) => r.data)
}

export function getVersion(id: string, versionNo: number): Promise<TemplateVersion> {
  return apiFetch<TemplateVersion>(`${PROCEDURES}/${id}/versions/${versionNo}`).then((r) => r.data)
}

/** What changed between two versions, matched on stable question keys. */
export function diffVersions(id: string, from: number, to: number): Promise<ProcedureDiff> {
  return apiFetch<ProcedureDiff>(`${PROCEDURES}/${id}/diff`, { query: { from, to } }).then((r) => r.data)
}

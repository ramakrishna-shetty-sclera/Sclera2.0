import { apiDownload, apiFetch, type ApiResult } from './client'
import type {
  CloneProcedureRequest,
  CreateProcedureRequest,
  EvaluationResponse,
  ExportedProcedure,
  FavouriteResponse,
  ImportFileResult,
  ImportRequest,
  NewDraftRequest,
  ProcedureDiff,
  ProcedureStatus,
  ProcedureTemplate,
  PublishResult,
  SaveDraftRequest,
  TemplateScope,
  TemplateVersion,
  TemplateVersionSummary,
  UpdateProcedureRequest,
  UpdateStatus,
} from './types'

// ── Procedures ───────────────────────────────────────────────────────────────
// The gateway must route /api/v1/procedure-templates/** to the procedure
// service (see the route table in the root README), or these 404 before
// reaching it.

const PROCEDURES = '/api/v1/procedure-templates'

/**
 * Identity only; the questions are on the versions. `status` filters ACTIVE
 * vs ARCHIVED, `scope` filters organization-wide vs property-scoped — the two
 * are independent and either, both or neither may be set.
 */
export function listProcedures(params: {
  status?: ProcedureStatus
  scope?: TemplateScope
  favouritesOnly?: boolean
  page?: number
  size?: number
} = {}): Promise<ApiResult<ProcedureTemplate[]>> {
  const { favouritesOnly, ...rest } = params
  return apiFetch<ProcedureTemplate[]>(PROCEDURES, {
    query: { ...rest, favouritesOnly: favouritesOnly === undefined ? undefined : String(favouritesOnly) },
  })
}

export function getProcedure(id: string): Promise<ProcedureTemplate> {
  return apiFetch<ProcedureTemplate>(`${PROCEDURES}/${id}`).then((r) => r.data)
}

/**
 * Also opens draft v1, with or without a starting document.
 *
 * `propertyId` on the created row is decided entirely by whether the request
 * carries `X-Sclera-Property` — never by anything in the body. So an author
 * standing inside a property who explicitly picked "Entire organization" in
 * the Availability field needs that header left off this one call, or the
 * backend has no way to know the procedure should be organization-wide.
 */
export function createProcedure(body: CreateProcedureRequest): Promise<ProcedureTemplate> {
  const omitPropertyHeader = body.meta?.availability === 'ORG'
  return apiFetch<ProcedureTemplate>(PROCEDURES, { method: 'POST', body, omitPropertyHeader }).then(
    (r) => r.data,
  )
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

/**
 * Download the checklist as a file. Exports the published version unless a
 * versionNo is named; the draft only when nothing was ever published.
 */
export function exportProcedure(
  id: string,
  format: 'pdf' | 'xlsx',
  versionNo?: number,
): Promise<{ blob: Blob; filename: string | null }> {
  return apiDownload(`${PROCEDURES}/${id}/export`, { format, versionNo })
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

/**
 * The procedures something can actually be attached to: active, and with a
 * published version to pin.
 *
 * Worth having in one place. "Published" used to be a status on the procedure
 * itself, so pickers filtered `status === 'PUBLISHED'`. It is now a property of
 * having a current published version, and every picker needs the same rule —
 * four hand-written copies of it would drift.
 */
export function listPublishedProcedures(): Promise<ProcedureTemplate[]> {
  return listProcedures({ status: 'ACTIVE', size: 100 }).then((r) =>
    r.data.filter((p) => p.currentPublishedVersionNo != null),
  )
}

/**
 * Evaluates a set of trial answers against a stored version — draft or
 * published. `answers` is keyed by the document's stable question keys, the
 * value shape the server reads: a string option key for a single choice, an
 * array of option keys for CHECKBOX, a whole number for INTEGER. Missing or
 * `undefined` means "not answered yet", which is a normal, evaluable state.
 */
export function evaluateVersion(
  id: string,
  versionNo: number,
  answers: Record<string, { value: unknown } | undefined>,
): Promise<EvaluationResponse> {
  return apiFetch<EvaluationResponse>(`${PROCEDURES}/${id}/versions/${versionNo}/evaluate`, {
    method: 'POST',
    body: { answers },
  }).then((r) => r.data)
}

// ── Sharing: the update lifecycle, import/export, fork, favourites ───────────
// (features 10-12). See src/api/library.ts for browsing the Sclera-wide
// library itself, which these act on the result of.

/** Copies an organization-wide procedure into a new, independent template scoped to the current property. */
export function forkProcedure(id: string): Promise<ProcedureTemplate> {
  return apiFetch<ProcedureTemplate>(`${PROCEDURES}/${id}/fork`, { method: 'POST' }).then((r) => r.data)
}

/**
 * The JSON shape `/import-file` reads back — distinct from `exportProcedure`
 * above, which downloads a formatted PDF/XLSX for a person to read. This one
 * is the machine-readable round trip the Sclera-wide sharing library and
 * import-from-file both use. Omitting `versions` exports the current
 * published version only.
 */
export function exportProcedureJson(
  id: string,
  params: { versions?: number[]; includeDocuments?: boolean } = {},
): Promise<ExportedProcedure> {
  return apiFetch<ExportedProcedure>(`${PROCEDURES}/${id}/export-json`, {
    query: {
      versions: params.versions?.join(','),
      includeDocuments: params.includeDocuments === undefined ? undefined : String(params.includeDocuments),
    },
  }).then((r) => r.data)
}

/** Create new (omit `templateId`) or update an existing procedure, from the Sclera-wide library. */
export function importProcedure(body: ImportRequest): Promise<ProcedureTemplate> {
  return apiFetch<ProcedureTemplate>(`${PROCEDURES}/import`, { method: 'POST', body }).then((r) => r.data)
}

/**
 * Recreates a procedure from a file `exportProcedure` produced, in whichever scope
 * (organization or property) the caller is standing in. Published at once, as its only
 * version. The body is the export's own shape, so upload the same object you downloaded.
 * Refused, with a message that names the problem, when the file was edited after export
 * or its content cannot be published here.
 */
export function importProcedureFile(body: ExportedProcedure): Promise<ImportFileResult> {
  return apiFetch<ImportFileResult>(`${PROCEDURES}/import-file`, { method: 'POST', body }).then((r) => r.data)
}

/** Stops tracking a linked parent without requiring an edit first. */
export function unlinkProcedure(id: string): Promise<ProcedureTemplate> {
  return apiFetch<ProcedureTemplate>(`${PROCEDURES}/${id}/unlink`, { method: 'POST' }).then((r) => r.data)
}

/** Whether this template's link to its parent (global template or org-wide procedure) is behind. */
export function getUpdateStatus(id: string): Promise<UpdateStatus> {
  return apiFetch<UpdateStatus>(`${PROCEDURES}/${id}/update-status`).then((r) => r.data)
}

/** What changed between the applied version and the parent's current one. */
export function getUpdateDiff(id: string): Promise<ProcedureDiff> {
  return apiFetch<ProcedureDiff>(`${PROCEDURES}/${id}/update-diff`).then((r) => r.data)
}

/** Pulls the parent's current content into a new draft. 409s if a draft is already open. */
export function applyUpdate(id: string): Promise<ProcedureTemplate> {
  return apiFetch<ProcedureTemplate>(`${PROCEDURES}/${id}/apply-update`, { method: 'POST' }).then((r) => r.data)
}

/** Records that an available update was seen and deliberately not applied yet. */
export function deferUpdate(id: string): Promise<UpdateStatus> {
  return apiFetch<UpdateStatus>(`${PROCEDURES}/${id}/defer-update`, { method: 'POST' }).then((r) => r.data)
}

export function favouriteProcedure(id: string): Promise<FavouriteResponse> {
  return apiFetch<FavouriteResponse>(`${PROCEDURES}/${id}/favourite`, { method: 'POST' }).then((r) => r.data)
}

export function unfavouriteProcedure(id: string): Promise<FavouriteResponse> {
  return apiFetch<FavouriteResponse>(`${PROCEDURES}/${id}/favourite`, { method: 'DELETE' }).then((r) => r.data)
}

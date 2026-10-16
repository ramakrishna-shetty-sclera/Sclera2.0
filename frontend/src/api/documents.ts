import { apiFetch, apiUpload } from './client'
import type { ProcedureDocument } from './types'

// The reference-document library lives in the procedure service. Bytes are
// uploaded straight here and stored on the row — the same way api/ai.ts's
// uploadAiDocument already stores an AI source document — no second service,
// no Dapr call.
const BASE = '/api/v1/procedure-documents'

/** Omit `active` to get both. Property scope comes from the header the client already sends. */
export function listDocuments(active?: boolean): Promise<ProcedureDocument[]> {
  return apiFetch<ProcedureDocument[]>(BASE, {
    query: { active: active === undefined ? undefined : String(active) },
  }).then((r) => r.data)
}

/** One multipart upload — the bytes land straight on the new row, nothing to register afterwards. */
export function uploadDocument(file: File): Promise<ProcedureDocument> {
  const form = new FormData()
  form.append('file', file)
  return apiUpload<ProcedureDocument>(BASE, form).then((r) => r.data)
}

export function activateDocument(id: string): Promise<ProcedureDocument> {
  return apiFetch<ProcedureDocument>(`${BASE}/${id}/activate`, { method: 'POST' }).then((r) => r.data)
}

export function deactivateDocument(id: string): Promise<ProcedureDocument> {
  return apiFetch<ProcedureDocument>(`${BASE}/${id}/deactivate`, { method: 'POST' }).then((r) => r.data)
}

/** Refused when a published version still cites the document; the message says to deactivate instead. */
export function deleteDocument(id: string): Promise<void> {
  return apiFetch<void>(`${BASE}/${id}`, { method: 'DELETE' }).then(() => undefined)
}

/** Opens a document in a new tab — served directly from this service, no signed link to fetch first. */
export function openDocument(id: string): void {
  window.open(`${BASE}/${id}/content`, '_blank', 'noopener')
}

/** '1.4 MB'. The size is metadata the author may leave out, so absent reads as nothing. */
export function readableSize(bytes: number | null | undefined): string {
  if (bytes == null) return ''
  if (bytes < 1024) return `${bytes} B`
  if (bytes < 1024 * 1024) return `${(bytes / 1024).toFixed(1)} KB`
  return `${(bytes / (1024 * 1024)).toFixed(1)} MB`
}

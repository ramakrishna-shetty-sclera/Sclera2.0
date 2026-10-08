import { apiFetch } from './client'
import type { ProcedureDocument } from './types'

// The reference-document library lives in the procedure service, which must be
// routed at /api/v1/procedure-documents/** by the gateway. The file itself goes
// to the helper service's storage port (routed at /api/v1/helper/**) and never
// through the procedure service: this service only ever stores the opaque
// `location` the upload hands back, plus metadata.
const BASE = '/api/v1/procedure-documents'
const STORAGE = '/api/v1/helper/documents'

/** What the storage port returns for an uploaded file. */
export interface UploadedFile {
  location: string
  downloadUrl: string
  sizeBytes: number
  /** True when the store is a stand-in. A fake artifact must not reach a customer unnoticed. */
  stub: boolean
}

export interface DocumentRequest {
  name: string
  mimeType?: string
  sizeBytes?: number
  location: string
}

/** Omit `active` to get both. Property scope comes from the header the client already sends. */
export function listDocuments(active?: boolean): Promise<ProcedureDocument[]> {
  return apiFetch<ProcedureDocument[]>(BASE, {
    query: { active: active === undefined ? undefined : String(active) },
  }).then((r) => r.data)
}

/** Step one of an upload: the bytes, to the storage port. */
export function uploadFile(file: File): Promise<UploadedFile> {
  const form = new FormData()
  form.append('file', file)
  return apiFetch<UploadedFile>(STORAGE, { method: 'POST', form }).then((r) => r.data)
}

/** Step two: the library row that points at the stored file. */
export function createDocument(body: DocumentRequest): Promise<ProcedureDocument> {
  return apiFetch<ProcedureDocument>(BASE, { method: 'POST', body }).then((r) => r.data)
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

/** A fresh link for a stored file, generated now and never kept: a signed link expires. */
export function freshDownloadUrl(location: string): Promise<string> {
  return apiFetch<{ downloadUrl: string }>(`${STORAGE}/url`, { query: { location } }).then(
    (r) => r.data.downloadUrl,
  )
}

/** Opens a document in a new tab, asking for the link at the moment it is wanted. */
export async function openDocument(location: string): Promise<void> {
  const url = await freshDownloadUrl(location)
  window.open(url, '_blank', 'noopener')
}

/** '1.4 MB'. The size is metadata the author may leave out, so absent reads as nothing. */
export function readableSize(bytes: number | null | undefined): string {
  if (bytes == null) return ''
  if (bytes < 1024) return `${bytes} B`
  if (bytes < 1024 * 1024) return `${(bytes / 1024).toFixed(1)} KB`
  return `${(bytes / (1024 * 1024)).toFixed(1)} MB`
}

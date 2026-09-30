import { apiFetch } from './client'
import type {
  ResultType,
  ResultTypeCreateRequest,
  ResultTypeReorderRequest,
  ResultTypeUpdateRequest,
} from './types'

// Procedure-service result types. The gateway must route /api/v1/result-types/**
// to the procedure service (see the route table in the root README).
const BASE = '/api/v1/result-types'

/** Ordered most severe first. Pass `active` to filter; omit it to get both active and inactive. */
export function listResultTypes(active?: boolean): Promise<ResultType[]> {
  return apiFetch<ResultType[]>(BASE, {
    query: { active: active === undefined ? undefined : String(active) },
  }).then((r) => r.data)
}

export function getResultType(id: string): Promise<ResultType> {
  return apiFetch<ResultType>(`${BASE}/${id}`).then((r) => r.data)
}

export function createResultType(body: ResultTypeCreateRequest): Promise<ResultType> {
  return apiFetch<ResultType>(BASE, { method: 'POST', body }).then((r) => r.data)
}

/** Name, colour and description only; the key never changes. */
export function updateResultType(id: string, body: ResultTypeUpdateRequest): Promise<ResultType> {
  return apiFetch<ResultType>(`${BASE}/${id}`, { method: 'PUT', body }).then((r) => r.data)
}

/**
 * Sets the severity order. `orderedIds` must hold EVERY result type id in the
 * organization, most severe first, or the server rejects it. Returns the
 * reordered list.
 */
export function reorderResultTypes(orderedIds: string[]): Promise<ResultType[]> {
  const body: ResultTypeReorderRequest = { orderedIds }
  return apiFetch<ResultType[]>(`${BASE}/reorder`, { method: 'POST', body }).then((r) => r.data)
}

export function activateResultType(id: string): Promise<ResultType> {
  return apiFetch<ResultType>(`${BASE}/${id}/activate`, { method: 'POST' }).then((r) => r.data)
}

/** Refused for system types (Pass, Fail). */
export function deactivateResultType(id: string): Promise<ResultType> {
  return apiFetch<ResultType>(`${BASE}/${id}/deactivate`, { method: 'POST' }).then((r) => r.data)
}

/** Refused for system types (Pass, Fail). The server answers 204; remaining ranks close the gap. */
export function deleteResultType(id: string): Promise<void> {
  return apiFetch<void>(`${BASE}/${id}`, { method: 'DELETE' }).then(() => undefined)
}

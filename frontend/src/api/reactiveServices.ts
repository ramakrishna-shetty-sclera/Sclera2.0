import { apiFetch } from './client'
import type { Checklist } from './checklists'

export interface ReactiveService {
  id: string
  orgId: string
  name: string
  procedureId: string
  procedureName: string
  locationId: string
  locationName: string
  qrToken: string
  createdBy: string
  createdAt: string
}

const BASE = '/api/v1/reactive-services'

export function listReactiveServices(): Promise<ReactiveService[]> {
  return apiFetch<ReactiveService[]>(BASE).then((r) => r.data)
}

export function createReactiveService(body: {
  name?: string
  procedureId: string
  procedureName: string
  locationId: string
  locationName: string
}): Promise<ReactiveService> {
  return apiFetch<ReactiveService>(BASE, { method: 'POST', body }).then((r) => r.data)
}

export function resolveReactiveServiceByToken(qrToken: string): Promise<ReactiveService> {
  return apiFetch<ReactiveService>(`${BASE}/by-token/${qrToken}`).then((r) => r.data)
}

export function raiseReactiveRequest(
  id: string,
  body: { assigneeEmail: string; dueDate?: string },
): Promise<Checklist> {
  return apiFetch<Checklist>(`${BASE}/${id}/requests`, { method: 'POST', body }).then((r) => r.data)
}

export function deleteReactiveService(id: string): Promise<void> {
  return apiFetch<void>(`${BASE}/${id}`, { method: 'DELETE' }).then(() => undefined)
}

/** The URL a phone lands on after scanning the QR (the /scan route resolves it). */
export function scanUrlFor(service: ReactiveService): string {
  return `${window.location.origin}/scan/${service.qrToken}`
}

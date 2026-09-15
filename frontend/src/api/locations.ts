import { apiFetch } from './client'

export type LocationType = 'BUILDING' | 'FLOOR' | 'LOCATION'

export interface Location {
  id: string
  orgId: string
  name: string
  type: LocationType
  parentId: string | null
  createdBy: string
  createdAt: string
  updatedAt: string
}

export interface LocationRequest {
  name: string
  type: LocationType
  parentId?: string | null
}

const BASE = '/api/v1/helper/locations'

export function listLocations(params: { type?: LocationType; parentId?: string } = {}): Promise<Location[]> {
  return apiFetch<Location[]>(BASE, { query: params }).then((r) => r.data)
}

export function createLocation(body: LocationRequest): Promise<Location> {
  return apiFetch<Location>(BASE, { method: 'POST', body }).then((r) => r.data)
}

export function updateLocation(id: string, body: LocationRequest): Promise<Location> {
  return apiFetch<Location>(`${BASE}/${id}`, { method: 'PUT', body }).then((r) => r.data)
}

export function deleteLocation(id: string): Promise<void> {
  return apiFetch<void>(`${BASE}/${id}`, { method: 'DELETE' }).then(() => undefined)
}

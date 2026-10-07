import { apiFetch } from './client'
import type { Permissions, Property } from './types'

/** The properties the signed-in user may open, sorted by code. Empty means organization level only. */
export async function listMyProperties(): Promise<Property[]> {
  const { data } = await apiFetch<Property[]>('/api/v1/me/properties')
  return data
}

/** What the signed-in user may do with procedures and result types. */
export async function getMyPermissions(): Promise<Permissions> {
  const { data } = await apiFetch<Permissions>('/api/v1/me/permissions')
  return data
}

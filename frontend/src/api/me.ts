import { apiFetch } from './client'
import type { Property } from './types'

/** The properties the signed-in user may open, sorted by code. Empty means organization level only. */
export async function listMyProperties(): Promise<Property[]> {
  const { data } = await apiFetch<Property[]>('/api/v1/me/properties')
  return data
}

import { apiFetch } from './client'

export type AssetType = 'IP' | 'NON_IP'

export interface Asset {
  id: string
  orgId: string
  name: string
  assetType: AssetType
  ipAddress: string | null
  locationId: string | null
  createdBy: string
  createdAt: string
  updatedAt: string
}

export interface AssetRequest {
  name: string
  assetType: AssetType
  ipAddress?: string | null
  locationId?: string | null
}

const BASE = '/api/v1/helper/assets'

export function listAssets(
  params: { assetType?: AssetType; locationId?: string; untagged?: boolean } = {},
): Promise<Asset[]> {
  const query: Record<string, string> = {}
  if (params.assetType) query.assetType = params.assetType
  if (params.locationId) query.locationId = params.locationId
  if (params.untagged !== undefined) query.untagged = String(params.untagged)
  return apiFetch<Asset[]>(BASE, { query }).then((r) => r.data)
}

export function createAsset(body: AssetRequest): Promise<Asset> {
  return apiFetch<Asset>(BASE, { method: 'POST', body }).then((r) => r.data)
}

export function updateAsset(id: string, body: AssetRequest): Promise<Asset> {
  return apiFetch<Asset>(`${BASE}/${id}`, { method: 'PUT', body }).then((r) => r.data)
}

export function deleteAsset(id: string): Promise<void> {
  return apiFetch<void>(`${BASE}/${id}`, { method: 'DELETE' }).then(() => undefined)
}

import { apiFetch } from './client'

export type Priority = 'LOW' | 'MEDIUM' | 'HIGH' | 'CRITICAL'
export type Frequency =
  | 'ONCE'
  | 'DAILY'
  | 'WEEKLY'
  | 'BIWEEKLY'
  | 'MONTHLY'
  | 'QUARTERLY'
  | 'HALF_YEARLY'
  | 'YEARLY'
export type Weekday = 'MON' | 'TUE' | 'WED' | 'THU' | 'FRI' | 'SAT' | 'SUN'

export interface InspectionConfig {
  id: string
  orgId: string
  name: string
  code: string | null
  description: string | null
  assigneeEmail: string
  secondaryAssigneeEmail: string | null
  category: string
  priority: Priority | null
  frequency: Frequency
  scheduleDays: Weekday[]
  bypassScan: boolean
  enableCheckInOut: boolean
  enablePoints: boolean
  mergedView: boolean
  createdBy: string
  createdAt: string
  updatedAt: string
}

export interface InspectionConfigRequest {
  name: string
  code?: string
  description?: string
  assigneeEmail: string
  secondaryAssigneeEmail?: string
  category?: string
  priority?: Priority | null
  frequency: Frequency
  scheduleDays: Weekday[]
  bypassScan: boolean
  enableCheckInOut: boolean
  enablePoints: boolean
  mergedView: boolean
}

const BASE = '/api/v1/inspection-configs'

export function listInspectionConfigs(): Promise<InspectionConfig[]> {
  return apiFetch<InspectionConfig[]>(BASE).then((r) => r.data)
}

export function getInspectionConfig(id: string): Promise<InspectionConfig> {
  return apiFetch<InspectionConfig>(`${BASE}/${id}`).then((r) => r.data)
}

export function createInspectionConfig(body: InspectionConfigRequest): Promise<InspectionConfig> {
  return apiFetch<InspectionConfig>(BASE, { method: 'POST', body }).then((r) => r.data)
}

export function updateInspectionConfig(
  id: string,
  body: InspectionConfigRequest,
): Promise<InspectionConfig> {
  return apiFetch<InspectionConfig>(`${BASE}/${id}`, { method: 'PUT', body }).then((r) => r.data)
}

export function deleteInspectionConfig(id: string): Promise<void> {
  return apiFetch<void>(`${BASE}/${id}`, { method: 'DELETE' }).then(() => undefined)
}

export const WEEKDAYS: Weekday[] = ['MON', 'TUE', 'WED', 'THU', 'FRI', 'SAT', 'SUN']
export const FREQUENCIES: Frequency[] = [
  'ONCE',
  'DAILY',
  'WEEKLY',
  'BIWEEKLY',
  'MONTHLY',
  'QUARTERLY',
  'HALF_YEARLY',
  'YEARLY',
]
export const PRIORITIES: Priority[] = ['LOW', 'MEDIUM', 'HIGH', 'CRITICAL']
export const CATEGORIES = ['Generic', 'Safety', 'Security', 'Electrical', 'HVAC', 'Fire', 'Plumbing']

export function frequencyLabel(f: Frequency): string {
  return f
    .toLowerCase()
    .replace('_', '-')
    .replace(/\b\w/g, (c) => c.toUpperCase())
}

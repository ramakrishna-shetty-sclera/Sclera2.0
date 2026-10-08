import { apiFetch } from './client'
import type { TargetKind } from './types'

// The property vocabulary a procedure's target types are drawn from. It is held
// by the helper service for now (a temporary stand-in), and the gateway already
// routes /api/v1/helper/**. Readable with the same permission as the lists.
const BASE = '/api/v1/helper/vocabulary'

/** One key in one of the four lists. A retired key stays listed with `active: false`. */
export interface VocabularyEntry {
  key: string
  name: string
  displayOrder: number
  active: boolean
}

/** Every list at once, keyed by the same kind names a target type uses. */
export type Vocabulary = Record<TargetKind, VocabularyEntry[]>

export function getVocabulary(): Promise<Vocabulary> {
  return apiFetch<Vocabulary>(BASE).then((r) => r.data)
}

/** The lists in the order an author reads them, with the words used on screen. */
export const TARGET_KINDS: { kind: TargetKind; label: string; singular: string }[] = [
  { kind: 'HIERARCHY_LEVEL', label: 'Hierarchy levels', singular: 'hierarchy level' },
  { kind: 'LOCATION_TYPE', label: 'Location types', singular: 'location type' },
  { kind: 'ASSET_CLASS', label: 'Asset classes', singular: 'asset class' },
  { kind: 'ASSET_TAG', label: 'Asset tags', singular: 'asset tag' },
]

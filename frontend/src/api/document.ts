import type { ItemType } from './types'

/**
 * What the frontend has to know about the document's types, mirroring
 * `QuestionType` on the server. Kept beside the types rather than inside a
 * screen, because the editor and the read-only views both need it.
 *
 * The server is still the authority — every rule here is also enforced in
 * `DefinitionValidator`. This exists so an author never meets a refusal they
 * could not have avoided, not to replace the check.
 */

/** Choice types are the ones whose options decide a result. */
export function isChoice(type: ItemType): boolean {
  return (
    type === 'YES_NO' ||
    type === 'YES_NO_NA' ||
    type === 'RADIO' ||
    type === 'CHECKBOX' ||
    type === 'DROPDOWN'
  )
}

/** Yes/No and Yes/No/NA have their answers seeded; the author cannot add or remove them. */
export function hasFixedOptions(type: ItemType): boolean {
  return type === 'YES_NO' || type === 'YES_NO_NA'
}

/** The answers a fixed-option type comes with. Empty for every other type. */
export function seededLabels(type: ItemType): string[] {
  if (type === 'YES_NO') return ['Yes', 'No']
  if (type === 'YES_NO_NA') return ['Yes', 'No', 'N/A']
  return []
}

export const ITEM_TYPES: ItemType[] = [
  'SECTION',
  'YES_NO',
  'YES_NO_NA',
  'RADIO',
  'CHECKBOX',
  'DROPDOWN',
  'TEXT',
  'INTEGER',
  'IMAGE',
  'MULTI_IMAGE',
  'AUDIO',
  'VIDEO',
  'DOCUMENT',
]

/** 'MULTI_IMAGE' reads as 'Multi image'. The stored value stays what it is. */
export function typeLabel(type: string): string {
  if (type === 'YES_NO') return 'Yes / No'
  if (type === 'YES_NO_NA') return 'Yes / No / N/A'
  const words = type.toLowerCase().split('_').join(' ')
  return words.charAt(0).toUpperCase() + words.slice(1)
}

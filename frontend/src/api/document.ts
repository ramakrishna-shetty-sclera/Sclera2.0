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

/**
 * What each seeded answer means to begin with, in step with `seededLabels`:
 * Yes passes, No fails, and N/A decides nothing. Empty means "decides nothing".
 *
 * The v3 prototype seeds exactly this, and it matters: a choice question whose
 * answers map to no result cannot be published, so without it every Yes/No
 * question needs two manual selections before it is usable. N/A stays unmapped
 * because choosing it should neither pass nor fail an inspection.
 *
 * These are result-type *keys*, not literals, like everything else here. PASS
 * and FAIL are the two system result types: seeded for every organization and
 * neither deletable nor deactivatable, so they are always there to map to. It is
 * a starting point, not a rule: the author can map Yes to Amber, or to nothing.
 */
export function seededResults(type: ItemType): string[] {
  if (type === 'YES_NO') return ['PASS', 'FAIL']
  if (type === 'YES_NO_NA') return ['PASS', 'FAIL', '']
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

/** Anything shaped like a document item — the stored one or the editor's draft. */
interface Numberable<T> {
  type: ItemType
  follow: T[]
}

/**
 * What each item is called on screen: '1', '2', '2.1', '2.1.1'.
 *
 * Three rules, all of them the prototype's, and each one easy to get wrong:
 *
 * - **A section has no number at all.** It is a heading with a title, not
 *   "Section 1", and it does not advance the count — so a section above a
 *   question does not push that question to 2.
 * - **The count runs across the whole level, not per section.** A second
 *   section's first question is 3, not 1. Sections group questions for
 *   reading; they do not restart them.
 * - **A follow-up is dotted** — question 2's first follow-up is 2.1, and its
 *   own follow-up is 2.1.1 — with the count restarting inside each parent.
 *
 * Keyed by the item object itself, so the editor and the detail screen can
 * share one answer without either of them needing a key or an index. Sections
 * are absent from the map rather than mapped to '': a caller asking for one
 * gets undefined, which is harder to render by accident.
 */
export function numberItems<T extends Numberable<T>>(items: T[]): Map<T, string> {
  const numbers = new Map<T, string>()

  const walk = (list: T[], prefix: string) => {
    let n = 0
    for (const item of list) {
      // A section carries no number and does not advance the count. It also
      // cannot hold follow-ups — the validator refuses them — so there is
      // nothing below it to walk into.
      if (item.type === 'SECTION') continue
      n += 1
      const number = prefix ? `${prefix}.${n}` : String(n)
      numbers.set(item, number)
      walk(item.follow, number)
    }
  }

  walk(items, '')
  return numbers
}

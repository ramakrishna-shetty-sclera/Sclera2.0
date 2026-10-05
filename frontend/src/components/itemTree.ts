import { hasFixedOptions, isChoice, seededLabels } from '../api/document'
import type { DefinitionDocument, DefinitionItem, ItemSource, ItemType } from '../api/types'

/**
 * The editing shape of a document, and the operations on it.
 *
 * Two things the document does not have and the editor cannot do without:
 *
 * **A uid.** A new item has no server key until it is saved, but React needs a
 * stable key for it and every operation needs an address. Index paths
 * (`items[2].follow[1]`) break the moment something is inserted above, so each
 * draft carries a `uid` the server never sees.
 *
 * **Numbers as strings.** `min` and `max` are kept as the text that is in the
 * input, because an input being emptied is a real state and `0` and `''` must
 * not collapse into each other on the way through.
 *
 * Everything else rides through untouched — including the fields no screen
 * edits yet. Dropping one on load would silently wipe it on the next save.
 */

export interface OptionDraft {
  uid: string
  /** Minted by the server. A follow-up's `when` points at this, so it never changes. */
  key?: string
  label: string
  /** A result-type key, or '' for an answer that decides nothing. */
  result: string
}

export interface ItemDraft {
  uid: string
  key?: string
  text: string
  help: string
  type: ItemType
  required: boolean
  options: OptionDraft[]
  unit: string
  min: string
  max: string
  workOrder: boolean
  alertProfile: string
  source?: ItemSource | null
  standard: string
  /** The parent option key that shows this item. Empty on a top-level item. */
  when: string
  follow: ItemDraft[]
}

let seq = 0
export const uid = (): string => `d${++seq}`

export function newItem(type: ItemType = 'TEXT'): ItemDraft {
  return retype(
    {
      uid: uid(),
      text: '',
      help: '',
      type: 'TEXT',
      required: false,
      options: [],
      unit: '',
      min: '',
      max: '',
      workOrder: false,
      alertProfile: '',
      standard: '',
      when: '',
      follow: [],
    },
    type,
  )
}

export function newOption(label = ''): OptionDraft {
  return { uid: uid(), label, result: '' }
}

// --- loading and saving --------------------------------------------------

export function fromDocument(document: DefinitionDocument): ItemDraft[] {
  return document.items.map(toDraft)
}

function toDraft(item: DefinitionItem): ItemDraft {
  return {
    uid: uid(),
    key: item.key,
    text: item.text,
    help: item.help ?? '',
    type: item.type,
    required: item.required,
    options: item.options.map((o) => ({
      uid: uid(),
      key: o.key,
      label: o.label,
      result: o.result ?? '',
    })),
    unit: item.unit ?? '',
    min: item.min === null || item.min === undefined ? '' : String(item.min),
    max: item.max === null || item.max === undefined ? '' : String(item.max),
    workOrder: item.workOrder,
    alertProfile: item.alertProfile ?? '',
    source: item.source,
    standard: item.standard ?? '',
    when: item.when ?? '',
    follow: item.follow.map(toDraft),
  }
}

/** Keys ride through untouched; a blank one is simply absent, so the server mints it. */
export function toDocument(schema: number, items: ItemDraft[]): DefinitionDocument {
  return { schema, items: items.map(toItem) }
}

function toItem(draft: ItemDraft): DefinitionItem {
  const number = (text: string): number | undefined =>
    text.trim() === '' ? undefined : Number(text)

  return {
    key: draft.key,
    text: draft.text.trim(),
    help: draft.help.trim() || undefined,
    type: draft.type,
    required: draft.type === 'SECTION' ? false : draft.required,
    // An answer row that was added and never filled in carries nothing, so it
    // is dropped rather than refused. A *keyed* one with a blank label is a
    // real mistake — something may already point at it — so it goes to the
    // server and is refused there.
    options: draft.options
      .filter((o) => o.key || o.label.trim() !== '')
      .map((o) => ({
        key: o.key,
        label: o.label.trim(),
        result: o.result || undefined,
      })),
    unit: draft.unit.trim() || undefined,
    min: number(draft.min),
    max: number(draft.max),
    workOrder: draft.workOrder,
    alertProfile: draft.alertProfile.trim() || undefined,
    source: draft.source ?? undefined,
    standard: draft.standard.trim() || undefined,
    when: draft.when || undefined,
    follow: draft.follow.map(toItem),
  }
}

// --- tree operations, all by uid and all pure -----------------------------

export function replaceIn(
  items: ItemDraft[],
  target: string,
  fn: (item: ItemDraft) => ItemDraft,
): ItemDraft[] {
  return items.map((item) =>
    item.uid === target ? fn(item) : { ...item, follow: replaceIn(item.follow, target, fn) },
  )
}

export function patchIn(
  items: ItemDraft[],
  target: string,
  patch: Partial<ItemDraft>,
): ItemDraft[] {
  return replaceIn(items, target, (item) => ({ ...item, ...patch }))
}

export function removeIn(items: ItemDraft[], target: string): ItemDraft[] {
  return items
    .filter((item) => item.uid !== target)
    .map((item) => ({ ...item, follow: removeIn(item.follow, target) }))
}

/** Moves an item among its own siblings. Nothing changes level by moving. */
export function moveIn(items: ItemDraft[], target: string, delta: number): ItemDraft[] {
  const index = items.findIndex((item) => item.uid === target)
  if (index === -1) {
    return items.map((item) => ({ ...item, follow: moveIn(item.follow, target, delta) }))
  }
  const to = index + delta
  if (to < 0 || to >= items.length) return items
  const next = [...items]
  const [moved] = next.splice(index, 1)
  next.splice(to, 0, moved)
  return next
}

/**
 * Where an item sits, as the indices to walk to reach it.
 *
 * A uid is this browser's and the server has never heard of it, so after a save
 * the reloaded tree has entirely new uids. Position is the one address both
 * sides agree on: `toDocument` preserves order and so does the server, so the
 * item at [1, 0] before a save is the item at [1, 0] after it.
 */
export function pathTo(items: ItemDraft[], target: string): number[] | null {
  for (let i = 0; i < items.length; i++) {
    if (items[i].uid === target) return [i]
    const below = pathTo(items[i].follow, target)
    if (below) return [i, ...below]
  }
  return null
}

export function atPath(items: ItemDraft[], path: number[]): ItemDraft | null {
  let list = items
  let found: ItemDraft | null = null
  for (const index of path) {
    found = list[index] ?? null
    if (!found) return null
    list = found.follow
  }
  return found
}

export function findIn(items: ItemDraft[], target: string): ItemDraft | null {
  for (const item of items) {
    if (item.uid === target) return item
    const found = findIn(item.follow, target)
    if (found) return found
  }
  return null
}

export function addFollowIn(
  items: ItemDraft[],
  parent: string,
  child: ItemDraft,
): ItemDraft[] {
  return replaceIn(items, parent, (item) => ({ ...item, follow: [...item.follow, child] }))
}

// --- answers --------------------------------------------------------------

export function addOptionIn(items: ItemDraft[], target: string): ItemDraft[] {
  return replaceIn(items, target, (item) => ({ ...item, options: [...item.options, newOption()] }))
}

export function patchOptionIn(
  items: ItemDraft[],
  target: string,
  option: string,
  patch: Partial<OptionDraft>,
): ItemDraft[] {
  return replaceIn(items, target, (item) => ({
    ...item,
    options: item.options.map((o) => (o.uid === option ? { ...o, ...patch } : o)),
  }))
}

export function removeOptionIn(
  items: ItemDraft[],
  target: string,
  option: string,
): ItemDraft[] {
  return replaceIn(items, target, (item) => ({
    ...item,
    options: item.options.filter((o) => o.uid !== option),
  }))
}

/**
 * Why an answer cannot be removed, or null if it can.
 *
 * A follow-up names the answer that shows it, so removing that answer would
 * leave the follow-up pointing at nothing. Saying so beats quietly deleting the
 * follow-up with it.
 */
export function optionRemovalBlockedBy(item: ItemDraft, option: string): string | null {
  const key = item.options.find((o) => o.uid === option)?.key
  if (!key) return null
  const dependent = item.follow.filter((f) => f.when === key)
  if (dependent.length === 0) return null
  const named = dependent[0].text.trim() || 'an untitled follow-up'
  return dependent.length === 1
    ? `“${named}” is shown by this answer. Remove or repoint it first.`
    : `${dependent.length} follow-ups are shown by this answer. Remove or repoint them first.`
}

// --- the rules a type change implies --------------------------------------

/**
 * The same item as a different type, with everything that type cannot carry
 * cleared and everything it must carry seeded.
 *
 * The server refuses options on a free-text question, a unit on a non-number
 * and a work order on anything that produces no result. Clearing them here is
 * what keeps the author from meeting a 400 they could not have avoided.
 */
export function retype(item: ItemDraft, type: ItemType): ItemDraft {
  const next: ItemDraft = { ...item, type }

  if (type === 'SECTION') {
    // A section is a heading: never answered, so never required, never
    // conditional, and nothing hangs off it.
    return { ...next, required: false, options: [], follow: [], when: '', workOrder: false,
      alertProfile: '', unit: '', min: '', max: '' }
  }

  if (!isChoice(type)) {
    next.options = []
    next.workOrder = false
    next.alertProfile = ''
  } else if (hasFixedOptions(type)) {
    next.options = seed(type, item.options)
  }

  if (type !== 'INTEGER') {
    next.unit = ''
    next.min = ''
    next.max = ''
  }

  return next
}

/**
 * Yes/No's answers, reusing the ones already there so their keys survive.
 *
 * That is the whole point of keying an option: Yes/No becoming Yes/No/NA must
 * not orphan a follow-up hanging off "No".
 */
function seed(type: ItemType, existing: OptionDraft[]): OptionDraft[] {
  return seededLabels(type).map((label, index) => {
    const kept = existing[index]
    return kept ? { ...kept, label } : newOption(label)
  })
}

/**
 * Why this type change cannot be made, or null if it can.
 *
 * The one case that matters: a follow-up points at one of its parent's answers
 * by key, so a change that removes that answer would leave the follow-up
 * pointing at nothing — which the server refuses, after the author has already
 * lost the answer.
 */
export function retypeBlockedBy(item: ItemDraft, type: ItemType): string | null {
  if (item.follow.length === 0) return null

  if (type === 'SECTION') {
    return 'A section cannot have follow-up questions. Remove them first.'
  }
  if (!isChoice(type)) {
    return `A ${typeName(type)} question has no answers for a follow-up to depend on. Remove the follow-ups first.`
  }

  const surviving = new Set(
    (hasFixedOptions(type) ? seed(type, item.options) : item.options)
      .map((o) => o.key)
      .filter((key): key is string => Boolean(key)),
  )
  const orphaned = item.follow.filter((f) => f.when && !surviving.has(f.when))
  if (orphaned.length > 0) {
    return `That would leave ${orphaned.length === 1 ? 'a follow-up' : `${orphaned.length} follow-ups`} pointing at an answer this type does not have.`
  }
  return null
}

function typeName(type: ItemType): string {
  return type.toLowerCase().split('_').join(' ')
}

/**
 * The answers a follow-up may be hung off.
 *
 * Only keyed ones: the key is minted by the server on save, so an answer that
 * has never been saved cannot be pointed at yet.
 */
export function triggerOptions(item: ItemDraft): OptionDraft[] {
  if (!isChoice(item.type)) return []
  return item.options.filter((o) => Boolean(o.key))
}

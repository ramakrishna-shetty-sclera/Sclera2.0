import { hasFixedOptions, isChoice, seededLabels, seededResults } from '../api/document'
import type {
  DefinitionDocument,
  DefinitionItem,
  ItemSource,
  ItemType,
  RangeRule,
  Rollup,
  TargetType,
  Threshold,
} from '../api/types'

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
  /** Points this answer is worth, as the text in the input. '' is unset, not zero — zero is a real score. */
  score: string
  excludeFromScoring: boolean
}

export interface ItemDraft {
  uid: string
  key?: string
  text: string
  help: string
  type: ItemType
  required: boolean
  critical: boolean
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
  /** How much this counts against its siblings, as the text in the input. '' means unset (1). */
  weight: string
  /** '' means unset (INDEPENDENT) — the same '' sentinel `result` and `when` use for "absent". */
  followRollup: Rollup | ''
  follow: ItemDraft[]
  /** A number question's bands — what a reading means, as opposed to what may be typed. */
  rules: RangeRule[]
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
      critical: false,
      options: [],
      unit: '',
      min: '',
      max: '',
      workOrder: false,
      alertProfile: '',
      standard: '',
      when: '',
      weight: '',
      followRollup: '',
      follow: [],
      rules: [],
    },
    type,
  )
}

/** How many empty answer rows a Radio, Checkbox or Dropdown starts with. */
export const STARTER_ANSWERS = 2

export function newOption(label = ''): OptionDraft {
  return { uid: uid(), label, result: '', score: '', excludeFromScoring: false }
}

// --- loading and saving --------------------------------------------------

export function fromDocument(document: DefinitionDocument): ItemDraft[] {
  return document.items.map(toDraft)
}

/** '' means absent — unset is different from zero, so a number is never defaulted to '0'. */
function numberToText(n: number | null | undefined): string {
  return n === null || n === undefined ? '' : String(n)
}

function toDraft(item: DefinitionItem): ItemDraft {
  return {
    uid: uid(),
    key: item.key,
    text: item.text,
    help: item.help ?? '',
    type: item.type,
    required: item.required,
    critical: item.critical,
    options: item.options.map((o) => ({
      uid: uid(),
      key: o.key,
      label: o.label,
      result: o.result ?? '',
      score: numberToText(o.score),
      excludeFromScoring: o.excludeFromScoring,
    })),
    unit: item.unit ?? '',
    min: numberToText(item.min),
    max: numberToText(item.max),
    workOrder: item.workOrder,
    alertProfile: item.alertProfile ?? '',
    source: item.source,
    standard: item.standard ?? '',
    when: item.when ?? '',
    weight: numberToText(item.weight),
    followRollup: item.followRollup ?? '',
    follow: item.follow.map(toDraft),
    rules: item.rules ?? [],
  }
}

/**
 * Keys ride through untouched; a blank one is simply absent, so the server
 * mints it. `thresholds` and `targetTypes` are document-level, not per item, so
 * they ride through exactly as they were loaded — no screen edits either yet.
 * An empty `targetTypes` is left out, the way the server stores it.
 */
export function toDocument(
  schema: number,
  items: ItemDraft[],
  thresholds: Threshold[] = [],
  targetTypes: TargetType[] = [],
): DefinitionDocument {
  return { schema, items: items.map(toItem), thresholds, ...(targetTypes.length > 0 ? { targetTypes } : {}) }
}

/**
 * Whether an answer row goes to the server.
 *
 * A row that was added and never filled in carries nothing, so it is dropped
 * rather than refused. A *keyed* one with a blank label is a real mistake —
 * something may already point at it — so it goes and is refused there.
 *
 * One definition, because two places must agree on it exactly: `toItem`, which
 * decides what is sent, and `keysFromSaved`, which has to walk the sent answers
 * and the saved ones in step.
 */
function isSentOption(option: OptionDraft): boolean {
  return Boolean(option.key) || option.label.trim() !== ''
}

/** '' or blank is unset; a real number — including 0 — is kept. */
function textToNumber(text: string): number | undefined {
  return text.trim() === '' ? undefined : Number(text)
}

function toItem(draft: ItemDraft): DefinitionItem {
  return {
    key: draft.key,
    text: draft.text.trim(),
    help: draft.help.trim() || undefined,
    type: draft.type,
    required: draft.type === 'SECTION' ? false : draft.required,
    critical: draft.critical,
    options: draft.options
      .filter(isSentOption)
      .map((o) => ({
        key: o.key,
        label: o.label.trim(),
        result: o.result || undefined,
        score: textToNumber(o.score),
        excludeFromScoring: o.excludeFromScoring,
      })),
    unit: draft.unit.trim() || undefined,
    min: textToNumber(draft.min),
    max: textToNumber(draft.max),
    workOrder: draft.workOrder,
    alertProfile: draft.alertProfile.trim() || undefined,
    source: draft.source ?? undefined,
    standard: draft.standard.trim() || undefined,
    when: draft.when || undefined,
    weight: textToNumber(draft.weight),
    // Never the literal 'INDEPENDENT': the canonicaliser keeps any non-blank
    // text, so that would change the hash where omitting the field does not.
    followRollup: draft.followRollup || undefined,
    follow: draft.follow.map(toItem),
    rules: draft.rules.length > 0 ? draft.rules : undefined,
  }
}

// --- autosave ----------------------------------------------------------------

/**
 * Why the document is not worth autosaving yet, or null when it is.
 *
 * Autosave runs while the author is mid-sentence, and the server refuses a
 * question with no text, a follow-up with no trigger, a keyed answer with no
 * label and a range running backwards. Each is a normal intermediate state, so
 * it should wait quietly for the author to finish rather than answer every
 * keystroke with a refusal. The manual Save still sends it and shows the
 * server's reasons, which is where an author expects to see them.
 *
 * Deliberately only what the server would refuse on structure — not publish
 * readiness, which a draft is allowed to fail.
 */
export function autosaveBlockedBy(items: ItemDraft[], followUps = false): string | null {
  for (const item of items) {
    if (item.text.trim() === '') return 'a question has no text yet'
    if (followUps && !item.when) return 'a follow-up has no answer picked'
    if (item.options.some((o) => o.key && o.label.trim() === '')) return 'an answer has no label'
    if (
      item.type === 'INTEGER' &&
      item.min.trim() !== '' &&
      item.max.trim() !== '' &&
      Number(item.min) > Number(item.max)
    ) {
      return 'a minimum is above its maximum'
    }
    // Structure the server refuses on every write, same as the checks above —
    // weight and a band's bounds included, now that a screen edits them.
    if (item.weight.trim() !== '' && Number(item.weight) < 1) {
      return 'a weight below 1 is refused'
    }
    for (const band of item.rules) {
      if (band.min != null && band.max != null && band.min > band.max) {
        return 'a band has a minimum above its maximum'
      }
    }
    for (const option of item.options) {
      if (option.score.trim() !== '' && Number(option.score) < 0) {
        return 'an answer cannot score below zero'
      }
      if (option.excludeFromScoring && option.score.trim() !== '') {
        return 'an answer excluded from scoring cannot also carry a score'
      }
    }
    const below = autosaveBlockedBy(item.follow, true)
    if (below) return below
  }
  return null
}

/** Whether any item in the tree — at any depth — is a section. */
function hasAnySection(items: ItemDraft[]): boolean {
  return items.some((item) => item.type === 'SECTION' || hasAnySection(item.follow))
}

/**
 * Why the document's score thresholds are not worth autosaving yet, or null
 * when they are. Mirrors the server's structural checks on a Threshold — a
 * band running backwards, outside the 0-100 scale, or scoped to a section
 * when the document has none — all refused on every write, so autosave should
 * wait for a finished band rather than meet the same refusal every tick.
 *
 * Separate from `autosaveBlockedBy` because thresholds are document-level, not
 * per item, and checking them does not need the recursive walk.
 */
export function thresholdsBlockedBy(thresholds: Threshold[], items: ItemDraft[]): string | null {
  const hasSections = hasAnySection(items)
  for (const band of thresholds) {
    if (band.min != null && band.max != null && band.min > band.max) {
      return 'a score band has a minimum above its maximum'
    }
    if ((band.min != null && (band.min < 0 || band.min > 100)) ||
        (band.max != null && (band.max < 0 || band.max > 100))) {
      return 'a score band is outside the 0 to 100 scale'
    }
    if (band.scope === 'SECTION' && !hasSections) {
      return 'a score band scores a section, but this procedure has none'
    }
  }
  return null
}

/** Everything the autosave tick has to look at, gathered so the decision can be tested. */
export interface AutosaveState {
  /** An existing draft that has finished loading — creating has nothing to save to yet. */
  ready: boolean
  /** Someone else saved first. Autosaving over them is exactly what the lock exists to stop. */
  conflict: boolean
  /** A manual save or a "+ Follow-up" save is already running. */
  busy: boolean
  snapshot: string
  savedSnapshot: string
  /** The document that last failed to autosave, so a deterministic refusal is not retried forever. */
  failedSnapshot: string | null
  items: ItemDraft[]
  thresholds: Threshold[]
}

/** Why this tick should not save, or null when it should. */
export function autosaveSkipReason(s: AutosaveState): string | null {
  if (!s.ready) return 'not an open draft'
  if (s.conflict) return 'conflict'
  if (s.busy) return 'busy'
  if (s.snapshot === s.savedSnapshot) return 'unchanged'
  if (s.snapshot === s.failedSnapshot) return 'already refused'
  return autosaveBlockedBy(s.items) ?? thresholdsBlockedBy(s.thresholds, s.items)
}

/** The keys a save minted, by the uid of the draft they belong to. */
export interface MintedKeys {
  items: Map<string, string>
  options: Map<string, string>
}

/**
 * Reads the keys the server assigned out of a saved document.
 *
 * Needed because a save does not hand the editor its keys, and an editor that
 * stays open — which autosave means — would send the same unkeyed items again.
 * The server mints a fresh key for anything without one: re-saving one dropdown
 * with two answers turned q1, o2, o3 into q4, o5, o6. Left alone, autosave would
 * churn every key in the document every thirty seconds, burn the counter, and
 * break the one guarantee keys exist for — that q2 in v1 is q2 in v7.
 *
 * Matched by *uid* rather than position because the author may have kept typing,
 * inserted or reordered while the request was in flight. The saved document
 * mirrors what was sent in shape and order, so `sent` and `saved` are walked in
 * step; `sent` must be the exact list the saved document was built from.
 */
export function keysFromSaved(sent: ItemDraft[], saved: DefinitionItem[]): MintedKeys {
  const minted: MintedKeys = { items: new Map(), options: new Map() }
  collectKeys(sent, saved, minted)
  return minted
}

function collectKeys(sent: ItemDraft[], saved: DefinitionItem[], out: MintedKeys): void {
  sent.forEach((draft, index) => {
    const item = saved[index]
    if (!item) return
    if (item.key) out.items.set(draft.uid, item.key)
    draft.options.filter(isSentOption).forEach((option, i) => {
      const key = item.options[i]?.key
      if (key) out.options.set(option.uid, key)
    })
    collectKeys(draft.follow, item.follow, out)
  })
}

/**
 * Gives the editor's items the keys a save minted, leaving every other edit
 * alone. Only fills a key that is missing: one the editor already holds is the
 * server's own and never changes.
 */
export function applyKeys(items: ItemDraft[], minted: MintedKeys): ItemDraft[] {
  return items.map((item) => ({
    ...item,
    key: item.key ?? minted.items.get(item.uid),
    options: item.options.map((o) => ({ ...o, key: o.key ?? minted.options.get(o.uid) })),
    follow: applyKeys(item.follow, minted),
  }))
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
    .map((item) => {
      const follow = removeIn(item.follow, target)
      // followRollup only means something when there is a follow-up to roll
      // up; the server refuses one set on an item with none. Removing the
      // last follow-up must clear it here too, or a scored item that loses
      // its only follow-up keeps saying how that follow-up should have
      // contributed.
      return follow.length === 0 ? { ...item, follow, followRollup: '' } : { ...item, follow }
    })
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
    // critical, never conditional, and nothing hangs off it. `weight` is kept —
    // a section is weighted as a group, the same as a question is weighted on
    // its own, and the validator accepts it on both.
    return { ...next, required: false, critical: false, options: [], follow: [], followRollup: '',
      when: '', workOrder: false, alertProfile: '', unit: '', min: '', max: '', rules: [] }
  }

  if (!isChoice(type)) {
    next.options = []
    next.workOrder = false
    next.alertProfile = ''
  } else if (hasFixedOptions(type)) {
    next.options = seed(type, item.options)
  } else if (item.options.length === 0) {
    // Radio, Checkbox and Dropdown have no fixed answers, so the author writes
    // them — but an Answers heading with nothing under it and a button nothing
    // points at reads as "this cannot be typed into". The prototype starts these
    // with two; so do we.
    //
    // Blank rather than "Option 1": a seeded label would save as a real answer
    // the author may never rename. A row that is never filled in and has no key
    // is dropped by toItem, so two blank starters cannot make a draft
    // unsaveable — and a Dropdown left empty is caught at publish, which says
    // it needs at least two answers.
    //
    // Only when there are none: switching Yes/No to Dropdown keeps the answers
    // it already had, keys and result mappings included.
    next.options = Array.from({ length: STARTER_ANSWERS }, () => newOption())
  }

  if (type !== 'INTEGER') {
    next.unit = ''
    next.min = ''
    next.max = ''
    next.rules = []
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
  const results = seededResults(type)
  return seededLabels(type).map((label, index) => {
    const kept = existing[index]
    // A row that already exists keeps whatever the author mapped it to. The
    // default applies only to a row this seeding creates: overwriting a mapping
    // the author chose, just because they changed type and back, would be wrong.
    return kept ? { ...kept, label } : { ...newOption(label), result: results[index] ?? '' }
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

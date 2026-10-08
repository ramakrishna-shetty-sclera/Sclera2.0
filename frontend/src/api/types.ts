export type TemplateStatus = 'DRAFT' | 'PUBLISHED' | 'ARCHIVED'
export type InspectionStatus = 'DRAFT' | 'IN_PROGRESS' | 'COMPLETED' | 'CANCELLED'
export type QuestionType =
  | 'TEXT'
  | 'NUMBER'
  | 'BOOLEAN'
  | 'SINGLE_CHOICE'
  | 'MULTI_CHOICE'
  | 'DATE'
  | 'PHOTO'
  | 'SIGNATURE'

export interface Question {
  id: string
  text: string
  helpText: string | null
  type: QuestionType
  required: boolean
  displayOrder: number
  options: string[] | null
  scoreWeight: number | null
}

export interface Section {
  id: string
  title: string
  displayOrder: number
  questions: Question[]
}

export interface QuestionTemplate {
  id: string
  orgId: string
  name: string
  description: string | null
  category: string | null
  status: TemplateStatus
  version: number
  createdBy: string
  createdAt: string
  updatedAt: string
  sections: Section[]
}

export interface Answer {
  id: string
  questionId: string
  value: unknown
  score: number | null
  comment: string | null
}

export interface Inspection {
  id: string
  orgId: string
  templateId: string
  templateVersion: number
  templateName: string
  templateSnapshot: QuestionTemplate | null
  status: InspectionStatus
  assigneeId: string | null
  scheduledFor: string | null
  startedAt: string | null
  completedAt: string | null
  notes: string | null
  createdBy: string
  createdAt: string
  updatedAt: string
  answers: Answer[]
}

export interface CreateInspectionRequest {
  templateId: string
  assigneeId?: string
  scheduledFor?: string
  notes?: string
}

export interface AnswerSubmission {
  questionId: string
  value: unknown
  score?: number
  comment?: string
}

export interface Pagination {
  page: number
  size: number
  totalElements: number
  totalPages: number
  hasNext: boolean
  hasPrevious: boolean
}

// ── Result types (procedure-service /api/v1/result-types) ────────────────────
// An organization's outcome vocabulary: Pass, Fail, Amber, … Lists come back
// ordered by severityOrder, where 1 is MOST severe. Pass and Fail are `system`
// types: they can be renamed and recoloured, never deleted or deactivated.

export interface ResultType {
  id: string
  /** Upper-case identifier that records and procedures store; never changes after create. */
  key: string
  name: string
  /** Six-digit hex, e.g. '#2ecc71'. */
  color: string
  /** Omitted by the server when empty. */
  description?: string | null
  /** 1 = most severe; ranks are contiguous 1..n. */
  severityOrder: number
  system: boolean
  active: boolean
  createdAt: string
  updatedAt: string
}

export interface ResultTypeCreateRequest {
  /** ^[A-Z][A-Z0-9_]{0,49}$ */
  key: string
  name: string
  color: string
  description?: string
  /** 1..n+1; omit to append as least severe. */
  severityOrder?: number
}

/** Key is deliberately absent: it cannot be changed. */
export interface ResultTypeUpdateRequest {
  name: string
  color: string
  description?: string
}

/** Every result type id in the organization, most severe first — a partial list is rejected. */
export interface ResultTypeReorderRequest {
  orderedIds: string[]
}

// ── Procedures (procedure-service /api/v1/procedure-templates) ───────────────
// Called "Procedures" everywhere the user can see. The backend calls the same
// thing a procedure template, and these mirror its DTOs field for field.
//
// The model to hold in your head: a ProcedureTemplate is identity only — name,
// description, status — and carries no questions. The questions live in
// versions. A template has any number of PUBLISHED versions, which are frozen
// forever, and at most one DRAFT, which is the only thing that can be edited.
// Publishing freezes the draft; editing a published procedure means opening a
// new draft from one of its versions.

/** The template's own lifecycle. Draft and published belong to the version, not here. */
export type ProcedureStatus = 'ACTIVE' | 'ARCHIVED'

export type VersionState = 'DRAFT' | 'PUBLISHED' | 'ARCHIVED'

export type VersionOrigin = 'AUTHORED' | 'MIGRATED' | 'IMPORTED' | 'GLOBAL_PUSH'

/**
 * What an item in a procedure document is.
 *
 * Deliberately not the `QuestionType` at the top of this file. That one belongs
 * to the generic inspection-run model the SPA still renders, and its values are
 * different ones — `BOOLEAN`, `SINGLE_CHOICE`, `NUMBER`. Both sets exist until
 * the integration branch retires the old model.
 *
 * `SECTION` sits in the same list as the questions rather than wrapping them: a
 * section is an item that happens to be a heading, which is what lets a
 * document be one flat list.
 */
export type ItemType =
  // a heading — carries text and nothing else
  | 'SECTION'
  // choice: the options carry the result
  | 'YES_NO'
  | 'YES_NO_NA'
  | 'RADIO'
  | 'CHECKBOX'
  | 'DROPDOWN'
  // input
  | 'TEXT'
  | 'INTEGER'
  // media
  | 'IMAGE'
  | 'MULTI_IMAGE'
  | 'AUDIO'
  | 'VIDEO'
  | 'DOCUMENT'

/** Where a question came from. Null means MANUAL — the common case costs nothing. */
export type ItemSource = 'MANUAL' | 'DOCUMENT' | 'TEMPLATE' | 'SUGGESTED'

/**
 * One answer a choice question offers.
 *
 * `result` is a result-type key — `PASS`, `FAIL`, or whatever else the
 * organization has defined — not a literal, so an organization that adds Amber
 * needs no migration. Null for an answer that decides nothing, such as "Not
 * applicable".
 *
 * `key` is minted by the server and is what a follow-up's `when` points at, so
 * an option keeps its meaning when the author rewords it. Omit it when adding
 * an option; send it back unchanged when editing one.
 *
 * `score` is the points this answer is worth. Zero is a real score — the
 * normal way to spell "this is the wrong answer" — and is kept in the
 * canonical form for that reason; an answer with no score at all is different
 * from one scoring zero, so an empty points field must send `undefined`, never
 * `0`. `excludeFromScoring` is the not-applicable answer: it earns no points
 * and does not count towards the total either, so choosing it cannot drag a
 * score down.
 */
export interface DefinitionOption {
  key?: string
  label: string
  result?: string | null
  score?: number | null
  excludeFromScoring: boolean
}

/**
 * How a question's follow-ups contribute to its result and its score. Absent
 * means INDEPENDENT — the common case, so it costs no bytes. As with
 * `Threshold.scope`, sending the literal `'INDEPENDENT'` is not the same as
 * omitting the field: the canonicaliser keeps any non-blank text. Omit
 * `followRollup` rather than send `'INDEPENDENT'` explicitly.
 */
export type Rollup = 'INDEPENDENT' | 'WORST' | 'AVERAGE'

/**
 * One entry in the document: a section, a question, or a follow-up question.
 *
 * `key` is minted by the server on first save and never changes after that,
 * which is what lets a diff say "q7 was reworded" instead of "one question
 * vanished and another appeared". Omit it when adding an item; send it back
 * unchanged when editing one.
 *
 * Most fields apply to some types and not others — `options` only to choice
 * types, `unit`/`min`/`max`/`rules` only to INTEGER, and a SECTION uses almost none of
 * them. The shape does not express that; the server's validator enforces it.
 */
export interface DefinitionItem {
  key?: string
  /** A question, or a section's heading. */
  text: string
  /** Shown under the question while answering. */
  help?: string | null
  type: ItemType
  /** Submit is blocked until this is answered. Never set on a section. */
  required: boolean
  /**
   * A failure here fails the whole inspection, whatever the score says. Never
   * set on a section: a heading is never answered, so it can never fail.
   */
  critical: boolean
  /**
   * An answer does not count until at least one piece of evidence, such as a
   * photo, is attached. A rule, not a question type, so a Yes / No question can
   * demand one. Never set on a section: a heading is never answered. Absent
   * reads false.
   */
  evidenceRequired?: boolean
  /** Choice types only; seeded for YES_NO and YES_NO_NA. */
  options: DefinitionOption[]
  /** INTEGER only — shown beside the field, e.g. "psi". */
  unit?: string | null
  /** INTEGER only. Inclusive. */
  min?: number | null
  max?: number | null
  /** Raise a work order when this answer fails. */
  workOrder: boolean
  /** Which alert profile that work order uses. A reference to a later feature. */
  alertProfile?: string | null
  source?: ItemSource | null
  /** The standard this came from, e.g. "NFPA 10". */
  standard?: string | null
  /**
   * The parent option key that makes this item appear. Set on follow-ups and on
   * nothing else — a top-level item is always shown.
   */
  when?: string | null
  /**
   * How much this counts against its siblings. On a SECTION it weights the
   * whole group; on a question it weights that question within its group.
   * Absent means 1 — an unweighted procedure scores everything equally.
   */
  weight?: number | null
  /** How this item's follow-ups contribute. Absent means INDEPENDENT. */
  followRollup?: Rollup | null
  /** Questions shown only when this one is answered a particular way. Nests to any depth. */
  follow: DefinitionItem[]
  /**
   * INTEGER only. What a reading means: bands mapping a range to a result type.
   * Absent means the reading is recorded and decides nothing. `min`/`max` above
   * bound what may be typed; these say what the typed value means.
   */
  rules?: RangeRule[]
}

/**
 * A band of readings — "12 to 15 is Amber". A missing `min` is "anything up
 * to `max`", a missing `max` "anything from `min`". Both ends are inclusive
 * and bands share no number: after one ending at 11 the next starts at 12.
 */
export interface RangeRule {
  min?: number | null
  max?: number | null
  /** A result-type key, the same vocabulary an option's `result` uses. */
  result?: string | null
}

/** What a {@link Threshold} is measured against. Absent means VERSION — the common case, so it costs no bytes. */
export type Scope = 'VERSION' | 'SECTION'

/**
 * A band of score mapping to a result type — "90 to 100 is a Pass".
 *
 * Open-ended at both ends, the same convention as {@link RangeRule}: a missing
 * `min` is "anything up to `max`", a missing `max` is "anything from `min`".
 *
 * `scope` absent (VERSION) reads the whole inspection's score, the common
 * case. `SECTION` reads a single section's own score against the same set of
 * bands, shared across every section rather than targeted at one — there is
 * no field naming which section, because there is only one shared scale.
 * Sending the literal string `'VERSION'` is not the same as omitting `scope`:
 * the canonicaliser drops `null`/absent but keeps any non-blank text, so an
 * explicit `'VERSION'` would change the version's hash. Omit it for the
 * common case; send `'SECTION'` only when it is genuinely section-scoped.
 */
export interface Threshold {
  scope?: Scope | null
  min?: number | null
  max?: number | null
  result?: string | null
}

/**
 * The whole form, as one flat list.
 *
 * Array order is the display order — there is no order field, so two items
 * cannot claim the same position.
 */
export interface DefinitionDocument {
  schema: number
  items: DefinitionItem[]
  /** Score bands that turn a final percentage into a result type. Empty means scoring is not configured. */
  thresholds: Threshold[]
  /**
   * What the procedure applies to. Absent or empty means anything — every
   * version written before this existed.
   */
  targetTypes?: TargetType[]
  /**
   * The reference documents this version cites, by library id. Never a name,
   * size or location: those resolve live from the library, so renaming a
   * document reaches every version citing it.
   */
  documents?: DocumentRef[]
}

/**
 * One row of the reference-document library: a standard's extract, a
 * manufacturer's sheet. The bytes live behind the storage port; `location` is
 * an opaque key to them, never a URL, because a link expires and a fresh one is
 * asked for each time it is needed.
 */
export interface ProcedureDocument {
  id: string
  /** Absent means organization-wide, visible from every property. Set means that property only. */
  propertyId?: string | null
  name: string
  mimeType?: string | null
  sizeBytes?: number | null
  location: string
  active: boolean
  uploadedBy?: string | null
  uploadedAt?: string | null
}

/** One citation of a library document: the whole procedure, or one question. */
export interface DocumentRef {
  id: string
  /** The question it hangs off. Absent means the whole procedure. */
  questionKey?: string | null
}

/** One key from one of the property-vocabulary lists the procedure applies to. */
export interface TargetType {
  kind: TargetKind
  key: string
}

export type TargetKind = 'HIERARCHY_LEVEL' | 'LOCATION_TYPE' | 'ASSET_CLASS' | 'ASSET_TAG'

/**
 * **Every optional field below arrives absent, not null.**
 *
 * The services set `default-property-inclusion: non_null`, so Jackson omits a
 * null field rather than sending `"field": null`. A field typed `number | null`
 * therefore reads as `undefined`, and `=== null` never matches it. Compare
 * these with `== null` / `!= null`, which catch both.
 */
export interface ProcedureTemplate {
  id: string
  orgId: string
  name: string
  description?: string | null
  consumerKey?: string | null
  status: ProcedureStatus
  /** Absent until something is published — which is how you tell a procedure is usable. */
  currentPublishedVersionId?: string | null
  currentPublishedVersionNo?: number | null
  /** Absent when no draft is open. */
  draftVersionNo?: number | null
  createdBy: string
  createdAt: string
  updatedAt: string
}

/** A version without its document, for history lists. */
export interface TemplateVersionSummary {
  id: string
  versionNo: number
  state: VersionState
  origin: VersionOrigin
  /** SHA-256 of the canonical document. Absent on a draft: only publishing sets it. */
  definitionHash?: string | null
  changeNote?: string | null
  createdBy: string
  createdAt: string
  publishedBy?: string | null
  publishedAt?: string | null
  /** Optimistic lock. Send the value you last read back on save, or you get a 409. */
  rowVersion: number
}

export interface TemplateVersion extends TemplateVersionSummary {
  templateId: string
  definition: DefinitionDocument
}

/**
 * `newVersion` is false when the draft matched a version that already existed:
 * nothing was created, the draft was discarded, and `version` is the one that
 * is now current. Publishing an unchanged draft is a no-op, not an error.
 */
export interface PublishResult {
  newVersion: boolean
  version: TemplateVersion
}

export type DiffKind = 'ADDED' | 'REMOVED' | 'MODIFIED'

/**
 * One item that changed.
 *
 * `parentKey` is the section or question this one hangs under, or null at the
 * top level — which is what makes "moved" meaningful: a question dragged into a
 * different section comes back as MODIFIED with `parent` in `changedFields`.
 *
 * changedFields: 'text', 'help', 'type', 'required', 'options', 'unit', 'min',
 * 'max', 'workOrder', 'alertProfile', 'standard', 'when', 'parent'.
 */
export interface ItemChange {
  key: string
  kind: DiffKind
  text: string
  parentKey: string | null
  changedFields: string[]
}

/**
 * Matched on stable keys, so a reworded question is a modification rather than
 * a removal plus an addition. One list, not two: sections and questions live in
 * one list in the document, so they do here too.
 *
 * `orderChanged` only fires on a genuine reshuffle — inserting one at the top
 * does not mark everything below it as moved.
 */
export interface DefinitionDiff {
  identical: boolean
  orderChanged: boolean
  /** What the procedure applies to differs between the two versions. */
  targetTypesChanged?: boolean
  /** Which documents are cited, or which question each hangs off, differs between the two versions. */
  documentsChanged?: boolean
  items: ItemChange[]
}

export interface ProcedureDiff {
  fromVersionNo: number
  toVersionNo: number
  diff: DefinitionDiff
}

export interface CreateProcedureRequest {
  name: string
  description?: string
  consumerKey?: string
  /** Optional: omitting it creates an empty draft v1. */
  definition?: DefinitionDocument
}

/** Identity only — name and description are not versioned. */
export interface UpdateProcedureRequest {
  name: string
  description?: string
}

export interface SaveDraftRequest {
  definition: DefinitionDocument
  rowVersion: number
  changeNote?: string
}

export interface NewDraftRequest {
  fromVersionNo: number
}

export interface CloneProcedureRequest {
  name: string
  /** Defaults to the current published version, else the draft. */
  fromVersionNo?: number
}

/**
 * A property (VDMS) the signed-in user may open. `id` is what goes in the
 * X-Sclera-Property header; `code` is a label such as VDMS001, unique only
 * within the organization.
 */
export interface Property {
  id: string
  code: string
}

/**
 * What the signed-in user may do with procedures and result types — the only
 * way the SPA can know, since "role" means an OpenFGA organization relation
 * and Keycloak defines no realm roles. Fetched once with the session.
 */
export interface Permissions {
  canViewProcedures: boolean
  canManageTemplates: boolean
  canPublishTemplates: boolean
  canManageResultTypes: boolean
}

// ── Evaluation (feature 7) ────────────────────────────────────────────────
// Mirrors EvaluationDtos.EvaluationResponse field for field. Result-type keys
// only, never names or colours — those resolve live from the organization's
// own result types, the same rule an option's `result` already follows.

/** Which version was evaluated. A draft is evaluated too — that is what a running-score preview needs. */
export interface EvaluationVersionRef {
  templateId: string
  versionNo: number
  state: VersionState
}

/**
 * The inspection as a whole. `percentage` is absent when nothing scorable has
 * been answered yet — not 0, which would read the bottom band and call an
 * inspection that has barely started a failure. `complete` is true only when
 * every reachable question has been answered.
 */
export interface OverallVerdict {
  result?: string | null
  percentage?: number | null
  answered: number
  scored: number
  complete: boolean
}

/** One section's own result and percentage, or the questions before the first heading (key/text absent). */
export interface SectionVerdict {
  key?: string | null
  text?: string | null
  result?: string | null
  percentage?: number | null
}

/**
 * One reachable, answered question. `counted: false` means a follow-up whose
 * result and score folded into its parent (`WORST`/`AVERAGE`) — still listed,
 * so the preview can show what it said even though it is not totalled on its
 * own.
 */
export interface QuestionVerdict {
  key: string
  result?: string | null
  score?: number | null
  possible?: number | null
  counted: boolean
  reason?: string | null
}

/** A failed answer on a question flagged to raise a work order. */
export interface WorkOrderVerdict {
  key: string
  alertProfile?: string | null
}

export interface EvaluationResponse {
  version: EvaluationVersionRef
  overall: OverallVerdict
  sections: SectionVerdict[]
  questions: QuestionVerdict[]
  /** Critical questions whose failure decided the overall result. */
  critical: string[]
  workOrders: WorkOrderVerdict[]
  /** Reachable questions not answered yet. */
  unanswered: string[]
  /** Answers given for a question that is not currently showing. */
  ignored: string[]
}

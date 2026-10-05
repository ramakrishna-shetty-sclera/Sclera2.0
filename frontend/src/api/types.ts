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
 */
export interface DefinitionOption {
  key?: string
  label: string
  result?: string | null
}

/**
 * One entry in the document: a section, a question, or a follow-up question.
 *
 * `key` is minted by the server on first save and never changes after that,
 * which is what lets a diff say "q7 was reworded" instead of "one question
 * vanished and another appeared". Omit it when adding an item; send it back
 * unchanged when editing one.
 *
 * Most fields apply to some types and not others — `options` only to choice
 * types, `unit`/`min`/`max` only to INTEGER, and a SECTION uses almost none of
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
  /** Questions shown only when this one is answered a particular way. Nests to any depth. */
  follow: DefinitionItem[]
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
}

export interface ProcedureTemplate {
  id: string
  orgId: string
  name: string
  description: string | null
  consumerKey: string | null
  status: ProcedureStatus
  /** Null until something is published — which is how you tell a procedure is usable. */
  currentPublishedVersionId: string | null
  currentPublishedVersionNo: number | null
  /** Null when no draft is open. */
  draftVersionNo: number | null
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
  /** SHA-256 of the canonical document. Null on a draft: only publishing sets it. */
  definitionHash: string | null
  changeNote: string | null
  createdBy: string
  createdAt: string
  publishedBy: string | null
  publishedAt: string | null
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

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
 * One question. `key` is minted by the server on first save and never changes
 * after that, which is what lets a diff say "q7 was reworded" instead of
 * "one question vanished and another appeared". Omit it when adding a question;
 * send it back unchanged when editing one.
 *
 * There are no options yet: feature 4 adds them, so a SINGLE_CHOICE question
 * currently has nothing to choose from.
 */
export interface DefinitionQuestion {
  key?: string
  text: string
  helpText?: string | null
  type: QuestionType
  required: boolean
}

export interface DefinitionCategory {
  key?: string
  name: string
  questions: DefinitionQuestion[]
}

/** The whole form. Array order is the display order — there is no order field. */
export interface DefinitionDocument {
  schema: number
  categories: DefinitionCategory[]
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

/** changedFields: 'name', 'questionOrder'. */
export interface CategoryChange {
  key: string
  kind: DiffKind
  name: string
  changedFields: string[]
}

/** changedFields: 'text', 'helpText', 'type', 'required', 'category'. */
export interface QuestionChange {
  key: string
  kind: DiffKind
  text: string
  categoryKey: string
  changedFields: string[]
}

/**
 * Matched on stable keys, so a reworded question is a modification rather than
 * a removal plus an addition. `categoryOrderChanged` and `questionOrder` only
 * fire on a genuine reshuffle — inserting at the top does not mark everything
 * below it as moved.
 */
export interface DefinitionDiff {
  identical: boolean
  categoryOrderChanged: boolean
  categories: CategoryChange[]
  questions: QuestionChange[]
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

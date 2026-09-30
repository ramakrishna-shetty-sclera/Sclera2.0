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

export interface QuestionRequest {
  text: string
  helpText?: string
  type: QuestionType
  required: boolean
  displayOrder: number
  options?: string[]
  scoreWeight?: number
}

export interface SectionRequest {
  title: string
  displayOrder: number
  questions: QuestionRequest[]
}

export interface TemplateRequest {
  name: string
  description?: string
  category?: string
  sections: SectionRequest[]
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

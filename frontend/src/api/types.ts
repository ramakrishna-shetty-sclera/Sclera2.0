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

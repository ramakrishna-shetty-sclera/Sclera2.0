import { apiFetch, apiUpload, type ApiResult } from './client'
import type { ItemType, TemplateVersion } from './types'

// ── AI question generation (procedure-service, behind a feature flag) ────────
// Two ways to generate candidate questions — from an uploaded document, or by
// chatting over the draft — and ONE way into the procedure: the import call,
// which appends the selected candidates to the open draft through the same
// validate-and-mint pipeline a hand edit uses.
//
// The whole feature is behind AI_QUESTION_GENERATION_ENABLED on the service;
// with it off every endpoint here is a plain 404, which is how the editor
// decides whether to show the panel at all.
//
// The gateway must route /api/v1/ai/** to the procedure service (see the route
// table in the root README) — /api/v1/procedure-templates/** already routes.

const PROCEDURES = '/api/v1/procedure-templates'
const AI = '/api/v1/ai'

export type AiDocumentStatus = 'PENDING' | 'EXTRACTING' | 'READY' | 'FAILED'
export type GenerationJobStatus = 'QUEUED' | 'RUNNING' | 'COMPLETED' | 'FAILED'
export type QuestionDraftStatus = 'DRAFT' | 'IMPORTED' | 'DISCARDED'
export type QuestionDraftSource = 'DOCUMENT' | 'CHAT'

/**
 * One candidate question, in its reviewable key-less shape. Deliberately not a
 * `DefinitionItem`: keys are minted only at import, a follow-up names its
 * trigger by option *index* because that option has no key yet either, and
 * follow-ups go one level deep at most.
 */
export interface CandidateQuestion {
  text: string
  help?: string | null
  type: ItemType
  required: boolean
  options: CandidateOption[]
  unit?: string | null
  min?: number | null
  max?: number | null
  rules: CandidateBand[]
  standard?: string | null
  followUps: CandidateFollowUp[]
}

export interface CandidateOption {
  label: string
  result?: string | null
}

export interface CandidateBand {
  min?: number | null
  max?: number | null
  result?: string | null
}

export interface CandidateFollowUp {
  /** Zero-based index into the parent's `options`. */
  whenOptionIndex: number
  text: string
  help?: string | null
  type: ItemType
  required: boolean
  options: CandidateOption[]
  unit?: string | null
  min?: number | null
  max?: number | null
  rules: CandidateBand[]
  standard?: string | null
}

/** A question the organization already has that looks like a candidate. */
export interface SimilarQuestion {
  templateId: string
  templateName: string
  questionKey: string
  text: string
}

export interface QuestionDraft {
  id: string
  source: QuestionDraftSource
  sourceRef: string
  /** The document phrase this came from, or the chat ask it answers. */
  sourceSpan?: string | null
  turnNo?: number | null
  question: CandidateQuestion
  /** 0..1, the model's own confidence. Advisory. */
  confidence?: number | null
  /** Set when the organization already has a question like this one. */
  similarExisting?: SimilarQuestion | null
  status: QuestionDraftStatus
  /** The question key the import minted; proof the candidate is in the draft. */
  importedKey?: string | null
  importedAt?: string | null
  createdAt: string
}

/** One inspectable topic the LLM found in a document, with its source quote. */
export interface DocumentTopic {
  title: string
  standard?: string | null
  quote?: string | null
}

export interface DocumentTopicGroup {
  title: string
  topics: DocumentTopic[]
}

export interface AiDocument {
  id: string
  filename: string
  mediaType: string
  sizeBytes: number
  status: AiDocumentStatus
  error?: string | null
  /** PENDING = topics never requested; READY = `topics` is present. */
  topicsStatus: AiDocumentStatus
  topics?: { groups: DocumentTopicGroup[] } | null
  topicsError?: string | null
  createdAt: string
}

export interface GenerationJob {
  id: string
  templateId: string
  documentId: string
  status: GenerationJobStatus
  error?: string | null
  model?: string | null
  inputTokens: number
  outputTokens: number
  createdAt: string
  completedAt?: string | null
  /** Present once COMPLETED; empty before. */
  candidates: QuestionDraft[]
}

export interface ChatSession {
  id: string
  templateId: string
  status: 'ACTIVE' | 'CLOSED'
  createdAt: string
}

export interface ChatTurn {
  sessionId: string
  reply: string
  /** The session's live candidates after this turn. */
  candidates: QuestionDraft[]
}

export interface ImportResult {
  imported: { candidateId: string; key: string }[]
  /** Skipped because an earlier import already placed them — a retry, not an error. */
  alreadyImported: string[]
  /** The draft as it now stands, with the new items and their minted keys. */
  draft: TemplateVersion
}

// --- Feature 1: document → questions ---

/** PDF, DOCX or TXT. Text extraction runs in the background; poll until READY. */
export function uploadAiDocument(file: File): Promise<AiDocument> {
  const form = new FormData()
  form.append('file', file)
  return apiUpload<AiDocument>(`${AI}/documents`, form).then((r) => r.data)
}

export function getAiDocument(id: string): Promise<AiDocument> {
  return apiFetch<AiDocument>(`${AI}/documents/${id}`).then((r) => r.data)
}

/** The organization's document library, newest first — stored for reuse and re-generation. */
export function listAiDocuments(): Promise<AiDocument[]> {
  return apiFetch<AiDocument[]>(`${AI}/documents`).then((r) => r.data)
}

/**
 * Ask for the document's selectable topics (cached after the first run). Poll
 * the document until topicsStatus is READY, then pass picked topics to
 * `generateFromDocument`.
 */
export function extractTopics(documentId: string): Promise<AiDocument> {
  return apiFetch<AiDocument>(`${AI}/documents/${documentId}/topics`, { method: 'POST' }).then(
    (r) => r.data,
  )
}

/** Starts an async generation; poll the returned job until COMPLETED. */
export function generateFromDocument(
  templateId: string,
  body: {
    documentId: string
    instructions?: string
    model?: string
    /** Narrows generation to these author-selected topics; omit for the whole document. */
    topics?: DocumentTopic[]
  },
): Promise<GenerationJob> {
  return apiFetch<GenerationJob>(`${PROCEDURES}/${templateId}/generate/document`, {
    method: 'POST',
    body,
  }).then((r) => r.data)
}

export function getGenerationJob(jobId: string): Promise<GenerationJob> {
  return apiFetch<GenerationJob>(`${AI}/generation-jobs/${jobId}`).then((r) => r.data)
}

// --- Feature 2: chat → questions ---

export function createChatSession(templateId: string): Promise<ChatSession> {
  return apiFetch<ChatSession>(`${PROCEDURES}/${templateId}/chat`, { method: 'POST' }).then(
    (r) => r.data,
  )
}

/** One turn: the author's message in, the reply and the updated candidates out. */
export function sendChatMessage(
  sessionId: string,
  message: string,
  model?: string,
): Promise<ChatTurn> {
  return apiFetch<ChatTurn>(`${AI}/chat/${sessionId}/messages`, {
    method: 'POST',
    body: { message, model },
  }).then((r) => r.data)
}

// --- review and the one shared import path ---

export function listQuestionDrafts(
  templateId: string,
  status?: QuestionDraftStatus,
): Promise<ApiResult<QuestionDraft[]>> {
  return apiFetch<QuestionDraft[]>(`${PROCEDURES}/${templateId}/question-drafts`, {
    query: { status },
  })
}

/**
 * Appends the selected candidates to the open draft. Idempotent: candidates
 * already imported come back in `alreadyImported` rather than duplicating.
 * The caller must make sure its own unsaved edits are saved first — the server
 * rewrites the whole draft document.
 */
export function importQuestions(templateId: string, candidateIds: string[]): Promise<ImportResult> {
  return apiFetch<ImportResult>(`${PROCEDURES}/${templateId}/questions/import`, {
    method: 'POST',
    body: { candidateIds },
  }).then((r) => r.data)
}

export function discardQuestionDraft(templateId: string, candidateId: string): Promise<QuestionDraft> {
  return apiFetch<QuestionDraft>(
    `${PROCEDURES}/${templateId}/question-drafts/${candidateId}/discard`,
    { method: 'POST' },
  ).then((r) => r.data)
}

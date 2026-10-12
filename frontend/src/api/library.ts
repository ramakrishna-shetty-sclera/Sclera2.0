import { apiFetch, type ApiResult } from './client'
import type { GlobalTemplateDetail, GlobalTemplateSummary, QuestionBankEntry } from './types'

// ── The Sclera-wide library (feature 12) ──────────────────────────────────────
// Read-only: browse and search what Sclera publishes, and its cross-template
// question bank. Importing a template is in templates.ts (importProcedure),
// since it acts on the caller's own procedures.

const LIBRARY = '/api/v1/global-procedure-templates'

export function browseLibrary(params: {
  search?: string
  consumer?: string
  page?: number
  size?: number
} = {}): Promise<ApiResult<GlobalTemplateSummary[]>> {
  return apiFetch<GlobalTemplateSummary[]>(LIBRARY, { query: params })
}

/** The current version's full document, for a read-only preview before importing. */
export function getLibraryTemplate(id: string): Promise<GlobalTemplateDetail> {
  return apiFetch<GlobalTemplateDetail>(`${LIBRARY}/${id}`).then((r) => r.data)
}

export function searchQuestionBank(params: {
  search?: string
  standard?: string
  page?: number
  size?: number
} = {}): Promise<ApiResult<QuestionBankEntry[]>> {
  return apiFetch<QuestionBankEntry[]>(`${LIBRARY}/questions`, { query: params })
}

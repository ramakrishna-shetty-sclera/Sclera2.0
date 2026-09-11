import { useEffect, useMemo, useState } from 'react'
import { useParams } from 'react-router-dom'
import {
  cancelInspection,
  completeInspection,
  getInspection,
  startInspection,
  submitAnswers,
} from '../api/inspections'
import type { AnswerSubmission, Inspection, Question } from '../api/types'
import { StatusBadge } from '../components/StatusBadge'
import { QuestionInput } from '../components/QuestionInput'

interface DraftAnswer {
  value: unknown
  comment: string
}

function hasValue(value: unknown): boolean {
  if (value === null || value === undefined) return false
  if (typeof value === 'string') return value.trim() !== ''
  if (Array.isArray(value)) return value.length > 0
  return true
}

export function InspectionRunPage() {
  const { id } = useParams<{ id: string }>()
  const [inspection, setInspection] = useState<Inspection | null>(null)
  const [drafts, setDrafts] = useState<Record<string, DraftAnswer>>({})
  const [error, setError] = useState<string | null>(null)
  const [notice, setNotice] = useState<string | null>(null)
  const [busy, setBusy] = useState(false)

  function applyInspection(i: Inspection) {
    setInspection(i)
    const next: Record<string, DraftAnswer> = {}
    for (const a of i.answers) {
      next[a.questionId] = { value: a.value, comment: a.comment ?? '' }
    }
    setDrafts(next)
  }

  useEffect(() => {
    if (!id) return
    getInspection(id).then(applyInspection).catch((e) => setError(e.message))
  }, [id])

  const sections = useMemo(() => {
    const snapshot = inspection?.templateSnapshot
    if (!snapshot?.sections) return []
    return [...snapshot.sections]
      .sort((a, b) => a.displayOrder - b.displayOrder)
      .map((s) => ({
        ...s,
        questions: [...s.questions].sort((a, b) => a.displayOrder - b.displayOrder),
      }))
  }, [inspection])

  const allQuestions: Question[] = useMemo(() => sections.flatMap((s) => s.questions), [sections])

  if (!inspection) {
    return error ? <div className="alert alert-error">{error}</div> : <p className="muted">Loading…</p>
  }

  const editable = inspection.status === 'IN_PROGRESS'
  const answeredCount = allQuestions.filter((q) => hasValue(drafts[q.id]?.value)).length

  function collectAnswers(): AnswerSubmission[] {
    return allQuestions
      .filter((q) => hasValue(drafts[q.id]?.value))
      .map((q) => {
        const d = drafts[q.id]
        return {
          questionId: q.id,
          value: d.value,
          comment: d.comment.trim() || undefined,
        }
      })
  }

  async function run(action: () => Promise<Inspection>, successMessage?: string) {
    setBusy(true)
    setError(null)
    setNotice(null)
    try {
      applyInspection(await action())
      if (successMessage) setNotice(successMessage)
    } catch (e) {
      setError(e instanceof Error ? e.message : 'Action failed')
    } finally {
      setBusy(false)
    }
  }

  const save = () => run(() => submitAnswers(inspection.id, collectAnswers()), 'Answers saved.')

  const complete = () =>
    run(async () => {
      const answers = collectAnswers()
      if (answers.length > 0) await submitAnswers(inspection.id, answers)
      return completeInspection(inspection.id)
    }, 'Inspection completed.')

  return (
    <div>
      <div className="page-head">
        <div>
          <h1>{inspection.templateName}</h1>
          <p className="muted">
            <StatusBadge status={inspection.status} /> · template v{inspection.templateVersion}
            {inspection.scheduledFor
              ? ` · scheduled ${new Date(inspection.scheduledFor).toLocaleString()}`
              : ''}
            {inspection.startedAt ? ` · started ${new Date(inspection.startedAt).toLocaleString()}` : ''}
            {inspection.completedAt
              ? ` · completed ${new Date(inspection.completedAt).toLocaleString()}`
              : ''}
          </p>
        </div>
        <div className="page-actions">
          {inspection.status === 'DRAFT' && (
            <button
              className="btn btn-primary"
              disabled={busy}
              onClick={() => run(() => startInspection(inspection.id), 'Inspection started.')}
            >
              Start inspection
            </button>
          )}
          {editable && (
            <>
              <button className="btn" disabled={busy} onClick={save}>
                Save answers
              </button>
              <button className="btn btn-primary" disabled={busy} onClick={complete}>
                Complete
              </button>
            </>
          )}
          {(inspection.status === 'DRAFT' || inspection.status === 'IN_PROGRESS') && (
            <button
              className="btn btn-danger"
              disabled={busy}
              onClick={() => {
                if (confirm('Cancel this inspection?')) {
                  run(() => cancelInspection(inspection.id), 'Inspection cancelled.')
                }
              }}
            >
              Cancel inspection
            </button>
          )}
        </div>
      </div>

      {error && <div className="alert alert-error">{error}</div>}
      {notice && <div className="alert alert-ok">{notice}</div>}
      {inspection.notes && (
        <p className="muted">
          <strong>Notes:</strong> {inspection.notes}
        </p>
      )}

      {inspection.status === 'DRAFT' && (
        <p className="muted">Start the inspection to begin answering questions.</p>
      )}

      {allQuestions.length > 0 && (
        <p className="muted small">
          {answeredCount} of {allQuestions.length} questions answered
        </p>
      )}

      {sections.map((section) => (
        <div className="card" key={section.id}>
          <h2>{section.title}</h2>
          {section.questions.map((q) => (
            <div className="question-block" key={q.id}>
              <div className="q-text">
                {q.text} {q.required && <span className="req">*</span>}
              </div>
              {q.helpText && <div className="muted small">{q.helpText}</div>}
              <QuestionInput
                question={q}
                value={drafts[q.id]?.value ?? null}
                disabled={!editable}
                onChange={(value) =>
                  setDrafts((prev) => ({
                    ...prev,
                    [q.id]: { value, comment: prev[q.id]?.comment ?? '' },
                  }))
                }
              />
              {(editable || drafts[q.id]?.comment) && (
                <input
                  className="comment-input"
                  placeholder="Comment (optional)"
                  value={drafts[q.id]?.comment ?? ''}
                  disabled={!editable}
                  maxLength={2000}
                  onChange={(e) =>
                    setDrafts((prev) => ({
                      ...prev,
                      [q.id]: { value: prev[q.id]?.value ?? null, comment: e.target.value },
                    }))
                  }
                />
              )}
            </div>
          ))}
        </div>
      ))}
    </div>
  )
}

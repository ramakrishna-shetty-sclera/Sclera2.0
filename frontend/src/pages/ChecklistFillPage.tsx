import { useEffect, useMemo, useState } from 'react'
import { useNavigate, useParams } from 'react-router-dom'
import {
  checkInChecklist,
  checklistStatusLabel,
  CHECKLIST_STATUS_CLASS,
  createChecklistWorkOrder,
  deleteChecklist,
  exceptionChecklist,
  getChecklist,
  markIncompleteChecklist,
  reopenChecklist,
  saveChecklist,
  submitChecklist,
  updateChecklistAssignee,
  type AnswerInput,
  type Checklist,
} from '../api/checklists'
import { getTemplate } from '../api/templates'
import type { Question, QuestionTemplate } from '../api/types'
import { QuestionInput } from '../components/QuestionInput'

interface Draft {
  value: unknown
  failed: boolean
  comment: string
}

function parseValue(raw: string | null): unknown {
  if (raw == null) return null
  try {
    return JSON.parse(raw)
  } catch {
    return raw
  }
}

export function ChecklistFillPage() {
  const { id } = useParams<{ id: string }>()
  const navigate = useNavigate()
  const [checklist, setChecklist] = useState<Checklist | null>(null)
  const [template, setTemplate] = useState<QuestionTemplate | null>(null)
  const [drafts, setDrafts] = useState<Record<string, Draft>>({})
  const [error, setError] = useState<string | null>(null)
  const [notice, setNotice] = useState<string | null>(null)
  const [busy, setBusy] = useState(false)

  function hydrate(c: Checklist) {
    setChecklist(c)
    const next: Record<string, Draft> = {}
    for (const a of c.answers) {
      next[a.questionId] = { value: parseValue(a.value), failed: a.failed, comment: a.comment ?? '' }
    }
    setDrafts(next)
  }

  useEffect(() => {
    if (!id) return
    getChecklist(id)
      .then((c) => {
        hydrate(c)
        return getTemplate(c.procedureId)
      })
      .then(setTemplate)
      .catch((e) => setError(e.message))
  }, [id])

  const sections = useMemo(() => {
    if (!template?.sections) return []
    return [...template.sections]
      .sort((a, b) => a.displayOrder - b.displayOrder)
      .map((s) => ({ ...s, questions: [...s.questions].sort((a, b) => a.displayOrder - b.displayOrder) }))
  }, [template])

  const allQuestions: Question[] = useMemo(() => sections.flatMap((s) => s.questions), [sections])

  if (!checklist) {
    return error ? <div className="alert alert-error">{error}</div> : <p className="muted">Loading…</p>
  }

  const editable =
    checklist.status === 'TODO' && (!checklist.checkInRequired || checklist.checkInAt != null)
  const needsCheckIn = checklist.status === 'TODO' && checklist.checkInRequired && !checklist.checkInAt

  function collect(): AnswerInput[] {
    return allQuestions
      .filter((q) => drafts[q.id] !== undefined)
      .map((q) => {
        const d = drafts[q.id]
        return {
          questionId: q.id,
          value: JSON.stringify(d.value ?? null),
          failed: d.failed,
          comment: d.comment.trim() || undefined,
        }
      })
  }

  async function act(fn: () => Promise<Checklist>, msg?: string) {
    setBusy(true)
    setError(null)
    setNotice(null)
    try {
      hydrate(await fn())
      if (msg) setNotice(msg)
    } catch (e) {
      setError(e instanceof Error ? e.message : 'Action failed')
    } finally {
      setBusy(false)
    }
  }

  function patch(qid: string, p: Partial<Draft>) {
    setDrafts((prev) => ({
      ...prev,
      [qid]: { value: prev[qid]?.value ?? null, failed: prev[qid]?.failed ?? false, comment: prev[qid]?.comment ?? '', ...p },
    }))
  }

  const cl = checklist

  return (
    <div>
      <div className="page-head">
        <div>
          <h1>{cl.procedureName}</h1>
          <p className="muted">
            <span className={`badge ${CHECKLIST_STATUS_CLASS[cl.status]}`}>
              {checklistStatusLabel(cl.status)}
            </span>{' '}
            · {cl.configName}
            {cl.targetName ? ` · ${cl.targetType === 'ASSET' ? '🖥️' : '📍'} ${cl.targetName}` : ''}
            {cl.dueDate ? ` · due ${new Date(cl.dueDate).toLocaleDateString()}` : ''}
          </p>
          <p className="muted small">
            Assignee: {cl.assigneeEmail}
            {cl.checkInAt ? ` · checked in ${new Date(cl.checkInAt).toLocaleString()}` : ''}
            {cl.checkOutAt ? ` · checked out ${new Date(cl.checkOutAt).toLocaleString()}` : ''}
            {cl.workOrderIds.length ? ` · ${cl.workOrderIds.length} work order(s)` : ''}
          </p>
        </div>
        <button className="btn" onClick={() => navigate(-1)}>
          ← Back
        </button>
      </div>

      {error && <div className="alert alert-error">{error}</div>}
      {notice && <div className="alert alert-ok">{notice}</div>}
      {cl.status === 'EXCEPTION' && cl.exceptionReason && (
        <div className="alert alert-amber">Exception: {cl.exceptionReason}</div>
      )}

      {/* Action bar */}
      <div className="card action-bar">
        {needsCheckIn && (
          <button className="btn btn-primary" disabled={busy} onClick={() => act(() => checkInChecklist(cl.id), 'Checked in.')}>
            Check in
          </button>
        )}
        {editable && (
          <>
            <button className="btn" disabled={busy} onClick={() => act(() => saveChecklist(cl.id, collect(), false), 'Saved.')}>
              Just save
            </button>
            <button className="btn" disabled={busy} onClick={() => act(() => saveChecklist(cl.id, collect(), true), 'Saved & checked out.')}>
              Save & checkout
            </button>
            <button className="btn btn-primary" disabled={busy} onClick={() => act(() => submitChecklist(cl.id, collect()), 'Submitted.')}>
              Submit
            </button>
            <button
              className="btn"
              disabled={busy}
              onClick={() => {
                const reason = prompt('Reason for exception?')
                if (reason) act(() => exceptionChecklist(cl.id, reason), 'Moved to Exception.')
              }}
            >
              Add to exception
            </button>
            <button className="btn" disabled={busy} onClick={() => act(() => markIncompleteChecklist(cl.id), 'Marked incomplete.')}>
              Mark incomplete
            </button>
          </>
        )}
        {(cl.status === 'EXCEPTION' || cl.status === 'INCOMPLETE') && (
          <button className="btn btn-primary" disabled={busy} onClick={() => act(() => reopenChecklist(cl.id), 'Reopened to To-Do.')}>
            Reopen to To-Do
          </button>
        )}
        <button
          className="btn"
          disabled={busy}
          onClick={() => {
            const email = prompt('New assignee email?', cl.assigneeEmail)
            if (email) act(() => updateChecklistAssignee(cl.id, email), 'Assignee updated.')
          }}
        >
          Update assignee
        </button>
        <button
          className="btn"
          disabled={busy}
          onClick={() => {
            const note = prompt('Work order note (optional)?') ?? undefined
            act(() => createChecklistWorkOrder(cl.id, note), 'Work order created.')
          }}
        >
          Create work order
        </button>
        <button
          className="btn btn-danger"
          disabled={busy}
          onClick={() => confirm('Delete this checklist?') && deleteChecklist(cl.id).then(() => navigate(-1))}
        >
          Delete
        </button>
      </div>

      {needsCheckIn && <p className="muted">Check in to start filling this checklist.</p>}

      {sections.map((section) => (
        <div className="card" key={section.id}>
          <h2>{section.title}</h2>
          {section.questions.map((q) => (
            <div className={`question-block ${drafts[q.id]?.failed ? 'q-failed' : ''}`} key={q.id}>
              <div className="q-text">
                {q.text} {q.required && <span className="req">*</span>}
              </div>
              {q.helpText && <div className="muted small">{q.helpText}</div>}
              <QuestionInput
                question={q}
                value={drafts[q.id]?.value ?? null}
                disabled={!editable}
                onChange={(value) => patch(q.id, { value })}
              />
              <div className="q-extra">
                <label className="checkbox fail-toggle">
                  <input
                    type="checkbox"
                    checked={drafts[q.id]?.failed ?? false}
                    disabled={!editable}
                    onChange={(e) => patch(q.id, { failed: e.target.checked })}
                  />
                  Mark failed
                </label>
                {(editable || drafts[q.id]?.comment) && (
                  <input
                    className="comment-input"
                    placeholder="Comment (optional)"
                    value={drafts[q.id]?.comment ?? ''}
                    disabled={!editable}
                    maxLength={2000}
                    onChange={(e) => patch(q.id, { comment: e.target.value })}
                  />
                )}
              </div>
            </div>
          ))}
        </div>
      ))}

      <div className="card">
        <h2>History</h2>
        {cl.history.length === 0 ? (
          <p className="muted">No history yet.</p>
        ) : (
          <ul className="history-list">
            {[...cl.history].reverse().map((h, i) => (
              <li key={i}>
                <span className="history-action">{h.action}</span>
                <span className="muted small"> · {new Date(h.at).toLocaleString()}</span>
                {h.detail && <div className="muted small">{h.detail}</div>}
              </li>
            ))}
          </ul>
        )}
      </div>
    </div>
  )
}

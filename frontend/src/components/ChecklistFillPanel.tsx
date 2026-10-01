import { useEffect, useState } from 'react'
import {
  checkInChecklist,
  checklistSourceLabel,
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

/**
 * The full checklist filling experience (header, action bar, questions,
 * history). Used by the standalone /checklists/:id page and inline from the
 * Task Dashboard.
 *
 * onExit — called after delete; onChanged — called after any state change so
 * embedding views can refresh their lists.
 */
export function ChecklistFillPanel({
  checklistId,
  onExit,
  onChanged,
}: {
  checklistId: string
  onExit: () => void
  onChanged?: () => void
}) {
  const [checklist, setChecklist] = useState<Checklist | null>(null)
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
    getChecklist(checklistId)
      .then(hydrate)
      .catch((e) => setError(e.message))
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [checklistId])

  if (!checklist) {
    return error ? <div className="alert alert-error">{error}</div> : <p className="muted">Loading…</p>
  }

  const cl = checklist
  const editable = cl.status === 'TODO' && (!cl.checkInRequired || cl.checkInAt != null)
  const needsCheckIn = cl.status === 'TODO' && cl.checkInRequired && !cl.checkInAt

  /**
   * Returns the answers exactly as they were loaded.
   *
   * There is no form to collect from while the questions cannot be resolved,
   * and returning an empty list would make Save and Submit wipe whatever is
   * already stored. Not rendering the questions must not destroy the answers.
   */
  function collect(): AnswerInput[] {
    return Object.entries(drafts).map(([questionId, d]) => ({
      questionId,
      value: JSON.stringify(d.value ?? null),
      failed: d.failed,
      comment: d.comment.trim() || undefined,
    }))
  }

  async function act(fn: () => Promise<Checklist>, msg?: string) {
    setBusy(true)
    setError(null)
    setNotice(null)
    try {
      hydrate(await fn())
      if (msg) setNotice(msg)
      onChanged?.()
    } catch (e) {
      setError(e instanceof Error ? e.message : 'Action failed')
    } finally {
      setBusy(false)
    }
  }

  return (
    <div>
      <div className="fill-head">
        <h2>{cl.procedureName}</h2>
        <p className="muted">
          <span className={`badge ${CHECKLIST_STATUS_CLASS[cl.status]}`}>
            {checklistStatusLabel(cl.status)}
          </span>{' '}
          · {checklistSourceLabel(cl.source)} · {cl.configName}
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

      {error && <div className="alert alert-error">{error}</div>}
      {notice && <div className="alert alert-ok">{notice}</div>}
      {cl.status === 'EXCEPTION' && cl.exceptionReason && (
        <div className="alert alert-amber">Exception: {cl.exceptionReason}</div>
      )}

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
          onClick={() => {
            if (!confirm('Delete this checklist?')) return
            deleteChecklist(cl.id).then(() => {
              onChanged?.()
              onExit()
            })
          }}
        >
          Delete
        </button>
      </div>

      {needsCheckIn && <p className="muted">Check in to start filling this checklist.</p>}

      {/*
        The questions cannot be shown yet, and this says so rather than
        rendering the wrong ones.

        A checklist records which procedure it came from but not which VERSION,
        and a published version is immutable precisely so that a checklist keeps
        rendering the form it was actually filled against. Resolving the form
        through the procedure instead would show today's questions over
        yesterday's answers — the bug the versioned model exists to prevent.

        The pin (checklist.procedureVersionId) arrives with the inspection
        service's move to version pinning, and this block becomes the real
        question list then.
      */}
      <div className="card">
        <h2>Questions</h2>
        <p className="muted">
          This checklist does not yet record which version of{' '}
          <strong>{cl.procedureName}</strong> it was created from, so its questions cannot be
          shown without risking the wrong ones.
        </p>
        <p className="muted small">
          Answers already saved against it are kept untouched — saving and submitting from here do
          not change them. Authoring and version history are available on the Procedures screens.
        </p>
      </div>

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

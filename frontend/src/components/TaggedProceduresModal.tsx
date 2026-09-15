import { useEffect, useState, type FormEvent } from 'react'
import {
  addOneTimeProcedure,
  createTaggedProcedure,
  deleteTaggedProcedure,
  fillTaggedProcedure,
  listTaggedProcedures,
  type TaggedProcedureLink,
} from '../api/taggedProcedures'
import { listChecklists, type Checklist } from '../api/checklists'
import { listTemplates } from '../api/templates'
import type { QuestionTemplate } from '../api/types'
import type { TargetType } from '../api/inspectionTagging'
import { ChecklistFillPanel } from './ChecklistFillPanel'
import { Modal } from './Modal'

/**
 * The "Procedures" view of one managed asset/location (VDMS Tagged Procedure):
 * - reusable TEMPLATE links (tagged at Procedure level) — Fill spawns a fresh
 *   checklist each time, the template stays;
 * - "Add procedure" with a Reusable/One-time choice — One-time creates a single
 *   checklist immediately and stores nothing;
 * - this target's checklists, fillable inline.
 */
export function TaggedProceduresModal({
  targetType,
  targetId,
  targetName,
  onClose,
}: {
  targetType: TargetType
  targetId: string
  targetName: string
  onClose: () => void
}) {
  const [links, setLinks] = useState<TaggedProcedureLink[]>([])
  const [checklists, setChecklists] = useState<Checklist[]>([])
  const [procedures, setProcedures] = useState<QuestionTemplate[]>([])
  const [error, setError] = useState<string | null>(null)
  const [busy, setBusy] = useState(false)
  const [fillId, setFillId] = useState<string | null>(null)

  const [procedureId, setProcedureId] = useState('')
  const [mode, setMode] = useState<'TEMPLATE' | 'ONE_TIME'>('TEMPLATE')

  function reload() {
    listTaggedProcedures({ targetType, targetId }).then(setLinks).catch((e) => setError(e.message))
    listChecklists({ source: 'TAGGED_PROCEDURE' })
      .then((all) => setChecklists(all.filter((c) => c.targetId === targetId)))
      .catch(() => {})
  }

  useEffect(() => {
    reload()
    listTemplates({ status: 'PUBLISHED', size: 100 }).then((r) => setProcedures(r.data)).catch(() => {})
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [targetId])

  async function guard(fn: () => Promise<unknown>) {
    setBusy(true)
    setError(null)
    try {
      await fn()
      reload()
    } catch (e) {
      setError(e instanceof Error ? e.message : 'Action failed')
    } finally {
      setBusy(false)
    }
  }

  async function onAdd(e: FormEvent) {
    e.preventDefault()
    const proc = procedures.find((p) => p.id === procedureId)
    if (!proc) return
    const base = {
      procedureId: proc.id,
      procedureName: proc.name,
      targetType,
      targetId,
      targetName,
    }
    if (mode === 'TEMPLATE') {
      await guard(() => createTaggedProcedure(base))
    } else {
      await guard(async () => {
        const checklist = await addOneTimeProcedure(base)
        setFillId(checklist.id)   // one-time: fill it right away
      })
    }
    setProcedureId('')
  }

  if (fillId) {
    return (
      <Modal title={`Checklist — ${targetName}`} wide onClose={() => setFillId(null)}>
        <button className="btn small" onClick={() => setFillId(null)}>
          ← Back to procedures
        </button>
        <ChecklistFillPanel checklistId={fillId} onExit={() => setFillId(null)} onChanged={reload} />
      </Modal>
    )
  }

  return (
    <Modal title={`Procedures — ${targetName}`} wide onClose={onClose}>
      {error && <div className="alert alert-error">{error}</div>}

      <form className="card" onSubmit={onAdd}>
        <h2>Add procedure</h2>
        <div className="form-grid">
          <label className="grow">
            Published procedure *
            <select value={procedureId} onChange={(e) => setProcedureId(e.target.value)} required>
              <option value="">Select…</option>
              {procedures.map((p) => (
                <option key={p.id} value={p.id}>
                  {p.name} (v{p.version})
                </option>
              ))}
            </select>
          </label>
          <label>
            Mode
            <select value={mode} onChange={(e) => setMode(e.target.value as 'TEMPLATE' | 'ONE_TIME')}>
              <option value="TEMPLATE">Reusable template</option>
              <option value="ONE_TIME">One-time</option>
            </select>
          </label>
          <button className="btn btn-primary" type="submit" disabled={busy || !procedureId}>
            {mode === 'TEMPLATE' ? 'Tag' : 'Add & fill'}
          </button>
        </div>
        <p className="muted small">
          Reusable template stays available here — each Fill creates a new checklist. One-time creates a
          single checklist and stores nothing.
        </p>
      </form>

      <div className="card">
        <h2>Tagged templates</h2>
        {links.length === 0 ? (
          <p className="muted">No procedures tagged to this {targetType.toLowerCase()}.</p>
        ) : (
          <ul className="cond-list">
            {links.map((l) => (
              <li key={l.id} className="cond-row">
                <span className="grow">📋 {l.procedureName}</span>
                <button
                  className="btn btn-soft small"
                  disabled={busy}
                  onClick={() =>
                    guard(async () => {
                      const checklist = await fillTaggedProcedure(l.id)
                      setFillId(checklist.id)
                    })
                  }
                >
                  Fill
                </button>
                <button
                  className="icon-btn danger-text"
                  title="Untag"
                  disabled={busy}
                  onClick={() =>
                    confirm(`Untag "${l.procedureName}"?`) && guard(() => deleteTaggedProcedure(l.id))
                  }
                >
                  🗑
                </button>
              </li>
            ))}
          </ul>
        )}
      </div>

      <div className="card">
        <h2>Checklists here</h2>
        {checklists.length === 0 ? (
          <p className="muted">None yet.</p>
        ) : (
          <ul className="cond-list">
            {checklists.map((c) => (
              <li key={c.id} className="cond-row">
                <span className="grow">
                  {c.procedureName} <span className="muted small">· {c.assigneeEmail}</span>
                </span>
                <span
                  className={`badge ${
                    c.status === 'COMPLETE'
                      ? 'badge-green'
                      : c.status === 'FAILED'
                        ? 'badge-red'
                        : c.status === 'TODO'
                          ? 'badge-gray'
                          : 'badge-amber'
                  }`}
                >
                  {c.status === 'TODO' ? 'To-Do' : c.status.charAt(0) + c.status.slice(1).toLowerCase()}
                </span>
                <button className="btn btn-ghost small" onClick={() => setFillId(c.id)}>
                  Open
                </button>
              </li>
            ))}
          </ul>
        )}
      </div>
    </Modal>
  )
}

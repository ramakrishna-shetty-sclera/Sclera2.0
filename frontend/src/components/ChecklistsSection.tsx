import { useEffect, useState } from 'react'
import { useNavigate } from 'react-router-dom'
import {
  checklistStatusLabel,
  CHECKLIST_STATUS_CLASS,
  generateChecklists,
  listChecklists,
  type Checklist,
} from '../api/checklists'

export function ChecklistsSection({ configId }: { configId: string }) {
  const navigate = useNavigate()
  const [checklists, setChecklists] = useState<Checklist[]>([])
  const [dueDate, setDueDate] = useState('')
  const [error, setError] = useState<string | null>(null)
  const [busy, setBusy] = useState(false)

  function reload() {
    listChecklists({ configId })
      .then(setChecklists)
      .catch((e) => setError(e.message))
  }

  useEffect(reload, [configId])

  async function onGenerate() {
    setBusy(true)
    setError(null)
    try {
      await generateChecklists(configId, dueDate ? new Date(dueDate).toISOString() : undefined)
      reload()
    } catch (e) {
      setError(e instanceof Error ? e.message : 'Generate failed')
    } finally {
      setBusy(false)
    }
  }

  return (
    <div className="card">
      <div className="section-head">
        <h2 className="grow">Checklists</h2>
        <div className="page-actions">
          <input
            type="date"
            value={dueDate}
            onChange={(e) => setDueDate(e.target.value)}
            title="Optional due date"
          />
          <button className="btn btn-primary small" disabled={busy} onClick={onGenerate}>
            Generate checklists
          </button>
        </div>
      </div>
      <p className="muted small">
        Generates one checklist per tagged procedure × asset/location. Fill them here or from the Task
        Dashboard (next step).
      </p>

      {error && <div className="alert alert-error">{error}</div>}

      {checklists.length === 0 ? (
        <p className="muted">No checklists yet.</p>
      ) : (
        <table className="table">
          <thead>
            <tr>
              <th>Procedure</th>
              <th>Target</th>
              <th>Assignee</th>
              <th>Status</th>
              <th>Due</th>
            </tr>
          </thead>
          <tbody>
            {checklists.map((c) => (
              <tr key={c.id} className="clickable" onClick={() => navigate(`/checklists/${c.id}`)}>
                <td>{c.procedureName}</td>
                <td>
                  {c.targetName ? (
                    <>
                      {c.targetType === 'ASSET' ? '🖥️' : '📍'} {c.targetName}
                    </>
                  ) : (
                    <span className="muted">—</span>
                  )}
                </td>
                <td>{c.assigneeEmail}</td>
                <td>
                  <span className={`badge ${CHECKLIST_STATUS_CLASS[c.status]}`}>
                    {checklistStatusLabel(c.status)}
                  </span>
                </td>
                <td>{c.dueDate ? new Date(c.dueDate).toLocaleDateString() : '—'}</td>
              </tr>
            ))}
          </tbody>
        </table>
      )}
    </div>
  )
}

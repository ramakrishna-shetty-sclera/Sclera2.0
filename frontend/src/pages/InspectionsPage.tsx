import { useEffect, useState, type FormEvent } from 'react'
import { useNavigate } from 'react-router-dom'
import { createInspection, listInspections } from '../api/inspections'
import { listTemplates } from '../api/templates'
import type { Inspection, InspectionStatus, Pagination, QuestionTemplate } from '../api/types'
import { StatusBadge } from '../components/StatusBadge'
import { Pager } from '../components/Pager'

export function InspectionsPage() {
  const navigate = useNavigate()
  const [inspections, setInspections] = useState<Inspection[]>([])
  const [pagination, setPagination] = useState<Pagination | null>(null)
  const [status, setStatus] = useState<InspectionStatus | ''>('')
  const [page, setPage] = useState(0)
  const [error, setError] = useState<string | null>(null)
  const [loading, setLoading] = useState(true)

  const [showCreate, setShowCreate] = useState(false)
  const [publishedTemplates, setPublishedTemplates] = useState<QuestionTemplate[]>([])
  const [templateId, setTemplateId] = useState('')
  const [scheduledFor, setScheduledFor] = useState('')
  const [notes, setNotes] = useState('')
  const [creating, setCreating] = useState(false)

  useEffect(() => {
    let cancelled = false
    setLoading(true)
    listInspections({ status: status || undefined, page, size: 20 })
      .then((r) => {
        if (cancelled) return
        setInspections(r.data)
        setPagination(r.pagination)
        setError(null)
      })
      .catch((e) => !cancelled && setError(e.message))
      .finally(() => !cancelled && setLoading(false))
    return () => {
      cancelled = true
    }
  }, [status, page])

  useEffect(() => {
    if (!showCreate) return
    listTemplates({ status: 'PUBLISHED', size: 100 })
      .then((r) => setPublishedTemplates(r.data))
      .catch((e) => setError(e.message))
  }, [showCreate])

  async function onCreate(e: FormEvent) {
    e.preventDefault()
    setCreating(true)
    setError(null)
    try {
      const inspection = await createInspection({
        templateId,
        scheduledFor: scheduledFor ? new Date(scheduledFor).toISOString() : undefined,
        notes: notes.trim() || undefined,
      })
      navigate(`/inspections/${inspection.id}`)
    } catch (err) {
      setError(err instanceof Error ? err.message : 'Could not create inspection')
      setCreating(false)
    }
  }

  return (
    <div>
      <div className="page-head">
        <h1>Inspections</h1>
        <div className="page-actions">
          <select
            value={status}
            onChange={(e) => {
              setStatus(e.target.value as InspectionStatus | '')
              setPage(0)
            }}
          >
            <option value="">All statuses</option>
            <option value="DRAFT">Draft</option>
            <option value="IN_PROGRESS">In progress</option>
            <option value="COMPLETED">Completed</option>
            <option value="CANCELLED">Cancelled</option>
          </select>
          <button className="btn btn-primary" onClick={() => setShowCreate((v) => !v)}>
            {showCreate ? 'Close' : 'New inspection'}
          </button>
        </div>
      </div>

      {error && <div className="alert alert-error">{error}</div>}

      {showCreate && (
        <form className="card" onSubmit={onCreate}>
          <h2>New inspection</h2>
          {publishedTemplates.length === 0 ? (
            <p className="muted">
              No published templates available. Publish a template first to run inspections.
            </p>
          ) : (
            <>
              <div className="form-grid">
                <label className="grow">
                  Template *
                  <select value={templateId} onChange={(e) => setTemplateId(e.target.value)} required>
                    <option value="">Select a published template…</option>
                    {publishedTemplates.map((t) => (
                      <option key={t.id} value={t.id}>
                        {t.name} (v{t.version})
                      </option>
                    ))}
                  </select>
                </label>
                <label>
                  Scheduled for
                  <input
                    type="datetime-local"
                    value={scheduledFor}
                    onChange={(e) => setScheduledFor(e.target.value)}
                  />
                </label>
              </div>
              <label>
                Notes
                <textarea value={notes} onChange={(e) => setNotes(e.target.value)} rows={2} maxLength={4000} />
              </label>
              <button className="btn btn-primary" type="submit" disabled={creating || !templateId}>
                {creating ? 'Creating…' : 'Create inspection'}
              </button>
            </>
          )}
        </form>
      )}

      {loading ? (
        <p className="muted">Loading…</p>
      ) : inspections.length === 0 ? (
        <p className="muted">No inspections found.</p>
      ) : (
        <table className="table">
          <thead>
            <tr>
              <th>Template</th>
              <th>Status</th>
              <th>Scheduled</th>
              <th>Started</th>
              <th>Completed</th>
              <th>Created</th>
            </tr>
          </thead>
          <tbody>
            {inspections.map((i) => (
              <tr key={i.id} className="clickable" onClick={() => navigate(`/inspections/${i.id}`)}>
                <td>
                  {i.templateName} <span className="muted small">v{i.templateVersion}</span>
                </td>
                <td>
                  <StatusBadge status={i.status} />
                </td>
                <td>{i.scheduledFor ? new Date(i.scheduledFor).toLocaleString() : '—'}</td>
                <td>{i.startedAt ? new Date(i.startedAt).toLocaleString() : '—'}</td>
                <td>{i.completedAt ? new Date(i.completedAt).toLocaleString() : '—'}</td>
                <td>{new Date(i.createdAt).toLocaleString()}</td>
              </tr>
            ))}
          </tbody>
        </table>
      )}
      <Pager pagination={pagination} onPage={setPage} />
    </div>
  )
}

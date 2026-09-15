import { useEffect, useState, type FormEvent } from 'react'
import { Link, useParams } from 'react-router-dom'
import {
  raiseReactiveRequest,
  resolveReactiveServiceByToken,
  type ReactiveService,
} from '../api/reactiveServices'
import { ChecklistFillPanel } from '../components/ChecklistFillPanel'

/**
 * Landing page for a scanned reactive-service QR (/scan/:token).
 * Resolves the token, takes assignee + due date, raises the request, then
 * lets the raiser fill the checklist right away.
 */
export function ScanPage() {
  const { token } = useParams<{ token: string }>()
  const [service, setService] = useState<ReactiveService | null>(null)
  const [error, setError] = useState<string | null>(null)
  const [assigneeEmail, setAssigneeEmail] = useState('')
  const [dueDate, setDueDate] = useState('')
  const [busy, setBusy] = useState(false)
  const [raisedId, setRaisedId] = useState<string | null>(null)

  useEffect(() => {
    if (!token) return
    resolveReactiveServiceByToken(token)
      .then(setService)
      .catch((e) => setError(e.message))
  }, [token])

  async function onRaise(e: FormEvent) {
    e.preventDefault()
    if (!service) return
    setBusy(true)
    setError(null)
    try {
      const checklist = await raiseReactiveRequest(service.id, {
        assigneeEmail: assigneeEmail.trim(),
        dueDate: dueDate ? new Date(dueDate).toISOString() : undefined,
      })
      setRaisedId(checklist.id)
    } catch (err) {
      setError(err instanceof Error ? err.message : 'Could not raise the request')
      setBusy(false)
    }
  }

  if (error && !service) {
    return (
      <div className="empty-state">
        <div className="empty-icon">❓</div>
        <p className="empty-title">Unknown QR code</p>
        <p className="muted">{error}</p>
        <Link className="btn" to="/reactive-services">
          ← Reactive services
        </Link>
      </div>
    )
  }

  if (!service) return <p className="muted">Resolving QR…</p>

  if (raisedId) {
    return (
      <div>
        <div className="page-head">
          <div>
            <h1>Request raised ✓</h1>
            <p className="muted">Fill the checklist now, or find it later in the Task Dashboard.</p>
          </div>
          <Link className="btn" to="/tasks">
            Task Dashboard
          </Link>
        </div>
        <ChecklistFillPanel checklistId={raisedId} onExit={() => setRaisedId(null)} />
      </div>
    )
  }

  return (
    <div className="scan-wrap">
      <form className="card scan-card" onSubmit={onRaise}>
        <div className="empty-icon" aria-hidden>
          📱
        </div>
        <h1>{service.name}</h1>
        <p className="muted">
          📋 {service.procedureName} · 📍 {service.locationName}
        </p>
        {error && <div className="alert alert-error">{error}</div>}
        <label>
          Assignee email *
          <input
            type="email"
            value={assigneeEmail}
            onChange={(e) => setAssigneeEmail(e.target.value)}
            required
            autoFocus
            maxLength={255}
          />
        </label>
        <label>
          Due date (optional)
          <input type="date" value={dueDate} onChange={(e) => setDueDate(e.target.value)} />
        </label>
        <button className="btn btn-primary" type="submit" disabled={busy || !assigneeEmail.trim()}>
          {busy ? 'Raising…' : 'Raise request'}
        </button>
      </form>
    </div>
  )
}

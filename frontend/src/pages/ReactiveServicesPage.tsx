import { useEffect, useState, type FormEvent } from 'react'
import { Link } from 'react-router-dom'
import QRCode from 'qrcode'
import {
  createReactiveService,
  deleteReactiveService,
  listReactiveServices,
  scanUrlFor,
  type ReactiveService,
} from '../api/reactiveServices'
import { listTemplates } from '../api/templates'
import type { QuestionTemplate } from '../api/types'
import { listLocations, type Location } from '../api/locations'
import { Modal } from '../components/Modal'

export function ReactiveServicesPage() {
  const [services, setServices] = useState<ReactiveService[]>([])
  const [loading, setLoading] = useState(true)
  const [error, setError] = useState<string | null>(null)
  const [createOpen, setCreateOpen] = useState(false)
  const [qrFor, setQrFor] = useState<ReactiveService | null>(null)

  function reload() {
    listReactiveServices()
      .then((s) => {
        setServices(s)
        setError(null)
      })
      .catch((e) => setError(e.message))
      .finally(() => setLoading(false))
  }

  useEffect(reload, [])

  async function onDelete(s: ReactiveService) {
    if (!confirm(`Delete reactive service "${s.name}"? Its QR code stops working.`)) return
    try {
      await deleteReactiveService(s.id)
      reload()
    } catch (e) {
      setError(e instanceof Error ? e.message : 'Delete failed')
    }
  }

  return (
    <div>
      <div className="page-head">
        <div>
          <h1>Reactive Service</h1>
          <p className="muted">
            Associate a checklist with a location, print the QR, and requests get raised by scanning it.
          </p>
        </div>
        <button className="btn btn-primary" onClick={() => setCreateOpen(true)}>
          + Reactive service
        </button>
      </div>

      {error && <div className="alert alert-error">{error}</div>}

      {loading ? (
        <p className="muted">Loading…</p>
      ) : services.length === 0 ? (
        <div className="empty-state">
          <div className="empty-icon">📱</div>
          <p className="empty-title">No reactive services yet</p>
          <p className="muted">Create one by associating a published procedure with a location.</p>
          <button className="btn btn-primary" onClick={() => setCreateOpen(true)}>
            + Reactive service
          </button>
        </div>
      ) : (
        <table className="table">
          <thead>
            <tr>
              <th>Name</th>
              <th>Procedure</th>
              <th>Location</th>
              <th>Created</th>
              <th></th>
            </tr>
          </thead>
          <tbody>
            {services.map((s) => (
              <tr key={s.id}>
                <td className="asset-name">{s.name}</td>
                <td>📋 {s.procedureName}</td>
                <td>
                  <span className="loc-chip">📍 {s.locationName}</span>
                </td>
                <td>{new Date(s.createdAt).toLocaleDateString()}</td>
                <td className="row-actions">
                  <button className="btn btn-soft small" onClick={() => setQrFor(s)}>
                    QR code
                  </button>
                  <Link className="btn btn-ghost small" to={`/scan/${s.qrToken}`}>
                    Raise request
                  </Link>
                  <button className="icon-btn danger-text" title="Delete" onClick={() => onDelete(s)}>
                    🗑
                  </button>
                </td>
              </tr>
            ))}
          </tbody>
        </table>
      )}

      <p className="muted small">
        Raised requests appear in the <Link to="/tasks">Task Dashboard</Link> under source "Reactive
        service" and on the <Link to="/task-map">Task Map</Link> at the service's location.
      </p>

      {createOpen && (
        <CreateServiceModal
          onClose={() => setCreateOpen(false)}
          onSaved={() => {
            setCreateOpen(false)
            reload()
          }}
          onError={setError}
        />
      )}

      {qrFor && <QrModal service={qrFor} onClose={() => setQrFor(null)} />}
    </div>
  )
}

function CreateServiceModal({
  onClose,
  onSaved,
  onError,
}: {
  onClose: () => void
  onSaved: () => void
  onError: (m: string) => void
}) {
  const [procedures, setProcedures] = useState<QuestionTemplate[]>([])
  const [locations, setLocations] = useState<Location[]>([])
  const [name, setName] = useState('')
  const [procedureId, setProcedureId] = useState('')
  const [locationId, setLocationId] = useState('')
  const [busy, setBusy] = useState(false)

  useEffect(() => {
    listTemplates({ status: 'PUBLISHED', size: 100 }).then((r) => setProcedures(r.data)).catch(() => {})
    listLocations().then(setLocations).catch(() => {})
  }, [])

  async function onSubmit(e: FormEvent) {
    e.preventDefault()
    const proc = procedures.find((p) => p.id === procedureId)
    const loc = locations.find((l) => l.id === locationId)
    if (!proc || !loc) return
    setBusy(true)
    try {
      await createReactiveService({
        name: name.trim() || undefined,
        procedureId: proc.id,
        procedureName: proc.name,
        locationId: loc.id,
        locationName: loc.name,
      })
      onSaved()
    } catch (err) {
      onError(err instanceof Error ? err.message : 'Create failed')
      setBusy(false)
    }
  }

  return (
    <Modal title="New reactive service" onClose={onClose}>
      <form onSubmit={onSubmit}>
        <label>
          Name (optional — defaults to procedure @ location)
          <input value={name} onChange={(e) => setName(e.target.value)} maxLength={200} />
        </label>
        <label>
          Checklist (published procedure) *
          <select value={procedureId} onChange={(e) => setProcedureId(e.target.value)} required autoFocus>
            <option value="">Select…</option>
            {procedures.map((p) => (
              <option key={p.id} value={p.id}>
                {p.name} (v{p.version})
              </option>
            ))}
          </select>
        </label>
        <label>
          Location *
          <select value={locationId} onChange={(e) => setLocationId(e.target.value)} required>
            <option value="">Select…</option>
            {locations.map((l) => (
              <option key={l.id} value={l.id}>
                {l.name} ({l.type.toLowerCase()})
              </option>
            ))}
          </select>
        </label>
        <div className="modal-actions">
          <button type="button" className="btn" onClick={onClose}>
            Cancel
          </button>
          <button type="submit" className="btn btn-primary" disabled={busy || !procedureId || !locationId}>
            {busy ? 'Creating…' : 'Create'}
          </button>
        </div>
      </form>
    </Modal>
  )
}

function QrModal({ service, onClose }: { service: ReactiveService; onClose: () => void }) {
  const [dataUrl, setDataUrl] = useState<string | null>(null)
  const [copied, setCopied] = useState(false)
  const url = scanUrlFor(service)

  useEffect(() => {
    QRCode.toDataURL(url, { width: 280, margin: 2 }).then(setDataUrl).catch(() => setDataUrl(null))
  }, [url])

  return (
    <Modal title={`QR — ${service.name}`} onClose={onClose}>
      <div className="qr-box">
        {dataUrl ? <img src={dataUrl} alt="QR code" /> : <p className="muted">Rendering…</p>}
        <p className="muted small">
          📍 {service.locationName} · 📋 {service.procedureName}
        </p>
        <code className="qr-url">{url}</code>
        <div className="modal-actions">
          <button
            className="btn"
            onClick={() => {
              navigator.clipboard.writeText(url).then(() => {
                setCopied(true)
                setTimeout(() => setCopied(false), 1500)
              })
            }}
          >
            {copied ? 'Copied ✓' : 'Copy link'}
          </button>
          {dataUrl && (
            <a className="btn btn-primary" href={dataUrl} download={`qr-${service.name}.png`}>
              Download PNG
            </a>
          )}
        </div>
      </div>
    </Modal>
  )
}

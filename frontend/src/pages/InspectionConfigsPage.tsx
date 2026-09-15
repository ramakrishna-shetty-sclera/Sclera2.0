import { useEffect, useState } from 'react'
import { Link, useNavigate } from 'react-router-dom'
import {
  frequencyLabel,
  listInspectionConfigs,
  type InspectionConfig,
} from '../api/inspectionConfigs'

const PRIORITY_CLASS: Record<string, string> = {
  LOW: 'badge-gray',
  MEDIUM: 'badge-blue',
  HIGH: 'badge-amber',
  CRITICAL: 'badge-red',
}

export function InspectionConfigsPage() {
  const navigate = useNavigate()
  const [configs, setConfigs] = useState<InspectionConfig[]>([])
  const [loading, setLoading] = useState(true)
  const [error, setError] = useState<string | null>(null)

  useEffect(() => {
    listInspectionConfigs()
      .then((c) => {
        setConfigs(c)
        setError(null)
      })
      .catch((e) => setError(e.message))
      .finally(() => setLoading(false))
  }, [])

  return (
    <div>
      <div className="page-head">
        <div>
          <h1>Inspections</h1>
          <p className="muted">Configured inspections. Tagging &amp; checklists come next.</p>
        </div>
        <Link className="btn btn-primary" to="/inspection-configs/new">
          + New inspection
        </Link>
      </div>

      {error && <div className="alert alert-error">{error}</div>}

      {loading ? (
        <p className="muted">Loading…</p>
      ) : configs.length === 0 ? (
        <div className="empty-state">
          <div className="empty-icon">🔍</div>
          <p className="empty-title">No inspections configured yet</p>
          <p className="muted">Create one with the configuration form.</p>
          <Link className="btn btn-primary" to="/inspection-configs/new">
            + New inspection
          </Link>
        </div>
      ) : (
        <table className="table">
          <thead>
            <tr>
              <th>Name</th>
              <th>Category</th>
              <th>Priority</th>
              <th>Frequency</th>
              <th>Assignee</th>
            </tr>
          </thead>
          <tbody>
            {configs.map((c) => (
              <tr key={c.id} className="clickable" onClick={() => navigate(`/inspection-configs/${c.id}`)}>
                <td>
                  <span className="asset-name">{c.name}</span>
                  {c.code && <span className="muted small"> · {c.code}</span>}
                </td>
                <td>{c.category}</td>
                <td>
                  {c.priority ? (
                    <span className={`badge ${PRIORITY_CLASS[c.priority] ?? 'badge-gray'}`}>
                      {c.priority}
                    </span>
                  ) : (
                    <span className="muted">—</span>
                  )}
                </td>
                <td>{frequencyLabel(c.frequency)}</td>
                <td>{c.assigneeEmail}</td>
              </tr>
            ))}
          </tbody>
        </table>
      )}
    </div>
  )
}

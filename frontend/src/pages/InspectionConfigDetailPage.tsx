import { useEffect, useState } from 'react'
import { Link, useNavigate, useParams } from 'react-router-dom'
import {
  deleteInspectionConfig,
  frequencyLabel,
  getInspectionConfig,
  WEEKDAYS,
  type InspectionConfig,
} from '../api/inspectionConfigs'
import { TaggingSection } from '../components/TaggingSection'
import { ChecklistsSection } from '../components/ChecklistsSection'

export function InspectionConfigDetailPage() {
  const { id } = useParams<{ id: string }>()
  const navigate = useNavigate()
  const [config, setConfig] = useState<InspectionConfig | null>(null)
  const [error, setError] = useState<string | null>(null)

  useEffect(() => {
    if (!id) return
    getInspectionConfig(id).then(setConfig).catch((e) => setError(e.message))
  }, [id])

  async function onDelete() {
    if (!config || !confirm(`Delete inspection "${config.name}"?`)) return
    try {
      await deleteInspectionConfig(config.id)
      navigate('/inspection-configs')
    } catch (e) {
      setError(e instanceof Error ? e.message : 'Delete failed')
    }
  }

  if (!config) {
    return error ? <div className="alert alert-error">{error}</div> : <p className="muted">Loading…</p>
  }

  const days = WEEKDAYS.filter((d) => config.scheduleDays.includes(d))
  const activeToggles = [
    config.bypassScan && 'Bypass Scan',
    config.enableCheckInOut && 'Check-in / Check-out',
    config.enablePoints && 'Points',
    config.mergedView && 'Merged View',
  ].filter(Boolean) as string[]

  return (
    <div>
      <div className="page-head">
        <div>
          <h1>{config.name}</h1>
          <p className="muted">
            {config.category}
            {config.code ? ` · ${config.code}` : ''} · {frequencyLabel(config.frequency)}
            {config.priority ? ` · ${config.priority}` : ''}
          </p>
        </div>
        <div className="page-actions">
          <Link className="btn" to={`/inspection-configs/${config.id}/edit`}>
            Edit
          </Link>
          <button className="btn btn-danger" onClick={onDelete}>
            Delete
          </button>
        </div>
      </div>

      {error && <div className="alert alert-error">{error}</div>}

      <div className="card">
        <h2>Details</h2>
        <dl className="detail-grid">
          <dt>Assignee</dt>
          <dd>{config.assigneeEmail}</dd>
          <dt>Secondary assignee</dt>
          <dd>{config.secondaryAssigneeEmail ?? '—'}</dd>
          <dt>Description</dt>
          <dd>{config.description ?? '—'}</dd>
          <dt>Schedule days</dt>
          <dd>
            <div className="day-picker">
              {WEEKDAYS.map((d) => (
                <span key={d} className={`day-chip ${days.includes(d) ? 'day-on' : 'day-off'}`}>
                  {d}
                </span>
              ))}
            </div>
          </dd>
          <dt>Options</dt>
          <dd>{activeToggles.length ? activeToggles.join(', ') : 'None enabled'}</dd>
        </dl>
      </div>

      <TaggingSection configId={config.id} />
      <ChecklistsSection configId={config.id} />
    </div>
  )
}

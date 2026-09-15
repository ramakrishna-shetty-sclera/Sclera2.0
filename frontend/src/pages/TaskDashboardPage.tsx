import { useEffect, useMemo, useState } from 'react'
import { Link } from 'react-router-dom'
import {
  checklistSourceLabel,
  checklistStatusLabel,
  CHECKLIST_SOURCES,
  CHECKLIST_STATUS_CLASS,
  listChecklists,
  type Checklist,
  type ChecklistSource,
  type ChecklistStatus,
} from '../api/checklists'
import { listInspectionConfigs, type InspectionConfig } from '../api/inspectionConfigs'
import { ChecklistFillPanel } from '../components/ChecklistFillPanel'
import { Modal } from '../components/Modal'

const STATUSES: ChecklistStatus[] = ['TODO', 'COMPLETE', 'FAILED', 'EXCEPTION', 'INCOMPLETE']

export function TaskDashboardPage() {
  const [checklists, setChecklists] = useState<Checklist[]>([])
  const [configs, setConfigs] = useState<InspectionConfig[]>([])
  const [loading, setLoading] = useState(true)
  const [error, setError] = useState<string | null>(null)

  const [status, setStatus] = useState<ChecklistStatus | ''>('')
  const [source, setSource] = useState<ChecklistSource | ''>('')
  const [configId, setConfigId] = useState('')
  const [search, setSearch] = useState('')
  const [openId, setOpenId] = useState<string | null>(null)

  function reload() {
    listChecklists({
      status: status || undefined,
      source: source || undefined,
      configId: configId || undefined,
    })
      .then((data) => {
        setChecklists(data)
        setError(null)
      })
      .catch((e) => setError(e.message))
      .finally(() => setLoading(false))
  }

  useEffect(() => {
    setLoading(true)
    reload()
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [status, source, configId])

  useEffect(() => {
    listInspectionConfigs().then(setConfigs).catch(() => {})
  }, [])

  const visible = useMemo(() => {
    const q = search.trim().toLowerCase()
    if (!q) return checklists
    return checklists.filter((c) =>
      [c.procedureName, c.configName, c.targetName ?? '', c.assigneeEmail]
        .some((f) => f.toLowerCase().includes(q)),
    )
  }, [checklists, search])

  const countBy = (s: ChecklistStatus) => checklists.filter((c) => c.status === s).length
  const overdue = checklists.filter(
    (c) => c.status === 'TODO' && c.dueDate && new Date(c.dueDate) < new Date(),
  ).length

  return (
    <div>
      <div className="page-head">
        <div>
          <h1>Task Dashboard</h1>
          <p className="muted">All checklists across inspections — fill them inline from here.</p>
        </div>
        <Link className="btn" to="/task-map">
          🗺 Task Map
        </Link>
      </div>

      {error && <div className="alert alert-error">{error}</div>}

      {!loading && checklists.length > 0 && (
        <div className="stat-row">
          <div className="stat">
            <span className="stat-num">{countBy('TODO')}</span>
            <span className="stat-label">📝 To-Do</span>
          </div>
          <div className="stat">
            <span className="stat-num">{countBy('COMPLETE')}</span>
            <span className="stat-label">✅ Complete</span>
          </div>
          <div className="stat">
            <span className="stat-num">{countBy('FAILED')}</span>
            <span className="stat-label">❌ Failed</span>
          </div>
          <div className="stat">
            <span className="stat-num">{countBy('EXCEPTION')}</span>
            <span className="stat-label">⚠️ Exception</span>
          </div>
          <div className="stat">
            <span className="stat-num">{countBy('INCOMPLETE')}</span>
            <span className="stat-label">⏰ Incomplete</span>
          </div>
          <div className="stat">
            <span className="stat-num">{overdue}</span>
            <span className="stat-label">🔥 Overdue</span>
          </div>
        </div>
      )}

      <div className="card filter-bar">
        <input
          className="search-input"
          placeholder="🔎 Search procedure, inspection, target, assignee…"
          value={search}
          onChange={(e) => setSearch(e.target.value)}
        />
        <select value={status} onChange={(e) => setStatus(e.target.value as ChecklistStatus | '')}>
          <option value="">All statuses</option>
          {STATUSES.map((s) => (
            <option key={s} value={s}>
              {checklistStatusLabel(s)}
            </option>
          ))}
        </select>
        <select value={source} onChange={(e) => setSource(e.target.value as ChecklistSource | '')}>
          <option value="">All sources</option>
          {CHECKLIST_SOURCES.map((s) => (
            <option key={s} value={s}>
              {checklistSourceLabel(s)}
            </option>
          ))}
        </select>
        <select value={configId} onChange={(e) => setConfigId(e.target.value)}>
          <option value="">All inspections</option>
          {configs.map((c) => (
            <option key={c.id} value={c.id}>
              {c.name}
            </option>
          ))}
        </select>
      </div>

      {loading ? (
        <p className="muted">Loading…</p>
      ) : visible.length === 0 ? (
        <div className="empty-state">
          <div className="empty-icon">✅</div>
          <p className="empty-title">{checklists.length === 0 ? 'No checklists yet' : 'No matches'}</p>
          <p className="muted">
            {checklists.length === 0
              ? 'Generate checklists from an inspection to see them here.'
              : 'Try clearing the search or filters.'}
          </p>
        </div>
      ) : (
        <table className="table">
          <thead>
            <tr>
              <th>Procedure</th>
              <th>Inspection</th>
              <th>Target</th>
              <th>Assignee</th>
              <th>Source</th>
              <th>Due</th>
              <th>Status</th>
            </tr>
          </thead>
          <tbody>
            {visible.map((c) => {
              const isOverdue = c.status === 'TODO' && c.dueDate && new Date(c.dueDate) < new Date()
              return (
                <tr key={c.id} className="clickable" onClick={() => setOpenId(c.id)}>
                  <td className="asset-name">{c.procedureName}</td>
                  <td>{c.configName}</td>
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
                    <span className="muted small">{checklistSourceLabel(c.source)}</span>
                  </td>
                  <td className={isOverdue ? 'danger-text' : ''}>
                    {c.dueDate ? new Date(c.dueDate).toLocaleDateString() : '—'}
                    {isOverdue ? ' 🔥' : ''}
                  </td>
                  <td>
                    <span className={`badge ${CHECKLIST_STATUS_CLASS[c.status]}`}>
                      {checklistStatusLabel(c.status)}
                    </span>
                  </td>
                </tr>
              )
            })}
          </tbody>
        </table>
      )}

      {openId && (
        <Modal title="Checklist" wide onClose={() => setOpenId(null)}>
          <ChecklistFillPanel
            checklistId={openId}
            onExit={() => setOpenId(null)}
            onChanged={reload}
          />
        </Modal>
      )}
    </div>
  )
}

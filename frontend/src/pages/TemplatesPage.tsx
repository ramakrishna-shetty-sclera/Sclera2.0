import { useEffect, useState } from 'react'
import { Link, useNavigate } from 'react-router-dom'
import { listTemplates } from '../api/templates'
import type { Pagination, QuestionTemplate, TemplateStatus } from '../api/types'
import { StatusBadge } from '../components/StatusBadge'
import { Pager } from '../components/Pager'

export function TemplatesPage() {
  const navigate = useNavigate()
  const [templates, setTemplates] = useState<QuestionTemplate[]>([])
  const [pagination, setPagination] = useState<Pagination | null>(null)
  const [status, setStatus] = useState<TemplateStatus | ''>('')
  const [page, setPage] = useState(0)
  const [error, setError] = useState<string | null>(null)
  const [loading, setLoading] = useState(true)

  useEffect(() => {
    let cancelled = false
    setLoading(true)
    listTemplates({ status: status || undefined, page, size: 20 })
      .then((r) => {
        if (cancelled) return
        setTemplates(r.data)
        setPagination(r.pagination)
        setError(null)
      })
      .catch((e) => !cancelled && setError(e.message))
      .finally(() => !cancelled && setLoading(false))
    return () => {
      cancelled = true
    }
  }, [status, page])

  return (
    <div>
      <div className="page-head">
        <h1>Templates</h1>
        <div className="page-actions">
          <select
            value={status}
            onChange={(e) => {
              setStatus(e.target.value as TemplateStatus | '')
              setPage(0)
            }}
          >
            <option value="">All statuses</option>
            <option value="DRAFT">Draft</option>
            <option value="PUBLISHED">Published</option>
            <option value="ARCHIVED">Archived</option>
          </select>
          <Link className="btn btn-primary" to="/templates/new">
            New template
          </Link>
        </div>
      </div>

      {error && <div className="alert alert-error">{error}</div>}
      {loading ? (
        <p className="muted">Loading…</p>
      ) : templates.length === 0 ? (
        <p className="muted">No templates yet. Create one to start running inspections.</p>
      ) : (
        <table className="table">
          <thead>
            <tr>
              <th>Name</th>
              <th>Category</th>
              <th>Status</th>
              <th>Version</th>
              <th>Updated</th>
            </tr>
          </thead>
          <tbody>
            {templates.map((t) => (
              <tr key={t.id} className="clickable" onClick={() => navigate(`/templates/${t.id}`)}>
                <td>{t.name}</td>
                <td>{t.category ?? '—'}</td>
                <td>
                  <StatusBadge status={t.status} />
                </td>
                <td>v{t.version}</td>
                <td>{new Date(t.updatedAt).toLocaleString()}</td>
              </tr>
            ))}
          </tbody>
        </table>
      )}
      <Pager pagination={pagination} onPage={setPage} />
    </div>
  )
}

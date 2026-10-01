import { useEffect, useState } from 'react'
import { Link, useNavigate } from 'react-router-dom'
import { listProcedures } from '../api/templates'
import type { Pagination, ProcedureStatus, ProcedureTemplate } from '../api/types'
import { StatusBadge } from '../components/StatusBadge'
import { Pager } from '../components/Pager'

/**
 * The procedure library.
 *
 * A procedure is identity only — its questions live in its versions — so
 * status alone says very little. The two columns that tell you where one
 * actually stands are which version is published (nothing can use a procedure
 * until something is) and whether a draft is open (someone is mid-edit).
 */
export function TemplatesPage() {
  const navigate = useNavigate()
  const [procedures, setProcedures] = useState<ProcedureTemplate[]>([])
  const [pagination, setPagination] = useState<Pagination | null>(null)
  const [status, setStatus] = useState<ProcedureStatus | ''>('')
  const [page, setPage] = useState(0)
  const [error, setError] = useState<string | null>(null)
  const [loading, setLoading] = useState(true)

  useEffect(() => {
    let cancelled = false
    setLoading(true)
    listProcedures({ status: status || undefined, page, size: 20 })
      .then((r) => {
        if (cancelled) return
        setProcedures(r.data)
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
        <div>
          <h1>Procedures</h1>
          <p className="muted small">
            Each procedure keeps every version it has ever published. Publishing freezes a version;
            editing one afterwards starts a new draft.
          </p>
        </div>
        <div className="page-actions">
          <select
            value={status}
            onChange={(e) => {
              setStatus(e.target.value as ProcedureStatus | '')
              setPage(0)
            }}
          >
            <option value="">All</option>
            <option value="ACTIVE">Active</option>
            <option value="ARCHIVED">Archived</option>
          </select>
          <Link className="btn btn-primary" to="/templates/new">
            New procedure
          </Link>
        </div>
      </div>

      {error && <div className="alert alert-error">{error}</div>}
      {loading ? (
        <p className="muted">Loading…</p>
      ) : procedures.length === 0 ? (
        <p className="muted">No procedures yet. Create one to start authoring.</p>
      ) : (
        <table className="table">
          <thead>
            <tr>
              <th>Name</th>
              <th>Status</th>
              <th>Published</th>
              <th>Draft</th>
              <th>Updated</th>
            </tr>
          </thead>
          <tbody>
            {procedures.map((p) => (
              <tr key={p.id} className="clickable" onClick={() => navigate(`/templates/${p.id}`)}>
                <td>
                  <div className="asset-name">{p.name}</div>
                  {p.description && <div className="muted small">{p.description}</div>}
                </td>
                <td>
                  <StatusBadge status={p.status} />
                </td>
                <td>
                  {p.currentPublishedVersionNo === null ? (
                    <span className="muted">Not published</span>
                  ) : (
                    <>v{p.currentPublishedVersionNo}</>
                  )}
                </td>
                <td>
                  {p.draftVersionNo === null ? (
                    <span className="muted">—</span>
                  ) : (
                    <span className="badge badge-amber">v{p.draftVersionNo} open</span>
                  )}
                </td>
                <td>{new Date(p.updatedAt).toLocaleString()}</td>
              </tr>
            ))}
          </tbody>
        </table>
      )}
      <Pager pagination={pagination} onPage={setPage} />
    </div>
  )
}

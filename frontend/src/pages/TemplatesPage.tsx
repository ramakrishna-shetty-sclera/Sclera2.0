import { useEffect, useState } from 'react'
import { Link, useNavigate } from 'react-router-dom'
import { listProcedures } from '../api/templates'
import type { Pagination, ProcedureStatus, ProcedureTemplate, TemplateScope } from '../api/types'
import { useAuth } from '../auth/AuthContext'
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
  const { permissions } = useAuth()
  const [procedures, setProcedures] = useState<ProcedureTemplate[]>([])
  const [pagination, setPagination] = useState<Pagination | null>(null)
  const [status, setStatus] = useState<ProcedureStatus | ''>('')
  const [scope, setScope] = useState<TemplateScope | ''>('')
  const [favouritesOnly, setFavouritesOnly] = useState(false)
  const [page, setPage] = useState(0)
  const [error, setError] = useState<string | null>(null)
  const [loading, setLoading] = useState(true)

  useEffect(() => {
    let cancelled = false
    setLoading(true)
    listProcedures({
      status: status || undefined,
      scope: scope || undefined,
      favouritesOnly: favouritesOnly || undefined,
      page,
      size: 20,
    })
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
  }, [status, scope, favouritesOnly, page])

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
          <select
            value={scope}
            aria-label="Scope"
            onChange={(e) => {
              setScope(e.target.value as TemplateScope | '')
              setPage(0)
            }}
          >
            <option value="">Organization + property</option>
            <option value="ORGANIZATION">Organization only</option>
            <option value="PROPERTY">Property only</option>
          </select>
          <label className="checkbox">
            <input
              type="checkbox"
              checked={favouritesOnly}
              onChange={(e) => {
                setFavouritesOnly(e.target.checked)
                setPage(0)
              }}
            />
            Favourites only
          </label>
          {permissions.canManageTemplates && (
            <Link className="btn btn-primary" to="/templates/new">
              New procedure
            </Link>
          )}
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
              <th>Scope</th>
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
                  {(p.globalTemplateId != null || p.forkedFromTemplateId != null) && (
                    <div className="muted small">
                      {p.globalTemplateId != null ? 'From the shared library' : 'Property fork'}
                    </div>
                  )}
                </td>
                <td>
                  <span className={`badge ${p.propertyId == null ? 'badge-blue' : 'badge-gray'}`}>
                    {p.propertyId == null ? 'Organization-wide' : 'This property'}
                  </span>
                </td>
                <td>
                  <StatusBadge status={p.status} />
                </td>
                <td>
                  {p.currentPublishedVersionNo == null ? (
                    <span className="muted">Not published</span>
                  ) : (
                    <>v{p.currentPublishedVersionNo}</>
                  )}
                </td>
                <td>
                  {p.draftVersionNo == null ? (
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

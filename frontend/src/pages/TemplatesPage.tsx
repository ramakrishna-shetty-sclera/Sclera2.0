import { useEffect, useRef, useState } from 'react'
import { Link, useNavigate } from 'react-router-dom'
import { ApiError } from '../api/client'
import { importProcedureFile, listProcedures } from '../api/templates'
import type {
  ExportedProcedure,
  Pagination,
  ProcedureStatus,
  ProcedureTemplate,
  TemplateScope,
} from '../api/types'
import { useAuth } from '../auth/AuthContext'
import { Pager } from '../components/Pager'
import { Refusal } from '../components/Refusal'
import { StatusBadge } from '../components/StatusBadge'

/** Enough to tell an exported procedure from any other JSON; the server judges everything else. */
function looksExported(value: unknown): value is ExportedProcedure {
  if (typeof value !== 'object' || value === null) return false
  const candidate = value as { name?: unknown; versions?: unknown }
  return typeof candidate.name === 'string' && Array.isArray(candidate.versions)
}

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
  const fileInput = useRef<HTMLInputElement>(null)
  const [importing, setImporting] = useState(false)
  const [importError, setImportError] = useState<unknown>(null)
  const [dropped, setDropped] = useState<{ id: string; name: string; count: number } | null>(null)
  const [reloadKey, setReloadKey] = useState(0)

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
  }, [status, scope, favouritesOnly, page, reloadKey])

  /**
   * Recreates a procedure from a file the Export button produced, in whichever scope the
   * switcher is on. The file is read and parsed here, so one that is not JSON is told so at
   * once with no round trip. Everything else is the server's to judge: a file edited after
   * export, content that cannot be published in this scope, a name already taken.
   */
  async function onFile(file: File | undefined) {
    if (!file) return
    setImporting(true)
    setImportError(null)
    setDropped(null)
    try {
      let parsed: unknown
      try {
        parsed = JSON.parse(await file.text())
      } catch {
        throw new Error('That file is not valid JSON. Choose a file the Export button produced.')
      }
      if (!looksExported(parsed)) {
        throw new Error(
          'That file does not look like an exported procedure. Choose a file the Export button produced.',
        )
      }
      const result = await importProcedureFile(parsed)
      if (result.droppedDocumentCitations > 0) {
        // Stay here: navigating away would lose the one thing the user has to be told.
        setDropped({
          id: result.template.id,
          name: result.template.name,
          count: result.droppedDocumentCitations,
        })
        setPage(0)
        setReloadKey((k) => k + 1)
      } else {
        navigate('/templates/' + result.template.id)
      }
    } catch (e) {
      // The name is not part of the file's hash, so editing it is the way out of a clash.
      setImportError(
        e instanceof ApiError && e.status === 409
          ? new Error(e.message + '. Change the name in the file and try again.')
          : e,
      )
    } finally {
      setImporting(false)
      if (fileInput.current) fileInput.current.value = ''
    }
  }

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
            <>
              <input
                ref={fileInput}
                type="file"
                accept="application/json,.json"
                hidden
                onChange={(e) => onFile(e.target.files?.[0])}
              />
              <button
                className="btn btn-primary"
                disabled={importing}
                title="Recreates an exported procedure in the scope you are working in"
                onClick={() => fileInput.current?.click()}
              >
                {importing ? 'Importing…' : 'Import from file'}
              </button>
              <Link className="btn btn-primary" to="/templates/new">
                New procedure
              </Link>
            </>
          )}
        </div>
      </div>

      {dropped && (
        <div className="alert alert-amber">
          '{dropped.name}' was imported, but {dropped.count} document{' '}
          {dropped.count === 1 ? 'citation was' : 'citations were'} left out: the{' '}
          {dropped.count === 1 ? 'document is' : 'documents are'} not in this scope's library.{' '}
          <Link to={'/templates/' + dropped.id}>Open it</Link> and cite{' '}
          {dropped.count === 1 ? 'it' : 'them'} again from the Reference documents card.
        </div>
      )}
      <Refusal error={importError} />

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

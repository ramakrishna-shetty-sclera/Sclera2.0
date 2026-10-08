import { useCallback, useEffect, useRef, useState } from 'react'
import {
  activateDocument,
  createDocument,
  deactivateDocument,
  deleteDocument,
  listDocuments,
  openDocument,
  readableSize,
  uploadFile,
} from '../api/documents'
import type { ProcedureDocument } from '../api/types'
import { useAuth } from '../auth/AuthContext'
import { Refusal } from '../components/Refusal'

type Filter = '' | 'active' | 'inactive'

/**
 * Settings → Documents: the reference documents procedures can cite — a
 * standard's extract, a manufacturer's sheet.
 *
 * Upload once, cite from any number of procedures. A procedure cites a document
 * by id, so renaming one here reaches everything citing it, and deleting one a
 * published version still cites is refused: deactivate it instead, which stops
 * new procedures citing it while the published ones keep resolving it.
 *
 * Uploading is two calls and either can fail: the file goes to the storage
 * port, then the library row is written. Failing in between leaves a stored
 * file nothing points at, which is harmless and is simply uploaded again.
 */
export function DocumentsPage() {
  const { permissions } = useAuth()
  const canManage = permissions.canManageTemplates
  const [documents, setDocuments] = useState<ProcedureDocument[]>([])
  const [loading, setLoading] = useState(true)
  const [error, setError] = useState<unknown>(null)
  const [filter, setFilter] = useState<Filter>('')
  const [uploading, setUploading] = useState(false)
  const [stubStore, setStubStore] = useState(false)
  const [busyId, setBusyId] = useState<string | null>(null)
  const fileInput = useRef<HTMLInputElement>(null)

  const reload = useCallback(() => {
    setLoading(true)
    return listDocuments()
      .then((list) => {
        setDocuments(list)
        setError(null)
      })
      .catch((e) => setError(e))
      .finally(() => setLoading(false))
  }, [])

  useEffect(() => {
    reload()
  }, [reload])

  async function onFile(file: File | undefined) {
    if (!file) return
    setUploading(true)
    setError(null)
    try {
      const stored = await uploadFile(file)
      if (stored.stub) setStubStore(true)
      await createDocument({
        name: file.name,
        mimeType: file.type || undefined,
        sizeBytes: stored.sizeBytes,
        location: stored.location,
      })
      await reload()
    } catch (e) {
      setError(e)
    } finally {
      setUploading(false)
      if (fileInput.current) fileInput.current.value = ''
    }
  }

  async function act(doc: ProcedureDocument, run: (id: string) => Promise<unknown>, verb: string) {
    setBusyId(doc.id)
    setError(null)
    try {
      await run(doc.id)
      await reload()
    } catch (e) {
      // Delete's refusal names the way out, so it is shown as the server wrote it.
      setError(new Error(`Could not ${verb} ${doc.name}: ${e instanceof Error ? e.message : String(e)}`))
      await reload()
    } finally {
      setBusyId(null)
    }
  }

  function onDelete(doc: ProcedureDocument) {
    if (!confirm(`Delete "${doc.name}"? This cannot be undone.`)) return
    act(doc, deleteDocument, 'delete')
  }

  async function onOpen(doc: ProcedureDocument) {
    setError(null)
    try {
      await openDocument(doc.location)
    } catch (e) {
      setError(e)
    }
  }

  const shown = documents.filter((d) => (filter === '' ? true : filter === 'active' ? d.active : !d.active))

  return (
    <div>
      <div className="page-head">
        <div>
          <h1>Documents</h1>
          <p className="muted small">
            Reference documents a procedure can cite — a standard's extract, a manufacturer's sheet.
            Upload one once and cite it from as many procedures as need it.
          </p>
        </div>
        <div className="page-actions">
          <select
            value={filter}
            onChange={(e) => setFilter(e.target.value as Filter)}
            aria-label="Show documents"
          >
            <option value="">All</option>
            <option value="active">Active</option>
            <option value="inactive">Inactive</option>
          </select>
          {canManage && (
            <>
              <input
                ref={fileInput}
                type="file"
                hidden
                onChange={(e) => onFile(e.target.files?.[0])}
              />
              <button
                className="btn btn-primary"
                disabled={uploading}
                onClick={() => fileInput.current?.click()}
              >
                {uploading ? 'Uploading…' : 'Upload a document'}
              </button>
            </>
          )}
        </div>
      </div>

      {stubStore && (
        <div className="alert alert-amber">
          The storage behind this is a stand-in: files are kept on the helper service's disk, not in
          real storage.
        </div>
      )}
      <Refusal error={error} />

      {loading && documents.length === 0 ? (
        <p className="muted">Loading…</p>
      ) : shown.length === 0 ? (
        <p className="muted">
          {documents.length === 0
            ? 'No documents yet.' + (canManage ? ' Upload one to start a library.' : '')
            : 'No documents match this filter.'}
        </p>
      ) : (
        <table className="table">
          <thead>
            <tr>
              <th>Name</th>
              <th>Visible from</th>
              <th>Size</th>
              <th>Uploaded</th>
              <th>Status</th>
              <th className="rt-actions">Actions</th>
            </tr>
          </thead>
          <tbody>
            {shown.map((d) => (
              <tr key={d.id} className={d.active ? '' : 'rt-inactive'}>
                <td>{d.name}</td>
                <td>
                  <span className={`badge ${d.propertyId == null ? 'badge-blue' : 'badge-gray'}`}>
                    {d.propertyId == null ? 'Organization-wide' : 'One property'}
                  </span>
                </td>
                <td className="muted small">{readableSize(d.sizeBytes)}</td>
                <td className="muted small">
                  {d.uploadedAt ? new Date(d.uploadedAt).toLocaleDateString() : ''}
                </td>
                <td>
                  <span className={`badge ${d.active ? 'badge-green' : 'badge-gray'}`}>
                    {d.active ? 'Active' : 'Inactive'}
                  </span>
                </td>
                <td className="rt-actions">
                  <button className="btn btn-ghost small" onClick={() => onOpen(d)}>
                    Open
                  </button>
                  {canManage ? (
                    <>
                      <button
                        className="btn btn-ghost small"
                        disabled={busyId === d.id}
                        onClick={() =>
                          act(d, d.active ? deactivateDocument : activateDocument, d.active ? 'deactivate' : 'activate')
                        }
                      >
                        {d.active ? 'Deactivate' : 'Activate'}
                      </button>
                      <button
                        className="btn btn-ghost small"
                        disabled={busyId === d.id}
                        onClick={() => onDelete(d)}
                      >
                        Delete
                      </button>
                    </>
                  ) : (
                    <span className="muted small">Read only</span>
                  )}
                </td>
              </tr>
            ))}
          </tbody>
        </table>
      )}
    </div>
  )
}

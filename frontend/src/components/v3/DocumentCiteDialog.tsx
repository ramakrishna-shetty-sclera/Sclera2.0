import { useRef, useState } from 'react'
import { createDocument, readableSize, uploadFile } from '../../api/documents'
import type { ProcedureDocument } from '../../api/types'

/**
 * The reference-document library, for citing — not the AI source-document
 * picker (`DocumentLibraryDialog`, a different system entirely: `AiDocument`
 * from `api/ai.ts`). Same upload-plus-browse-plus-select shape, wired to the
 * real `procedure_document` library instead.
 *
 * Takes the caller's already-loaded `documents` rather than fetching its own
 * copy, so a document uploaded mid-dialog (`onUploaded`) and the caller's own
 * citation list stay in step with no second source of truth.
 */
export function DocumentCiteDialog({
  documents,
  onUploaded,
  onConfirm,
  onClose,
}: {
  documents: ProcedureDocument[]
  onUploaded: (doc: ProcedureDocument) => void
  onConfirm: (ids: string[]) => void
  onClose: () => void
}) {
  const [selected, setSelected] = useState<Record<string, boolean>>({})
  const [query, setQuery] = useState('')
  const [uploading, setUploading] = useState(false)
  const [error, setError] = useState<string | null>(null)
  const fileInput = useRef<HTMLInputElement | null>(null)

  // Only an active document can be cited — the server refuses publishing a
  // citation of one that isn't, so there is nothing to select here.
  const active = documents.filter((d) => d.active)
  const needle = query.trim().toLowerCase()
  const shown = active.filter((d) => !needle || d.name.toLowerCase().includes(needle))
  const picked = active.filter((d) => selected[d.id])

  function toggle(id: string) {
    setSelected((s) => ({ ...s, [id]: !s[id] }))
  }

  async function upload(files: FileList | null) {
    const file = files?.[0]
    if (!file) return
    setError(null)
    setUploading(true)
    try {
      const uploaded = await uploadFile(file)
      const doc = await createDocument({
        name: file.name,
        mimeType: file.type || undefined,
        sizeBytes: file.size,
        location: uploaded.location,
      })
      onUploaded(doc)
      setSelected((s) => ({ ...s, [doc.id]: true }))
    } catch (e) {
      setError(e instanceof Error ? e.message : 'Could not upload the file')
    } finally {
      setUploading(false)
    }
  }

  return (
    <div
      className="scrim"
      onMouseDown={(e) => {
        if (e.target === e.currentTarget) onClose()
      }}
    >
      <div
        className="dialog"
        role="dialog"
        aria-modal="true"
        aria-labelledby="dc-t"
        style={{ width: 760, maxHeight: '90%' }}
      >
        <div className="dhead">
          <h2 id="dc-t" style={{ fontSize: 20 }}>
            Attach a document
          </h2>
          <input
            ref={fileInput}
            type="file"
            style={{ display: 'none' }}
            aria-label="Upload a document"
            onChange={(e) => {
              void upload(e.target.files)
              e.target.value = ''
            }}
          />
          <button
            type="button"
            className="btn btn-outline btn-sm"
            style={{ marginLeft: 'auto' }}
            disabled={uploading}
            onClick={() => fileInput.current?.click()}
          >
            {uploading ? 'Uploading…' : '⇧ Upload a document'}
          </button>
          <button type="button" className="ibtn lg" aria-label="Close" onClick={onClose}>
            ×
          </button>
        </div>
        <div className="dbody" style={{ display: 'flex', flexDirection: 'column', gap: 14 }}>
          {error && <div className="alert-error">{error}</div>}
          <input
            type="search"
            className="input"
            aria-label="Search documents"
            placeholder="Search documents"
            value={query}
            onChange={(e) => setQuery(e.target.value)}
          />
          {active.length === 0 ? (
            <p className="muted">No documents yet — upload the first one.</p>
          ) : shown.length === 0 ? (
            <p className="muted">No documents match.</p>
          ) : (
            <div style={{ display: 'flex', flexDirection: 'column', gap: 8 }}>
              {shown.map((doc) => (
                <label key={doc.id} className={`topic${selected[doc.id] ? ' on' : ''}`} style={{ alignItems: 'center' }}>
                  <span style={{ flex: 1, display: 'flex', flexDirection: 'column', minWidth: 0 }}>
                    <span className="ellipsis" style={{ fontWeight: 500 }}>
                      {doc.name}
                    </span>
                    <span className="small muted">
                      {doc.propertyId == null ? 'Organization-wide' : 'This property'}
                      {doc.sizeBytes != null ? ` · ${readableSize(doc.sizeBytes)}` : ''}
                    </span>
                  </span>
                  <input
                    type="checkbox"
                    aria-label={`Select ${doc.name}`}
                    checked={!!selected[doc.id]}
                    onChange={() => toggle(doc.id)}
                  />
                </label>
              ))}
            </div>
          )}
        </div>
        <div className="dfoot">
          <span className="small muted">{picked.length} selected</span>
          <button
            type="button"
            className="btn btn-primary"
            disabled={picked.length === 0}
            onClick={() => onConfirm(picked.map((d) => d.id))}
          >
            Attach {picked.length || ''} document{picked.length === 1 ? '' : 's'}
          </button>
        </div>
      </div>
    </div>
  )
}

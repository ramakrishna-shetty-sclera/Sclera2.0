import { useEffect, useRef, useState } from 'react'
import { listAiDocuments, uploadAiDocument, type AiDocument } from '../../api/ai'

const MAX_SELECT = 5

/**
 * The organization's document library (docs/procedures-v3.html DocLibrary):
 * every standard or SOP ever uploaded, kept for reference. From here documents
 * are re-used — questions can be generated again from a document uploaded
 * weeks ago — or new ones imported by file picker or drag-and-drop.
 */
export function DocumentLibraryDialog({
  confirmLabel,
  onClose,
  onConfirm,
}: {
  /** e.g. "Add" → "Add 2 documents", "Create procedure from" → "Create procedure from 2 documents" */
  confirmLabel: string
  onClose: () => void
  onConfirm: (docs: AiDocument[]) => void
}) {
  const [docs, setDocs] = useState<AiDocument[] | null>(null)
  const [selected, setSelected] = useState<Record<string, boolean>>({})
  const [fresh, setFresh] = useState<Record<string, boolean>>({})
  const [query, setQuery] = useState('')
  const [over, setOver] = useState(false)
  const [error, setError] = useState<string | null>(null)
  const fileInput = useRef<HTMLInputElement | null>(null)

  useEffect(() => {
    listAiDocuments()
      .then(setDocs)
      .catch((e) => setError(e instanceof Error ? e.message : 'Could not load documents'))
  }, [])

  async function importFiles(files: FileList | null) {
    if (!files || files.length === 0) return
    setError(null)
    const results = await Promise.allSettled(Array.from(files).map((f) => uploadAiDocument(f)))
    const added = results.flatMap((r) => (r.status === 'fulfilled' ? [r.value] : []))
    const failed = results.flatMap((r) =>
      r.status === 'rejected' ? [r.reason instanceof Error ? r.reason.message : 'Upload failed'] : [],
    )
    if (added.length) {
      setDocs((d) => [...added, ...(d ?? [])])
      setFresh((f) => ({ ...f, ...Object.fromEntries(added.map((x) => [x.id, true])) }))
      setSelected((s) => {
        const next = { ...s }
        for (const doc of added) {
          if (Object.values(next).filter(Boolean).length < MAX_SELECT) next[doc.id] = true
        }
        return next
      })
    }
    if (failed.length) setError(failed.join(' · '))
  }

  const needle = query.trim().toLowerCase()
  const shown = (docs ?? []).filter((d) => !needle || d.filename.toLowerCase().includes(needle))
  const picked = (docs ?? []).filter((d) => selected[d.id])

  function toggle(doc: AiDocument) {
    setSelected((s) => {
      if (!s[doc.id] && Object.values(s).filter(Boolean).length >= MAX_SELECT) return s
      return { ...s, [doc.id]: !s[doc.id] }
    })
  }

  return (
    <div
      className="scrim"
      onMouseDown={(e) => {
        if (e.target === e.currentTarget) onClose()
      }}
    >
      <div className="dialog" role="dialog" aria-modal="true" aria-labelledby="dl-t" style={{ width: 880, maxHeight: '90%' }}>
        <div className="dhead">
          <h2 id="dl-t" style={{ fontSize: 20 }}>
            Documents
          </h2>
          <span className="tag">{docs?.length ?? '…'}</span>
          <input
            ref={fileInput}
            type="file"
            accept=".pdf,.docx,.txt"
            multiple
            style={{ display: 'none' }}
            aria-label="Import documents"
            onChange={(e) => {
              void importFiles(e.target.files)
              e.target.value = ''
            }}
          />
          <button className="btn btn-outline btn-sm" style={{ marginLeft: 'auto' }} onClick={() => fileInput.current?.click()}>
            ⇧ Import document
          </button>
          <button className="ibtn lg" aria-label="Close" onClick={onClose}>
            ×
          </button>
        </div>
        <div className="dbody" style={{ display: 'flex', flexDirection: 'column', gap: 14 }}>
          {error && <div className="alert-error">{error}</div>}
          <div
            role="button"
            tabIndex={0}
            onClick={() => fileInput.current?.click()}
            onKeyDown={(e) => {
              if (e.key === 'Enter' || e.key === ' ') {
                e.preventDefault()
                fileInput.current?.click()
              }
            }}
            onDragOver={(e) => {
              e.preventDefault()
              setOver(true)
            }}
            onDragLeave={() => setOver(false)}
            onDrop={(e) => {
              e.preventDefault()
              setOver(false)
              void importFiles(e.dataTransfer.files)
            }}
            style={{
              border: `1px dashed ${over ? 'var(--action)' : 'var(--dim)'}`,
              background: over ? 'var(--selected)' : 'transparent',
              borderRadius: 8,
              padding: '18px 16px',
              display: 'flex',
              alignItems: 'center',
              justifyContent: 'center',
              gap: 10,
              color: 'var(--muted)',
              cursor: 'pointer',
            }}
          >
            <span>
              <span style={{ color: 'var(--action)', fontWeight: 500 }}>Import a document</span> or drop PDF / Word /
              text files here
            </span>
          </div>
          <input
            type="search"
            className="input"
            aria-label="Search documents"
            placeholder="Search documents"
            value={query}
            onChange={(e) => setQuery(e.target.value)}
          />
          {docs == null ? (
            <p className="muted">Loading…</p>
          ) : shown.length === 0 ? (
            <p className="muted">{docs.length === 0 ? 'No documents yet — import the first one.' : 'No documents match.'}</p>
          ) : (
            <div style={{ display: 'grid', gridTemplateColumns: 'repeat(2,minmax(0,1fr))', gap: 12 }}>
              {shown.map((doc) => (
                <label key={doc.id} className={`topic${selected[doc.id] ? ' on' : ''}`} style={{ alignItems: 'center', padding: 14 }}>
                  <span className="tag fail" style={{ height: 40, width: 40, justifyContent: 'center' }}>
                    {extOf(doc.filename)}
                  </span>
                  <span style={{ flex: 1, display: 'flex', flexDirection: 'column', minWidth: 0 }}>
                    <span className="ellipsis" style={{ fontWeight: 500 }}>
                      {doc.filename}
                    </span>
                    <span className="small muted">
                      {new Date(doc.createdAt).toLocaleDateString()} · {formatBytes(doc.sizeBytes)}
                      {doc.status === 'FAILED' ? ' · unreadable' : doc.status !== 'READY' ? ' · processing…' : ''}
                    </span>
                  </span>
                  {fresh[doc.id] && <span className="tag pass">New</span>}
                  <input
                    type="checkbox"
                    aria-label={`Select ${doc.filename}`}
                    checked={!!selected[doc.id]}
                    disabled={doc.status === 'FAILED'}
                    onChange={() => toggle(doc)}
                  />
                </label>
              ))}
            </div>
          )}
        </div>
        <div className="dfoot">
          <span className="small muted">Select up to {MAX_SELECT} documents per procedure.</span>
          <button className="btn btn-primary" disabled={picked.length === 0} onClick={() => onConfirm(picked)}>
            {confirmLabel} {picked.length || ''} document{picked.length === 1 ? '' : 's'}
          </button>
        </div>
      </div>
    </div>
  )
}

function extOf(filename: string): string {
  const dot = filename.lastIndexOf('.')
  return dot < 0 ? 'DOC' : filename.slice(dot + 1).toUpperCase().slice(0, 4)
}

export function formatBytes(bytes: number): string {
  if (bytes < 1024) return `${bytes} B`
  if (bytes < 1024 * 1024) return `${Math.round(bytes / 1024)} KB`
  return `${(bytes / (1024 * 1024)).toFixed(1)} MB`
}

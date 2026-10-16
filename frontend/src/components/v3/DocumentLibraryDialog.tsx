import { useEffect, useRef, useState } from 'react'
import { listAiDocuments, uploadAiDocument, type AiDocument } from '../../api/ai'
import { fetchDocumentFile, listDocuments, readableSize } from '../../api/documents'
import type { ProcedureDocument } from '../../api/types'

const MAX_SELECT = 5

/** One row in the merged browse list — either system's document, rendered identically either way. */
type Entry = { kind: 'ai'; doc: AiDocument } | { kind: 'reference'; doc: ProcedureDocument }

function entryKey(e: Entry): string {
  return `${e.kind}:${e.doc.id}`
}

function entryName(e: Entry): string {
  return e.kind === 'ai' ? e.doc.filename : e.doc.name
}

/**
 * The organization's document library (docs/procedures-v3.html DocLibrary):
 * every standard or SOP ever uploaded, kept for reference. From here documents
 * are re-used — questions can be generated again from a document uploaded
 * weeks ago — or new ones imported by file picker or drag-and-drop.
 *
 * Browses **two** backend systems at once, merged into one list, because they
 * are genuinely two different tables: `ai_document` (this dialog's own,
 * always has been) and `procedure_document` (the reference-document library
 * used elsewhere for citing a document on a procedure or question). Picking
 * an `ai_document` entry behaves exactly as before. Picking a
 * `procedure_document` entry that was never uploaded here bridges it in at
 * the moment of selection — fetches its bytes and re-uploads them through
 * the same `uploadAiDocument` endpoint a direct upload in this dialog already
 * uses, so it goes through the identical PENDING→EXTRACTING→READY pipeline.
 * Uploading a brand-new file through this dialog's own "Import document"
 * still goes straight into `ai_document`, unchanged — only the *browse* list
 * and the *bridging on selection* are new.
 */
export function DocumentLibraryDialog({
  confirmLabel,
  onClose,
  onConfirm,
}: {
  confirmLabel: string
  onClose: () => void
  onConfirm: (docs: AiDocument[]) => void
}) {
  const [docs, setDocs] = useState<AiDocument[] | null>(null)
  const [refDocs, setRefDocs] = useState<ProcedureDocument[] | null>(null)
  const [selected, setSelected] = useState<Record<string, boolean>>({})
  const [fresh, setFresh] = useState<Record<string, boolean>>({})
  const [query, setQuery] = useState('')
  const [over, setOver] = useState(false)
  const [error, setError] = useState<string | null>(null)
  const [importing, setImporting] = useState(false)
  const fileInput = useRef<HTMLInputElement | null>(null)

  useEffect(() => {
    listAiDocuments()
      .then(setDocs)
      .catch((e) => setError(e instanceof Error ? e.message : 'Could not load documents'))
    listDocuments(true)
      .then(setRefDocs)
      .catch(() => setRefDocs([]))
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
      setFresh((f) => ({ ...f, ...Object.fromEntries(added.map((x) => [`ai:${x.id}`, true])) }))
      setSelected((s) => {
        const next = { ...s }
        for (const doc of added) {
          if (Object.values(next).filter(Boolean).length < MAX_SELECT) next[`ai:${doc.id}`] = true
        }
        return next
      })
    }
    if (failed.length) setError(failed.join(' · '))
  }

  const entries: Entry[] = [
    ...(docs ?? []).map((doc): Entry => ({ kind: 'ai', doc })),
    ...(refDocs ?? []).map((doc): Entry => ({ kind: 'reference', doc })),
  ]
  const loaded = docs != null && refDocs != null
  const needle = query.trim().toLowerCase()
  const shown = entries.filter((e) => !needle || entryName(e).toLowerCase().includes(needle))
  const picked = entries.filter((e) => selected[entryKey(e)])

  function toggle(e: Entry) {
    const key = entryKey(e)
    setSelected((s) => {
      if (!s[key] && Object.values(s).filter(Boolean).length >= MAX_SELECT) return s
      return { ...s, [key]: !s[key] }
    })
  }

  /** A reference-only document has never been through extraction — bridge it in, the same way a direct upload here already would. */
  async function bridge(doc: ProcedureDocument): Promise<AiDocument> {
    const file = await fetchDocumentFile(doc)
    return uploadAiDocument(file)
  }

  async function confirm() {
    const aiPicked = picked.filter((e): e is { kind: 'ai'; doc: AiDocument } => e.kind === 'ai').map((e) => e.doc)
    const refPicked = picked.filter(
      (e): e is { kind: 'reference'; doc: ProcedureDocument } => e.kind === 'reference',
    )
    if (refPicked.length === 0) {
      onConfirm(aiPicked)
      return
    }
    setError(null)
    setImporting(true)
    try {
      const bridged = await Promise.all(refPicked.map((e) => bridge(e.doc)))
      onConfirm([...aiPicked, ...bridged])
    } catch (e) {
      setError(e instanceof Error ? e.message : 'Could not import a selected library document')
    } finally {
      setImporting(false)
    }
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
          <span className="tag">{loaded ? entries.length : '…'}</span>
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
          {!loaded ? (
            <p className="muted">Loading…</p>
          ) : shown.length === 0 ? (
            <p className="muted">{entries.length === 0 ? 'No documents yet — import the first one.' : 'No documents match.'}</p>
          ) : (
            <div style={{ display: 'grid', gridTemplateColumns: 'repeat(2,minmax(0,1fr))', gap: 12 }}>
              {shown.map((e) => {
                const key = entryKey(e)
                const name = entryName(e)
                const failed = e.kind === 'ai' && e.doc.status === 'FAILED'
                return (
                  <label key={key} className={`topic${selected[key] ? ' on' : ''}`} style={{ alignItems: 'center', padding: 14 }}>
                    <span className="tag fail" style={{ height: 40, width: 40, justifyContent: 'center' }}>
                      {extOf(name)}
                    </span>
                    <span style={{ flex: 1, display: 'flex', flexDirection: 'column', minWidth: 0 }}>
                      <span className="ellipsis" style={{ fontWeight: 500 }}>
                        {name}
                      </span>
                      <span className="small muted">
                        {e.kind === 'ai' ? (
                          <>
                            {new Date(e.doc.createdAt).toLocaleDateString()} · {formatBytes(e.doc.sizeBytes)}
                            {e.doc.status === 'FAILED' ? ' · unreadable' : e.doc.status !== 'READY' ? ' · processing…' : ''}
                          </>
                        ) : (
                          <>
                            {e.doc.uploadedAt ? `${new Date(e.doc.uploadedAt).toLocaleDateString()} · ` : ''}
                            {readableSize(e.doc.sizeBytes)}
                          </>
                        )}
                      </span>
                    </span>
                    {fresh[key] && <span className="tag pass">New</span>}
                    <input
                      type="checkbox"
                      aria-label={`Select ${name}`}
                      checked={!!selected[key]}
                      disabled={failed}
                      onChange={() => toggle(e)}
                    />
                  </label>
                )
              })}
            </div>
          )}
        </div>
        <div className="dfoot">
          <span className="small muted">Select up to {MAX_SELECT} documents per procedure.</span>
          <button className="btn btn-primary" disabled={picked.length === 0 || importing} onClick={() => void confirm()}>
            {importing ? 'Importing…' : `${confirmLabel} ${picked.length || ''} document${picked.length === 1 ? '' : 's'}`}
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

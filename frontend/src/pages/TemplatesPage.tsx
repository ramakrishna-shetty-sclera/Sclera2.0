import { useEffect, useMemo, useState } from 'react'
import { Link, useNavigate } from 'react-router-dom'
import { favouriteProcedure, listProcedures, unfavouriteProcedure } from '../api/templates'
import { listDocuments } from '../api/documents'
import type { Pagination, ProcedureDocument, ProcedureTemplate } from '../api/types'
import { useAuth } from '../auth/AuthContext'
import { Pager } from '../components/Pager'
import { DocumentCiteDialog } from '../components/v3/DocumentCiteDialog'
import { FOLDERS, PRIORITY_CLASS } from '../components/v3/procedureVocab'

/**
 * The procedure library, in the Sclera 2.0 design (docs/procedures-v3.html):
 * a card grid behind All / Published / Drafts / Favourites / Archived tabs.
 *
 * The tabs are cuts of the model, not a status field: Published means a
 * current published version exists, Drafts means a draft is open — a
 * procedure with both (published, being edited) appears under each and its
 * card says "Unpublished changes". Favourites is a star the user sets
 * themselves, independent of everything else — the same procedure shows in
 * "All" whether starred or not. Archived is its own tab because an archived
 * procedure is out of every other conversation.
 */
type Cut = 'all' | 'published' | 'draft' | 'favourites' | 'archived'

export function TemplatesPage() {
  const navigate = useNavigate()
  const { permissions, permissionsLoaded } = useAuth()
  const [procedures, setProcedures] = useState<ProcedureTemplate[]>([])
  const [pagination, setPagination] = useState<Pagination | null>(null)
  const [cut, setCut] = useState<Cut>('all')
  const [query, setQuery] = useState('')
  const [page, setPage] = useState(0)
  const [error, setError] = useState<string | null>(null)
  const [loading, setLoading] = useState(true)
  const [docLibOpen, setDocLibOpen] = useState(false)
  const [libraryDocuments, setLibraryDocuments] = useState<ProcedureDocument[]>([])
  const [view, setView] = useState<'grid' | 'folders'>('grid')
  const [folder, setFolder] = useState<string | null>(null)

  useEffect(() => {
    listDocuments()
      .then(setLibraryDocuments)
      .catch(() => setLibraryDocuments([]))
  }, [])

  useEffect(() => {
    let cancelled = false
    setLoading(true)
    listProcedures({ status: cut === 'archived' ? 'ARCHIVED' : 'ACTIVE', page, size: 30 })
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
  }, [cut === 'archived', page])

  function toggleFavourite(p: ProcedureTemplate) {
    const next = !p.favourite
    // Optimistic: flip it locally first, and only undo if the server refuses.
    setProcedures((prev) => prev.map((x) => (x.id === p.id ? { ...x, favourite: next } : x)))
    ;(next ? favouriteProcedure(p.id) : unfavouriteProcedure(p.id)).catch(() => {
      setProcedures((prev) => prev.map((x) => (x.id === p.id ? { ...x, favourite: !next } : x)))
    })
  }

  const needle = query.trim().toLowerCase()
  const base = useMemo(
    () =>
      procedures.filter(
        (p) => !needle || `${p.name} ${p.description ?? ''}`.toLowerCase().includes(needle),
      ),
    [procedures, needle],
  )
  const published = base.filter((p) => p.currentPublishedVersionNo != null)
  const drafts = base.filter((p) => p.draftVersionNo != null)
  const favourites = base.filter((p) => p.favourite)
  const byStatus =
    cut === 'published' ? published : cut === 'draft' ? drafts : cut === 'favourites' ? favourites : base
  const inFolder = view === 'folders' && folder != null
  const shown = inFolder ? byStatus.filter((p) => folderOf(p) === folder) : byStatus
  const showFolders = view === 'folders' && folder == null

  const tabs: [Cut, string, number][] = [
    ['all', 'All', base.length],
    ['published', 'Published', published.length],
    ['draft', 'Drafts', drafts.length],
    ['favourites', 'Favourites', favourites.length],
    ['archived', 'Archived', cut === 'archived' ? base.length : 0],
  ]

  return (
    <div className="v3 v3-page">
      <div className="phead">
        <div className="ptitle">
          <h1>Procedures</h1>
          <span className="psub">
            {shown.length} procedure{shown.length === 1 ? '' : 's'} · every published version is
            kept; editing starts a new draft
          </span>
        </div>
        <div className="pacts">
          <button className="btn btn-outline" onClick={() => setDocLibOpen(true)}>
            ⌸ Documents
          </button>
          {permissionsLoaded && permissions.canManageTemplates && (
            <Link className="btn btn-primary" to="/templates/new">
              + Create procedure
            </Link>
          )}
        </div>
      </div>

      {docLibOpen && (
        <DocumentCiteDialog
          documents={libraryDocuments}
          onUploaded={(doc) => setLibraryDocuments((prev) => [doc, ...prev])}
          onClose={() => setDocLibOpen(false)}
        />
      )}

      <div className="lib-head">
        <div className="dtabs" role="tablist" aria-label="Status">
          {tabs.map(([k, label, n]) => (
            <button
              key={k}
              role="tab"
              className="dtab"
              aria-selected={cut === k}
              onClick={() => {
                setCut(k)
                setPage(0)
              }}
            >
              {label}
              <span className={`count-badge${k === 'draft' && n > 0 ? '' : ' quiet'}`}>
                {k === 'archived' && cut !== 'archived' ? '…' : n}
              </span>
            </button>
          ))}
        </div>
        <div className="row between" style={{ paddingBottom: 16 }}>
          <label className="search">
            <span aria-hidden="true">⌕</span>
            <input
              type="search"
              aria-label="Search procedures"
              placeholder="Search by name or description"
              value={query}
              onChange={(e) => setQuery(e.target.value)}
            />
          </label>
          <div className="seg-tabs" role="group" aria-label="View">
            <button
              aria-pressed={view === 'grid'}
              onClick={() => {
                setView('grid')
                setFolder(null)
              }}
            >
              ▦ Grid
            </button>
            <button aria-pressed={view === 'folders'} onClick={() => setView('folders')}>
              🗀 Folders
            </button>
          </div>
        </div>
      </div>

      <div className="scroll" style={{ padding: '0 24px 24px' }}>
        {error && <div className="alert-error">{error}</div>}
        {inFolder && (
          <div className="row" style={{ gap: 8, paddingBottom: 12 }}>
            <button className="link" onClick={() => setFolder(null)}>
              ‹ All folders
            </button>
            <span className="small muted">
              {folder} · {shown.length} procedure{shown.length === 1 ? '' : 's'}
            </span>
          </div>
        )}
        {loading ? (
          <p className="muted">Loading…</p>
        ) : showFolders ? (
          <div style={{ display: 'grid', gridTemplateColumns: 'repeat(5,minmax(0,1fr))', gap: 16 }}>
            {FOLDERS.map((f) => {
              const inF = byStatus.filter((p) => folderOf(p) === f)
              const dr = inF.filter((p) => p.draftVersionNo != null).length
              return (
                <button key={f} className="fcard" onClick={() => setFolder(f)}>
                  <span aria-hidden="true" style={{ fontSize: 36, lineHeight: 1 }}>
                    🗀
                  </span>
                  <span style={{ fontWeight: 600, color: 'var(--heading)', fontSize: 15, marginTop: 6 }}>{f}</span>
                  <span className="small muted">
                    {inF.length} procedure{inF.length === 1 ? '' : 's'}
                    {dr ? ` · ${dr} draft${dr > 1 ? 's' : ''}` : ''}
                  </span>
                </button>
              )
            })}
          </div>
        ) : shown.length === 0 ? (
          <div className="empty">
            <h2 style={{ fontSize: 16 }}>
              {cut === 'draft'
                ? 'No open drafts'
                : cut === 'favourites'
                  ? 'No favourites yet'
                  : cut === 'archived'
                    ? 'Nothing archived'
                    : 'No procedures yet'}
            </h2>
            <p className="muted">
              {cut === 'draft'
                ? 'Editing a procedure opens a draft, and it appears here until it is published.'
                : cut === 'favourites'
                  ? 'Star a procedure from any tab to find it here quickly.'
                  : 'Create one to start authoring, or clear the search.'}
            </p>
            {cut !== 'favourites' && permissionsLoaded && permissions.canManageTemplates && (
              <Link className="btn btn-primary" to="/templates/new">
                Create procedure
              </Link>
            )}
          </div>
        ) : (
          <div className="grid3">
            {shown.map((p) => (
              <ProcCard
                key={p.id}
                p={p}
                onOpen={() => navigate(`/templates/${p.id}`)}
                onToggleFavourite={() => toggleFavourite(p)}
              />
            ))}
          </div>
        )}
        <Pager pagination={pagination} onPage={setPage} />
      </div>
    </div>
  )
}

/** The design's default folder — a procedure filed nowhere shows under Inspection. */
function folderOf(p: ProcedureTemplate): string {
  return p.meta?.folder || 'Inspection'
}

function ProcCard({
  p,
  onOpen,
  onToggleFavourite,
}: {
  p: ProcedureTemplate
  onOpen: () => void
  onToggleFavourite: () => void
}) {
  const archived = p.status === 'ARCHIVED'
  const isPublished = p.currentPublishedVersionNo != null
  const hasDraft = p.draftVersionNo != null
  const draftOnly = hasDraft && !isPublished
  const meta = p.meta ?? {}

  return (
    <div
      className={`pcard${draftOnly ? ' draft' : ''}`}
      role="button"
      tabIndex={0}
      style={{ cursor: 'pointer' }}
      onClick={onOpen}
      onKeyDown={(e) => {
        if (e.key === 'Enter' || e.key === ' ') {
          e.preventDefault()
          onOpen()
        }
      }}
    >
      <span className="row" style={{ alignItems: 'flex-start', gap: 14, width: '100%' }}>
        <span className="ptile" aria-hidden="true">
          ☑
        </span>
        <span style={{ display: 'flex', flexDirection: 'column', gap: 6, minWidth: 0, flex: 1 }}>
          <span className="row" style={{ gap: 6, flexWrap: 'wrap' }}>
            {archived ? (
              <span className="tag">Archived</span>
            ) : isPublished ? (
              <span className="tag pass">Published</span>
            ) : (
              <span className="tag">Draft</span>
            )}
            {isPublished && hasDraft && !archived && (
              <span className="tag warn">Unpublished changes</span>
            )}
            {meta.priority && !archived && (
              <span className={`tag ${PRIORITY_CLASS[meta.priority] ?? ''}`}>{meta.priority}</span>
            )}
          </span>
          <span
            className="ellipsis"
            style={{ fontWeight: 500, fontSize: 16, color: 'var(--heading)' }}
          >
            {p.name}
          </span>
          <span className="small muted clamp2">{p.description || 'No description yet.'}</span>
        </span>
        <button
          className="ibtn"
          aria-label={p.favourite ? 'Remove from favourites' : 'Add to favourites'}
          aria-pressed={p.favourite}
          style={{ color: p.favourite ? 'var(--action)' : undefined, marginLeft: 'auto' }}
          onClick={(e) => {
            e.stopPropagation()
            onToggleFavourite()
          }}
        >
          {p.favourite ? '★' : '☆'}
        </button>
      </span>
      <span className="meta3">
        <span>
          <span>{draftOnly ? 'Folder' : 'Frequency'}</span>
          {(draftOnly ? folderOf(p) : meta.frequency) || '—'}
        </span>
        <span>
          <span>{draftOnly ? 'Draft' : 'Published'}</span>
          {draftOnly ? `v${p.draftVersionNo}` : `v${p.currentPublishedVersionNo}`}
        </span>
        <span>
          <span>Updated</span>
          {new Date(p.updatedAt).toLocaleDateString()}
        </span>
      </span>
      <span className="row" style={{ gap: 6, flexWrap: 'wrap' }}>
        {meta.category ? <span className="tag">{meta.category}</span> : <span className="tag line">No category</span>}
        {meta.standard && <span className="tag line">{meta.standard}</span>}
        {draftOnly && !archived && (
          <span className="small" style={{ marginLeft: 'auto', color: 'var(--action)', fontWeight: 500 }}>
            Continue editing →
          </span>
        )}
      </span>
    </div>
  )
}

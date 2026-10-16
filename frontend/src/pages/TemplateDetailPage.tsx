import { useCallback, useEffect, useState } from 'react'
import { useNavigate, useParams } from 'react-router-dom'
import {
  archiveProcedure,
  cloneProcedure,
  createDraft,
  diffVersions,
  discardDraft,
  exportProcedure,
  getDraft,
  getProcedure,
  getVersion,
  listVersions,
  publishProcedure,
} from '../api/templates'
import { createInspection } from '../api/inspections'
import type {
  DefinitionItem,
  ProcedureDiff,
  ProcedureTemplate,
  TemplateVersion,
  TemplateVersionSummary,
} from '../api/types'
import { useAuth } from '../auth/AuthContext'
import { Refusal } from '../components/Refusal'
import { DiffSummary } from '../components/v3/DiffSummary'
import { PRIORITY_CLASS } from '../components/v3/procedureVocab'
import { ReadOnlyChecklist } from '../components/v3/ReadOnlyChecklist'

/**
 * One procedure in the Sclera 2.0 design (docs/procedures-v3.html ViewPage):
 * metrics, the checklist read-only, and a details card beside it.
 *
 * The actions still follow from the model, not a status field: a draft is the
 * only editable thing, so Edit/Publish/Discard appear exactly when one exists
 * and "Start a draft" when one does not.
 */
export function TemplateDetailPage() {
  const { id } = useParams<{ id: string }>()
  const navigate = useNavigate()
  const { permissions, permissionsLoaded } = useAuth()
  const [procedure, setProcedure] = useState<ProcedureTemplate | null>(null)
  const [version, setVersion] = useState<TemplateVersion | null>(null)
  const [versions, setVersions] = useState<TemplateVersionSummary[]>([])
  const [diff, setDiff] = useState<ProcedureDiff | null>(null)
  const [error, setError] = useState<string | null>(null)
  const [refusal, setRefusal] = useState<unknown>(null)
  const [notice, setNotice] = useState<string | null>(null)
  const [busy, setBusy] = useState(false)
  const [exportOpen, setExportOpen] = useState(false)
  const [exportFormat, setExportFormat] = useState<'pdf' | 'xlsx' | null>(null)
  const canManage = permissionsLoaded && permissions.canManageTemplates
  const canPublish = permissionsLoaded && permissions.canPublishTemplates

  const reload = useCallback(async () => {
    if (!id) return
    const p = await getProcedure(id)
    setProcedure(p)
    setVersions(await listVersions(id))
    // The published version is what inspections run against, so it is what
    // this page shows; an open draft is announced, not displayed (the
    // procedures-v3 design). A never-published procedure IS its draft.
    if (p.currentPublishedVersionNo != null) {
      setVersion(await getVersion(id, p.currentPublishedVersionNo))
    } else if (p.draftVersionNo != null) {
      setVersion(await getDraft(id))
    } else {
      setVersion(null)
    }
  }, [id])

  async function showVersion(versionNo: number) {
    if (!id || !procedure) return
    setError(null)
    try {
      setVersion(
        versionNo === procedure.draftVersionNo
          ? await getDraft(id)
          : await getVersion(id, versionNo),
      )
    } catch (e) {
      setError(e instanceof Error ? e.message : 'Could not load that version')
    }
  }

  useEffect(() => {
    reload().catch((e) => setError(e instanceof Error ? e.message : 'Could not load this procedure'))
  }, [reload])

  // What changed to produce whichever version is on screen — against the one
  // immediately before it, so clicking back through history reads like a
  // sequence of changes rather than one big comparison against today.
  useEffect(() => {
    if (!id || !version || versions.length < 2) {
      setDiff(null)
      return
    }
    const sorted = [...versions].sort((a, b) => a.versionNo - b.versionNo)
    const at = sorted.findIndex((v) => v.versionNo === version.versionNo)
    const previous = at > 0 ? sorted[at - 1] : null
    if (!previous) {
      setDiff(null)
      return
    }
    let cancelled = false
    diffVersions(id, previous.versionNo, version.versionNo)
      .then((d) => !cancelled && setDiff(d))
      .catch(() => !cancelled && setDiff(null))
    return () => {
      cancelled = true
    }
  }, [id, version, versions])

  async function run(action: () => Promise<unknown>) {
    setBusy(true)
    setError(null)
    setRefusal(null)
    setNotice(null)
    try {
      await action()
      await reload()
    } catch (e) {
      setRefusal(e instanceof Error ? e : new Error('Action failed'))
    } finally {
      setBusy(false)
    }
  }

  async function onPublish() {
    if (!procedure || procedure.draftVersionNo == null) return
    await run(async () => {
      // `version` is the published one this page shows, not the draft being
      // published: its row version belongs to a different row and would trip
      // the server's stale-draft check. Read the open draft and send its own.
      const draft = await getDraft(procedure.id)
      const result = await publishProcedure(procedure.id, draft.rowVersion)
      setNotice(
        result.newVersion
          ? `Published v${result.version.versionNo}.`
          : `No change: the draft matched v${result.version.versionNo}, which is already published. The draft was discarded.`,
      )
    })
  }

  async function onClone() {
    if (!procedure) return
    const name = prompt('Name for the copy', `${procedure.name} (copy)`)
    if (!name) return
    setBusy(true)
    setError(null)
    try {
      const copy = await cloneProcedure(procedure.id, { name })
      navigate(`/templates/${copy.id}`)
    } catch (e) {
      setError(e instanceof Error ? e.message : 'Could not duplicate')
      setBusy(false)
    }
  }

  async function onExport() {
    if (!procedure || !version || !exportFormat) return
    setBusy(true)
    setError(null)
    try {
      const { blob, filename } = await exportProcedure(procedure.id, exportFormat, version.versionNo)
      const url = URL.createObjectURL(blob)
      const a = document.createElement('a')
      a.href = url
      a.download = filename ?? `${procedure.name}-v${version.versionNo}.${exportFormat}`
      a.click()
      URL.revokeObjectURL(url)
      setExportOpen(false)
      setNotice(`Exported v${version.versionNo} as ${exportFormat.toUpperCase()}.`)
    } catch (e) {
      setError(e instanceof Error ? e.message : 'Export failed')
    } finally {
      setBusy(false)
    }
  }

  async function startInspection() {
    if (!procedure) return
    setBusy(true)
    setError(null)
    try {
      const inspection = await createInspection({ templateId: procedure.id })
      navigate(`/inspections/${inspection.id}`)
    } catch (e) {
      setError(e instanceof Error ? e.message : 'Could not create inspection')
      setBusy(false)
    }
  }

  if (!procedure) {
    return (
      <div className="v3 v3-page" style={{ padding: 24 }}>
        {error ? <div className="alert-error">{error}</div> : <p className="muted">Loading…</p>}
      </div>
    )
  }

  const hasDraft = procedure.draftVersionNo != null
  const published = procedure.currentPublishedVersionNo
  const archived = procedure.status === 'ARCHIVED'
  const meta = procedure.meta ?? {}
  const draftSummary = versions.find((v) => v.state === 'DRAFT')
  const draftSavedAt = draftSummary ? new Date(draftSummary.createdAt).toLocaleDateString() : null
  const items = version?.definition.items ?? []
  const flat = flattenItems(items)
  const questions = flat.filter((f) => f.item.type !== 'SECTION')
  const stats: [string, number, string, string][] = [
    ['Questions', questions.filter((f) => !f.parent).length, 'Top-level checks', '☑'],
    ['Required', questions.filter((f) => f.item.required).length, 'Must be answered', '✱'],
    ['Follow-ups', questions.filter((f) => f.parent).length, 'Shown on a matching answer', '↳'],
    ['Work orders', questions.filter((f) => f.item.workOrder).length, 'Raised on a failed answer', '⚠'],
  ]

  return (
    <div className="v3 v3-page">
      <div className="phead">
        <button className="ibtn xl" aria-label="Back to procedures" onClick={() => navigate('/templates')}>
          ‹
        </button>
        <div className="ptitle">
          <span className="psub">Procedure</span>
          <div className="row" style={{ gap: 12, flexWrap: 'wrap' }}>
            <h1>{procedure.name}</h1>
            {archived ? (
              <span className="tag">Archived</span>
            ) : published != null ? (
              <span className="tag pass">Published v{published}</span>
            ) : (
              <span className="tag">Never published</span>
            )}
            {hasDraft && <span className="tag warn">Draft v{procedure.draftVersionNo} open</span>}
            {meta.priority && (
              <span className={`tag ${PRIORITY_CLASS[meta.priority] ?? ''}`}>{meta.priority}</span>
            )}
          </div>
        </div>
        <div className="pacts">
          {published != null && (
            <button className="btn btn-outline" disabled={busy} onClick={startInspection}>
              New inspection
            </button>
          )}
          {version != null && (
            <button className="btn btn-outline" disabled={busy} onClick={() => setExportOpen(true)}>
              ⤓ Export
            </button>
          )}
          {canManage && (
            <button className="btn btn-outline" disabled={busy} onClick={onClone}>
              Duplicate
            </button>
          )}
          {!archived && hasDraft && (
            <>
              {canPublish && (
                <button className="btn btn-quiet" disabled={busy} onClick={onPublish}>
                  Publish
                </button>
              )}
              {canManage && (
                <button
                  className="btn btn-primary"
                  onClick={() => navigate(`/templates/${procedure.id}/edit`)}
                >
                  ✎ Edit
                </button>
              )}
            </>
          )}
          {!archived && !hasDraft && published != null && canManage && (
            <button
              className="btn btn-primary"
              disabled={busy}
              onClick={() => run(() => createDraft(procedure.id, { fromVersionNo: published }))}
            >
              ✎ Edit (start a draft)
            </button>
          )}
        </div>
      </div>

      <div className="scroll" style={{ padding: 16, display: 'flex', flexDirection: 'column', gap: 12 }}>
        {notice && <div className="alert-ok">{notice}</div>}
        {error && <div className="alert-error">{error}</div>}
        <Refusal error={refusal} />

        <div className="row" style={{ gap: 12, alignItems: 'stretch' }}>
          {stats.map(([label, value, note, icon]) => (
            <div key={label} className="metric">
              <div className="mbody">
                <span className="mlabel">{label}</span>
                <span className="mval">{value}</span>
                <span className="mnote">{note}</span>
              </div>
              <span className="ichip" aria-hidden="true">
                {icon}
              </span>
            </div>
          ))}
        </div>

        <div style={{ display: 'flex', gap: 12, alignItems: 'flex-start' }}>
          <section
            style={{ flex: 1, minWidth: 0, display: 'flex', flexDirection: 'column', gap: 8 }}
            aria-label="Checklist"
          >
            {hasDraft && !archived && (
              <div className="pending-strip" role="status">
                <span aria-hidden="true" style={{ color: 'var(--action)' }}>
                  ✎
                </span>
                <span style={{ flex: 1 }}>
                  {published != null ? (
                    <>
                      You have unpublished changes saved as draft v{procedure.draftVersionNo}
                      {draftSavedAt ? ` (${draftSavedAt})` : ''}.{' '}
                      {version?.state === 'DRAFT'
                        ? 'You are viewing that draft.'
                        : `This page shows the published version (v${version?.versionNo}).`}
                    </>
                  ) : (
                    <>Draft v{procedure.draftVersionNo} is open — this page shows the draft.</>
                  )}
                </span>
                {canManage && (
                  <>
                    <button
                      className="btn btn-quiet btn-xs"
                      disabled={busy || published == null}
                      title={published == null ? 'A never-published procedure is only its draft' : undefined}
                      onClick={() => {
                        if (confirm(`Discard draft v${procedure.draftVersionNo}? Its edits are lost.`)) {
                          void run(() => discardDraft(procedure.id))
                        }
                      }}
                    >
                      Discard draft
                    </button>
                    <button
                      className="btn btn-primary btn-xs"
                      onClick={() => navigate(`/templates/${procedure.id}/edit`)}
                    >
                      Continue editing
                    </button>
                  </>
                )}
              </div>
            )}
            {procedure.description && (
              <p className="muted" style={{ maxWidth: '65ch', marginBottom: 8 }}>
                {procedure.description}
              </p>
            )}
            {version == null ? (
              <div className="empty">
                <h2 style={{ fontSize: 16 }}>Nothing authored yet</h2>
                {canManage && (
                  <button className="btn btn-primary" onClick={() => navigate(`/templates/${procedure.id}/edit`)}>
                    Start the draft
                  </button>
                )}
              </div>
            ) : (
              <>
                <div className="row" style={{ gap: 8 }}>
                  <span className="small muted">
                    Showing {version.state === 'DRAFT' ? 'draft ' : ''}v{version.versionNo}
                    {version.changeNote ? ` — ${version.changeNote}` : ''}
                  </span>
                </div>
                {diff && (
                  <div className="dcard" style={{ padding: 14 }}>
                    <DiffSummary result={diff} />
                  </div>
                )}
                <ReadOnlyChecklist items={items} />
              </>
            )}
          </section>

          <aside style={{ width: 360, flexShrink: 0, display: 'flex', flexDirection: 'column', gap: 12 }}>
            <section className="dcard" aria-labelledby="pd-t">
              <div className="dcard-head">
                <h2 id="pd-t">Procedure details</h2>
                {!archived && canManage && (
                  <button className="link" onClick={() => navigate(`/templates/${procedure.id}/edit`)}>
                    Edit
                  </button>
                )}
              </div>
              <div className="fields">
                {(
                  [
                    ['Status', archived ? 'Archived' : 'Active'],
                    ['Category', meta.category ?? '—'],
                    ['Sub category', meta.subCategory ?? '—'],
                    ['Standard', meta.standard ?? '—'],
                    ['Frequency', meta.frequency ?? '—'],
                    ['Priority', meta.priority ?? '—'],
                    ['Code', meta.code ?? '—'],
                    ['Availability', meta.availability === 'ORG' ? 'Entire organization' : 'This property'],
                    ['Folder', meta.folder ?? '—'],
                    ['Consumer', procedure.consumerKey ?? 'INSPECTION'],
                    ['Published', published != null ? `v${published}` : 'Never'],
                    ['Draft', hasDraft ? `v${procedure.draftVersionNo}` : 'None'],
                    ['Created', new Date(procedure.createdAt).toLocaleDateString()],
                    ['Updated', new Date(procedure.updatedAt).toLocaleDateString()],
                  ] as [string, string][]
                ).map(([k, v]) => (
                  <div key={k} className="fld">
                    <span>{k}</span>
                    <span>{v}</span>
                  </div>
                ))}
              </div>
              {!archived && canManage && (
                <div style={{ padding: '0 16px 16px' }}>
                  <button
                    className="btn btn-danger btn-xs"
                    disabled={busy}
                    onClick={() => {
                      if (confirm('Archive this procedure? It can no longer be used for new work.')) {
                        void run(() => archiveProcedure(procedure.id))
                      }
                    }}
                  >
                    Archive procedure
                  </button>
                </div>
              )}
            </section>

            {versions.length > 0 && (
              <section className="dcard" aria-labelledby="vh-t">
                <div className="dcard-head">
                  <h2 id="vh-t">Versions</h2>
                </div>
                <div style={{ padding: 8, display: 'flex', flexDirection: 'column' }}>
                  {versions.map((v) => (
                    <button
                      key={v.id}
                      className="nitem"
                      style={{ alignItems: 'center' }}
                      onClick={() => void showVersion(v.versionNo)}
                    >
                      <span className="num">v{v.versionNo}</span>
                      <span className="body">
                        <span className="txt">
                          {v.state === 'DRAFT' ? 'Draft' : v.state === 'PUBLISHED' ? 'Published' : 'Archived'}
                          {v.changeNote ? ` — ${v.changeNote}` : ''}
                        </span>
                        <span className="sub">
                          {new Date(v.publishedAt ?? v.createdAt).toLocaleString()}
                        </span>
                      </span>
                      {version?.versionNo === v.versionNo && <span className="tag sel">Viewing</span>}
                    </button>
                  ))}
                </div>
              </section>
            )}
          </aside>
        </div>
      </div>

      {exportOpen && version && (
        <div
          className="scrim"
          onMouseDown={(e) => {
            if (e.target === e.currentTarget) setExportOpen(false)
          }}
        >
          <div className="dialog" role="dialog" aria-modal="true" aria-labelledby="ex-t" style={{ width: 640 }}>
            <div className="dhead">
              <h2 id="ex-t" style={{ fontSize: 20 }}>
                Export procedure
              </h2>
              <span className="small muted">v{version.versionNo}</span>
              <button
                className="ibtn lg"
                style={{ marginLeft: 'auto' }}
                aria-label="Close"
                onClick={() => setExportOpen(false)}
              >
                ×
              </button>
            </div>
            <div
              className="dbody"
              role="radiogroup"
              aria-label="Format"
              style={{ display: 'grid', gridTemplateColumns: 'repeat(2,minmax(0,1fr))', gap: 14 }}
            >
              {(
                [
                  ['pdf', 'PDF', 'Readable format', 'Formatted for reading or printing.', 'fail'],
                  ['xlsx', 'XLS', 'Structured format', 'Structured data of the checklist.', 'pass'],
                ] as [('pdf' | 'xlsx'), string, string, string, string][]
              ).map(([key, ext, title, sub, color]) => (
                <button
                  key={key}
                  role="radio"
                  aria-checked={exportFormat === key}
                  className="choice"
                  style={{ flexDirection: 'column', padding: 22, textAlign: 'center' }}
                  onClick={() => setExportFormat(key)}
                >
                  <span
                    className={`tag ${color}`}
                    style={{ width: 56, height: 68, justifyContent: 'center', fontSize: 16 }}
                  >
                    {ext}
                  </span>
                  <span style={{ fontWeight: 600 }}>{title}</span>
                  <span className="small muted">{sub}</span>
                </button>
              ))}
            </div>
            <div className="dfoot">
              <span />
              <button className="btn btn-primary" disabled={!exportFormat || busy} onClick={() => void onExport()}>
                Export
              </button>
            </div>
          </div>
        </div>
      )}
    </div>
  )
}

interface FlatItem {
  item: DefinitionItem
  parent: DefinitionItem | null
}

function flattenItems(items: DefinitionItem[], parent: DefinitionItem | null = null): FlatItem[] {
  const out: FlatItem[] = []
  for (const item of items) {
    out.push({ item, parent })
    out.push(...flattenItems(item.follow, item))
  }
  return out
}

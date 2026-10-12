import { useCallback, useEffect, useState } from 'react'
import { useNavigate, useParams } from 'react-router-dom'
import {
  archiveProcedure,
  cloneProcedure,
  createDraft,
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
  ProcedureTemplate,
  TemplateVersion,
  TemplateVersionSummary,
} from '../api/types'
import { Refusal } from '../components/Refusal'
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
  const [procedure, setProcedure] = useState<ProcedureTemplate | null>(null)
  const [version, setVersion] = useState<TemplateVersion | null>(null)
  const [versions, setVersions] = useState<TemplateVersionSummary[]>([])
  const [error, setError] = useState<string | null>(null)
  const [refusal, setRefusal] = useState<unknown>(null)
  const [notice, setNotice] = useState<string | null>(null)
  const [busy, setBusy] = useState(false)
  const [exportOpen, setExportOpen] = useState(false)
  const [exportFormat, setExportFormat] = useState<'pdf' | 'xlsx' | null>(null)

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
    if (!procedure || !version) return
    await run(async () => {
      const result = await publishProcedure(procedure.id, version.rowVersion)
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
          <button className="btn btn-outline" disabled={busy} onClick={onClone}>
            Duplicate
          </button>
          {!archived && hasDraft && (
            <>
              <button className="btn btn-quiet" disabled={busy} onClick={onPublish}>
                Publish
              </button>
              <button
                className="btn btn-primary"
                onClick={() => navigate(`/templates/${procedure.id}/edit`)}
              >
                ✎ Edit
              </button>
            </>
          )}
          {!archived && !hasDraft && published != null && (
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
                <button className="btn btn-primary" onClick={() => navigate(`/templates/${procedure.id}/edit`)}>
                  Start the draft
                </button>
              </div>
            ) : (
              <>
                <div className="row" style={{ gap: 8 }}>
                  <span className="small muted">
                    Showing {version.state === 'DRAFT' ? 'draft ' : ''}v{version.versionNo}
                    {version.changeNote ? ` — ${version.changeNote}` : ''}
                  </span>
                </div>
                <ReadOnlyChecklist items={items} />
              </>
            )}
          </section>

          <aside style={{ width: 360, flexShrink: 0, display: 'flex', flexDirection: 'column', gap: 12 }}>
            <section className="dcard" aria-labelledby="pd-t">
              <div className="dcard-head">
                <h2 id="pd-t">Procedure details</h2>
                {!archived && (
                  <button className="link" onClick={() => navigate(`/templates/${procedure.id}/edit`)}>
                    Edit
                  </button>
                )}
              </div>
              <div className="fields">
                {(
                  [
                    ['Status', archived ? 'Archived' : 'Active'],
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
              {!archived && (
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

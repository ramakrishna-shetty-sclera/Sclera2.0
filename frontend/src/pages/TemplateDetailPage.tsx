import { useCallback, useEffect, useState } from 'react'
import { Link, useNavigate, useParams } from 'react-router-dom'
import {
  applyUpdate,
  archiveProcedure,
  cloneProcedure,
  createDraft,
  deferUpdate,
  discardDraft,
  exportProcedure,
  favouriteProcedure,
  forkProcedure,
  getDraft,
  getProcedure,
  getUpdateDiff,
  getUpdateStatus,
  getVersion,
  listProcedures,
  listVersions,
  publishProcedure,
  unfavouriteProcedure,
  unlinkProcedure,
} from '../api/templates'
import { createInspection } from '../api/inspections'
import { getSelectedProperty } from '../api/client'
import type { ProcedureDiff, ProcedureTemplate, TemplateVersion, TemplateVersionSummary, UpdateStatus } from '../api/types'
import { useAuth } from '../auth/AuthContext'
import { StatusBadge } from '../components/StatusBadge'
import { DefinitionView } from '../components/DefinitionView'
import { DiffView } from '../components/DiffView'
import { VersionHistory } from '../components/VersionHistory'
import { Refusal } from '../components/Refusal'
import { Modal } from '../components/Modal'

/**
 * One procedure: what it is, what it can do next, and the content of whichever
 * version you are looking at.
 *
 * The actions follow from the model rather than from a status field. A draft is
 * the only thing that can be edited, there is at most one, and publishing
 * freezes it — so "Edit" and "Publish" appear exactly when a draft exists, and
 * "Start a draft" appears when one does not.
 */
export function TemplateDetailPage() {
  const { id } = useParams<{ id: string }>()
  const navigate = useNavigate()
  const { permissions } = useAuth()
  const [procedure, setProcedure] = useState<ProcedureTemplate | null>(null)
  const [version, setVersion] = useState<TemplateVersion | null>(null)
  const [versions, setVersions] = useState<TemplateVersionSummary[]>([])
  const [error, setError] = useState<string | null>(null)
  /** What the server refused, kept whole: a refusal can name several things at once. */
  const [refusal, setRefusal] = useState<unknown>(null)
  const [notice, setNotice] = useState<string | null>(null)
  const [busy, setBusy] = useState(false)

  /** Only fetched when the procedure tracks a parent — an import or a fork. */
  const [updateStatus, setUpdateStatus] = useState<UpdateStatus | null>(null)
  const [diff, setDiff] = useState<ProcedureDiff | null>(null)
  const [diffError, setDiffError] = useState<string | null>(null)
  const [showExport, setShowExport] = useState(false)
  const [exportVersions, setExportVersions] = useState<Set<number>>(new Set())
  const [exportIncludeDocuments, setExportIncludeDocuments] = useState(false)
  /**
   * There is no field on TemplateResponse saying whether the current user has
   * starred this procedure, so this is read back from the favourites-only
   * list rather than the procedure itself — fine at this app's scale, and
   * self-correcting the moment the toggle below is used, since that always
   * trusts the server's own returned state.
   */
  const [favourited, setFavourited] = useState(false)

  /**
   * Loads the procedure, its history, and the version worth showing by default:
   * the draft when one is open, since that is what the author is working on,
   * otherwise the published version. A procedure with neither has no content.
   *
   * Always returns to that default, including after an action — publishing or
   * discarding changes which version the default *is*, so holding the previous
   * selection would leave the screen showing something that no longer applies.
   */
  const reload = useCallback(async () => {
    if (!id) return
    const p = await getProcedure(id)
    setProcedure(p)
    setVersions(await listVersions(id))
    if (p.draftVersionNo != null) {
      setVersion(await getDraft(id))
    } else if (p.currentPublishedVersionNo != null) {
      setVersion(await getVersion(id, p.currentPublishedVersionNo))
    } else {
      setVersion(null)
    }

    setDiff(null)
    if (p.globalTemplateId != null || p.forkedFromTemplateId != null) {
      setUpdateStatus(await getUpdateStatus(id).catch(() => null))
    } else {
      setUpdateStatus(null)
    }

    const favs = await listProcedures({ favouritesOnly: true, size: 200 }).catch(() => null)
    setFavourited(favs?.data.some((t) => t.id === id) ?? false)
  }, [id])

  /** Shows an older version from the history without changing anything. */
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

  /**
   * Runs an action, then reloads so the screen matches the server rather than
   * what we guessed it would become. A refusal is kept whole rather than
   * flattened to a string: publish names every reason it is not ready at once,
   * and Refusal is what turns that back into a list.
   */
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

  async function onFork() {
    if (!procedure) return
    setBusy(true)
    setError(null)
    try {
      const fork = await forkProcedure(procedure.id)
      navigate(`/templates/${fork.id}`)
    } catch (e) {
      setError(e instanceof Error ? e.message : 'Could not fork this procedure')
      setBusy(false)
    }
  }

  async function onViewDiff() {
    if (!procedure) return
    setDiffError(null)
    try {
      setDiff(await getUpdateDiff(procedure.id))
    } catch (e) {
      setDiff(null)
      setDiffError(e instanceof Error ? e.message : 'Could not load the diff')
    }
  }

  function toggleExportVersion(versionNo: number) {
    setExportVersions((prev) => {
      const next = new Set(prev)
      if (next.has(versionNo)) next.delete(versionNo)
      else next.add(versionNo)
      return next
    })
  }

  async function onExport() {
    if (!procedure) return
    setBusy(true)
    setError(null)
    try {
      const exported = await exportProcedure(procedure.id, {
        versions: exportVersions.size > 0 ? Array.from(exportVersions) : undefined,
        includeDocuments: exportIncludeDocuments,
      })
      const blob = new Blob([JSON.stringify(exported, null, 2)], { type: 'application/json' })
      const url = URL.createObjectURL(blob)
      const a = document.createElement('a')
      a.href = url
      a.download = `${procedure.name.replace(/[^a-z0-9]+/gi, '-').toLowerCase()}.json`
      a.click()
      URL.revokeObjectURL(url)
      setShowExport(false)
    } catch (e) {
      setError(e instanceof Error ? e.message : 'Could not export this procedure')
    } finally {
      setBusy(false)
    }
  }

  async function onToggleFavourite() {
    if (!procedure) return
    const was = favourited
    setFavourited(!was)
    try {
      const result = was ? await unfavouriteProcedure(procedure.id) : await favouriteProcedure(procedure.id)
      setFavourited(result.favourite)
    } catch (e) {
      setFavourited(was)
      setError(e instanceof Error ? e.message : 'Could not update the favourite')
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
    return error ? <div className="alert alert-error">{error}</div> : <p className="muted">Loading…</p>
  }

  const hasDraft = procedure.draftVersionNo != null
  const published = procedure.currentPublishedVersionNo
  const archived = procedure.status === 'ARCHIVED'
  const canFork =
    procedure.propertyId == null &&
    procedure.forkedFromTemplateId == null &&
    getSelectedProperty() != null &&
    permissions.canManageTemplates
  const canUnlink = updateStatus?.linkState === 'LINKED' || updateStatus?.linkState === 'DEFERRED'

  return (
    <div>
      <div className="page-head">
        <div>
          <h1>
            <button
              className="btn btn-ghost small"
              title={favourited ? 'Remove from favourites' : 'Add to favourites'}
              onClick={onToggleFavourite}
            >
              {favourited ? '★' : '☆'}
            </button>{' '}
            {procedure.name}
          </h1>
          <p className="muted">
            <StatusBadge status={procedure.status} />
            {published == null ? ' · never published' : ` · published v${published}`}
            {hasDraft && (
              <>
                {' · '}
                <span className="badge badge-amber">draft v{procedure.draftVersionNo}</span>
              </>
            )}
          </p>
        </div>
        <div className="page-actions">
          {canFork && (
            <button className="btn" disabled={busy} onClick={onFork}>
              Customize for this property
            </button>
          )}
          {canUnlink && permissions.canManageTemplates && (
            <button
              className="btn"
              disabled={busy}
              onClick={() => {
                if (confirm('Stop tracking this procedure\'s shared source? You can still edit it on your own.')) {
                  run(() => unlinkProcedure(procedure.id))
                }
              }}
            >
              Unlink
            </button>
          )}
          <button className="btn" disabled={busy} onClick={() => setShowExport(true)}>
            Export
          </button>
          {!archived && hasDraft && (
            <>
              {permissions.canManageTemplates && (
                <Link className="btn" to={`/templates/${procedure.id}/edit`}>
                  Edit draft
                </Link>
              )}
              {permissions.canPublishTemplates && (
                <button className="btn btn-primary" disabled={busy} onClick={onPublish}>
                  Publish
                </button>
              )}
              {permissions.canManageTemplates && (
                <button
                  className="btn"
                  disabled={busy}
                  onClick={() => {
                    if (confirm(`Discard draft v${procedure.draftVersionNo}? Its edits are lost.`)) {
                      run(() => discardDraft(procedure.id))
                    }
                  }}
                >
                  Discard draft
                </button>
              )}
            </>
          )}
          {!archived && !hasDraft && published != null && permissions.canManageTemplates && (
            <button
              className="btn btn-primary"
              disabled={busy}
              onClick={() => run(() => createDraft(procedure.id, { fromVersionNo: published }))}
            >
              Start a draft from v{published}
            </button>
          )}
          {published != null && (
            <button className="btn" disabled={busy} onClick={startInspection}>
              New inspection
            </button>
          )}
          {permissions.canManageTemplates && (
            <button className="btn" disabled={busy} onClick={onClone}>
              Duplicate
            </button>
          )}
          {!archived && permissions.canManageTemplates && (
            <button
              className="btn btn-danger"
              disabled={busy}
              onClick={() => {
                if (confirm('Archive this procedure? It can no longer be used for new work.')) {
                  run(() => archiveProcedure(procedure.id))
                }
              }}
            >
              Archive
            </button>
          )}
        </div>
      </div>

      {notice && <div className="alert alert-ok">{notice}</div>}
      {error && <div className="alert alert-error">{error}</div>}
      <Refusal error={refusal} />

      {updateStatus?.updateAvailable && (
        <div className="alert alert-amber">
          An update is available — v{updateStatus.currentVersionNo}
          {updateStatus.appliedVersionNo != null && `, you have v${updateStatus.appliedVersionNo}`}.
          <div className="page-actions">
            <button className="btn btn-ghost small" onClick={onViewDiff}>
              View diff
            </button>
            {permissions.canManageTemplates && (
              <>
                <button className="btn btn-primary small" disabled={busy} onClick={() => run(() => applyUpdate(procedure.id))}>
                  Apply update
                </button>
                <button
                  className="btn small"
                  disabled={busy}
                  onClick={() => {
                    if (confirm('Defer this update? You can apply it later.')) {
                      run(() => deferUpdate(procedure.id))
                    }
                  }}
                >
                  Defer
                </button>
              </>
            )}
          </div>
          {diffError && <div className="alert alert-error">{diffError}</div>}
          {diff && <DiffView result={diff} />}
        </div>
      )}

      {procedure.description && <p>{procedure.description}</p>}

      {version === null ? (
        <p className="muted">
          Nothing authored yet.{' '}
          {permissions.canManageTemplates ? (
            <Link to={`/templates/${procedure.id}/edit`}>Start the draft</Link>
          ) : (
            'Ask someone with the template author role to start it.'
          )}
        </p>
      ) : (
        <>
          <h2>
            {version.state === 'DRAFT' ? `Draft v${version.versionNo}` : `v${version.versionNo}`}
            {version.changeNote && <span className="muted small"> — {version.changeNote}</span>}
          </h2>
          <DefinitionView definition={version.definition} />
        </>
      )}

      {versions.length > 0 && (
        <VersionHistory
          procedureId={procedure.id}
          versions={versions}
          viewingVersionNo={version?.versionNo ?? null}
          onView={showVersion}
        />
      )}

      {showExport && (
        <Modal title="Export" onClose={() => setShowExport(false)}>
          <p className="muted small">
            Choose which published versions to include. Leaving none checked exports the current
            published version only.
          </p>
          {versions
            .filter((v) => v.state !== 'DRAFT')
            .map((v) => (
              <label key={v.id} className="checkbox">
                <input
                  type="checkbox"
                  checked={exportVersions.has(v.versionNo)}
                  onChange={() => toggleExportVersion(v.versionNo)}
                />
                v{v.versionNo}
              </label>
            ))}
          <label className="checkbox">
            <input
              type="checkbox"
              checked={exportIncludeDocuments}
              onChange={(e) => setExportIncludeDocuments(e.target.checked)}
            />
            Include cited documents
          </label>
          <div className="page-actions">
            <button className="btn btn-primary" disabled={busy} onClick={onExport}>
              Download
            </button>
          </div>
        </Modal>
      )}
    </div>
  )
}

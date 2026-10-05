import { useCallback, useEffect, useState } from 'react'
import { Link, useNavigate, useParams } from 'react-router-dom'
import {
  archiveProcedure,
  cloneProcedure,
  createDraft,
  discardDraft,
  getDraft,
  getProcedure,
  getVersion,
  listVersions,
  publishProcedure,
} from '../api/templates'
import { createInspection } from '../api/inspections'
import type { ProcedureTemplate, TemplateVersion, TemplateVersionSummary } from '../api/types'
import { StatusBadge } from '../components/StatusBadge'
import { DefinitionView } from '../components/DefinitionView'
import { VersionHistory } from '../components/VersionHistory'
import { Refusal } from '../components/Refusal'

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
  const [procedure, setProcedure] = useState<ProcedureTemplate | null>(null)
  const [version, setVersion] = useState<TemplateVersion | null>(null)
  const [versions, setVersions] = useState<TemplateVersionSummary[]>([])
  const [error, setError] = useState<string | null>(null)
  /** What the server refused, kept whole: a refusal can name several things at once. */
  const [refusal, setRefusal] = useState<unknown>(null)
  const [notice, setNotice] = useState<string | null>(null)
  const [busy, setBusy] = useState(false)

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

  return (
    <div>
      <div className="page-head">
        <div>
          <h1>{procedure.name}</h1>
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
          {!archived && hasDraft && (
            <>
              <Link className="btn" to={`/templates/${procedure.id}/edit`}>
                Edit draft
              </Link>
              <button className="btn btn-primary" disabled={busy} onClick={onPublish}>
                Publish
              </button>
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
            </>
          )}
          {!archived && !hasDraft && published != null && (
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
          <button className="btn" disabled={busy} onClick={onClone}>
            Duplicate
          </button>
          {!archived && (
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

      {procedure.description && <p>{procedure.description}</p>}

      {version === null ? (
        <p className="muted">
          Nothing authored yet. <Link to={`/templates/${procedure.id}/edit`}>Start the draft</Link>.
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
    </div>
  )
}

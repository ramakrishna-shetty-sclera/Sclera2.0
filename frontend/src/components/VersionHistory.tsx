import { useState } from 'react'
import { diffVersions } from '../api/templates'
import type { ProcedureDiff, TemplateVersionSummary } from '../api/types'
import { StatusBadge } from './StatusBadge'
import { DiffView } from './DiffView'

/**
 * Every version this procedure has, newest first, and a way to compare two.
 *
 * The history is the point of the whole model: published versions are frozen,
 * so this list is a permanent record rather than an audit trail that can drift
 * from what was actually filled in. The hash is shown short because its job
 * here is recognition — two versions with the same hash are the same form.
 */
export function VersionHistory({
  procedureId,
  versions,
  viewingVersionNo,
  onView,
}: {
  procedureId: string
  versions: TemplateVersionSummary[]
  viewingVersionNo: number | null
  onView: (versionNo: number) => void
}) {
  const published = versions.filter((v) => v.state !== 'DRAFT')
  const [from, setFrom] = useState<string>('')
  const [to, setTo] = useState<string>('')
  const [diff, setDiff] = useState<ProcedureDiff | null>(null)
  const [error, setError] = useState<string | null>(null)
  const [busy, setBusy] = useState(false)

  async function compare() {
    setBusy(true)
    setError(null)
    try {
      setDiff(await diffVersions(procedureId, Number(from), Number(to)))
    } catch (e) {
      setDiff(null)
      setError(e instanceof Error ? e.message : 'Could not compare these versions')
    } finally {
      setBusy(false)
    }
  }

  return (
    <div>
      <h2>Version history</h2>

      <table className="table">
        <thead>
          <tr>
            <th>Version</th>
            <th>State</th>
            <th>Note</th>
            <th>Published</th>
            <th>Hash</th>
          </tr>
        </thead>
        <tbody>
          {versions.map((v) => (
            <tr
              key={v.id}
              className={`clickable${v.versionNo === viewingVersionNo ? ' rt-drop-target' : ''}`}
              onClick={() => onView(v.versionNo)}
            >
              <td>v{v.versionNo}</td>
              <td>
                <StatusBadge status={v.state} />
              </td>
              <td className="muted">{v.changeNote || '—'}</td>
              <td className="muted">
                {v.publishedAt ? new Date(v.publishedAt).toLocaleString() : '—'}
              </td>
              <td className="rt-key">{v.definitionHash ? v.definitionHash.slice(0, 12) : '—'}</td>
            </tr>
          ))}
        </tbody>
      </table>

      {published.length >= 2 && (
        <div className="card">
          <h2>Compare</h2>
          <div className="rt-color-row">
            <select value={from} onChange={(e) => setFrom(e.target.value)}>
              <option value="">From…</option>
              {published.map((v) => (
                <option key={v.id} value={v.versionNo}>
                  v{v.versionNo}
                </option>
              ))}
            </select>
            <select value={to} onChange={(e) => setTo(e.target.value)}>
              <option value="">To…</option>
              {published.map((v) => (
                <option key={v.id} value={v.versionNo}>
                  v{v.versionNo}
                </option>
              ))}
            </select>
            <button
              className="btn btn-primary small"
              disabled={busy || from === '' || to === '' || from === to}
              onClick={compare}
            >
              Compare
            </button>
          </div>
          {error && <div className="alert alert-error">{error}</div>}
          {diff && <DiffView result={diff} />}
        </div>
      )}
    </div>
  )
}

import { useCallback, useEffect, useState, type FormEvent } from 'react'
import { ApiError } from '../api/client'
import {
  activateResultType,
  createResultType,
  deactivateResultType,
  deleteResultType,
  listResultTypes,
  reorderResultTypes,
  updateResultType,
} from '../api/resultTypes'
import type { ResultType } from '../api/types'
import { useAuth } from '../auth/AuthContext'
import { Modal } from '../components/Modal'

const KEY_PATTERN = /^[A-Z][A-Z0-9_]{0,49}$/
const COLOR_PATTERN = /^#[0-9A-Fa-f]{6}$/

type EditorState = { mode: 'create' } | { mode: 'edit'; type: ResultType } | null

/**
 * Settings → Result types: the organization's outcome vocabulary (Pass, Fail,
 * Amber, …). Rows are shown most severe first — severity 1 is the most
 * severe, and that order decides which result wins when results roll up.
 * Pass and Fail are system types: they can be renamed and recoloured, never
 * deleted or deactivated.
 */
export function ResultTypesPage() {
  const { permissions } = useAuth()
  const canManage = permissions.canManageResultTypes
  const [types, setTypes] = useState<ResultType[]>([])
  const [loading, setLoading] = useState(true)
  const [error, setError] = useState<string | null>(null)
  const [editor, setEditor] = useState<EditorState>(null)
  const [dragId, setDragId] = useState<string | null>(null)
  const [overId, setOverId] = useState<string | null>(null)
  const [reordering, setReordering] = useState(false)
  const [busyId, setBusyId] = useState<string | null>(null)

  // Always fetch active and inactive together: reorder needs every id in the
  // organization, and inactive types still hold a rank.
  const reload = useCallback(() => {
    setLoading(true)
    return listResultTypes()
      .then((list) => {
        setTypes([...list].sort((a, b) => a.severityOrder - b.severityOrder))
        setError(null)
      })
      .catch((e) => setError(e.message))
      .finally(() => setLoading(false))
  }, [])

  useEffect(() => {
    reload()
  }, [reload])

  /**
   * Moves one row to a new position and saves the whole order. The list
   * updates immediately; the server then receives EVERY id, most severe
   * first (a partial list is rejected). If it refuses, the saved order is
   * reloaded and the reason shown.
   */
  async function moveTo(id: string, toIndex: number) {
    if (!canManage) return
    const from = types.findIndex((t) => t.id === id)
    if (from < 0 || from === toIndex || reordering) return
    const next = [...types]
    const [moved] = next.splice(from, 1)
    next.splice(toIndex, 0, moved)
    setTypes(next.map((t, i) => ({ ...t, severityOrder: i + 1 })))
    setReordering(true)
    try {
      const saved = await reorderResultTypes(next.map((t) => t.id))
      setTypes([...saved].sort((a, b) => a.severityOrder - b.severityOrder))
      setError(null)
    } catch (e) {
      // Reload first: a successful reload clears the error, so set it after.
      await reload()
      setError(`Could not save the new order: ${e instanceof Error ? e.message : 'unknown error'}`)
    } finally {
      setReordering(false)
    }
  }

  function endDrag() {
    setDragId(null)
    setOverId(null)
  }

  /** Runs one row action, then reloads so ranks and flags match the server. */
  async function act(t: ResultType, verb: string, call: (id: string) => Promise<unknown>) {
    setBusyId(t.id)
    let failure: string | null = null
    try {
      await call(t.id)
    } catch (e) {
      failure = `Could not ${verb} ${t.name}: ${e instanceof Error ? e.message : 'unknown error'}`
    }
    // Reload either way; a successful reload clears the error, so set it after.
    await reload()
    if (failure) setError(failure)
    setBusyId(null)
  }

  function onDelete(t: ResultType) {
    if (!confirm(`Delete result type "${t.name}"? This cannot be undone.`)) return
    act(t, 'delete', deleteResultType)
  }

  return (
    <div>
      <div className="page-head">
        <div>
          <h1>Result types</h1>
          <p className="muted small">
            The outcomes an answer, a question or a whole inspection can produce. Most severe first:
            when results combine, the one nearest the top wins.
          </p>
        </div>
        <div className="page-actions">
          {canManage && (
            <button className="btn btn-primary" onClick={() => setEditor({ mode: 'create' })}>
              New result type
            </button>
          )}
        </div>
      </div>

      {error && <div className="alert alert-error">{error}</div>}
      {canManage && types.length > 1 && (
        <p className="muted small">
          {reordering ? 'Saving the new order…' : 'Drag a row, or use ▲ ▼, to change its severity.'}
        </p>
      )}

      {loading && types.length === 0 ? (
        <p className="muted">Loading…</p>
      ) : types.length === 0 ? (
        <p className="muted">No result types. Pass and Fail should always exist — reload the page.</p>
      ) : (
        <table className="table">
          <thead>
            <tr>
              <th className="rt-rank">Severity</th>
              <th>Result type</th>
              <th>Description</th>
              <th>Status</th>
              <th className="rt-actions">Actions</th>
            </tr>
          </thead>
          <tbody>
            {types.map((t, index) => (
              <tr
                key={t.id}
                className={[
                  t.active ? '' : 'rt-inactive',
                  dragId === t.id ? 'rt-dragging' : '',
                  overId === t.id && dragId !== t.id ? 'rt-drop-target' : '',
                ].join(' ')}
                draggable={canManage && !reordering}
                onDragStart={(e) => {
                  if (!canManage) return
                  setDragId(t.id)
                  e.dataTransfer.effectAllowed = 'move'
                  e.dataTransfer.setData('text/plain', t.id) // Firefox won't start a drag without data
                }}
                onDragOver={(e) => {
                  if (!dragId) return
                  e.preventDefault() // marks this row as a valid drop target
                  e.dataTransfer.dropEffect = 'move'
                  if (overId !== t.id) setOverId(t.id)
                }}
                onDrop={(e) => {
                  e.preventDefault()
                  if (dragId) moveTo(dragId, index)
                  endDrag()
                }}
                onDragEnd={endDrag}
              >
                <td className="rt-rank">
                  <div className="rt-rank-cell">
                    {canManage && (
                      <span className="rt-handle" title="Drag to change severity" aria-hidden="true">
                        ⠿
                      </span>
                    )}
                    <span>{t.severityOrder}</span>
                    {canManage && (
                      <span className="rt-arrows">
                        <button
                          className="btn btn-ghost small"
                          onClick={() => moveTo(t.id, index - 1)}
                          disabled={index === 0 || reordering}
                          aria-label={`Make ${t.name} more severe`}
                          title="More severe"
                        >
                          ▲
                        </button>
                        <button
                          className="btn btn-ghost small"
                          onClick={() => moveTo(t.id, index + 1)}
                          disabled={index === types.length - 1 || reordering}
                          aria-label={`Make ${t.name} less severe`}
                          title="Less severe"
                        >
                          ▼
                        </button>
                      </span>
                    )}
                  </div>
                </td>
                <td>
                  <div className="rt-name">
                    <span className="rt-swatch" style={{ background: t.color }} title={t.color} />
                    <span>
                      {t.name}
                      <span className="muted small rt-key">{t.key}</span>
                    </span>
                  </div>
                </td>
                <td className="muted">{t.description || '—'}</td>
                <td>
                  <span className={`badge ${t.active ? 'badge-green' : 'badge-gray'}`}>
                    {t.active ? 'Active' : 'Inactive'}
                  </span>{' '}
                  {t.system && <span className="badge badge-blue">System</span>}
                </td>
                <td className="rt-actions">
                  {canManage ? (
                    <button
                      className="btn small"
                      onClick={() => setEditor({ mode: 'edit', type: t })}
                      disabled={busyId === t.id}
                    >
                      Edit
                    </button>
                  ) : (
                    <span className="muted small">Read only</span>
                  )}
                  {/* Pass and Fail can never be deactivated or deleted, so those actions are not offered. */}
                  {canManage && !t.system && (
                    <>
                      {t.active ? (
                        <button
                          className="btn small"
                          onClick={() => act(t, 'deactivate', deactivateResultType)}
                          disabled={busyId !== null || reordering}
                        >
                          Deactivate
                        </button>
                      ) : (
                        <button
                          className="btn small"
                          onClick={() => act(t, 'activate', activateResultType)}
                          disabled={busyId !== null || reordering}
                        >
                          Activate
                        </button>
                      )}
                      <button
                        className="btn btn-danger small"
                        onClick={() => onDelete(t)}
                        disabled={busyId !== null || reordering}
                      >
                        Delete
                      </button>
                    </>
                  )}
                </td>
              </tr>
            ))}
          </tbody>
        </table>
      )}

      {editor && (
        <ResultTypeEditor
          state={editor}
          count={types.length}
          onClose={() => setEditor(null)}
          onSaved={() => {
            setEditor(null)
            reload()
          }}
        />
      )}
    </div>
  )
}

/**
 * Create or edit one result type. The key is set only when creating — records
 * and procedures store it, so it can never change. Rank is chosen only when
 * creating; afterwards it changes by dragging rows.
 */
function ResultTypeEditor({
  state,
  count,
  onClose,
  onSaved,
}: {
  state: { mode: 'create' } | { mode: 'edit'; type: ResultType }
  count: number
  onClose: () => void
  onSaved: () => void
}) {
  const existing = state.mode === 'edit' ? state.type : null
  const [key, setKey] = useState('')
  const [name, setName] = useState(existing?.name ?? '')
  const [color, setColor] = useState(existing?.color ?? '#f39c12')
  const [description, setDescription] = useState(existing?.description ?? '')
  // '' = append as least severe; otherwise a rank 1..count+1
  const [rank, setRank] = useState('')
  const [busy, setBusy] = useState(false)
  const [error, setError] = useState<string | null>(null)
  const [fieldErrors, setFieldErrors] = useState<Record<string, string>>({})

  const keyProblem = existing || key === '' || KEY_PATTERN.test(key) ? null : 'Upper-case letters, digits and _; start with a letter'
  const colorProblem = COLOR_PATTERN.test(color) ? null : 'Six hex digits, e.g. #2ecc71'
  const canSave = !busy && name.trim() !== '' && (existing !== null || KEY_PATTERN.test(key)) && !colorProblem

  async function onSubmit(e: FormEvent) {
    e.preventDefault()
    setBusy(true)
    setError(null)
    setFieldErrors({})
    try {
      const desc = description.trim() || undefined
      if (existing) {
        await updateResultType(existing.id, { name: name.trim(), color, description: desc })
      } else {
        await createResultType({
          key,
          name: name.trim(),
          color,
          description: desc,
          severityOrder: rank === '' ? undefined : Number(rank),
        })
      }
      onSaved()
    } catch (err) {
      if (err instanceof ApiError && err.fieldErrors.length > 0) {
        setFieldErrors(Object.fromEntries(err.fieldErrors.map((f) => [f.field, f.message])))
        setError('Please fix the highlighted fields.')
      } else {
        setError(err instanceof Error ? err.message : 'Save failed')
      }
      setBusy(false)
    }
  }

  return (
    <Modal title={existing ? `Edit ${existing.name}` : 'New result type'} onClose={onClose}>
      <form onSubmit={onSubmit}>
        {error && <div className="alert alert-error">{error}</div>}
        {existing?.system && (
          <p className="muted small">
            {existing.name} is a system type: you can rename and recolour it, but it cannot be deleted or
            deactivated.
          </p>
        )}

        {existing ? (
          <p className="small">
            Key <span className="rt-key-inline">{existing.key}</span>{' '}
            <span className="muted">— fixed, because records refer to it.</span>
          </p>
        ) : (
          <label>
            Key *
            <input
              value={key}
              onChange={(e) => setKey(e.target.value.toUpperCase().replace(/[^A-Z0-9_]/g, '_'))}
              placeholder="e.g. AMBER"
              maxLength={50}
              autoFocus
              required
            />
            <FieldMessage problem={keyProblem ?? fieldErrors.key} hint="Cannot be changed later." />
          </label>
        )}

        <label>
          Name *
          <input
            value={name}
            onChange={(e) => setName(e.target.value)}
            maxLength={100}
            autoFocus={existing !== null}
            required
          />
          <FieldMessage problem={fieldErrors.name} />
        </label>

        <label>
          Colour *
          <div className="rt-color-row">
            <input
              type="color"
              className="rt-color-picker"
              value={COLOR_PATTERN.test(color) ? color.toLowerCase() : '#000000'}
              onChange={(e) => setColor(e.target.value)}
              aria-label="Pick a colour"
            />
            <input value={color} onChange={(e) => setColor(e.target.value.trim())} maxLength={7} />
          </div>
          <FieldMessage problem={colorProblem ?? fieldErrors.color} />
        </label>

        <label>
          Description
          <textarea
            value={description}
            onChange={(e) => setDescription(e.target.value)}
            maxLength={500}
            rows={2}
          />
          <FieldMessage problem={fieldErrors.description} />
        </label>

        {!existing && (
          <label>
            Severity
            <select value={rank} onChange={(e) => setRank(e.target.value)}>
              <option value="">Least severe (add at the bottom)</option>
              {Array.from({ length: count + 1 }, (_, i) => i + 1).map((n) => (
                <option key={n} value={n}>
                  {n === 1 ? '1 — most severe' : n}
                </option>
              ))}
            </select>
            <FieldMessage problem={fieldErrors.severityOrder} hint="You can drag rows to reorder later." />
          </label>
        )}

        <div className="modal-actions">
          <button type="button" className="btn" onClick={onClose}>
            Cancel
          </button>
          <button type="submit" className="btn btn-primary" disabled={!canSave}>
            {busy ? 'Saving…' : 'Save'}
          </button>
        </div>
      </form>
    </Modal>
  )
}

/** A problem (red) takes priority over a hint (grey). */
function FieldMessage({ problem, hint }: { problem?: string | null; hint?: string }) {
  if (problem) return <span className="field-error">{problem}</span>
  if (hint) return <span className="muted small field-hint">{hint}</span>
  return null
}

import { useEffect, useState, type FormEvent } from 'react'
import {
  addCondition,
  addTarget,
  getTagging,
  removeCondition,
  removeTarget,
  tagProcedure,
  untagProcedure,
  updateTarget,
  type Tagging,
  type Target,
  type TargetType,
} from '../api/inspectionTagging'
import { listTemplates } from '../api/templates'
import type { QuestionTemplate } from '../api/types'
import { listAssets, type Asset } from '../api/assets'
import { listLocations, type Location } from '../api/locations'
import { Modal } from './Modal'

export function TaggingSection({ configId }: { configId: string }) {
  const [tagging, setTagging] = useState<Tagging | null>(null)
  const [procedures, setProcedures] = useState<QuestionTemplate[]>([])
  const [assets, setAssets] = useState<Asset[]>([])
  const [locations, setLocations] = useState<Location[]>([])
  const [error, setError] = useState<string | null>(null)

  const [tagOpen, setTagOpen] = useState(false)
  const [targetEditor, setTargetEditor] = useState<{ tpId: string; existing?: Target } | null>(null)
  const [condOpen, setCondOpen] = useState(false)

  function reload() {
    getTagging(configId)
      .then(setTagging)
      .catch((e) => setError(e.message))
  }

  useEffect(() => {
    reload()
    listTemplates({ status: 'PUBLISHED', size: 100 }).then((r) => setProcedures(r.data)).catch(() => {})
    listAssets().then(setAssets).catch(() => {})
    listLocations().then(setLocations).catch(() => {})
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [configId])

  async function guard(fn: () => Promise<unknown>) {
    setError(null)
    try {
      await fn()
      reload()
    } catch (e) {
      setError(e instanceof Error ? e.message : 'Action failed')
    }
  }

  if (!tagging) {
    return error ? <div className="alert alert-error">{error}</div> : <p className="muted">Loading tagging…</p>
  }

  return (
    <>
      {error && <div className="alert alert-error">{error}</div>}

      <div className="card">
        <div className="section-head">
          <h2 className="grow">Tagged procedures &amp; assets</h2>
          <button className="btn btn-primary small" onClick={() => setTagOpen(true)}>
            + Tag procedure
          </button>
        </div>

        {tagging.taggedProcedures.length === 0 ? (
          <p className="muted">
            No procedures tagged yet. Tag a published procedure, then attach the assets/locations it applies
            to — each with its own condition and assignee.
          </p>
        ) : (
          tagging.taggedProcedures.map((tp) => (
            <div className="tagged-proc" key={tp.id}>
              <div className="tagged-proc-head">
                <span className="tagged-proc-name">📋 {tp.procedureName}</span>
                <span className="tree-actions">
                  <button
                    className="btn btn-soft small"
                    onClick={() => setTargetEditor({ tpId: tp.id })}
                  >
                    + Asset / Location
                  </button>
                  <button
                    className="icon-btn danger-text"
                    title="Untag procedure"
                    onClick={() =>
                      confirm(`Untag procedure "${tp.procedureName}"?`) &&
                      guard(() => untagProcedure(configId, tp.id))
                    }
                  >
                    🗑
                  </button>
                </span>
              </div>

              {tp.targets.length === 0 ? (
                <p className="muted small target-empty">No assets/locations attached.</p>
              ) : (
                <table className="table target-table">
                  <thead>
                    <tr>
                      <th>Target</th>
                      <th>Condition</th>
                      <th>Assignee</th>
                      <th></th>
                    </tr>
                  </thead>
                  <tbody>
                    {tp.targets.map((t) => (
                      <tr key={t.id}>
                        <td>
                          <span className={`type-pill ${t.targetType === 'ASSET' ? 'pill-ip' : 'pill-nonip'}`}>
                            {t.targetType === 'ASSET' ? '🖥️ Asset' : '📍 Location'}
                          </span>{' '}
                          {t.targetName}
                        </td>
                        <td>{t.condition ?? <span className="muted">—</span>}</td>
                        <td>{t.assigneeEmail ?? <span className="muted">—</span>}</td>
                        <td className="row-actions">
                          <button
                            className="icon-btn"
                            title="Edit"
                            onClick={() => setTargetEditor({ tpId: tp.id, existing: t })}
                          >
                            ✎
                          </button>
                          <button
                            className="icon-btn danger-text"
                            title="Remove"
                            onClick={() => guard(() => removeTarget(configId, tp.id, t.id))}
                          >
                            🗑
                          </button>
                        </td>
                      </tr>
                    ))}
                  </tbody>
                </table>
              )}
            </div>
          ))
        )}
      </div>

      <div className="card">
        <div className="section-head">
          <h2 className="grow">Inspection-wide conditions</h2>
          <button className="btn small" onClick={() => setCondOpen(true)}>
            + Condition
          </button>
        </div>
        <p className="muted small">Apply to the whole inspection and can trigger email alerts / work orders.</p>
        {tagging.outerConditions.length === 0 ? (
          <p className="muted">None yet.</p>
        ) : (
          <ul className="cond-list">
            {tagging.outerConditions.map((c) => (
              <li key={c.id} className="cond-row">
                <span className="grow">{c.description}</span>
                {c.emailAlert && <span className="badge badge-blue">✉ Email</span>}
                {c.createWorkOrder && <span className="badge badge-amber">🛠 Work order</span>}
                <button
                  className="icon-btn danger-text"
                  title="Remove"
                  onClick={() => guard(() => removeCondition(configId, c.id))}
                >
                  🗑
                </button>
              </li>
            ))}
          </ul>
        )}
      </div>

      {tagOpen && (
        <TagProcedureModal
          procedures={procedures}
          alreadyTagged={tagging.taggedProcedures.map((tp) => tp.procedureId)}
          onClose={() => setTagOpen(false)}
          onSave={async (proc) => {
            await guard(() =>
              tagProcedure(configId, { procedureId: proc.id, procedureName: proc.name }),
            )
            setTagOpen(false)
          }}
        />
      )}

      {targetEditor && (
        <TargetModal
          assets={assets}
          locations={locations}
          existing={targetEditor.existing}
          onClose={() => setTargetEditor(null)}
          onSave={async (payload) => {
            if (targetEditor.existing) {
              await guard(() =>
                updateTarget(configId, targetEditor.tpId, targetEditor.existing!.id, {
                  condition: payload.condition,
                  assigneeEmail: payload.assigneeEmail,
                }),
              )
            } else {
              await guard(() => addTarget(configId, targetEditor.tpId, payload))
            }
            setTargetEditor(null)
          }}
        />
      )}

      {condOpen && (
        <ConditionModal
          onClose={() => setCondOpen(false)}
          onSave={async (payload) => {
            await guard(() => addCondition(configId, payload))
            setCondOpen(false)
          }}
        />
      )}
    </>
  )
}

function TagProcedureModal({
  procedures,
  alreadyTagged,
  onClose,
  onSave,
}: {
  procedures: QuestionTemplate[]
  alreadyTagged: string[]
  onClose: () => void
  onSave: (proc: QuestionTemplate) => void
}) {
  const available = procedures.filter((p) => !alreadyTagged.includes(p.id))
  const [procedureId, setProcedureId] = useState('')

  function onSubmit(e: FormEvent) {
    e.preventDefault()
    const proc = procedures.find((p) => p.id === procedureId)
    if (proc) onSave(proc)
  }

  return (
    <Modal title="Tag a procedure" onClose={onClose}>
      <form onSubmit={onSubmit}>
        {available.length === 0 ? (
          <p className="muted">
            No published procedures available to tag. Publish a procedure first (Procedures tab).
          </p>
        ) : (
          <label>
            Procedure *
            <select value={procedureId} onChange={(e) => setProcedureId(e.target.value)} required autoFocus>
              <option value="">Select a published procedure…</option>
              {available.map((p) => (
                <option key={p.id} value={p.id}>
                  {p.name} (v{p.version})
                </option>
              ))}
            </select>
          </label>
        )}
        <div className="modal-actions">
          <button type="button" className="btn" onClick={onClose}>
            Cancel
          </button>
          <button type="submit" className="btn btn-primary" disabled={!procedureId}>
            Tag
          </button>
        </div>
      </form>
    </Modal>
  )
}

function TargetModal({
  assets,
  locations,
  existing,
  onClose,
  onSave,
}: {
  assets: Asset[]
  locations: Location[]
  existing?: Target
  onClose: () => void
  onSave: (payload: {
    targetType: TargetType
    targetId: string
    targetName: string
    condition?: string
    assigneeEmail?: string
  }) => void
}) {
  const [targetType, setTargetType] = useState<TargetType>(existing?.targetType ?? 'ASSET')
  const [targetId, setTargetId] = useState(existing?.targetId ?? '')
  const [condition, setCondition] = useState(existing?.condition ?? '')
  const [assigneeEmail, setAssigneeEmail] = useState(existing?.assigneeEmail ?? '')
  const editing = Boolean(existing)

  const options = targetType === 'ASSET' ? assets : locations

  function onSubmit(e: FormEvent) {
    e.preventDefault()
    const chosen = options.find((o) => o.id === targetId)
    const targetName = existing?.targetName ?? chosen?.name ?? ''
    onSave({
      targetType,
      targetId: existing?.targetId ?? targetId,
      targetName,
      condition: condition.trim() || undefined,
      assigneeEmail: assigneeEmail.trim() || undefined,
    })
  }

  return (
    <Modal title={editing ? 'Edit target' : 'Attach asset / location'} onClose={onClose}>
      <form onSubmit={onSubmit}>
        {!editing && (
          <>
            <label>
              Type
              <select
                value={targetType}
                onChange={(e) => {
                  setTargetType(e.target.value as TargetType)
                  setTargetId('')
                }}
              >
                <option value="ASSET">Asset</option>
                <option value="LOCATION">Location</option>
              </select>
            </label>
            <label>
              {targetType === 'ASSET' ? 'Asset' : 'Location'} *
              <select value={targetId} onChange={(e) => setTargetId(e.target.value)} required>
                <option value="">Select…</option>
                {options.map((o) => (
                  <option key={o.id} value={o.id}>
                    {o.name}
                  </option>
                ))}
              </select>
            </label>
          </>
        )}
        {editing && (
          <p className="muted small">
            {existing!.targetType === 'ASSET' ? '🖥️ Asset' : '📍 Location'}: <strong>{existing!.targetName}</strong>
          </p>
        )}
        <label>
          Condition (optional)
          <input
            value={condition}
            onChange={(e) => setCondition(e.target.value)}
            maxLength={1000}
            placeholder="e.g. score below 80 ⇒ flag"
          />
        </label>
        <label>
          Assignee email (optional)
          <input
            type="email"
            value={assigneeEmail}
            onChange={(e) => setAssigneeEmail(e.target.value)}
            maxLength={255}
          />
        </label>
        <div className="modal-actions">
          <button type="button" className="btn" onClick={onClose}>
            Cancel
          </button>
          <button type="submit" className="btn btn-primary" disabled={!editing && !targetId}>
            Save
          </button>
        </div>
      </form>
    </Modal>
  )
}

function ConditionModal({
  onClose,
  onSave,
}: {
  onClose: () => void
  onSave: (payload: { description: string; emailAlert: boolean; createWorkOrder: boolean }) => void
}) {
  const [description, setDescription] = useState('')
  const [emailAlert, setEmailAlert] = useState(false)
  const [createWorkOrder, setCreateWorkOrder] = useState(false)

  function onSubmit(e: FormEvent) {
    e.preventDefault()
    onSave({ description: description.trim(), emailAlert, createWorkOrder })
  }

  return (
    <Modal title="Add inspection-wide condition" onClose={onClose}>
      <form onSubmit={onSubmit}>
        <label>
          Description *
          <input value={description} onChange={(e) => setDescription(e.target.value)} required autoFocus maxLength={1000} />
        </label>
        <label className="checkbox">
          <input type="checkbox" checked={emailAlert} onChange={(e) => setEmailAlert(e.target.checked)} />
          Trigger email alert
        </label>
        <label className="checkbox">
          <input
            type="checkbox"
            checked={createWorkOrder}
            onChange={(e) => setCreateWorkOrder(e.target.checked)}
          />
          Create work order
        </label>
        <div className="modal-actions">
          <button type="button" className="btn" onClick={onClose}>
            Cancel
          </button>
          <button type="submit" className="btn btn-primary" disabled={!description.trim()}>
            Add
          </button>
        </div>
      </form>
    </Modal>
  )
}

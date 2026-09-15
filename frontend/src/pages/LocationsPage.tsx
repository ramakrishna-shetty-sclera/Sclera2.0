import { useEffect, useMemo, useState, type FormEvent } from 'react'
import {
  createLocation,
  deleteLocation,
  listLocations,
  updateLocation,
  type Location,
  type LocationType,
} from '../api/locations'
import { Modal } from '../components/Modal'
import { TaggedProceduresModal } from '../components/TaggedProceduresModal'

const CHILD_TYPE: Record<LocationType, LocationType | null> = {
  BUILDING: 'FLOOR',
  FLOOR: 'LOCATION',
  LOCATION: null,
}

const TYPE_LABEL: Record<LocationType, string> = {
  BUILDING: 'Building',
  FLOOR: 'Floor',
  LOCATION: 'Location',
}

const TYPE_ICON: Record<LocationType, string> = {
  BUILDING: '🏢',
  FLOOR: '🗂️',
  LOCATION: '📍',
}

interface EditorState {
  type: LocationType
  parentId: string | null
  /** present when editing an existing node */
  existing?: Location
}

export function LocationsPage() {
  const [locations, setLocations] = useState<Location[]>([])
  const [loading, setLoading] = useState(true)
  const [error, setError] = useState<string | null>(null)
  const [editor, setEditor] = useState<EditorState | null>(null)
  const [proceduresFor, setProceduresFor] = useState<Location | null>(null)

  function reload() {
    setLoading(true)
    listLocations()
      .then((data) => {
        setLocations(data)
        setError(null)
      })
      .catch((e) => setError(e.message))
      .finally(() => setLoading(false))
  }

  useEffect(reload, [])

  const byParent = useMemo(() => {
    const map = new Map<string | null, Location[]>()
    for (const l of locations) {
      const key = l.parentId
      if (!map.has(key)) map.set(key, [])
      map.get(key)!.push(l)
    }
    for (const list of map.values()) list.sort((a, b) => a.name.localeCompare(b.name))
    return map
  }, [locations])

  const buildings = locations.filter((l) => l.type === 'BUILDING').sort((a, b) => a.name.localeCompare(b.name))

  async function onDelete(node: Location) {
    if (!confirm(`Delete ${TYPE_LABEL[node.type].toLowerCase()} "${node.name}"?`)) return
    setError(null)
    try {
      await deleteLocation(node.id)
      reload()
    } catch (e) {
      setError(e instanceof Error ? e.message : 'Delete failed')
    }
  }

  function renderNode(node: Location) {
    const childType = CHILD_TYPE[node.type]
    const children = byParent.get(node.id) ?? []
    return (
      <li key={node.id} className="tree-node">
        <div className={`tree-row lvl-${node.type.toLowerCase()}`}>
          <span className={`tree-icon icon-${node.type.toLowerCase()}`} aria-hidden>
            {TYPE_ICON[node.type]}
          </span>
          <span className="tree-name">
            <span className="tree-title">{node.name}</span>
            <span className="tree-meta">
              {TYPE_LABEL[node.type]}
              {childType && children.length > 0 && (
                <>
                  {' · '}
                  {children.length} {TYPE_LABEL[childType].toLowerCase()}
                  {children.length === 1 ? '' : 's'}
                </>
              )}
            </span>
          </span>
          <span className="tree-actions">
            {childType && (
              <button
                className="btn btn-soft small"
                onClick={() => setEditor({ type: childType, parentId: node.id })}
              >
                + {TYPE_LABEL[childType]}
              </button>
            )}
            <button className="icon-btn" title="Procedures" onClick={() => setProceduresFor(node)}>
              📋
            </button>
            <button
              className="icon-btn"
              title="Edit"
              onClick={() => setEditor({ type: node.type, parentId: node.parentId, existing: node })}
            >
              ✎
            </button>
            <button className="icon-btn danger-text" title="Delete" onClick={() => onDelete(node)}>
              🗑
            </button>
          </span>
        </div>
        {children.length > 0 && <ul className="tree-children">{children.map(renderNode)}</ul>}
      </li>
    )
  }

  const counts = {
    buildings: locations.filter((l) => l.type === 'BUILDING').length,
    floors: locations.filter((l) => l.type === 'FLOOR').length,
    locations: locations.filter((l) => l.type === 'LOCATION').length,
  }

  return (
    <div>
      <div className="page-head">
        <div>
          <h1>Locations</h1>
          <p className="muted">Building → Floor → Location hierarchy.</p>
        </div>
        <button className="btn btn-primary" onClick={() => setEditor({ type: 'BUILDING', parentId: null })}>
          + Building
        </button>
      </div>

      {error && <div className="alert alert-error">{error}</div>}

      {!loading && locations.length > 0 && (
        <div className="stat-row">
          <div className="stat">
            <span className="stat-num">{counts.buildings}</span>
            <span className="stat-label">🏢 Buildings</span>
          </div>
          <div className="stat">
            <span className="stat-num">{counts.floors}</span>
            <span className="stat-label">🗂️ Floors</span>
          </div>
          <div className="stat">
            <span className="stat-num">{counts.locations}</span>
            <span className="stat-label">📍 Locations</span>
          </div>
        </div>
      )}

      {loading ? (
        <p className="muted">Loading…</p>
      ) : buildings.length === 0 ? (
        <div className="empty-state">
          <div className="empty-icon">🏢</div>
          <p className="empty-title">No buildings yet</p>
          <p className="muted">Add a building to start the location hierarchy.</p>
          <button className="btn btn-primary" onClick={() => setEditor({ type: 'BUILDING', parentId: null })}>
            + Add building
          </button>
        </div>
      ) : (
        <ul className="tree">{buildings.map(renderNode)}</ul>
      )}

      {editor && (
        <LocationEditor
          state={editor}
          locations={locations}
          onClose={() => setEditor(null)}
          onSaved={() => {
            setEditor(null)
            reload()
          }}
        />
      )}

      {proceduresFor && (
        <TaggedProceduresModal
          targetType="LOCATION"
          targetId={proceduresFor.id}
          targetName={proceduresFor.name}
          onClose={() => setProceduresFor(null)}
        />
      )}
    </div>
  )
}

function LocationEditor({
  state,
  locations,
  onClose,
  onSaved,
}: {
  state: EditorState
  locations: Location[]
  onClose: () => void
  onSaved: () => void
}) {
  const [name, setName] = useState(state.existing?.name ?? '')
  const [busy, setBusy] = useState(false)
  const [error, setError] = useState<string | null>(null)

  const parent = state.parentId ? locations.find((l) => l.id === state.parentId) : null
  const verb = state.existing ? 'Edit' : 'Add'
  const title = `${verb} ${TYPE_LABEL[state.type]}`

  async function onSubmit(e: FormEvent) {
    e.preventDefault()
    setBusy(true)
    setError(null)
    try {
      const body = { name: name.trim(), type: state.type, parentId: state.parentId ?? undefined }
      if (state.existing) await updateLocation(state.existing.id, body)
      else await createLocation(body)
      onSaved()
    } catch (err) {
      setError(err instanceof Error ? err.message : 'Save failed')
      setBusy(false)
    }
  }

  return (
    <Modal title={title} onClose={onClose}>
      <form onSubmit={onSubmit}>
        {error && <div className="alert alert-error">{error}</div>}
        {parent && (
          <p className="muted small">
            Under {TYPE_LABEL[parent.type].toLowerCase()}: <strong>{parent.name}</strong>
          </p>
        )}
        <label>
          {TYPE_LABEL[state.type]} name *
          <input value={name} onChange={(e) => setName(e.target.value)} autoFocus required maxLength={200} />
        </label>
        <div className="modal-actions">
          <button type="button" className="btn" onClick={onClose}>
            Cancel
          </button>
          <button type="submit" className="btn btn-primary" disabled={busy || !name.trim()}>
            {busy ? 'Saving…' : 'Save'}
          </button>
        </div>
      </form>
    </Modal>
  )
}

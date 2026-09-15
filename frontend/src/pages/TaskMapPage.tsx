import { useEffect, useMemo, useState } from 'react'
import {
  checklistStatusLabel,
  CHECKLIST_STATUS_CLASS,
  listChecklists,
  type Checklist,
  type ChecklistStatus,
} from '../api/checklists'
import { listInspectionConfigs, type InspectionConfig } from '../api/inspectionConfigs'
import { listLocations, type Location } from '../api/locations'
import { listAssets, type Asset } from '../api/assets'
import { ChecklistFillPanel } from '../components/ChecklistFillPanel'
import { Modal } from '../components/Modal'

const STATUSES: ChecklistStatus[] = ['TODO', 'COMPLETE', 'FAILED', 'EXCEPTION', 'INCOMPLETE']

const DOT: Record<ChecklistStatus, string> = {
  TODO: 'dot-gray',
  COMPLETE: 'dot-green',
  FAILED: 'dot-red',
  EXCEPTION: 'dot-amber',
  INCOMPLETE: 'dot-blue',
}

interface NodeData {
  checklists: Checklist[]
  assets: { asset: Asset; checklists: Checklist[] }[]
}

export function TaskMapPage() {
  const [locations, setLocations] = useState<Location[]>([])
  const [assets, setAssets] = useState<Asset[]>([])
  const [checklists, setChecklists] = useState<Checklist[]>([])
  const [configs, setConfigs] = useState<InspectionConfig[]>([])
  const [loading, setLoading] = useState(true)
  const [error, setError] = useState<string | null>(null)

  const [configId, setConfigId] = useState('')
  const [status, setStatus] = useState<ChecklistStatus | ''>('')
  const [openId, setOpenId] = useState<string | null>(null)

  function reload() {
    Promise.all([
      listLocations(),
      listAssets(),
      listChecklists({ configId: configId || undefined, status: status || undefined }),
    ])
      .then(([l, a, c]) => {
        setLocations(l)
        setAssets(a)
        setChecklists(c)
        setError(null)
      })
      .catch((e) => setError(e.message))
      .finally(() => setLoading(false))
  }

  useEffect(() => {
    setLoading(true)
    reload()
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [configId, status])

  useEffect(() => {
    listInspectionConfigs().then(setConfigs).catch(() => {})
  }, [])

  const model = useMemo(() => {
    const assetById = new Map(assets.map((a) => [a.id, a]))
    const byLocation = new Map<string, NodeData>()
    const unassigned: NodeData = { checklists: [], assets: [] }

    const nodeFor = (locId: string): NodeData => {
      if (!byLocation.has(locId)) byLocation.set(locId, { checklists: [], assets: [] })
      return byLocation.get(locId)!
    }

    // Place each checklist on the map: LOCATION target → that location,
    // ASSET target → the asset's tagged location (or unassigned), else unassigned.
    const assetChecklists = new Map<string, Checklist[]>()
    for (const c of checklists) {
      if (c.targetType === 'LOCATION' && c.targetId) {
        nodeFor(c.targetId).checklists.push(c)
      } else if (c.targetType === 'ASSET' && c.targetId) {
        if (!assetChecklists.has(c.targetId)) assetChecklists.set(c.targetId, [])
        assetChecklists.get(c.targetId)!.push(c)
      } else {
        unassigned.checklists.push(c)
      }
    }
    for (const [assetId, cls] of assetChecklists) {
      const asset = assetById.get(assetId)
      if (asset?.locationId) {
        nodeFor(asset.locationId).assets.push({ asset, checklists: cls })
      } else if (asset) {
        unassigned.assets.push({ asset, checklists: cls })
      } else {
        // asset deleted since tagging — keep the checklists visible
        unassigned.checklists.push(...cls)
      }
    }

    // Also show assets tagged to a location even without checklists (context)
    for (const a of assets) {
      if (a.locationId && !assetChecklists.has(a.id)) {
        nodeFor(a.locationId).assets.push({ asset: a, checklists: [] })
      }
    }

    const byParent = new Map<string | null, Location[]>()
    for (const l of locations) {
      const k = l.parentId
      if (!byParent.has(k)) byParent.set(k, [])
      byParent.get(k)!.push(l)
    }
    for (const list of byParent.values()) list.sort((a, b) => a.name.localeCompare(b.name))

    return { byLocation, unassigned, byParent }
  }, [locations, assets, checklists])

  /** Roll-up of checklist statuses for a location subtree (incl. assets there). */
  function rollup(locId: string): Record<ChecklistStatus, number> {
    const counts = { TODO: 0, COMPLETE: 0, FAILED: 0, EXCEPTION: 0, INCOMPLETE: 0 }
    const walk = (id: string) => {
      const node = model.byLocation.get(id)
      if (node) {
        for (const c of node.checklists) counts[c.status]++
        for (const a of node.assets) for (const c of a.checklists) counts[c.status]++
      }
      for (const child of model.byParent.get(id) ?? []) walk(child.id)
    }
    walk(locId)
    return counts
  }

  function RollupBadges({ counts }: { counts: Record<ChecklistStatus, number> }) {
    const total = STATUSES.reduce((n, s) => n + counts[s], 0)
    if (total === 0) return <span className="muted small">no checklists</span>
    return (
      <span className="rollup">
        {STATUSES.filter((s) => counts[s] > 0).map((s) => (
          <span key={s} className="rollup-item" title={checklistStatusLabel(s)}>
            <span className={`dot ${DOT[s]}`} /> {counts[s]}
          </span>
        ))}
      </span>
    )
  }

  function Markers({ items }: { items: Checklist[] }) {
    return (
      <>
        {items.map((c) => (
          <button
            key={c.id}
            className={`marker ${CHECKLIST_STATUS_CLASS[c.status]}`}
            title={`${c.procedureName} — ${checklistStatusLabel(c.status)} · ${c.assigneeEmail}`}
            onClick={() => setOpenId(c.id)}
          >
            <span className={`dot ${DOT[c.status]}`} />
            {c.procedureName}
          </button>
        ))}
      </>
    )
  }

  function LocationTile({ loc }: { loc: Location }) {
    const node = model.byLocation.get(loc.id)
    return (
      <div className="map-location">
        <div className="map-location-head">📍 {loc.name}</div>
        {node?.checklists.length ? (
          <div className="marker-row">
            <Markers items={node.checklists} />
          </div>
        ) : null}
        {node?.assets.map(({ asset, checklists: cls }) => (
          <div className="map-asset" key={asset.id}>
            <span className="map-asset-name">
              {asset.assetType === 'IP' ? '🌐' : '🔌'} {asset.name}
            </span>
            {cls.length > 0 && (
              <div className="marker-row">
                <Markers items={cls} />
              </div>
            )}
          </div>
        ))}
        {!node?.checklists.length && !node?.assets.length && (
          <span className="muted small">empty</span>
        )}
      </div>
    )
  }

  const buildings = model.byParent.get(null) ?? []
  const hasUnassigned = model.unassigned.checklists.length > 0 || model.unassigned.assets.length > 0

  return (
    <div>
      <div className="page-head">
        <div>
          <h1>Task Map</h1>
          <p className="muted">
            Checklists placed on the building → floor → location hierarchy. Click a marker to fill.
          </p>
        </div>
        <div className="page-actions">
          <select value={configId} onChange={(e) => setConfigId(e.target.value)}>
            <option value="">All inspections</option>
            {configs.map((c) => (
              <option key={c.id} value={c.id}>
                {c.name}
              </option>
            ))}
          </select>
          <select value={status} onChange={(e) => setStatus(e.target.value as ChecklistStatus | '')}>
            <option value="">All statuses</option>
            {STATUSES.map((s) => (
              <option key={s} value={s}>
                {checklistStatusLabel(s)}
              </option>
            ))}
          </select>
        </div>
      </div>

      {error && <div className="alert alert-error">{error}</div>}

      <div className="map-legend">
        {STATUSES.map((s) => (
          <span key={s} className="rollup-item">
            <span className={`dot ${DOT[s]}`} /> {checklistStatusLabel(s)}
          </span>
        ))}
      </div>

      {loading ? (
        <p className="muted">Loading…</p>
      ) : buildings.length === 0 && !hasUnassigned ? (
        <div className="empty-state">
          <div className="empty-icon">🗺</div>
          <p className="empty-title">Nothing to map yet</p>
          <p className="muted">Add buildings/locations and generate checklists to see them here.</p>
        </div>
      ) : (
        <>
          {buildings.map((b) => (
            <div className="map-building" key={b.id}>
              <div className="map-building-head">
                <span className="map-building-name">🏢 {b.name}</span>
                <RollupBadges counts={rollup(b.id)} />
              </div>
              {(model.byParent.get(b.id) ?? []).map((floor) => (
                <div className="map-floor" key={floor.id}>
                  <div className="map-floor-head">
                    <span>🗂️ {floor.name}</span>
                    <RollupBadges counts={rollup(floor.id)} />
                  </div>
                  <div className="map-floor-grid">
                    {(model.byParent.get(floor.id) ?? []).map((loc) => (
                      <LocationTile key={loc.id} loc={loc} />
                    ))}
                    {(model.byParent.get(floor.id) ?? []).length === 0 && (
                      <span className="muted small">no locations on this floor</span>
                    )}
                  </div>
                </div>
              ))}
            </div>
          ))}

          {hasUnassigned && (
            <div className="map-building map-unassigned">
              <div className="map-building-head">
                <span className="map-building-name">🏷️ Unassigned</span>
                <span className="muted small">checklists / assets with no mapped location</span>
              </div>
              <div className="map-floor-grid">
                {model.unassigned.checklists.length > 0 && (
                  <div className="map-location">
                    <div className="map-location-head">No target</div>
                    <div className="marker-row">
                      <Markers items={model.unassigned.checklists} />
                    </div>
                  </div>
                )}
                {model.unassigned.assets.map(({ asset, checklists: cls }) => (
                  <div className="map-location" key={asset.id}>
                    <div className="map-location-head">
                      {asset.assetType === 'IP' ? '🌐' : '🔌'} {asset.name}
                    </div>
                    <div className="marker-row">
                      <Markers items={cls} />
                    </div>
                  </div>
                ))}
              </div>
            </div>
          )}
        </>
      )}

      {openId && (
        <Modal title="Checklist" wide onClose={() => setOpenId(null)}>
          <ChecklistFillPanel checklistId={openId} onExit={() => setOpenId(null)} onChanged={reload} />
        </Modal>
      )}
    </div>
  )
}

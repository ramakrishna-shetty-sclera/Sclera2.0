import { useEffect, useMemo, useState, type FormEvent } from 'react'
import {
  createAsset,
  deleteAsset,
  listAssets,
  updateAsset,
  type Asset,
  type AssetType,
} from '../api/assets'
import { listLocations, type Location } from '../api/locations'
import { Modal } from '../components/Modal'

export function AssetsPage() {
  const [assets, setAssets] = useState<Asset[]>([])
  const [locations, setLocations] = useState<Location[]>([])
  const [loading, setLoading] = useState(true)
  const [error, setError] = useState<string | null>(null)
  const [typeFilter, setTypeFilter] = useState<AssetType | ''>('')
  const [editor, setEditor] = useState<{ existing?: Asset } | null>(null)

  function reload() {
    setLoading(true)
    Promise.all([listAssets(typeFilter ? { assetType: typeFilter } : {}), listLocations()])
      .then(([a, l]) => {
        setAssets(a)
        setLocations(l)
        setError(null)
      })
      .catch((e) => setError(e.message))
      .finally(() => setLoading(false))
  }

  useEffect(reload, [typeFilter])

  const locName = useMemo(() => {
    const m = new Map(locations.map((l) => [l.id, l.name]))
    return (id: string | null) => (id ? (m.get(id) ?? '—') : '—')
  }, [locations])

  async function onDelete(asset: Asset) {
    if (!confirm(`Delete asset "${asset.name}"?`)) return
    setError(null)
    try {
      await deleteAsset(asset.id)
      reload()
    } catch (e) {
      setError(e instanceof Error ? e.message : 'Delete failed')
    }
  }

  return (
    <div>
      <div className="page-head">
        <div>
          <h1>Assets</h1>
          <p className="muted">IP &amp; non-IP assets, optionally tagged to a location.</p>
        </div>
        <div className="page-actions">
          <select value={typeFilter} onChange={(e) => setTypeFilter(e.target.value as AssetType | '')}>
            <option value="">All types</option>
            <option value="IP">IP</option>
            <option value="NON_IP">Non-IP</option>
          </select>
          <button className="btn btn-primary" onClick={() => setEditor({})}>
            + Asset
          </button>
        </div>
      </div>

      {error && <div className="alert alert-error">{error}</div>}

      {!loading && assets.length > 0 && (
        <div className="stat-row">
          <div className="stat">
            <span className="stat-num">{assets.length}</span>
            <span className="stat-label">📦 Total</span>
          </div>
          <div className="stat">
            <span className="stat-num">{assets.filter((a) => a.assetType === 'IP').length}</span>
            <span className="stat-label">🌐 IP</span>
          </div>
          <div className="stat">
            <span className="stat-num">{assets.filter((a) => a.assetType === 'NON_IP').length}</span>
            <span className="stat-label">🔌 Non-IP</span>
          </div>
          <div className="stat">
            <span className="stat-num">{assets.filter((a) => !a.locationId).length}</span>
            <span className="stat-label">🏷️ Untagged</span>
          </div>
        </div>
      )}

      {loading ? (
        <p className="muted">Loading…</p>
      ) : assets.length === 0 ? (
        <div className="empty-state">
          <div className="empty-icon">🖥️</div>
          <p className="empty-title">No assets yet</p>
          <p className="muted">Add an IP or non-IP asset to get started.</p>
          <button className="btn btn-primary" onClick={() => setEditor({})}>
            + Add asset
          </button>
        </div>
      ) : (
        <table className="table asset-table">
          <thead>
            <tr>
              <th>Name</th>
              <th>Type</th>
              <th>IP address</th>
              <th>Location</th>
              <th></th>
            </tr>
          </thead>
          <tbody>
            {assets.map((a) => (
              <tr key={a.id}>
                <td className="asset-name">{a.name}</td>
                <td>
                  <span className={`type-pill ${a.assetType === 'IP' ? 'pill-ip' : 'pill-nonip'}`}>
                    {a.assetType === 'IP' ? '🌐 IP' : '🔌 Non-IP'}
                  </span>
                </td>
                <td>{a.ipAddress ? <code className="ip">{a.ipAddress}</code> : <span className="muted">—</span>}</td>
                <td>
                  {a.locationId ? (
                    <span className="loc-chip">📍 {locName(a.locationId)}</span>
                  ) : (
                    <span className="muted">untagged</span>
                  )}
                </td>
                <td className="row-actions">
                  <button className="icon-btn" title="Edit" onClick={() => setEditor({ existing: a })}>
                    ✎
                  </button>
                  <button className="icon-btn danger-text" title="Delete" onClick={() => onDelete(a)}>
                    🗑
                  </button>
                </td>
              </tr>
            ))}
          </tbody>
        </table>
      )}

      {editor && (
        <AssetEditor
          existing={editor.existing}
          locations={locations}
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

function AssetEditor({
  existing,
  locations,
  onClose,
  onSaved,
}: {
  existing?: Asset
  locations: Location[]
  onClose: () => void
  onSaved: () => void
}) {
  const [name, setName] = useState(existing?.name ?? '')
  const [assetType, setAssetType] = useState<AssetType>(existing?.assetType ?? 'IP')
  const [ipAddress, setIpAddress] = useState(existing?.ipAddress ?? '')
  const [locationId, setLocationId] = useState(existing?.locationId ?? '')
  const [busy, setBusy] = useState(false)
  const [error, setError] = useState<string | null>(null)

  async function onSubmit(e: FormEvent) {
    e.preventDefault()
    setBusy(true)
    setError(null)
    try {
      const body = {
        name: name.trim(),
        assetType,
        ipAddress: assetType === 'IP' ? ipAddress.trim() : undefined,
        locationId: locationId || undefined,
      }
      if (existing) await updateAsset(existing.id, body)
      else await createAsset(body)
      onSaved()
    } catch (err) {
      setError(err instanceof Error ? err.message : 'Save failed')
      setBusy(false)
    }
  }

  return (
    <Modal title={existing ? 'Edit asset' : 'Add asset'} onClose={onClose}>
      <form onSubmit={onSubmit}>
        {error && <div className="alert alert-error">{error}</div>}
        <label>
          Name *
          <input value={name} onChange={(e) => setName(e.target.value)} autoFocus required maxLength={200} />
        </label>
        <div className="form-grid">
          <label>
            Type
            <select value={assetType} onChange={(e) => setAssetType(e.target.value as AssetType)}>
              <option value="IP">IP</option>
              <option value="NON_IP">Non-IP</option>
            </select>
          </label>
          {assetType === 'IP' && (
            <label className="grow">
              IP address *
              <input
                value={ipAddress}
                onChange={(e) => setIpAddress(e.target.value)}
                placeholder="10.0.0.5"
                required
                maxLength={45}
              />
            </label>
          )}
        </div>
        <label>
          Location (optional)
          <select value={locationId} onChange={(e) => setLocationId(e.target.value)}>
            <option value="">— untagged —</option>
            {locations.map((l) => (
              <option key={l.id} value={l.id}>
                {l.name} ({l.type.toLowerCase()})
              </option>
            ))}
          </select>
        </label>
        <div className="modal-actions">
          <button type="button" className="btn" onClick={onClose}>
            Cancel
          </button>
          <button
            type="submit"
            className="btn btn-primary"
            disabled={busy || !name.trim() || (assetType === 'IP' && !ipAddress.trim())}
          >
            {busy ? 'Saving…' : 'Save'}
          </button>
        </div>
      </form>
    </Modal>
  )
}

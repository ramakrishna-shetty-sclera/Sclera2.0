import { useEffect, useState } from 'react'
import { getSelectedProperty, setSelectedProperty } from '../api/client'
import { listMyProperties } from '../api/me'
import type { Property } from '../api/types'

/**
 * Chooses which property (VDMS) the app is looking at. "Organization" is the
 * organization level — only what the whole organization shares — and picking a
 * property adds that property's own procedures to it.
 *
 * Only the properties the user may open are listed, read from the backend's
 * grants, so the choice is never one the backend would refuse. Hidden when the
 * user has none: they work at organization level and there is nothing to pick.
 */
export function PropertySwitcher({ onChange }: { onChange: (propertyId: string | null) => void }) {
  const [properties, setProperties] = useState<Property[]>([])
  const [selected, setSelected] = useState<string | null>(getSelectedProperty())

  useEffect(() => {
    let cancelled = false
    listMyProperties()
      .then((list) => {
        if (cancelled) return
        setProperties(list)
        // A remembered property this user may not open — someone else's choice
        // earlier in this tab, or a grant since removed — falls back to
        // organization level rather than making every request a 403.
        const current = getSelectedProperty()
        if (current && !list.some((p) => p.id === current)) choose(null)
      })
      .catch(() => {
        // No list, no switcher. Organization level always works, so fall back to it.
        if (cancelled) return
        setProperties([])
        if (getSelectedProperty()) choose(null)
      })
    return () => {
      cancelled = true
    }
  }, [])

  function choose(propertyId: string | null) {
    setSelectedProperty(propertyId)
    setSelected(propertyId)
    onChange(propertyId)
  }

  if (properties.length === 0) return null

  return (
    <select
      className="property-switcher"
      aria-label="Property"
      value={selected ?? ''}
      onChange={(e) => choose(e.target.value || null)}
    >
      <option value="">Organization</option>
      {properties.map((p) => (
        <option key={p.id} value={p.id}>
          {p.code}
        </option>
      ))}
    </select>
  )
}

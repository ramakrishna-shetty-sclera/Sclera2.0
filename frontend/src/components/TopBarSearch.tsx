import { useEffect, useMemo, useRef, useState } from 'react'
import { useNavigate } from 'react-router-dom'
import { listProcedures } from '../api/templates'
import type { ProcedureTemplate } from '../api/types'

/**
 * Finds a procedure by name from anywhere in the app. The list endpoint has no
 * search parameter, so the active procedures are read once each time the box
 * is focused (so a new or renamed one shows up) and matched here, on name,
 * description, code and standard. Archived procedures are left out — they can
 * no longer be used for new work.
 */

const PAGE_SIZE = 200
const MAX_PAGES = 10
const MAX_RESULTS = 8

async function loadActiveProcedures(): Promise<ProcedureTemplate[]> {
  const all: ProcedureTemplate[] = []
  for (let page = 0; page < MAX_PAGES; page++) {
    const r = await listProcedures({ status: 'ACTIVE', page, size: PAGE_SIZE })
    all.push(...r.data)
    if (!r.pagination?.hasNext) break
  }
  return all
}

function matches(p: ProcedureTemplate, q: string): boolean {
  const hay = [p.name, p.description, p.meta?.code, p.meta?.standard].filter(Boolean).join(' ').toLowerCase()
  return q
    .toLowerCase()
    .split(/\s+/)
    .filter(Boolean)
    .every((word) => hay.includes(word))
}

function stateOf(p: ProcedureTemplate): string {
  if (p.currentPublishedVersionNo != null) return p.draftVersionNo != null ? 'Published · draft open' : 'Published'
  return 'Draft'
}

export function TopBarSearch() {
  const navigate = useNavigate()
  const root = useRef<HTMLDivElement>(null)
  const input = useRef<HTMLInputElement>(null)
  const request = useRef(0)
  const [query, setQuery] = useState('')
  const [open, setOpen] = useState(false)
  const [procedures, setProcedures] = useState<ProcedureTemplate[]>([])
  const [loading, setLoading] = useState(false)
  const [failed, setFailed] = useState(false)
  const [active, setActive] = useState(0)

  const results = useMemo(
    () => (query.trim() ? procedures.filter((p) => matches(p, query)).slice(0, MAX_RESULTS) : []),
    [procedures, query],
  )

  useEffect(() => setActive(0), [query])

  useEffect(() => {
    if (!open) return
    const onDown = (e: MouseEvent) => {
      if (root.current && !root.current.contains(e.target as Node)) setOpen(false)
    }
    document.addEventListener('mousedown', onDown)
    return () => document.removeEventListener('mousedown', onDown)
  }, [open])

  function onFocus() {
    setOpen(true)
    const mine = ++request.current
    setLoading(true)
    setFailed(false)
    loadActiveProcedures()
      .then((list) => mine === request.current && setProcedures(list))
      .catch(() => mine === request.current && setFailed(true))
      .finally(() => mine === request.current && setLoading(false))
  }

  function go(p: ProcedureTemplate) {
    setOpen(false)
    setQuery('')
    input.current?.blur()
    navigate(`/templates/${p.id}`)
  }

  function onKeyDown(e: React.KeyboardEvent) {
    if (e.key === 'Escape') {
      setOpen(false)
      input.current?.blur()
    } else if (e.key === 'ArrowDown' && results.length) {
      e.preventDefault()
      setActive((i) => (i + 1) % results.length)
    } else if (e.key === 'ArrowUp' && results.length) {
      e.preventDefault()
      setActive((i) => (i - 1 + results.length) % results.length)
    } else if (e.key === 'Enter' && results[active]) {
      e.preventDefault()
      go(results[active])
    }
  }

  const showPanel = open && query.trim() !== ''

  return (
    <div className="topbar-search" ref={root} role="search">
      <svg className="topbar-search-icon" width="16" height="16" viewBox="0 0 24 24" fill="none" stroke="currentColor" strokeWidth="1.8" strokeLinecap="round" aria-hidden="true">
        <circle cx="11" cy="11" r="7" />
        <path d="m20 20-3.5-3.5" />
      </svg>
      <input
        ref={input}
        type="search"
        className="topbar-search-input"
        placeholder="Search procedures"
        aria-label="Search procedures"
        role="combobox"
        aria-expanded={showPanel}
        aria-controls="topbar-search-list"
        aria-autocomplete="list"
        value={query}
        onChange={(e) => setQuery(e.target.value)}
        onFocus={onFocus}
        onKeyDown={onKeyDown}
      />
      {showPanel && (
        <ul className="topbar-panel topbar-search-list" id="topbar-search-list" role="listbox">
          {results.map((p, i) => (
            <li
              key={p.id}
              role="option"
              aria-selected={i === active}
              className={`topbar-search-item${i === active ? ' active' : ''}`}
              onMouseEnter={() => setActive(i)}
              // mousedown, not click: the input would lose focus and close the list first
              onMouseDown={(e) => {
                e.preventDefault()
                go(p)
              }}
            >
              <span className="topbar-search-name">{p.name}</span>
              <span className="topbar-search-meta">{stateOf(p)}</span>
            </li>
          ))}
          {results.length === 0 && (
            <li className="topbar-search-note">
              {loading ? 'Searching…' : failed ? 'Could not load procedures.' : `No procedure matches “${query.trim()}”.`}
            </li>
          )}
        </ul>
      )}
    </div>
  )
}

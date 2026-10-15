import { useMemo, useState } from 'react'
import { typeLabel } from '../../api/document'
import type { ItemDraft } from '../itemTree'

export interface FlatDraft {
  item: ItemDraft
  parent: ItemDraft | null
  depth: number
}

export function flattenDrafts(items: ItemDraft[], parent: ItemDraft | null = null, depth = 0): FlatDraft[] {
  const out: FlatDraft[] = []
  for (const item of items) {
    out.push({ item, parent, depth })
    out.push(...flattenDrafts(item.follow, item, depth + 1))
  }
  return out
}

/**
 * The builder's left rail (docs/procedures-v3.html Navigator): every question
 * with its number, filterable, click to jump. Sections appear as headings
 * under the All filter and step aside when filtering — a heading is not a
 * question and never matches one.
 */
export function BuilderNavigator({
  flat,
  numbers,
  active,
  issues,
  onSelect,
  onAdd,
}: {
  flat: FlatDraft[]
  numbers: Map<ItemDraft, string>
  active: string | null
  issues: Map<string, string[]>
  onSelect: (uid: string) => void
  onAdd: () => void
}) {
  const [filter, setFilter] = useState<'all' | 'attention' | 'required' | 'follow'>('all')
  const [query, setQuery] = useState('')

  const questions = useMemo(() => flat.filter((f) => f.item.type !== 'SECTION'), [flat])
  const attention = questions.filter((f) => (issues.get(f.item.uid) ?? []).length > 0)
  const needle = query.trim().toLowerCase()

  const shown = flat.filter((f) => {
    if (f.item.type === 'SECTION') return filter === 'all' && !needle
    if (filter === 'attention' && !(issues.get(f.item.uid) ?? []).length) return false
    if (filter === 'required' && !f.item.required) return false
    if (filter === 'follow' && f.item.follow.length === 0 && !f.parent) return false
    if (needle && !`${f.item.text} ${numbers.get(f.item) ?? ''}`.toLowerCase().includes(needle)) return false
    return true
  })

  const filters: ['all' | 'attention' | 'required' | 'follow', string, number][] = [
    ['all', 'All', questions.length],
    ['attention', 'Needs attention', attention.length],
    ['required', 'Required', questions.filter((f) => f.item.required).length],
    ['follow', 'Follow-ups', questions.filter((f) => f.item.follow.length > 0 || f.parent).length],
  ]

  return (
    <nav className="navi" aria-label="Question navigator">
      <div className="navi-top">
        <div className="row between">
          <h2 style={{ fontSize: 15, fontWeight: 600 }}>Questions</h2>
          <span className="small muted" style={{ fontVariantNumeric: 'tabular-nums' }}>
            {questions.length}
          </span>
        </div>
        <input
          type="search"
          className="input sm soft"
          placeholder="Search questions"
          aria-label="Search questions"
          value={query}
          onChange={(e) => setQuery(e.target.value)}
        />
        <div className="row" style={{ gap: 6, flexWrap: 'wrap' }} role="group" aria-label="Show">
          {filters.map(([k, label, n]) => (
            <button key={k} type="button" className={`pill${filter === k ? ' on' : ''}`} aria-pressed={filter === k} onClick={() => setFilter(k)}>
              {k === 'attention' && n > 0 && <span className="dot" style={{ background: 'var(--fail)' }} />}
              {label}
              <span className="n">{n}</span>
            </button>
          ))}
        </div>
      </div>
      <div className="navi-list">
        {shown.length === 0 && (
          <p className="small muted" style={{ padding: 12 }}>
            {filter === 'attention' ? 'Nothing needs attention.' : 'No questions match.'}
          </p>
        )}
        {shown.map((f) =>
          f.item.type === 'SECTION' ? (
            <div key={f.item.uid} className="section-nav">
              {f.item.text || 'Untitled section'}
            </div>
          ) : (
            <button
              key={f.item.uid}
              type="button"
              className={`nitem${active === f.item.uid ? ' cur' : ''}`}
              style={{ paddingLeft: 8 + f.depth * 16 }}
              onClick={() => onSelect(f.item.uid)}
            >
              <span className="num">{numbers.get(f.item)}</span>
              <span className="body">
                <span className={`txt ellipsis${f.item.text ? '' : ' muted'}`}>{f.item.text || 'Untitled question'}</span>
                <span className="sub">
                  {typeLabel(f.item.type)}
                  {f.item.required && <span className="req">Required</span>}
                  {f.item.follow.length > 0 && (
                    <span>
                      · {f.item.follow.length} follow-up{f.item.follow.length > 1 ? 's' : ''}
                    </span>
                  )}
                </span>
              </span>
              {(issues.get(f.item.uid) ?? []).length > 0 && (
                <span className="warn" title={(issues.get(f.item.uid) ?? []).join(', ')}>
                  ⚠
                </span>
              )}
            </button>
          ),
        )}
      </div>
      <div className="navi-foot">
        <span className="small muted">Click a question to jump to it</span>
        <button type="button" className="btn btn-outline btn-xs" onClick={onAdd}>
          + Question
        </button>
      </div>
    </nav>
  )
}

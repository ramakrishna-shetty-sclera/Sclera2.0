import { useEffect, useState } from 'react'
import type { TargetKind, TargetType } from '../api/types'
import { getVocabulary, TARGET_KINDS } from '../api/vocabulary'
import type { Vocabulary } from '../api/vocabulary'

/**
 * What the procedure applies to — the places and assets it is for.
 *
 * Empty means **anything**: that is what every procedure written before this
 * existed means, and it is the common case, so the card says so in words rather
 * than letting an empty card read as "applies to nothing".
 *
 * A key can be typed as well as picked. A draft that names a key the vocabulary
 * does not have still saves — a key that does not exist yet is a normal
 * intermediate state — and it is publish that refuses it, so the card marks the
 * key instead of blocking it. A retired key is marked too, but is not an error:
 * retiring a key must not block an unrelated edit to a procedure that names it.
 *
 * If the vocabulary cannot be read the card still works. The author can still
 * type keys; they just cannot be checked or offered.
 */
export function AppliesToCard({
  targetTypes,
  onChange,
}: {
  targetTypes: TargetType[]
  onChange: (targetTypes: TargetType[]) => void
}) {
  const [vocabulary, setVocabulary] = useState<Vocabulary | null>(null)
  const [unavailable, setUnavailable] = useState(false)

  useEffect(() => {
    let cancelled = false
    getVocabulary()
      .then((v) => {
        if (!cancelled) setVocabulary(v)
      })
      .catch(() => {
        if (!cancelled) setUnavailable(true)
      })
    return () => {
      cancelled = true
    }
  }, [])

  function add(kind: TargetKind, raw: string) {
    const key = raw.trim()
    if (key === '' || targetTypes.some((t) => t.kind === kind && t.key === key)) return
    onChange([...targetTypes, { kind, key }])
  }

  function remove(target: TargetType) {
    onChange(targetTypes.filter((t) => !(t.kind === target.kind && t.key === target.key)))
  }

  return (
    <div className="card">
      <div className="field-label">Applies to</div>
      <p className="muted small">
        The places and assets this procedure is for.{' '}
        {targetTypes.length === 0
          ? 'None chosen, so it applies to anything.'
          : 'It is offered only for these; anything not listed is left out.'}
      </p>
      {unavailable && (
        <div className="alert alert-amber">
          The list of valid keys could not be read, so keys can be typed but not checked here.
          Publishing checks them again.
        </div>
      )}
      {TARGET_KINDS.map(({ kind, label }) => (
        <KindRow
          key={kind}
          kind={kind}
          label={label}
          chosen={targetTypes.filter((t) => t.kind === kind)}
          vocabulary={vocabulary}
          onAdd={(key) => add(kind, key)}
          onRemove={remove}
        />
      ))}
    </div>
  )
}

function KindRow({
  kind,
  label,
  chosen,
  vocabulary,
  onAdd,
  onRemove,
}: {
  kind: TargetKind
  label: string
  chosen: TargetType[]
  vocabulary: Vocabulary | null
  onAdd: (key: string) => void
  onRemove: (target: TargetType) => void
}) {
  const [draft, setDraft] = useState('')
  const entries = vocabulary?.[kind] ?? []
  const listId = `vocabulary-${kind}`

  /** Null when the vocabulary is unknown, so nothing is claimed about the key. */
  function status(key: string): 'ok' | 'retired' | 'unknown' | null {
    if (!vocabulary) return null
    const entry = entries.find((e) => e.key === key)
    if (!entry) return 'unknown'
    return entry.active ? 'ok' : 'retired'
  }

  function commit() {
    onAdd(draft)
    setDraft('')
  }

  return (
    <div className="applies-row">
      <div className="applies-label">{label}</div>
      <div className="applies-body">
        <div className="applies-chips">
          {chosen.length === 0 && <span className="muted small">none</span>}
          {chosen.map((t) => {
            const s = status(t.key)
            return (
              <span className="key-chip" key={t.key}>
                {entries.find((e) => e.key === t.key)?.name ?? t.key}
                <span className="rt-key-inline"> {t.key}</span>
                {s === 'unknown' && (
                  <span className="badge badge-red" title="Publishing will refuse this key">
                    not in the vocabulary
                  </span>
                )}
                {s === 'retired' && (
                  <span className="badge badge-gray" title="Still valid; it is no longer offered">
                    retired
                  </span>
                )}
                <button
                  type="button"
                  className="chip-remove"
                  aria-label={`Remove ${t.key}`}
                  onClick={() => onRemove(t)}
                >
                  ×
                </button>
              </span>
            )
          })}
        </div>
        <div className="applies-add">
          <input
            list={listId}
            value={draft}
            maxLength={50}
            placeholder={vocabulary ? 'Pick or type a key' : 'Type a key'}
            onChange={(e) => setDraft(e.target.value)}
            onKeyDown={(e) => {
              if (e.key === 'Enter') {
                e.preventDefault()
                commit()
              }
            }}
          />
          <datalist id={listId}>
            {entries
              .filter((e) => e.active && !chosen.some((t) => t.key === e.key))
              .map((e) => (
                <option key={e.key} value={e.key}>
                  {e.name}
                </option>
              ))}
          </datalist>
          <button type="button" className="btn btn-ghost small" onClick={commit} disabled={draft.trim() === ''}>
            + Add
          </button>
        </div>
      </div>
    </div>
  )
}

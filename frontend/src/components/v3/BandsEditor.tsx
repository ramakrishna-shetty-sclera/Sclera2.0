import type { RangeRule, ResultType } from '../../api/types'

/**
 * A result-type picker for a band — the same `<select>` shape
 * `ResultControl` uses for its non-segmented case (a result mapped to a
 * retired type still shows, marked invalid, rather than being silently
 * dropped), except the placeholder says a band is meant to mean something:
 * unlike an ordinary option, a band that exists with nothing picked is an
 * unfinished row, not a deliberate "decides nothing".
 */
export function BandResultSelect({
  value,
  resultTypes,
  onChange,
  label,
}: {
  value: string
  resultTypes: ResultType[]
  onChange: (result: string) => void
  label: string
}) {
  const stale = value !== '' && !resultTypes.some((r) => r.key === value)
  return (
    <select
      className={`select sm${stale ? ' invalid' : ''}`}
      style={{ width: 150, flexShrink: 0 }}
      aria-label={label}
      value={value}
      onChange={(e) => onChange(e.target.value)}
    >
      <option value="">— pick a result —</option>
      {resultTypes.map((r) => (
        <option key={r.id} value={r.key}>
          {r.name}
        </option>
      ))}
      {stale && <option value={value}>{value} — not active</option>}
    </select>
  )
}

/**
 * What an INTEGER reading means — distinct from Min/Max, which bound what
 * the inspector may *type*. Open-ended at both ends: a missing `min` is
 * "anything up to `max`", a missing `max` is "anything from `min`", the same
 * convention `Threshold` uses one level up.
 */
export function BandsEditor({
  rules,
  resultTypes,
  onChange,
}: {
  rules: RangeRule[]
  resultTypes: ResultType[]
  onChange: (rules: RangeRule[]) => void
}) {
  function patch(index: number, patch: Partial<RangeRule>) {
    onChange(rules.map((r, i) => (i === index ? { ...r, ...patch } : r)))
  }
  function remove(index: number) {
    onChange(rules.filter((_, i) => i !== index))
  }

  return (
    <div className="indent" style={{ display: 'flex', flexDirection: 'column', gap: 8 }}>
      <span className="small muted">What a reading means</span>
      {rules.length === 0 && (
        <p className="hint">
          No bands yet — the reading is recorded and decides nothing, which is a fine thing for a meter reading to do.
        </p>
      )}
      {rules.map((rule, i) => (
        <div key={i} className="optrow">
          <input
            type="number"
            aria-label={`Band ${i + 1} minimum`}
            className="input soft sm"
            style={{ width: 90 }}
            value={rule.min ?? ''}
            placeholder="No lower bound"
            onChange={(e) => patch(i, { min: e.target.value.trim() === '' ? null : Number(e.target.value) })}
          />
          <span className="small muted">to</span>
          <input
            type="number"
            aria-label={`Band ${i + 1} maximum`}
            className="input soft sm"
            style={{ width: 90 }}
            value={rule.max ?? ''}
            placeholder="No upper bound"
            onChange={(e) => patch(i, { max: e.target.value.trim() === '' ? null : Number(e.target.value) })}
          />
          <span className="small muted">is</span>
          <BandResultSelect
            value={rule.result ?? ''}
            resultTypes={resultTypes}
            onChange={(result) => patch(i, { result: result || null })}
            label={`Result for band ${i + 1}`}
          />
          <button type="button" className="ibtn danger" aria-label={`Remove band ${i + 1}`} onClick={() => remove(i)}>
            🗑
          </button>
        </div>
      ))}
      <button type="button" className="link" style={{ alignSelf: 'flex-start' }} onClick={() => onChange([...rules, {}])}>
        + Add band
      </button>
    </div>
  )
}

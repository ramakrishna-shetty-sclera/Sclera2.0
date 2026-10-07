import type { RangeRule, ResultType } from '../api/types'

/** '' is unset, not zero — a band's bound can genuinely be 0. */
function toNumber(text: string): number | undefined {
  return text.trim() === '' ? undefined : Number(text)
}

/**
 * A result picker for a band rather than an answer — the same organization
 * vocabulary, but no "decides nothing" choice: every band the author finishes
 * must map to a result, so the blank option reads as a prompt rather than a
 * legitimate resting state. (The server only enforces this at publish, so a
 * band mid-edit may still be blank — this is wording, not validation.)
 *
 * Shared by {@link BandsEditor} (a number question's reading bands) and
 * `ThresholdsEditor` (the document's score bands), which are the same shape
 * one level apart.
 */
export function BandResultPicker({
  value,
  resultTypes,
  onChange,
}: {
  value: string | null | undefined
  resultTypes: ResultType[]
  onChange: (result: string | undefined) => void
}) {
  const current = value ?? ''
  const stale = current !== '' && !resultTypes.some((r) => r.key === current)
  const swatch = resultTypes.find((r) => r.key === current)?.color

  return (
    <>
      {swatch && <span className="rt-swatch" style={{ background: swatch }} />}
      <select
        className={stale ? 'field-stale' : undefined}
        value={current}
        onChange={(e) => onChange(e.target.value || undefined)}
      >
        <option value="">— pick a result —</option>
        {resultTypes.map((r) => (
          <option key={r.id} value={r.key}>
            {r.name}
          </option>
        ))}
        {stale && <option value={current}>{current} — not an active result type</option>}
      </select>
    </>
  )
}

/**
 * What a number question's reading means — bands, each mapping a range to a
 * result type, so 9 can be Pass, 13 Amber and 20 Required.
 *
 * Distinct from the Minimum/Maximum above it on the question: those bound what
 * the inspector may type, these say what the typed value means. Open-ended at
 * both ends, same as the document's score thresholds — a blank minimum reads
 * "anything up to the maximum", a blank maximum "anything from the minimum".
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
  function patch(index: number, change: Partial<RangeRule>) {
    onChange(rules.map((r, i) => (i === index ? { ...r, ...change } : r)))
  }
  function remove(index: number) {
    onChange(rules.filter((_, i) => i !== index))
  }

  return (
    <div className="bands">
      <div className="field-label">What a reading means</div>
      {rules.length === 0 && (
        <p className="muted small">
          No bands yet — the reading is recorded and decides nothing, which is a fine thing for a
          meter reading to do.
        </p>
      )}
      {rules.map((rule, index) => (
        <div className="band-row" key={index}>
          <input
            type="number"
            value={rule.min ?? ''}
            placeholder="No lower bound"
            onChange={(e) => patch(index, { min: toNumber(e.target.value) })}
          />
          <span className="muted small">to</span>
          <input
            type="number"
            value={rule.max ?? ''}
            placeholder="No upper bound"
            onChange={(e) => patch(index, { max: toNumber(e.target.value) })}
          />
          <span className="muted small">is</span>
          <BandResultPicker
            value={rule.result}
            resultTypes={resultTypes}
            onChange={(result) => patch(index, { result })}
          />
          <button type="button" className="btn btn-ghost small" onClick={() => remove(index)}>
            Remove
          </button>
        </div>
      ))}
      <button
        type="button"
        className="btn btn-ghost small"
        onClick={() => onChange([...rules, {}])}
      >
        + Add band
      </button>
    </div>
  )
}

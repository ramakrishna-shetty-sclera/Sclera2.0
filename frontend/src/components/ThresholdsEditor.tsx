import type { ResultType, Scope, Threshold } from '../api/types'
import { BandResultPicker } from './BandsEditor'

/** '' is unset, not zero. */
function toNumber(text: string): number | undefined {
  return text.trim() === '' ? undefined : Number(text)
}

/**
 * The document's score thresholds — the first document-level control the
 * editor has had besides name and description, so it lives on the page
 * rather than inside one question's editor.
 *
 * Turns a final percentage into a result type, in the organization's own
 * vocabulary — "90 to 100 is a Pass". Entirely optional: a procedure with no
 * bands at all still publishes, and `weight`/`critical` on a question work
 * without ever reaching this card.
 *
 * `scope` only offers "Each section" once the document actually has a
 * section — a SECTION-scoped band in a document with none is refused on
 * every write, so the control that could cause that refusal is simply not
 * shown rather than offered and then rejected.
 */
export function ThresholdsEditor({
  thresholds,
  resultTypes,
  hasSections,
  onChange,
}: {
  thresholds: Threshold[]
  resultTypes: ResultType[]
  hasSections: boolean
  onChange: (thresholds: Threshold[]) => void
}) {
  function patch(index: number, change: Partial<Threshold>) {
    onChange(thresholds.map((t, i) => (i === index ? { ...t, ...change } : t)))
  }
  function remove(index: number) {
    onChange(thresholds.filter((_, i) => i !== index))
  }

  return (
    <div className="card">
      <div className="field-label">Score thresholds</div>
      <p className="muted small">
        What a final score means — e.g. 90 to 100 is a Pass, 70 to 89 is Amber, below that is a
        Fail. Leave this empty and the procedure still publishes; scoring is something an
        organization opts into.
      </p>
      {thresholds.map((band, index) => (
        <div className="band-row" key={index}>
          <input
            type="number"
            min={0}
            max={100}
            value={band.min ?? ''}
            placeholder="0"
            onChange={(e) => patch(index, { min: toNumber(e.target.value) })}
          />
          <span className="muted small">% to</span>
          <input
            type="number"
            min={0}
            max={100}
            value={band.max ?? ''}
            placeholder="100"
            onChange={(e) => patch(index, { max: toNumber(e.target.value) })}
          />
          <span className="muted small">% is</span>
          <BandResultPicker
            value={band.result}
            resultTypes={resultTypes}
            onChange={(result) => patch(index, { result })}
          />
          {hasSections && (
            <select
              value={band.scope ?? ''}
              title="What this band is read against"
              onChange={(e) =>
                patch(index, { scope: (e.target.value || undefined) as Scope | undefined })
              }
            >
              <option value="">Whole inspection</option>
              <option value="SECTION">Each section</option>
            </select>
          )}
          <button type="button" className="btn btn-ghost small" onClick={() => remove(index)}>
            Remove
          </button>
        </div>
      ))}
      <button
        type="button"
        className="btn btn-ghost small"
        onClick={() => onChange([...thresholds, {}])}
      >
        + Add band
      </button>
    </div>
  )
}

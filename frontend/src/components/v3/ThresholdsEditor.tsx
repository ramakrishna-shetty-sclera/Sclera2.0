import type { ResultType, Threshold } from '../../api/types'
import { BandResultSelect } from './BandsEditor'

/**
 * What a final score means — document-level, so it lives in the side panel
 * rather than on any one question. Empty publishes fine: scoring is
 * something an organization opts into, not a tax on every checklist.
 *
 * The scope selector only appears once the document has a section to be
 * scoped to — a `SECTION`-scoped band is refused by the server otherwise, so
 * it is hidden rather than offered and then rejected. "Whole inspection"
 * must map to `scope: null`, never the literal string `'VERSION'`: the
 * canonicaliser keeps any non-blank text, so sending it would change the
 * version's content hash where omitting it does not.
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
  function patch(index: number, patch: Partial<Threshold>) {
    onChange(thresholds.map((t, i) => (i === index ? { ...t, ...patch } : t)))
  }
  function remove(index: number) {
    onChange(thresholds.filter((_, i) => i !== index))
  }

  return (
    <div className="field" style={{ display: 'flex', flexDirection: 'column', gap: 8 }}>
      <label>Score thresholds</label>
      <span className="small muted">
        What a final score means — e.g. 90 to 100 is a Pass, 70 to 89 is Amber, below that is a Fail. Leave this empty
        and the procedure still publishes; scoring is something an organization opts into.
      </span>
      {thresholds.map((band, i) => (
        <div key={i} className="optrow" style={{ flexWrap: 'wrap' }}>
          <input
            type="number"
            aria-label={`Threshold ${i + 1} minimum percentage`}
            className="input soft sm"
            style={{ width: 70 }}
            min={0}
            max={100}
            value={band.min ?? ''}
            placeholder="0"
            onChange={(e) => patch(i, { min: e.target.value.trim() === '' ? null : Number(e.target.value) })}
          />
          <span className="small muted">% to</span>
          <input
            type="number"
            aria-label={`Threshold ${i + 1} maximum percentage`}
            className="input soft sm"
            style={{ width: 70 }}
            min={0}
            max={100}
            value={band.max ?? ''}
            placeholder="100"
            onChange={(e) => patch(i, { max: e.target.value.trim() === '' ? null : Number(e.target.value) })}
          />
          <span className="small muted">% is</span>
          <BandResultSelect
            value={band.result ?? ''}
            resultTypes={resultTypes}
            onChange={(result) => patch(i, { result: result || null })}
            label={`Result for threshold ${i + 1}`}
          />
          {hasSections && (
            <select
              className="select sm"
              style={{ width: 150 }}
              aria-label={`What threshold ${i + 1} is read against`}
              title="What this band is read against"
              value={band.scope ?? ''}
              onChange={(e) => patch(i, { scope: e.target.value === 'SECTION' ? 'SECTION' : null })}
            >
              <option value="">Whole inspection</option>
              <option value="SECTION">Each section</option>
            </select>
          )}
          <button type="button" className="ibtn danger" aria-label={`Remove threshold ${i + 1}`} onClick={() => remove(i)}>
            🗑
          </button>
        </div>
      ))}
      <button type="button" className="link" style={{ alignSelf: 'flex-start' }} onClick={() => onChange([...thresholds, {}])}>
        + Add band
      </button>
    </div>
  )
}

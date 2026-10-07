import { useEffect, useMemo, useState } from 'react'
import { ApiError } from '../api/client'
import { isChoice } from '../api/document'
import { evaluateVersion } from '../api/templates'
import type { EvaluationResponse, QuestionVerdict, ResultType } from '../api/types'
import type { ItemDraft } from './itemTree'

/** What the trial answer for one question looks like, the same shape the server reads. */
type Trial = Record<string, { value: unknown } | undefined>

/**
 * Only a choice question or an INTEGER reading decides a result or a score —
 * text and media "decide nothing", a section is never answered — so those are
 * the only two kinds worth a trial-answer control here.
 */
function scoresSomething(item: ItemDraft): boolean {
  return isChoice(item.type) || item.type === 'INTEGER'
}

function ResultBadge({ result, resultTypes }: { result?: string | null; resultTypes: ResultType[] }) {
  if (!result) return <span className="muted small">no result yet</span>
  const type = resultTypes.find((r) => r.key === result)
  return (
    <span className="badge" style={type ? { background: type.color, color: '#fff' } : undefined}>
      {type?.name ?? result}
    </span>
  )
}

function percent(value?: number | null): string {
  return value == null ? '—' : `${value}%`
}

/**
 * One question's trial-answer control, and everything hanging off it.
 *
 * A follow-up only renders once its parent's trial answer selects it — the
 * same reachability rule the server applies, kept in step here so the
 * preview never asks for an answer the server would then report in
 * `ignored`.
 *
 * `item.key` absent means this question has never been saved, so the stored
 * version has no key to evaluate it against yet — it is silently skipped,
 * the same rule "+ Follow-up" already teaches (save first, then it counts).
 */
function PreviewQuestion({
  item,
  parentKey,
  trial,
  onAnswer,
  resultTypes,
  verdictFor,
}: {
  item: ItemDraft
  parentKey: string | null
  trial: Trial
  onAnswer: (key: string, value: unknown) => void
  resultTypes: ResultType[]
  verdictFor: (key: string) => QuestionVerdict | undefined
}) {
  if (item.type === 'SECTION') {
    return <div className="preview-section">{item.text}</div>
  }
  if (!item.key) return null
  if (parentKey && trial[parentKey]?.value !== item.when) return null

  const scoring = scoresSomething(item)
  const verdict = verdictFor(item.key)
  const keyedOptions = item.options.filter((o): o is typeof o & { key: string } => Boolean(o.key))
  const current = trial[item.key]?.value

  return (
    <>
      {scoring && (
        <div className="preview-row">
          <span className="preview-text">{item.text}</span>

          {isChoice(item.type) && item.type !== 'CHECKBOX' && (
            <select
              value={typeof current === 'string' ? current : ''}
              onChange={(e) => onAnswer(item.key!, e.target.value || undefined)}
            >
              <option value="">— not answered —</option>
              {keyedOptions.map((o) => (
                <option key={o.uid} value={o.key}>
                  {o.label}
                </option>
              ))}
            </select>
          )}

          {item.type === 'CHECKBOX' && (
            <span className="preview-checkboxes">
              {keyedOptions.map((o) => {
                const selected = Array.isArray(current) ? (current as string[]) : []
                return (
                  <label className="checkbox small" key={o.uid}>
                    <input
                      type="checkbox"
                      checked={selected.includes(o.key)}
                      onChange={(e) => {
                        const next = e.target.checked
                          ? [...selected, o.key]
                          : selected.filter((k) => k !== o.key)
                        onAnswer(item.key!, next.length > 0 ? next : undefined)
                      }}
                    />
                    {o.label}
                  </label>
                )
              })}
            </span>
          )}

          {item.type === 'INTEGER' && (
            <input
              type="number"
              value={typeof current === 'number' ? current : ''}
              onChange={(e) =>
                onAnswer(item.key!, e.target.value.trim() === '' ? undefined : Number(e.target.value))
              }
            />
          )}

          <ResultBadge result={verdict?.result} resultTypes={resultTypes} />
          {verdict?.score != null && (
            <span className="muted small">
              {verdict.score}
              {verdict.possible != null ? ` / ${verdict.possible}` : ''}
            </span>
          )}
        </div>
      )}
      {item.follow.map((child) => (
        <PreviewQuestion
          key={child.uid}
          item={child}
          parentKey={item.key!}
          trial={trial}
          onAnswer={onAnswer}
          resultTypes={resultTypes}
          verdictFor={verdictFor}
        />
      ))}
    </>
  )
}

/**
 * A running score, built by picking trial answers and watching the endpoint's
 * verdict update — the same thing an inspector will eventually see, used here
 * at design time so an author can tell whether the weights, bands and
 * thresholds they just set actually produce what they meant.
 *
 * Reflects the draft's **last save**, not every keystroke in the editor
 * above: the endpoint evaluates a stored version by id, so a question added
 * since the last autosave has no key yet and will not appear here until it
 * is saved. `savedAt` is what triggers a fresh read after one lands.
 */
export function EvaluationPreview({
  templateId,
  versionNo,
  items,
  resultTypes,
  savedAt,
}: {
  templateId: string
  versionNo: number
  items: ItemDraft[]
  resultTypes: ResultType[]
  savedAt: Date | null
}) {
  const [trial, setTrial] = useState<Trial>({})
  const [result, setResult] = useState<EvaluationResponse | null>(null)
  const [error, setError] = useState<string | null>(null)
  const [loading, setLoading] = useState(false)

  function onAnswer(key: string, value: unknown) {
    setTrial((prev) => ({ ...prev, [key]: value === undefined ? undefined : { value } }))
  }

  // Re-evaluates whenever a trial answer changes, or a fresh autosave lands —
  // the structure may have changed even if no trial answer did.
  useEffect(() => {
    let cancelled = false
    setLoading(true)
    setError(null)
    evaluateVersion(templateId, versionNo, trial)
      .then((r) => {
        if (!cancelled) setResult(r)
      })
      .catch((e) => {
        if (cancelled) return
        setResult(null)
        setError(e instanceof ApiError ? e.message : 'Could not evaluate this draft')
      })
      .finally(() => !cancelled && setLoading(false))
    return () => {
      cancelled = true
    }
    // trial is a plain object rebuilt on every answer; comparing it by
    // reference is exactly what "an answer changed" means here.
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [templateId, versionNo, trial, savedAt])

  const verdictByKey = useMemo(() => {
    const map = new Map<string, QuestionVerdict>()
    result?.questions.forEach((q) => map.set(q.key, q))
    return map
  }, [result])

  return (
    <div className="card">
      <div className="field-label">Preview</div>
      <p className="muted small">
        Pick trial answers to see what this draft currently produces. This reflects the last save, not
        every keystroke above — questions added since then have no key yet, so they will not appear
        here until saved.
      </p>

      {error && <div className="alert alert-error">{error}</div>}

      {result && (
        <>
          <div className="preview-summary">
            <ResultBadge result={result.overall.result} resultTypes={resultTypes} />
            <span>{percent(result.overall.percentage)}</span>
            <span className="muted small">
              {result.overall.answered} answered · {result.overall.complete ? 'complete' : 'running'}
            </span>
            {result.critical.length > 0 && (
              <span className="badge badge-red">Critical failure ({result.critical.length})</span>
            )}
            {loading && <span className="muted small">Evaluating…</span>}
          </div>

          {result.sections.length > 1 && (
            <ul className="preview-sections">
              {result.sections.map((s) => (
                <li key={s.key ?? 'top'}>
                  <span>{s.text ?? 'Top-level questions'}</span>
                  <ResultBadge result={s.result} resultTypes={resultTypes} />
                  <span className="muted small">{percent(s.percentage)}</span>
                </li>
              ))}
            </ul>
          )}

          {result.workOrders.length > 0 && (
            <p className="field-hint">
              {result.workOrders.length} work order{result.workOrders.length === 1 ? '' : 's'} would be
              raised by these answers.
            </p>
          )}
        </>
      )}

      <div className="preview-questions">
        {items.map((item) => (
          <PreviewQuestion
            key={item.uid}
            item={item}
            parentKey={null}
            trial={trial}
            onAnswer={onAnswer}
            resultTypes={resultTypes}
            verdictFor={(key) => verdictByKey.get(key)}
          />
        ))}
      </div>
    </div>
  )
}

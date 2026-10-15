import { useEffect, useMemo, useState } from 'react'
import { ApiError } from '../api/client'
import { isChoice, numberItems, typeLabel } from '../api/document'
import { evaluateVersion } from '../api/templates'
import type { EvaluationResponse, QuestionVerdict, ResultType } from '../api/types'
import { flattenDrafts } from './v3/BuilderNavigator'
import type { ItemDraft } from './itemTree'

/** What the trial answer for one question looks like, the same shape the server reads. */
type Trial = Record<string, { value: unknown } | undefined>

/** How long to let trial answers settle before re-evaluating, so a quick run of clicks is one request, not several. */
const DEBOUNCE_MS = 250

function hasAnswer(value: unknown): boolean {
  if (Array.isArray(value)) return value.length > 0
  return value !== undefined && value !== null && String(value).trim() !== ''
}

/** Whether `value` selects `when` — array membership for Checkbox, equality otherwise. */
function selects(value: unknown, when: string): boolean {
  return Array.isArray(value) ? value.includes(when) : value === when
}

function percent(value?: number | null): string {
  return value == null ? '—' : `${Math.round(value)}%`
}

/**
 * A result-type key, resolved live to that type's own name and colour — never
 * a hard-coded pass/fail. A key that does not resolve (an inactive or deleted
 * result type a published version still legitimately references) falls back
 * to the raw key in a neutral tag rather than erroring.
 */
function ResultChip({ result, resultTypes }: { result?: string | null; resultTypes: ResultType[] }) {
  if (!result) return null
  const type = resultTypes.find((r) => r.key === result)
  return (
    <span
      className="tag"
      style={type ? { background: `${type.color}26`, color: type.color, border: `1px solid ${type.color}` } : undefined}
    >
      {type?.name ?? result}
    </span>
  )
}

/**
 * One reachable question's trial-answer control, verdict tag and critical
 * flag. Reachability is decided by the caller — by the time this renders, the
 * question is already known to be visible.
 */
function TrialQuestionCard({
  item,
  number,
  isFollow,
  trial,
  onAnswer,
  resultTypes,
  verdict,
}: {
  item: ItemDraft
  number: string
  isFollow: boolean
  trial: Trial
  onAnswer: (key: string, value: unknown) => void
  resultTypes: ResultType[]
  verdict: QuestionVerdict | undefined
}) {
  const current = item.key ? trial[item.key]?.value : undefined
  const keyedOptions = item.options.filter((o): o is typeof o & { key: string } => Boolean(o.key))

  function pillStyle(pressed: boolean, resultKey: string): React.CSSProperties | undefined {
    if (!pressed) return undefined
    const type = resultKey ? resultTypes.find((r) => r.key === resultKey) : undefined
    return type ? { background: `${type.color}33`, borderColor: type.color, color: type.color } : undefined
  }

  return (
    <div
      style={{
        border: `1px solid ${isFollow ? 'var(--tonal)' : 'var(--line)'}`,
        background: isFollow ? 'var(--selected)' : 'transparent',
        borderRadius: 8,
        padding: 12,
        display: 'flex',
        flexDirection: 'column',
        gap: 10,
      }}
    >
      <div className="row between">
        <span>{isFollow && <span className="small" style={{ color: 'var(--selected-text)' }}>Follow-up</span>}</span>
        <span className="row" style={{ gap: 6 }}>
          {item.critical && <span className="tag warn">Critical</span>}
          <ResultChip result={verdict?.result} resultTypes={resultTypes} />
        </span>
      </div>
      <span style={{ fontWeight: 500, marginTop: -6 }}>
        {number}. {item.text || 'Untitled question'}
        {item.required && <span className="req"> *</span>}
      </span>
      {item.help && (
        <span className="small muted" style={{ marginTop: -4 }}>
          {item.help}
        </span>
      )}

      {isChoice(item.type) && item.type !== 'CHECKBOX' && (
        <div className="row" style={{ gap: 8, flexWrap: 'wrap' }}>
          {keyedOptions.map((o) => {
            const pressed = current === o.key
            return (
              <button
                key={o.uid}
                type="button"
                className="simopt"
                aria-pressed={pressed}
                style={pillStyle(pressed, o.result)}
                onClick={() => onAnswer(item.key!, pressed ? undefined : o.key)}
              >
                {o.label}
              </button>
            )
          })}
        </div>
      )}

      {item.type === 'CHECKBOX' && (
        <div className="row" style={{ gap: 8, flexWrap: 'wrap' }}>
          {keyedOptions.map((o) => {
            const selected = Array.isArray(current) ? (current as string[]) : []
            const pressed = selected.includes(o.key)
            return (
              <button
                key={o.uid}
                type="button"
                className="simopt"
                aria-pressed={pressed}
                style={pillStyle(pressed, o.result)}
                onClick={() => {
                  const next = pressed ? selected.filter((k) => k !== o.key) : [...selected, o.key]
                  onAnswer(item.key!, next.length > 0 ? next : undefined)
                }}
              >
                {o.label}
              </button>
            )
          })}
        </div>
      )}

      {item.type === 'INTEGER' && (
        <input
          type="number"
          className="input"
          style={{ maxWidth: 160 }}
          value={typeof current === 'number' ? current : ''}
          onChange={(e) => onAnswer(item.key!, e.target.value.trim() === '' ? undefined : Number(e.target.value))}
        />
      )}

      {item.type === 'TEXT' && (
        <input
          type="text"
          className="input"
          value={typeof current === 'string' ? current : ''}
          onChange={(e) => onAnswer(item.key!, e.target.value.trim() === '' ? undefined : e.target.value)}
        />
      )}

      {!isChoice(item.type) && item.type !== 'INTEGER' && item.type !== 'TEXT' && (
        <span className="small muted">{typeLabel(item.type)} — not simulated in preview</span>
      )}
    </div>
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
 *
 * Which questions currently show is computed client-side from the trial
 * answers so far (the same reachability rule the server applies), not from
 * the endpoint's response — a follow-up has to appear the instant its
 * trigger is picked, not after a debounced round trip lands.
 */
export function EvaluationPreview({
  procedureName,
  templateId,
  versionNo,
  items,
  resultTypes,
  savedAt,
}: {
  procedureName: string
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

  useEffect(() => {
    let cancelled = false
    const timer = window.setTimeout(() => {
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
        .finally(() => {
          if (!cancelled) setLoading(false)
        })
    }, DEBOUNCE_MS)
    return () => {
      cancelled = true
      window.clearTimeout(timer)
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

  const indexed = useMemo(() => {
    const flat = flattenDrafts(items)
    const numbers = numberItems(items)
    const byKey = new Map<string, { item: ItemDraft; number: string }>()
    flat.forEach((f) => {
      if (f.item.key) byKey.set(f.item.key, { item: f.item, number: numbers.get(f.item) ?? '' })
    })
    return { flat, numbers, byKey }
  }, [items])

  // A single forward pass is enough: flattenDrafts always lists a parent
  // before its own children, so a node's reachability is already known by
  // the time its follow-ups are reached.
  const visible = useMemo(() => {
    const reachable = new Map<ItemDraft, boolean>()
    const out: { item: ItemDraft; parent: ItemDraft | null; number: string }[] = []
    for (const f of indexed.flat) {
      const parentOk = f.parent ? (reachable.get(f.parent) ?? false) : true
      const parentValue = f.parent?.key ? trial[f.parent.key]?.value : undefined
      const ok = f.parent ? parentOk && Boolean(f.item.when) && selects(parentValue, f.item.when) : true
      reachable.set(f.item, ok)
      if (ok && f.item.type !== 'SECTION' && f.item.key) {
        out.push({ item: f.item, parent: f.parent, number: indexed.numbers.get(f.item) ?? '' })
      }
    }
    return out
  }, [indexed, trial])

  const answerable = visible.length
  const answered = visible.filter((v) => hasAnswer(v.item.key ? trial[v.item.key]?.value : undefined)).length

  const overallType = result?.overall.result ? resultTypes.find((r) => r.key === result.overall.result) : undefined
  const criticalItems = (result?.critical ?? [])
    .map((k) => indexed.byKey.get(k))
    .filter((x): x is { item: ItemDraft; number: string } => Boolean(x))

  return (
    <div style={{ display: 'flex', flexDirection: 'column', gap: 10 }}>
      <p className="small muted">
        Pick trial answers to see what this draft currently produces. This reflects the last save — a question added
        since then has no key yet and will not appear here until it is saved.
      </p>
      {error && <div className="alert-error">{error}</div>}

      <div className="row" style={{ gap: 40, justifyContent: 'center', flexWrap: 'wrap', paddingTop: 4 }}>
        <div className="phone" aria-label="Inspector app">
          <div
            style={{
              padding: '18px 18px 12px',
              borderBottom: '1px solid var(--line-soft)',
              display: 'flex',
              flexDirection: 'column',
              gap: 8,
            }}
          >
            <span className="ellipsis" style={{ fontWeight: 600, color: 'var(--heading)' }}>
              {procedureName || 'Untitled procedure'}
            </span>
            <div className="progress">
              <div style={{ width: `${answerable ? (answered / answerable) * 100 : 0}%` }} />
            </div>
            <span className="small muted">
              {answered} of {answerable} answered
            </span>
          </div>
          <div style={{ flex: 1, overflow: 'auto', padding: 14, display: 'flex', flexDirection: 'column', gap: 10 }}>
            {visible.length === 0 && <p className="small muted">Nothing to try yet — add a question first.</p>}
            {visible.map((v) => (
              <TrialQuestionCard
                key={v.item.uid}
                item={v.item}
                number={v.number}
                isFollow={Boolean(v.parent)}
                trial={trial}
                onAnswer={onAnswer}
                resultTypes={resultTypes}
                verdict={v.item.key ? verdictByKey.get(v.item.key) : undefined}
              />
            ))}
          </div>
        </div>

        <div style={{ width: 340, display: 'flex', flexDirection: 'column', gap: 14 }}>
          <h3 style={{ fontSize: 16 }}>Outcome</h3>
          <p className="muted small">
            Answer as an inspector would. Follow-ups appear only when their answer is picked.
          </p>

          <div className="grid3" style={{ gap: 10 }}>
            <div className="stat">
              <b style={overallType ? { color: overallType.color } : undefined}>{overallType?.name ?? '—'}</b>
              <span className="small muted">Result</span>
            </div>
            <div className="stat">
              <b>{percent(result?.overall.percentage)}</b>
              <span className="small muted">Score</span>
            </div>
            <div className="stat">
              <b style={{ color: 'var(--action)' }}>{result?.workOrders.length ?? 0}</b>
              <span className="small muted">Work orders</span>
            </div>
          </div>

          {criticalItems.length > 0 && (
            <div className="alert-error">
              Failed on {criticalItems.length === 1 ? 'a critical question' : `${criticalItems.length} critical questions`}:{' '}
              {criticalItems.map((c) => `${c.number}. ${c.item.text || 'Untitled question'}`).join('; ')}
            </div>
          )}

          {result && result.workOrders.length > 0 && (
            <div style={{ display: 'flex', flexDirection: 'column', gap: 6 }}>
              {result.workOrders.map((w) => {
                const found = indexed.byKey.get(w.key)
                return (
                  <div
                    key={w.key}
                    className="row"
                    style={{ padding: '10px 12px', borderRadius: 4, background: 'var(--fail-bg)', gap: 8 }}
                  >
                    <span style={{ flex: 1 }}>
                      {found ? `${found.number}. ${found.item.text || 'Untitled question'}` : w.key}
                    </span>
                    <span className="small" style={{ color: 'var(--selected-text)', flexShrink: 0 }}>
                      Work order · {w.alertProfile || 'no alert profile set'}
                    </span>
                  </div>
                )
              })}
            </div>
          )}

          <button type="button" className="btn btn-quiet" style={{ alignSelf: 'flex-start' }} onClick={() => setTrial({})}>
            Reset answers
          </button>
          {loading && <span className="small muted">Evaluating…</span>}
        </div>
      </div>
    </div>
  )
}

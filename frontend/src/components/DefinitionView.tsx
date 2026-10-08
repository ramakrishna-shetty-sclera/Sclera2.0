import { useEffect, useState } from 'react'
import { numberItems, typeLabel } from '../api/document'
import { listDocuments, openDocument } from '../api/documents'
import type { DefinitionDocument, DefinitionItem, DefinitionOption, ProcedureDocument } from '../api/types'

/** What a follow-up hangs off, named by the answer rather than by its key. */
function trigger(when: string | null | undefined, parentOptions: DefinitionOption[]): string | null {
  if (!when) return null
  return parentOptions.find((o) => o.key === when)?.label ?? when
}

function bounds(item: DefinitionItem): string | null {
  const parts: string[] = []
  if (item.min !== null && item.min !== undefined) parts.push(`min ${item.min}`)
  if (item.max !== null && item.max !== undefined) parts.push(`max ${item.max}`)
  if (item.unit) parts.push(item.unit)
  return parts.length > 0 ? parts.join(' · ') : null
}

/**
 * One question, its answers, and the follow-ups hanging off them.
 *
 * Keys are shown deliberately — on the question and on every answer. They are
 * what a stored answer, a follow-up's trigger and a diff all refer to, so
 * seeing them is what makes "q4 was reworded" legible when you come back to the
 * version history.
 */
function QuestionView({
  item,
  parentOptions,
  numbers,
}: {
  item: DefinitionItem
  parentOptions: DefinitionOption[]
  numbers: Map<DefinitionItem, string>
}) {
  const when = trigger(item.when, parentOptions)
  const range = bounds(item)
  const number = numbers.get(item)

  return (
    <li className="doc-item">
      <div className="q-text">
        {number && <span className="doc-number">{number}</span>}
        {item.text} {item.required && <span className="req">*</span>}
        {item.key && <span className="rt-key-inline"> {item.key}</span>}
      </div>

      <div className="doc-meta muted small">
        <span className="badge badge-gray">{typeLabel(item.type)}</span>
        {when && <span>shown when answered “{when}”</span>}
        {range && <span>{range}</span>}
        {item.workOrder && (
          <span>raises a work order{item.alertProfile ? ` · ${item.alertProfile}` : ''}</span>
        )}
        {item.evidenceRequired && <span className="badge badge-amber">evidence required</span>}
        {item.standard && <span>{item.standard}</span>}
        {item.source && item.source !== 'MANUAL' && <span>from {typeLabel(item.source)}</span>}
      </div>

      {item.help && <div className="muted small">{item.help}</div>}

      {item.options.length > 0 && (
        <ul className="doc-options">
          {item.options.map((option, index) => (
            <li key={option.key ?? `o${index}`}>
              {option.label}
              {option.key && <span className="rt-key-inline"> {option.key}</span>}
              {option.result && <span className="doc-result">→ {option.result}</span>}
            </li>
          ))}
        </ul>
      )}

      {item.follow.length > 0 && (
        <ul className="doc-follow">
          {item.follow.map((child, index) => (
            <QuestionView
              key={child.key ?? `f${index}`}
              item={child}
              parentOptions={item.options}
              numbers={numbers}
            />
          ))}
        </ul>
      )}
    </li>
  )
}

/** The questions of a document at every depth, with the number the reader sees. */
function questionLabels(definition: DefinitionDocument): Map<string, string> {
  const numbers = numberItems(definition.items)
  const out = new Map<string, string>()
  const walk = (list: DefinitionItem[]) => {
    for (const item of list) {
      if (item.key && item.type !== 'SECTION') out.set(item.key, (numbers.get(item) ?? '') + ' ' + item.text)
      walk(item.follow)
    }
  }
  walk(definition.items)
  return out
}

/**
 * The documents this version cites. Names come from the library at the moment
 * of viewing, because a citation is an id and nothing else.
 */
function CitedDocuments({ definition }: { definition: DefinitionDocument }) {
  const refs = definition.documents ?? []
  const [library, setLibrary] = useState<ProcedureDocument[] | null>(null)

  useEffect(() => {
    if (refs.length === 0) return
    let cancelled = false
    listDocuments()
      .then((list) => {
        if (!cancelled) setLibrary(list)
      })
      .catch(() => {
        if (!cancelled) setLibrary([])
      })
    return () => {
      cancelled = true
    }
  }, [refs.length])

  if (refs.length === 0) return null
  const byId = new Map((library ?? []).map((d) => [d.id, d]))
  const labels = questionLabels(definition)

  return (
    <div className="card">
      <div className="field-label">Reference documents</div>
      <ul className="doc-refs">
        {refs.map((ref) => {
          const doc = byId.get(ref.id)
          return (
            <li key={ref.id + ':' + (ref.questionKey ?? '')}>
              {doc ? (
                <button type="button" className="link-button" onClick={() => openDocument(doc.location)}>
                  {doc.name}
                </button>
              ) : (
                <span className="muted">{library ? 'document unavailable' : 'Loading…'}</span>
              )}
              <span className="muted small">
                {' '}
                {ref.questionKey ? 'on ' + (labels.get(ref.questionKey) ?? 'question ' + ref.questionKey) : 'whole procedure'}
              </span>
            </li>
          )
        })}
      </ul>
    </div>
  )
}

/**
 * A section heading and the questions that come after it.
 *
 * The document does not nest them — a section's `follow` is empty and the
 * questions are its siblings, because nesting under a section would mean "shown
 * only when the section is answered" and a section is never answered. So the
 * grouping is reconstructed here, for reading, and nothing about the document
 * changes.
 */
interface Group {
  section: DefinitionItem | null
  questions: DefinitionItem[]
}

function group(items: DefinitionItem[]): Group[] {
  const groups: Group[] = []
  for (const item of items) {
    if (item.type === 'SECTION' || groups.length === 0) {
      groups.push({ section: item.type === 'SECTION' ? item : null, questions: [] })
    }
    if (item.type !== 'SECTION') {
      groups[groups.length - 1].questions.push(item)
    }
  }
  return groups
}

/**
 * A procedure version's content, read-only.
 *
 * Shared by the detail screen and the version viewer, because a published
 * version and a draft render identically — the difference between them is what
 * you may do to them, not what they look like.
 */
export function DefinitionView({ definition }: { definition: DefinitionDocument }) {
  // Numbered from the document, not from the groups: the count runs across the
  // whole procedure, so a second section's first question is 3, not 1.
  const numbers = numberItems(definition.items)

  if (definition.items.length === 0) {
    return <p className="muted">Nothing in this procedure yet.</p>
  }

  const targetTypes = definition.targetTypes ?? []

  return (
    <>
      <CitedDocuments definition={definition} />
      {targetTypes.length > 0 && (
        <div className="card">
          <div className="field-label">Applies to</div>
          <div className="applies-chips">
            {targetTypes.map((t) => (
              <span className="key-chip" key={t.kind + ':' + t.key}>
                {t.key}
                <span className="muted small"> {typeLabel(t.kind)}</span>
              </span>
            ))}
          </div>
        </div>
      )}
      {group(definition.items).map((g, index) => (
        <div className="card" key={g.section?.key ?? `g${index}`}>
          {g.section && (
            <h2 className="doc-section">
              {g.section.text}
              {g.section.key && <span className="rt-key-inline"> {g.section.key}</span>}
            </h2>
          )}
          {g.section?.help && <p className="muted small">{g.section.help}</p>}
          {g.questions.length === 0 ? (
            <p className="muted small">No questions here yet.</p>
          ) : (
            <ul className="doc-list">
              {g.questions.map((item, qIndex) => (
                <QuestionView
                  key={item.key ?? `q${qIndex}`}
                  item={item}
                  parentOptions={[]}
                  numbers={numbers}
                />
              ))}
            </ul>
          )}
        </div>
      ))}
    </>
  )
}

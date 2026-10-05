import { typeLabel } from '../api/document'
import type { DefinitionDocument, DefinitionItem, DefinitionOption } from '../api/types'

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
}: {
  item: DefinitionItem
  parentOptions: DefinitionOption[]
}) {
  const when = trigger(item.when, parentOptions)
  const range = bounds(item)

  return (
    <li className="doc-item">
      <div className="q-text">
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
            <QuestionView key={child.key ?? `f${index}`} item={child} parentOptions={item.options} />
          ))}
        </ul>
      )}
    </li>
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
  if (definition.items.length === 0) {
    return <p className="muted">Nothing in this procedure yet.</p>
  }

  return (
    <>
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
                <QuestionView key={item.key ?? `q${qIndex}`} item={item} parentOptions={[]} />
              ))}
            </ul>
          )}
        </div>
      ))}
    </>
  )
}

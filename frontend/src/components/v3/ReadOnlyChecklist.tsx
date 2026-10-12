import { numberItems, typeLabel, isChoice } from '../../api/document'
import type { DefinitionItem } from '../../api/types'

/**
 * A procedure document rendered the way docs/procedures-v3.html's ViewPage
 * does: one card per question with its number, its answers as chips (the dot
 * carries the result), follow-ups indented with an "If … is …" tag, and
 * sections as uppercase headings between cards.
 */
export function ReadOnlyChecklist({ items }: { items: DefinitionItem[] }) {
  const numbers = numberItems(items)
  return (
    <div style={{ display: 'flex', flexDirection: 'column', gap: 8 }}>
      {renderLevel(items, null, 0, numbers)}
    </div>
  )
}

export function resultDot(result: string | null | undefined): string {
  if (!result) return 'var(--dim)'
  if (result === 'PASS') return 'var(--pass)'
  if (result === 'FAIL') return 'var(--fail)'
  return 'var(--info)'
}

function renderLevel(
  items: DefinitionItem[],
  parent: DefinitionItem | null,
  depth: number,
  numbers: Map<DefinitionItem, string>,
): React.ReactNode[] {
  return items.flatMap((item) => {
    if (item.type === 'SECTION') {
      return [
        <div key={keyOf(item)} className="section-nav" style={{ padding: '14px 0 4px' }}>
          {item.text || 'Untitled section'}
        </div>,
      ]
    }
    const number = numbers.get(item)
    const whenLabel = parent?.options.find((o) => o.key === item.when)?.label
    return [
      <div key={keyOf(item)} className="vq" style={{ marginLeft: depth * 28 }}>
        <span className="qnum">{number}</span>
        <span style={{ flex: 1, display: 'flex', flexDirection: 'column', gap: 6, minWidth: 0 }}>
          {parent && (
            <span className="tag sel" style={{ alignSelf: 'flex-start' }}>
              If {number?.split('.').slice(0, -1).join('.')} is {whenLabel ?? '…'}
            </span>
          )}
          <span style={{ fontWeight: 500 }}>
            {item.text}
            {item.required && <span className="req"> *</span>}
          </span>
          {item.help && <span className="small muted">{item.help}</span>}
          <span className="opts-line" style={{ marginTop: 0 }}>
            {item.options.map((o) => (
              <span key={o.key ?? o.label} className="ochip">
                <span className="dot" style={{ background: resultDot(o.result) }} />
                {o.label}
              </span>
            ))}
            {!isChoice(item.type) && (
              <span className="ochip muted">
                {typeLabel(item.type)}
                {item.unit ? ` (${item.unit})` : ''}
                {item.min != null || item.max != null
                  ? ` · ${item.min ?? '…'}–${item.max ?? '…'}`
                  : ''}
              </span>
            )}
            {item.workOrder && <span className="tag warn">Work order on fail</span>}
          </span>
        </span>
        <span className="small muted" style={{ flexShrink: 0 }}>
          {typeLabel(item.type)}
        </span>
      </div>,
      ...renderLevel(item.follow, item, depth + 1, numbers),
    ]
  })
}

let fallback = 0
const keys = new WeakMap<DefinitionItem, string>()
function keyOf(item: DefinitionItem): string {
  if (item.key) return item.key
  let k = keys.get(item)
  if (!k) {
    k = `ro${++fallback}`
    keys.set(item, k)
  }
  return k
}

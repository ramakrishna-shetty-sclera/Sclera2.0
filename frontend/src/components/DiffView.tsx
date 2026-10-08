import type { DiffKind, ProcedureDiff } from '../api/types'

const KIND_BADGE: Record<DiffKind, string> = {
  ADDED: 'badge-green',
  REMOVED: 'badge-red',
  MODIFIED: 'badge-amber',
}

/** 'workOrder' becomes 'work order', 'alertProfile' 'alert profile'. */
function readable(field: string): string {
  return field.replace(/([A-Z])/g, ' $1').toLowerCase()
}

/**
 * What changed between two versions.
 *
 * One list, not two: sections and questions live in one list in the document,
 * so they do here too, and `parentKey` says where each one sits. That is what
 * makes a move legible — a question dragged into another section comes back as
 * one MODIFIED entry whose changed field is `parent`.
 *
 * The server matches on stable keys, which is why a reworded question is one
 * MODIFIED entry rather than a REMOVED and an ADDED that the reader has to pair
 * up. Keys are shown for the same reason they are shown elsewhere: they are
 * what the change is anchored to.
 */
export function DiffView({ result }: { result: ProcedureDiff }) {
  const { diff, fromVersionNo, toVersionNo } = result

  if (diff.identical) {
    return (
      <p className="muted">
        v{fromVersionNo} and v{toVersionNo} are identical.
      </p>
    )
  }

  return (
    <div>
      <p className="muted small">
        What changed from v{fromVersionNo} to v{toVersionNo}.
      </p>

      {diff.orderChanged && <div className="alert alert-amber">The items were reordered.</div>}
      {diff.targetTypesChanged && (
        <div className="alert alert-amber">What the procedure applies to changed.</div>
      )}
      {diff.documentsChanged && (
        <div className="alert alert-amber">The documents this procedure cites changed.</div>
      )}

      {diff.items.length > 0 && (
        <div className="card">
          <ul className="question-list">
            {diff.items.map((item) => (
              <li key={item.key}>
                <div className="q-text">
                  <span className={`badge ${KIND_BADGE[item.kind]}`}>{item.kind}</span> {item.text}
                  <span className="rt-key-inline"> {item.key}</span>
                </div>
                <div className="muted small">
                  {item.parentKey ? `under ${item.parentKey}` : 'top level'}
                  {item.changedFields.length > 0 &&
                    ` · changed: ${item.changedFields.map(readable).join(', ')}`}
                </div>
              </li>
            ))}
          </ul>
        </div>
      )}
    </div>
  )
}

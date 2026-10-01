import type { DiffKind, ProcedureDiff } from '../api/types'

const KIND_BADGE: Record<DiffKind, string> = {
  ADDED: 'badge-green',
  REMOVED: 'badge-red',
  MODIFIED: 'badge-amber',
}

/** Turns 'helpText' into 'help text' so the list reads as prose, not as field names. */
function readable(field: string): string {
  return field.replace(/([A-Z])/g, ' $1').toLowerCase()
}

/**
 * What changed between two versions.
 *
 * The server matches on stable question keys, which is why a reworded question
 * comes back as one MODIFIED entry rather than a REMOVED and an ADDED that the
 * reader has to pair up themselves. Keys are shown for the same reason they are
 * shown elsewhere: they are what the change is actually anchored to.
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

      {diff.categoryOrderChanged && (
        <div className="alert alert-amber">The categories were reordered.</div>
      )}

      {diff.categories.length > 0 && (
        <div className="card">
          <h2>Categories</h2>
          <ul className="question-list">
            {diff.categories.map((c) => (
              <li key={c.key}>
                <div className="q-text">
                  <span className={`badge ${KIND_BADGE[c.kind]}`}>{c.kind}</span> {c.name}
                  <span className="rt-key-inline"> {c.key}</span>
                </div>
                {c.changedFields.length > 0 && (
                  <div className="muted small">changed: {c.changedFields.map(readable).join(', ')}</div>
                )}
              </li>
            ))}
          </ul>
        </div>
      )}

      {diff.questions.length > 0 && (
        <div className="card">
          <h2>Questions</h2>
          <ul className="question-list">
            {diff.questions.map((q) => (
              <li key={q.key}>
                <div className="q-text">
                  <span className={`badge ${KIND_BADGE[q.kind]}`}>{q.kind}</span> {q.text}
                  <span className="rt-key-inline"> {q.key}</span>
                </div>
                <div className="muted small">
                  in {q.categoryKey}
                  {q.changedFields.length > 0 && ` · changed: ${q.changedFields.map(readable).join(', ')}`}
                </div>
              </li>
            ))}
          </ul>
        </div>
      )}
    </div>
  )
}

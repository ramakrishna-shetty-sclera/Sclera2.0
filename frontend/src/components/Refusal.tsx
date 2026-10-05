import { ApiError, splitReasons } from '../api/client'

/**
 * What the server refused, as a list when it named more than one thing.
 *
 * Both refusals an author meets — a save that breaks the document's structure
 * and a publish that is not ready — arrive naming everything at once, so the
 * screen shows everything at once. A conflict is not one of these: it needs a
 * reload rather than a fix, so the pages handle it themselves.
 */
export function Refusal({ error, tone = 'error' }: { error: unknown; tone?: 'error' | 'amber' }) {
  if (!error) return null

  const message = error instanceof Error ? error.message : String(error)
  // A 400 with per-field errors is already a list; use it rather than splitting
  // a sentence apart.
  const fields =
    error instanceof ApiError && error.fieldErrors.length > 0
      ? error.fieldErrors.map((f) => (f.field ? `${f.field}: ${f.message}` : f.message))
      : null
  const { lead, list } = fields ? { lead: message, list: fields } : splitReasons(message)

  return (
    <div className={`alert alert-${tone}`}>
      {list.length === 0 ? (
        message
      ) : (
        <>
          <strong>{lead ?? 'This cannot be saved yet'}:</strong>
          <ul className="refusal-list">
            {list.map((reason, index) => (
              <li key={`${index}-${reason}`}>{reason}</li>
            ))}
          </ul>
        </>
      )}
    </div>
  )
}

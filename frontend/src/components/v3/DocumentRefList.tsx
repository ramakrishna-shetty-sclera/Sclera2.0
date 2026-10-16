import { openDocument } from '../../api/documents'
import type { DocumentRef, ProcedureDocument } from '../../api/types'

/**
 * Citations, read-only — no Remove button — for the two preview views (the
 * Full checklist tab, and the running simulation) where nothing is editable.
 */
export function ReadOnlyDocumentLinks({ refs, library }: { refs: DocumentRef[]; library: ProcedureDocument[] }) {
  if (refs.length === 0) return null
  return (
    <span className="row small" style={{ gap: 10, flexWrap: 'wrap' }}>
      {refs.map((ref) => {
        const doc = library.find((d) => d.id === ref.id)
        return doc ? (
          <button key={ref.id} type="button" className="link" onClick={() => void openDocument(doc.location)}>
            📎 {doc.name}
            {!doc.active && ' (inactive)'}
          </button>
        ) : (
          <span key={ref.id} className="muted small">
            📎 document unavailable
          </span>
        )
      })}
    </span>
  )
}

/**
 * A list of cited documents, resolved by id against the library live — a
 * rename reaches every citation, and one whose document was later
 * deactivated still shows (with a plain "inactive" tag) rather than
 * vanishing, since a published version may still legitimately cite it.
 * Shared between the procedure-level and per-question citation lists so
 * both read identically.
 */
export function DocumentRefList({
  refs,
  library,
  onRemove,
}: {
  refs: DocumentRef[]
  library: ProcedureDocument[]
  onRemove: (ref: DocumentRef) => void
}) {
  if (refs.length === 0) return null
  return (
    <ul style={{ listStyle: 'none', margin: 0, padding: 0, display: 'flex', flexDirection: 'column', gap: 4 }}>
      {refs.map((ref) => {
        const doc = library.find((d) => d.id === ref.id)
        return (
          <li key={`${ref.id}-${ref.questionKey ?? ''}`} className="row" style={{ gap: 8, flexWrap: 'wrap' }}>
            <span aria-hidden="true">📎</span>
            {doc ? (
              <button type="button" className="link" onClick={() => void openDocument(doc.location)}>
                {doc.name}
              </button>
            ) : (
              <span className="muted small">document unavailable</span>
            )}
            {doc && !doc.active && <span className="tag line">inactive</span>}
            <button type="button" className="btn btn-quiet btn-xs" onClick={() => onRemove(ref)}>
              Remove
            </button>
          </li>
        )
      })}
    </ul>
  )
}

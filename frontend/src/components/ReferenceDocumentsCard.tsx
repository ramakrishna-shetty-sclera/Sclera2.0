import { useEffect, useMemo, useState } from 'react'
import { listDocuments, openDocument } from '../api/documents'
import { numberItems } from '../api/document'
import type { DocumentRef, ProcedureDocument } from '../api/types'
import type { ItemDraft } from './itemTree'

/** Every keyed question, with the number the author sees. A section is never cited. */
function citableQuestions(items: ItemDraft[]): { key: string; label: string }[] {
  const numbers = numberItems(items)
  const out: { key: string; label: string }[] = []
  const walk = (list: ItemDraft[]) => {
    for (const item of list) {
      if (item.type !== 'SECTION' && item.key) {
        const text = item.text.trim() || '(no text yet)'
        out.push({ key: item.key, label: `${numbers.get(item) ?? ''} ${text}`.trim() })
      }
      walk(item.follow)
    }
  }
  walk(items)
  return out
}

/**
 * The reference documents this procedure cites — the whole procedure, or one
 * question.
 *
 * A citation is an id and nothing else, so names are looked up from the library
 * every time: renaming a document there shows here at once, and an id the
 * library no longer has reads "unavailable" instead of vanishing — it would
 * otherwise be a silent loss, and publish refuses it anyway.
 *
 * Only a question that already has a key can be cited. The key is what the
 * citation points at and it is minted by the first save, so a question typed a
 * moment ago appears here once the draft has saved.
 */
export function ReferenceDocumentsCard({
  documents,
  items,
  onChange,
}: {
  documents: DocumentRef[]
  items: ItemDraft[]
  onChange: (documents: DocumentRef[]) => void
}) {
  const [library, setLibrary] = useState<ProcedureDocument[] | null>(null)
  const [unavailable, setUnavailable] = useState(false)
  const [pick, setPick] = useState('')
  const [scope, setScope] = useState('')

  useEffect(() => {
    let cancelled = false
    listDocuments()
      .then((list) => {
        if (!cancelled) setLibrary(list)
      })
      .catch(() => {
        if (!cancelled) setUnavailable(true)
      })
    return () => {
      cancelled = true
    }
  }, [])

  const questions = useMemo(() => citableQuestions(items), [items])
  const byId = new Map((library ?? []).map((d) => [d.id, d]))
  const sameCitation = (r: DocumentRef, id: string, key: string) =>
    r.id === id && (r.questionKey ?? '') === key

  const offered = (library ?? []).filter((d) => d.active && !documents.some((r) => sameCitation(r, d.id, scope)))

  function add() {
    if (!pick) return
    onChange([...documents, scope ? { id: pick, questionKey: scope } : { id: pick }])
    setPick('')
  }

  return (
    <div className="card">
      <div className="field-label">Reference documents</div>
      <p className="muted small">
        Documents an inspector can open while doing this procedure. Add them to the library under
        Documents first.
      </p>
      {unavailable && (
        <div className="alert alert-amber">The document library could not be read.</div>
      )}

      {documents.length === 0 ? (
        <p className="muted small">None cited.</p>
      ) : (
        <ul className="doc-refs">
          {documents.map((ref) => {
            const doc = byId.get(ref.id)
            const question = ref.questionKey ? questions.find((q) => q.key === ref.questionKey) : null
            return (
              <li key={ref.id + ':' + (ref.questionKey ?? '')}>
                {doc ? (
                  <button type="button" className="link-button" onClick={() => openDocument(doc.location)}>
                    {doc.name}
                  </button>
                ) : (
                  <span className="muted">{library ? 'document unavailable' : 'Loading…'}</span>
                )}
                {doc && !doc.active && (
                  <span className="badge badge-gray" title="Publishing will refuse a cited document that is inactive">
                    inactive
                  </span>
                )}
                <span className="muted small">
                  {' '}
                  {ref.questionKey ? `on ${question ? question.label : 'question ' + ref.questionKey}` : 'whole procedure'}
                </span>
                <button
                  type="button"
                  className="btn btn-ghost small"
                  onClick={() => onChange(documents.filter((r) => r !== ref))}
                >
                  Remove
                </button>
              </li>
            )
          })}
        </ul>
      )}

      <div className="applies-add">
        <select value={pick} onChange={(e) => setPick(e.target.value)} aria-label="Document to cite">
          <option value="">{library && library.length === 0 ? 'The library is empty' : 'Pick a document…'}</option>
          {offered.map((d) => (
            <option key={d.id} value={d.id}>
              {d.name}
            </option>
          ))}
        </select>
        <select value={scope} onChange={(e) => setScope(e.target.value)} aria-label="Cite it on">
          <option value="">Whole procedure</option>
          {questions.map((q) => (
            <option key={q.key} value={q.key}>
              {q.label}
            </option>
          ))}
        </select>
        <button type="button" className="btn btn-ghost small" onClick={add} disabled={!pick}>
          + Cite
        </button>
      </div>
    </div>
  )
}

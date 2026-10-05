import { useEffect, useState, type FormEvent } from 'react'
import { useNavigate, useParams } from 'react-router-dom'
import { createProcedure, getDraft, getProcedure, saveDraft, updateProcedure } from '../api/templates'
import { ApiError } from '../api/client'
import type { DefinitionDocument, ItemType } from '../api/types'

const ITEM_TYPES: ItemType[] = [
  'SECTION',
  'YES_NO',
  'YES_NO_NA',
  'RADIO',
  'CHECKBOX',
  'DROPDOWN',
  'TEXT',
  'INTEGER',
  'IMAGE',
  'MULTI_IMAGE',
  'AUDIO',
  'VIDEO',
  'DOCUMENT',
]

/**
 * Editing state mirrors the document, with one addition: `key`.
 *
 * Keys are minted by the server and are what make a procedure's history
 * meaningful — a stored answer, a rule and a diff all refer to an item by key.
 * So an existing item carries its key through the edit and back, unchanged, and
 * a new one has none until the server assigns it. Dropping a key on the way
 * through would read as "that question was deleted and another one appeared",
 * silently orphaning everything pointing at it.
 */
interface ItemDraft {
  key?: string
  text: string
  help: string
  type: ItemType
  required: boolean
}

const emptyItem = (): ItemDraft => ({ text: '', help: '', type: 'TEXT', required: false })

/**
 * Authors the one editable thing a procedure has: its draft.
 *
 * Two modes. Creating makes the procedure and its draft v1 in a single call.
 * Editing loads the open draft and replaces it wholesale, under an optimistic
 * lock — name and description are identity rather than version content, so they
 * go through a separate call and only when they actually changed.
 *
 * One flat list, which is what the document is: a section is an item that
 * happens to be a heading, and the questions after it are its siblings.
 * Answers and follow-ups are not editable here yet.
 */
export function TemplateEditorPage() {
  const { id } = useParams<{ id: string }>()
  const navigate = useNavigate()
  const editing = Boolean(id)

  const [name, setName] = useState('')
  const [description, setDescription] = useState('')
  const [items, setItems] = useState<ItemDraft[]>([emptyItem()])
  const [changeNote, setChangeNote] = useState('')
  const [schema, setSchema] = useState(2)
  const [rowVersion, setRowVersion] = useState<number | null>(null)
  /** Set separately from `error`: a conflict needs a reload, not a retry. */
  const [conflict, setConflict] = useState(false)
  const [error, setError] = useState<string | null>(null)
  const [busy, setBusy] = useState(false)
  const [loaded, setLoaded] = useState(!editing)
  /** What identity looked like when loaded, so an unchanged name costs no write. */
  const [original, setOriginal] = useState({ name: '', description: '' })

  useEffect(() => {
    if (!id) return
    Promise.all([getProcedure(id), getDraft(id)])
      .then(([procedure, draft]) => {
        setName(procedure.name)
        setDescription(procedure.description ?? '')
        setOriginal({ name: procedure.name, description: procedure.description ?? '' })
        setSchema(draft.definition.schema)
        setRowVersion(draft.rowVersion)
        setItems(
          draft.definition.items.map((item) => ({
            key: item.key,
            text: item.text,
            help: item.help ?? '',
            type: item.type,
            required: item.required,
          })),
        )
        setLoaded(true)
      })
      .catch((e) =>
        setError(
          e instanceof ApiError && e.status === 404
            ? 'This procedure has no open draft. Start one from its detail screen.'
            : e instanceof Error
              ? e.message
              : 'Could not load the draft',
        ),
      )
  }, [id])

  function patchItem(index: number, patch: Partial<ItemDraft>) {
    setItems((prev) => prev.map((item, i) => (i === index ? { ...item, ...patch } : item)))
  }

  /** Keys ride through untouched; a blank one is simply absent, so the server mints it. */
  function toDocument(): DefinitionDocument {
    return {
      schema,
      items: items.map((item) => ({
        key: item.key,
        text: item.text.trim(),
        help: item.help.trim() || undefined,
        type: item.type,
        // A section is never answered, so it is never required.
        required: item.type === 'SECTION' ? false : item.required,
        options: [],
        workOrder: false,
        follow: [],
      })),
    }
  }

  async function onSubmit(e: FormEvent) {
    e.preventDefault()
    setBusy(true)
    setError(null)
    setConflict(false)
    try {
      if (!editing) {
        const created = await createProcedure({
          name: name.trim(),
          description: description.trim() || undefined,
          definition: toDocument(),
        })
        navigate(`/templates/${created.id}`)
        return
      }

      if (name.trim() !== original.name || description.trim() !== original.description) {
        await updateProcedure(id!, { name: name.trim(), description: description.trim() || undefined })
      }
      await saveDraft(id!, {
        definition: toDocument(),
        rowVersion: rowVersion!,
        changeNote: changeNote.trim() || undefined,
      })
      navigate(`/templates/${id}`)
    } catch (e) {
      if (e instanceof ApiError && e.status === 409) {
        setConflict(true)
        setError('Someone else saved this draft while you were editing it.')
      } else if (e instanceof ApiError && e.fieldErrors.length > 0) {
        setError(e.fieldErrors.map((f) => `${f.field}: ${f.message}`).join(' · '))
      } else {
        setError(e instanceof Error ? e.message : 'Could not save')
      }
      setBusy(false)
    }
  }

  if (!loaded) {
    return error ? <div className="alert alert-error">{error}</div> : <p className="muted">Loading…</p>
  }

  return (
    <form onSubmit={onSubmit}>
      <div className="page-head">
        <div>
          <h1>{editing ? 'Edit draft' : 'New procedure'}</h1>
          <p className="muted small">
            {editing
              ? 'Saving replaces the whole draft. Publishing it from the detail screen is what freezes it.'
              : 'This creates the procedure and opens draft v1. Nothing is published until you say so.'}
          </p>
        </div>
        <div className="page-actions">
          <button type="button" className="btn" onClick={() => navigate(-1)}>
            Cancel
          </button>
          <button type="submit" className="btn btn-primary" disabled={busy || conflict}>
            {busy ? 'Saving…' : 'Save draft'}
          </button>
        </div>
      </div>

      {conflict ? (
        <div className="alert alert-error">
          {error} Reload to see their version — saving over it would lose their work.{' '}
          <button type="button" className="btn small" onClick={() => window.location.reload()}>
            Reload
          </button>
        </div>
      ) : (
        error && <div className="alert alert-error">{error}</div>
      )}

      <div className="card">
        <label>
          Name *
          <input value={name} onChange={(e) => setName(e.target.value)} required maxLength={200} />
        </label>
        <label>
          Description
          <textarea
            value={description}
            onChange={(e) => setDescription(e.target.value)}
            maxLength={2000}
            rows={2}
          />
        </label>
        {editing && (
          <label>
            What changed
            <input
              value={changeNote}
              onChange={(e) => setChangeNote(e.target.value)}
              maxLength={2000}
              placeholder="Shown in the version history"
            />
          </label>
        )}
      </div>

      <div className="card">
        {items.map((item, index) => (
          <div className="question-editor" key={item.key ?? `new-${index}`}>
            <div className="form-grid">
              <label className="grow">
                {item.type === 'SECTION' ? 'Section' : 'Question'} {index + 1} *
                <input
                  value={item.text}
                  onChange={(e) => patchItem(index, { text: e.target.value })}
                  required
                  maxLength={1000}
                />
              </label>
              <label>
                Type
                <select
                  value={item.type}
                  onChange={(e) => patchItem(index, { type: e.target.value as ItemType })}
                >
                  {ITEM_TYPES.map((t) => (
                    <option key={t} value={t}>
                      {t}
                    </option>
                  ))}
                </select>
              </label>
            </div>
            <div className="form-grid">
              <label className="grow">
                Help text
                <input
                  value={item.help}
                  onChange={(e) => patchItem(index, { help: e.target.value })}
                  maxLength={1000}
                />
              </label>
              {item.type !== 'SECTION' && (
                <label className="checkbox">
                  <input
                    type="checkbox"
                    checked={item.required}
                    onChange={(e) => patchItem(index, { required: e.target.checked })}
                  />
                  Required
                </label>
              )}
            </div>
            <div className="rt-actions">
              {item.key && <span className="rt-key-inline">{item.key}</span>}
              {items.length > 1 && (
                <button
                  type="button"
                  className="btn btn-ghost small"
                  onClick={() => setItems((prev) => prev.filter((_, i) => i !== index))}
                >
                  Remove
                </button>
              )}
            </div>
          </div>
        ))}

        <button type="button" className="btn" onClick={() => setItems((prev) => [...prev, emptyItem()])}>
          + Add item
        </button>
      </div>

      <p className="muted small">
        Answers, result mapping and follow-up questions are not editable here yet.
      </p>
    </form>
  )
}

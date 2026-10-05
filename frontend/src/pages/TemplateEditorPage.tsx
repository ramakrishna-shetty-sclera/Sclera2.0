import { useEffect, useState, type FormEvent } from 'react'
import { useNavigate, useParams } from 'react-router-dom'
import { createProcedure, getDraft, getProcedure, saveDraft, updateProcedure } from '../api/templates'
import { ApiError } from '../api/client'
import type { ItemType, ResultType } from '../api/types'
import { listResultTypes } from '../api/resultTypes'
import { ItemEditor } from '../components/ItemEditor'
import { Refusal } from '../components/Refusal'
import {
  addFollowIn,
  addOptionIn,
  findIn,
  fromDocument,
  moveIn,
  newItem,
  optionRemovalBlockedBy,
  patchIn,
  patchOptionIn,
  removeIn,
  removeOptionIn,
  replaceIn,
  retype,
  retypeBlockedBy,
  toDocument,
  triggerOptions,
  type ItemDraft,
  type OptionDraft,
} from '../components/itemTree'

/**
 * Authors the one editable thing a procedure has: its draft.
 *
 * Two modes. Creating makes the procedure and its draft v1 in a single call.
 * Editing loads the open draft and replaces it wholesale, under an optimistic
 * lock — name and description are identity rather than version content, so they
 * go through a separate call and only when they actually changed.
 *
 * The document is one flat list: a section is an item that happens to be a
 * heading, and the questions after it are its siblings. The one thing that
 * nests is a follow-up, which hangs off the answer that shows it.
 */
export function TemplateEditorPage() {
  const { id } = useParams<{ id: string }>()
  const navigate = useNavigate()
  const editing = Boolean(id)

  const [name, setName] = useState('')
  const [description, setDescription] = useState('')
  const [items, setItems] = useState<ItemDraft[]>([newItem()])
  const [changeNote, setChangeNote] = useState('')
  const [schema, setSchema] = useState(2)
  const [rowVersion, setRowVersion] = useState<number | null>(null)
  /** Set separately from `error`: a conflict needs a reload, not a retry. */
  const [conflict, setConflict] = useState(false)
  const [error, setError] = useState<string | null>(null)
  /** Why an edit was refused before it reached the server. Cleared on the next one. */
  const [refused, setRefused] = useState<string | null>(null)
  /** What the server refused, kept whole: a refusal can name several things at once. */
  const [refusal, setRefusal] = useState<unknown>(null)
  const [busy, setBusy] = useState(false)
  const [loaded, setLoaded] = useState(!editing)
  /** What identity looked like when loaded, so an unchanged name costs no write. */
  const [original, setOriginal] = useState({ name: '', description: '' })
  /**
   * The organization's vocabulary for what an answer means. Only the active
   * ones are offered; a mapping to a deactivated one is kept and marked, since
   * that is a publish blocker the author needs to see rather than lose.
   */
  const [resultTypes, setResultTypes] = useState<ResultType[]>([])

  useEffect(() => {
    listResultTypes(true)
      .then(setResultTypes)
      .catch(() => setResultTypes([]))
  }, [])

  useEffect(() => {
    if (!id) return
    Promise.all([getProcedure(id), getDraft(id)])
      .then(([procedure, draft]) => {
        setName(procedure.name)
        setDescription(procedure.description ?? '')
        setOriginal({ name: procedure.name, description: procedure.description ?? '' })
        setSchema(draft.definition.schema)
        setRowVersion(draft.rowVersion)
        const loadedItems = fromDocument(draft.definition)
        setItems(loadedItems.length > 0 ? loadedItems : [newItem()])
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

  function onPatch(uid: string, patch: Partial<ItemDraft>) {
    setItems((prev) => patchIn(prev, uid, patch))
  }

  /**
   * A type change can invalidate a follow-up, so it is refused rather than
   * applied destructively — losing a question to a dropdown nobody meant to
   * touch is worse than being told no.
   */
  function onRetype(uid: string, type: ItemType) {
    const item = findIn(items, uid)
    if (!item) return
    const blocked = retypeBlockedBy(item, type)
    setRefused(blocked)
    if (blocked) return
    setItems((prev) => replaceIn(prev, uid, (current) => retype(current, type)))
  }

  function onRemove(uid: string) {
    setRefused(null)
    setItems((prev) => {
      const next = removeIn(prev, uid)
      return next.length === 0 ? [newItem()] : next
    })
  }

  function onMove(uid: string, delta: number) {
    setItems((prev) => moveIn(prev, uid, delta))
  }

  /** A follow-up is born pointing at the parent's first answer, which is the usual intent. */
  function onAddFollow(uid: string) {
    setRefused(null)
    const parent = findIn(items, uid)
    const first = parent ? triggerOptions(parent)[0] : undefined
    const when = first?.key
    if (!when) return
    setItems((prev) => addFollowIn(prev, uid, { ...newItem(), when }))
  }

  function onAddOption(uid: string) {
    setRefused(null)
    setItems((prev) => addOptionIn(prev, uid))
  }

  function onPatchOption(uid: string, option: string, patch: Partial<OptionDraft>) {
    setItems((prev) => patchOptionIn(prev, uid, option, patch))
  }

  /** Refused rather than applied, for the same reason a type change is. */
  function onRemoveOption(uid: string, option: string) {
    const item = findIn(items, uid)
    if (!item) return
    const blocked = optionRemovalBlockedBy(item, option)
    setRefused(blocked)
    if (blocked) return
    setItems((prev) => removeOptionIn(prev, uid, option))
  }

  async function onSubmit(e: FormEvent) {
    e.preventDefault()
    setBusy(true)
    setError(null)
    setRefusal(null)
    setConflict(false)
    try {
      const definition = toDocument(schema, items)

      if (!editing) {
        const created = await createProcedure({
          name: name.trim(),
          description: description.trim() || undefined,
          definition,
        })
        navigate(`/templates/${created.id}`)
        return
      }

      if (name.trim() !== original.name || description.trim() !== original.description) {
        await updateProcedure(id!, { name: name.trim(), description: description.trim() || undefined })
      }
      await saveDraft(id!, {
        definition,
        rowVersion: rowVersion!,
        changeNote: changeNote.trim() || undefined,
      })
      navigate(`/templates/${id}`)
    } catch (e) {
      if (e instanceof ApiError && e.status === 409) {
        setConflict(true)
        setError('Someone else saved this draft while you were editing it.')
      } else {
        // The server names every structural problem at once; Refusal unpacks
        // them, because an author fixing four things wants four lines.
        setRefusal(e instanceof Error ? e : new Error('Could not save'))
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

      {refused && <div className="alert alert-amber">{refused}</div>}
      <Refusal error={refusal} />

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
          <ItemEditor
            key={item.uid}
            item={item}
            parent={null}
            index={index}
            siblings={items.length}
            resultTypes={resultTypes}
            onPatch={onPatch}
            onRetype={onRetype}
            onRemove={onRemove}
            onMove={onMove}
            onAddFollow={onAddFollow}
            onAddOption={onAddOption}
            onPatchOption={onPatchOption}
            onRemoveOption={onRemoveOption}
          />
        ))}

        <div className="rt-actions">
          <button
            type="button"
            className="btn"
            onClick={() => setItems((prev) => [...prev, newItem('SECTION')])}
          >
            + Add section
          </button>
          <button type="button" className="btn" onClick={() => setItems((prev) => [...prev, newItem()])}>
            + Add question
          </button>
        </div>
      </div>

      {resultTypes.length === 0 && (
        <p className="field-hint">
          No active result types, so no answer can be given a meaning. Add them under Settings →
          Result types.
        </p>
      )}
    </form>
  )
}

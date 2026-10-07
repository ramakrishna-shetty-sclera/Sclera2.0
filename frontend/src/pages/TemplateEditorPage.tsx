import { useEffect, useMemo, useRef, useState, type FormEvent } from 'react'
import { useLocation, useNavigate, useParams } from 'react-router-dom'
import { createProcedure, getDraft, getProcedure, saveDraft, updateProcedure } from '../api/templates'
import { ApiError } from '../api/client'
import { numberItems } from '../api/document'
import type { ItemType, ResultType, Threshold } from '../api/types'
import { listResultTypes } from '../api/resultTypes'
import { useAuth } from '../auth/AuthContext'
import { ItemEditor } from '../components/ItemEditor'
import { Refusal } from '../components/Refusal'
import { EvaluationPreview } from '../components/EvaluationPreview'
import { ThresholdsEditor } from '../components/ThresholdsEditor'
import {
  addFollowIn,
  addOptionIn,
  applyKeys,
  atPath,
  autosaveBlockedBy,
  autosaveSkipReason,
  findIn,
  fromDocument,
  keysFromSaved,
  moveIn,
  newItem,
  optionRemovalBlockedBy,
  patchIn,
  patchOptionIn,
  pathTo,
  removeIn,
  removeOptionIn,
  replaceIn,
  retype,
  retypeBlockedBy,
  thresholdsBlockedBy,
  toDocument,
  triggerOptions,
  type ItemDraft,
  type OptionDraft,
} from '../components/itemTree'

/** How often a changed draft is saved without being asked. */
const AUTOSAVE_MS = 30_000

/** The document as it would be sent — what "has this changed since the last save" compares. */
const snapshotOf = (schema: number, items: ItemDraft[], thresholds: Threshold[]): string =>
  JSON.stringify(toDocument(schema, items, thresholds))

/**
 * Carried through the navigation that creating a procedure forces.
 *
 * Clicking "+ Follow-up" on an unsaved procedure has to create it first, which
 * changes the route — so the position of the question being followed up rides
 * along and the follow-up is added once the saved draft has loaded.
 */
interface PendingFollow {
  addFollowAt?: number[]
}

/**
 * Hangs a new follow-up off the question at `path`, pointed at its first
 * answer — the usual intent, and changeable from the dropdown.
 *
 * Returns the tree unchanged if that question has gone or still has no keyed
 * answer. Losing the click is better than guessing at a different question:
 * the author's own edits are all still there, and clicking again costs nothing.
 */
function withFollowAt(items: ItemDraft[], path: number[]): ItemDraft[] {
  const target = atPath(items, path)
  const when = target ? triggerOptions(target)[0]?.key : undefined
  if (!target || !when) return items
  return addFollowIn(items, target.uid, { ...newItem(), when })
}

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
  const location = useLocation()
  const editing = Boolean(id)
  const { permissions, permissionsLoaded } = useAuth()

  const [name, setName] = useState('')
  const [description, setDescription] = useState('')
  const [items, setItems] = useState<ItemDraft[]>([newItem()])
  /**
   * Score bands that turn a final percentage into a result type. Document-level,
   * not per item. No screen edits them yet — they ride through untouched, the
   * same way an INTEGER question's rules do — so this state exists only to
   * make sure they survive a load-and-save round trip rather than being
   * silently dropped.
   */
  const [thresholds, setThresholds] = useState<Threshold[]>([])
  const [changeNote, setChangeNote] = useState('')
  const [schema, setSchema] = useState(2)
  /**
   * The open draft's own version number — distinct from `rowVersion`, the
   * optimistic lock. The evaluation endpoint is addressed by this, not by the
   * in-memory document, so the preview can only exist once it is known.
   */
  const [draftVersionNo, setDraftVersionNo] = useState<number | null>(null)
  /**
   * The draft's optimistic lock. A ref, not state: autosave runs from a timer
   * and must always send the value the previous save returned, and a closure
   * over state would hand it a stale one — which then 409s against the author's
   * own autosave. Null means no draft has loaded, so nothing may be saved.
   */
  const rowVersionRef = useRef<number | null>(null)
  /**
   * Every save goes through here, one at a time. An autosave and a manual save
   * that overlapped would both send the same rowVersion, and the second would
   * conflict with the first.
   */
  const saveQueue = useRef<Promise<unknown>>(Promise.resolve())
  /** What the server holds, as a snapshot — the thing "unsaved changes" is measured against. */
  const [savedSnapshot, setSavedSnapshot] = useState(() => snapshotOf(2, [newItem()], []))
  /** The snapshot an autosave was refused on, so a deterministic refusal is not retried every tick. */
  const failedSnapshot = useRef<string | null>(null)
  const [autosave, setAutosave] = useState<{ phase: 'idle' | 'saving' | 'failed'; at: Date | null }>({
    phase: 'idle',
    at: null,
  })
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
  /** The uid whose "+ Follow-up" click is currently saving, so its button can say so. */
  const [savingFor, setSavingFor] = useState<string | null>(null)

  useEffect(() => {
    listResultTypes(true)
      .then(setResultTypes)
      .catch(() => setResultTypes([]))
  }, [])

  useEffect(() => {
    if (!id) return
    // Until this draft has loaded there is nothing to save to. Clearing it
    // matters when creating a procedure hands over to its edit route without
    // remounting, so the previous screen's state is briefly still on show.
    rowVersionRef.current = null
    setDraftVersionNo(null)
    // StrictMode runs this effect twice in development, and both loads resolve.
    // Without this the later response overwrote whatever had been typed since the
    // first one landed — items, rowVersion and the saved-snapshot baseline all —
    // so a draft could appear to lose its edits, or to have none. Only the
    // effect that is still current may apply its result.
    let cancelled = false
    const pending = (location.state as PendingFollow | null)?.addFollowAt
    Promise.all([getProcedure(id), getDraft(id)])
      .then(([procedure, draft]) => {
        if (cancelled) return
        setName(procedure.name)
        setDescription(procedure.description ?? '')
        setOriginal({ name: procedure.name, description: procedure.description ?? '' })
        setSchema(draft.definition.schema)
        setDraftVersionNo(draft.versionNo)
        const loadedThresholds = draft.definition.thresholds ?? []
        setThresholds(loadedThresholds)
        rowVersionRef.current = draft.rowVersion
        let loadedItems = fromDocument(draft.definition)
        if (loadedItems.length === 0) loadedItems = [newItem()]
        // What the server holds, taken BEFORE any pending follow-up is added:
        // that follow-up is not saved yet, so it should read as a change.
        setSavedSnapshot(snapshotOf(draft.definition.schema, loadedItems, loadedThresholds))
        failedSnapshot.current = null
        // Finishes a "+ Follow-up" that had to create the procedure first. The
        // answers now carry the keys the server minted, so the follow-up has
        // something to point at.
        if (pending) {
          loadedItems = withFollowAt(loadedItems, pending)
          // Clear it, or a reload adds a second one.
          navigate(location.pathname, { replace: true, state: null })
        }
        setItems(loadedItems)
        setLoaded(true)
      })
      .catch((e) => {
        if (cancelled) return
        setError(
          e instanceof ApiError && e.status === 404
            ? 'This procedure has no open draft. Start one from its detail screen.'
            : e instanceof Error
              ? e.message
              : 'Could not load the draft',
        )
      })
    return () => {
      cancelled = true
    }
  }, [id])

  // --- autosave -------------------------------------------------------------
  //
  // Authoring makes no requests, so a session that expires mid-edit is only
  // discovered by the Save click that matters, and the whole draft lived in
  // React state. Saving while dirty protects the work, and as a side effect
  // every save is an authenticated request, which slides the session's idle
  // timeout — a person who is working is not idle.
  //
  // Edit mode only. Creating has no draft to save to, and making a procedure
  // the author has not asked for out of half a typed name is worse than the
  // problem it solves.

  /** Runs a save after every save already queued, so no two share a rowVersion. */
  function enqueue<T>(job: () => Promise<T>): Promise<T> {
    const run = saveQueue.current.then(job)
    saveQueue.current = run.catch(() => undefined)
    return run
  }

  /**
   * The timer fires with whatever the closure that started it saw, so it reads
   * the current values from here instead.
   */
  const latest = useRef({
    id, editing, items, schema, thresholds, changeNote, conflict, busy, savingFor, savedSnapshot,
  })
  latest.current = {
    id, editing, items, schema, thresholds, changeNote, conflict, busy, savingFor, savedSnapshot,
  }

  async function autosaveNow() {
    const s = latest.current
    const snapshot = snapshotOf(s.schema, s.items, s.thresholds)
    const skip = autosaveSkipReason({
      ready: s.editing && rowVersionRef.current !== null,
      conflict: s.conflict,
      busy: s.busy || s.savingFor !== null,
      snapshot,
      savedSnapshot: s.savedSnapshot,
      failedSnapshot: failedSnapshot.current,
      items: s.items,
      thresholds: s.thresholds,
    })
    if (skip) return

    // Exactly what is sent, kept so the keys the server mints can be matched
    // back to it even if the author keeps typing while the request is out.
    const sent = s.items
    setAutosave((a) => ({ ...a, phase: 'saving' }))
    try {
      const saved = await enqueue(() =>
        saveDraft(s.id!, {
          definition: toDocument(s.schema, sent, s.thresholds),
          rowVersion: rowVersionRef.current!,
          changeNote: s.changeNote.trim() || undefined,
        }),
      )
      rowVersionRef.current = saved.rowVersion

      // The editor does not otherwise learn the keys a save minted, and the
      // server mints a fresh one for anything unkeyed — so without this every
      // autosave would re-key every new question and answer.
      const minted = keysFromSaved(sent, saved.definition.items)
      setItems((prev) => applyKeys(prev, minted))
      setThresholds(saved.definition.thresholds ?? [])
      setSavedSnapshot(snapshotOf(s.schema, applyKeys(sent, minted), saved.definition.thresholds ?? []))
      failedSnapshot.current = null
      setAutosave({ phase: 'idle', at: new Date() })
    } catch (e) {
      if (e instanceof ApiError && e.status === 409) {
        setConflict(true)
        setError('Someone else saved this draft while you were editing it.')
        setAutosave((a) => ({ ...a, phase: 'idle' }))
      } else if (e instanceof ApiError && e.status === 401) {
        // The session has ended and the app is already heading to the sign-in
        // screen. Say nothing more here.
        setAutosave((a) => ({ ...a, phase: 'idle' }))
      } else {
        // Not shown as a refusal: this was never asked for, and the author may
        // be mid-thought. Remembered, so the same document is not retried every
        // tick, and the manual Save will say why in full.
        failedSnapshot.current = snapshot
        setAutosave((a) => ({ ...a, phase: 'failed' }))
      }
    }
  }

  useEffect(() => {
    // Without canManageTemplates the form never renders, so there is nothing
    // an autosave could be protecting — only a periodic PUT the server would
    // refuse anyway. Checked here rather than left to the server, so an
    // account that cannot edit never tries to.
    if (!editing || !permissions.canManageTemplates) return
    const timer = window.setInterval(() => {
      void autosaveNow()
    }, AUTOSAVE_MS)
    return () => window.clearInterval(timer)
  }, [editing, id, permissions.canManageTemplates])

  const docDirty = useMemo(
    () => snapshotOf(schema, items, thresholds) !== savedSnapshot,
    [schema, items, thresholds, savedSnapshot],
  )
  const identityDirty = editing
    ? name.trim() !== original.name || description.trim() !== original.description
    : name.trim() !== '' || description.trim() !== ''
  const dirty = docDirty || identityDirty

  // Closing the tab or refreshing is the one way left to lose a draft that is
  // not yet saved — notably the name and description, which autosave does not
  // touch, and everything while creating. Only ever asks when there is
  // something to lose.
  useEffect(() => {
    if (!dirty) return
    const warn = (e: BeforeUnloadEvent) => {
      e.preventDefault()
      e.returnValue = ''
    }
    window.addEventListener('beforeunload', warn)
    return () => window.removeEventListener('beforeunload', warn)
  }, [dirty])

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

  /**
   * Adds a follow-up, saving the draft first when it has to.
   *
   * A follow-up names the answer that shows it by key, and KeyMinter assigns
   * option keys on save and refuses any key the template never issued — so an
   * answer typed a moment ago has nothing a follow-up can point at. Rather than
   * leave the button dead and make the author work that out, this does the save
   * and carries on.
   *
   * The round trip loses the uids, so the question is found again by position:
   * `toDocument` preserves order and so does the server.
   */
  async function onAddFollow(uid: string) {
    setRefused(null)
    const parent = findIn(items, uid)
    if (!parent) return

    const when = triggerOptions(parent)[0]?.key
    if (when) {
      setItems((prev) => addFollowIn(prev, uid, { ...newItem(), when }))
      return
    }

    // Nothing written yet, so the save below would drop every blank row, mint
    // no key, and leave the follow-up with nothing to point at — a save that
    // changes nothing and a click that appears to do nothing. Say so instead.
    // Matters more now that Radio, Checkbox and Dropdown start with blank rows.
    if (!parent.options.some((o) => o.label.trim() !== '')) {
      setRefused('Write at least one answer first — a follow-up is shown when one of them is picked.')
      return
    }

    const path = pathTo(items, uid)
    if (!path) return

    setSavingFor(uid)
    setRefusal(null)
    try {
      if (!editing) {
        // Nothing exists server-side yet, so this click is the first save. The
        // route changes with it, and the position rides along.
        const created = await createProcedure({
          name: name.trim(),
          description: description.trim() || undefined,
          definition: toDocument(schema, items, thresholds),
        })
        navigate(`/templates/${created.id}/edit`, { state: { addFollowAt: path } })
        return
      }

      const definition = toDocument(schema, items, thresholds)
      const saved = await enqueue(() =>
        saveDraft(id!, {
          definition,
          rowVersion: rowVersionRef.current!,
          changeNote: changeNote.trim() || undefined,
        }),
      )
      rowVersionRef.current = saved.rowVersion
      // The follow-up added below is not saved yet, so the snapshot is taken
      // from what came back and not from what is about to be shown.
      const reloaded = fromDocument(saved.definition)
      const reloadedThresholds = saved.definition.thresholds ?? []
      setThresholds(reloadedThresholds)
      setSavedSnapshot(snapshotOf(schema, reloaded, reloadedThresholds))
      failedSnapshot.current = null
      setItems(withFollowAt(reloaded, path))
    } catch (e) {
      if (e instanceof ApiError && e.status === 409) {
        setConflict(true)
        setError('Someone else saved this draft while you were editing it.')
      } else {
        setRefusal(e instanceof Error ? e : new Error('Could not save'))
      }
    } finally {
      setSavingFor(null)
    }
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
      const definition = toDocument(schema, items, thresholds)

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
      // Through the queue, so an autosave already in flight finishes first and
      // this sends the rowVersion it returned rather than conflicting with it.
      await enqueue(() =>
        saveDraft(id!, {
          definition,
          rowVersion: rowVersionRef.current!,
          changeNote: changeNote.trim() || undefined,
        }),
      )
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

  // One answer for the whole tree, so a section never pushes the count along
  // and a follow-up reads as 2.1 rather than as another question 1.
  const numbers = numberItems(items)

  const waitingOn = docDirty ? (autosaveBlockedBy(items) ?? thresholdsBlockedBy(thresholds, items)) : null
  // Sections are items, not a separate list — the thresholds editor only
  // offers "Each section" once one exists, since a SECTION-scoped band is
  // refused on every write while the document has none.
  const hasSections = items.some((item) => item.type === 'SECTION')
  let saveStatus: string
  if (!editing) {
    saveStatus = 'Not saved yet — nothing is kept until you save.'
  } else if (conflict) {
    // Autosave stops for good on a conflict, so "saving shortly" would be false.
    saveStatus = 'Not saved — someone else saved this draft first. Reload to see their version.'
  } else if (autosave.phase === 'saving') {
    saveStatus = 'Saving…'
  } else if (autosave.phase === 'failed' && docDirty) {
    saveStatus = 'Could not save automatically — use Save draft to see why.'
  } else if (waitingOn) {
    // Said out loud, because "Unsaved changes" with no end in sight reads as
    // broken when it is only waiting for a half-written question to finish.
    saveStatus = `Not saved yet — ${waitingOn}.`
  } else if (docDirty) {
    saveStatus = 'Unsaved changes — saving shortly.'
  } else if (autosave.at) {
    saveStatus = `Saved ${autosave.at.toLocaleTimeString([], { hour: '2-digit', minute: '2-digit' })}.`
  } else {
    saveStatus = 'No unsaved changes.'
  }

  // Checked after every hook above has run, so the two early returns below
  // never change which hooks this component calls. Nothing gated renders
  // until permissions have loaded — a form that flashes and then disappears
  // is worse than one that arrives a moment late.
  if (!permissionsLoaded) {
    return <p className="muted center-note">Checking permissions…</p>
  }
  if (!permissions.canManageTemplates) {
    return (
      <div className="alert alert-error">
        You do not have permission to author procedures. This closes the route whether you clicked
        here or typed it directly — ask an organization admin for the template author role.
      </div>
    )
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
              ? 'The draft saves itself as you work. Publishing it from the detail screen is what freezes it.'
              : 'This creates the procedure and opens draft v1. Nothing is published until you say so.'}
          </p>
          <p className="muted small" role="status" aria-live="polite">
            {saveStatus}
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
            numbers={numbers}
            resultTypes={resultTypes}
            savingFor={savingFor}
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

      <ThresholdsEditor
        thresholds={thresholds}
        resultTypes={resultTypes}
        hasSections={hasSections}
        onChange={setThresholds}
      />

      {/* Only once a draft is open and has a version number to address —
          there is nothing to evaluate against while creating. */}
      {editing && id && draftVersionNo != null && (
        <EvaluationPreview
          templateId={id}
          versionNo={draftVersionNo}
          items={items}
          resultTypes={resultTypes}
          savedAt={autosave.at}
        />
      )}

      {resultTypes.length === 0 && (
        <p className="field-hint">
          No active result types, so no answer can be given a meaning. Add them under Settings →
          Result types.
        </p>
      )}
    </form>
  )
}

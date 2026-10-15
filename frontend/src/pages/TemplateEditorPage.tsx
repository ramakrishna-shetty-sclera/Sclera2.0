import { useEffect, useMemo, useRef, useState } from 'react'
import { useLocation, useNavigate, useParams } from 'react-router-dom'
import {
  createProcedure,
  getDraft,
  getProcedure,
  publishProcedure,
  saveDraft,
  updateProcedure,
} from '../api/templates'
import { importQuestions, type ImportResult } from '../api/ai'
import { ApiError } from '../api/client'
import { isChoice, numberItems } from '../api/document'
import type { DocumentRef, ItemType, ResultType, TemplateMeta, Threshold } from '../api/types'
import { listResultTypes } from '../api/resultTypes'
import { useAuth } from '../auth/AuthContext'
import { Refusal } from '../components/Refusal'
import { BuilderNavigator, flattenDrafts } from '../components/v3/BuilderNavigator'
import { BuilderQuestionCard, type BuilderCtx } from '../components/v3/BuilderQuestionCard'
import { BuilderSidePanel, type SideTab } from '../components/v3/BuilderSidePanel'
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
  toDocument,
  triggerOptions,
  type ItemDraft,
  type OptionDraft,
} from '../components/itemTree'

/** How often a changed draft is saved without being asked. */
const AUTOSAVE_MS = 30_000

/**
 * `thresholds` and `documents` have no editor here yet — nothing on this
 * screen edits them — but they still have to ride through every load/save
 * round trip untouched, or saving a procedure that already has scoring
 * thresholds or cited documents would silently wipe them.
 */
const snapshotOf = (
  schema: number,
  items: ItemDraft[],
  thresholds: Threshold[] = [],
  documents: DocumentRef[] = [],
): string => JSON.stringify(toDocument(schema, items, thresholds, documents))

/**
 * Carried through the navigation that creating a procedure forces: the
 * position of the question being followed up, and which of its answers the
 * follow-up should point at, ride along so the click finishes after the
 * saved draft has loaded.
 */
interface PendingFollow {
  addFollowAt?: number[]
  optionIndex?: number
  /** Documents picked before the procedure existed ("create from documents", docs-tab save-first). */
  docIds?: string[]
  /** Which side tab to land on after the navigation a create forces. */
  sideTab?: SideTab
}

function withFollowAt(items: ItemDraft[], path: number[], optionIndex?: number): ItemDraft[] {
  const target = atPath(items, path)
  if (!target) return items
  const keyed = triggerOptions(target)
  const when =
    optionIndex != null && target.options[optionIndex]?.key
      ? target.options[optionIndex].key
      : keyed[0]?.key
  if (!when) return items
  return addFollowIn(items, target.uid, { ...newItem(), when })
}

/**
 * The problems an author can see without asking the server — shown in the
 * navigator and on the cards, and summed into the Ready chip. Advisory only:
 * the server's validator and publish blockers remain the authority, and its
 * refusals are still rendered whole.
 */
/** True when the checklist is only the blank starter question — nothing authored yet. */
function isEffectivelyEmpty(items: ItemDraft[]): boolean {
  return (
    items.length === 1 &&
    items[0].text.trim() === '' &&
    items[0].follow.length === 0 &&
    items[0].type !== 'SECTION'
  )
}

function clientIssues(items: ItemDraft[]): Map<string, string[]> {
  const issues = new Map<string, string[]>()
  const walk = (list: ItemDraft[], parent: ItemDraft | null) => {
    for (const item of list) {
      if (item.type !== 'SECTION') {
        const mine: string[] = []
        if (!item.text.trim()) mine.push('No question text')
        if (isChoice(item.type)) {
          const labeled = item.options.filter((o) => o.label.trim() !== '')
          if (labeled.length < 2) mine.push('Needs at least two answers')
          else if (labeled.every((o) => o.result === '')) mine.push('No answer mapped to a result')
        }
        if (parent && !item.when) mine.push('No trigger answer chosen')
        if (item.type === 'INTEGER' && item.min !== '' && item.max !== '' && Number(item.min) > Number(item.max)) {
          mine.push('Min is above Max')
        }
        if (mine.length) issues.set(item.uid, mine)
      }
      walk(item.follow, item)
    }
  }
  walk(items, null)
  return issues
}

/**
 * The procedure builder, in the Sclera 2.0 design (docs/procedures-v3.html):
 * a full-screen shell with the question navigator on the left, the checklist
 * canvas in the middle — cards collapsed to summaries, the active one
 * expanded — and Details plus the AI sources on the right.
 *
 * All persistence behaviour is unchanged from the previous editor: autosave
 * every 30s through one save queue, optimistic lock via rowVersion, keys
 * minted by the server and matched back after every save, and AI imports
 * riding the same queue so they cannot race an autosave.
 */
export function TemplateEditorPage() {
  const { id } = useParams<{ id: string }>()
  const navigate = useNavigate()
  const location = useLocation()
  const { permissions, permissionsLoaded } = useAuth()
  const editing = Boolean(id)

  const [name, setName] = useState('')
  const [description, setDescription] = useState('')
  const [meta, setMeta] = useState<TemplateMeta>({})
  const [items, setItems] = useState<ItemDraft[]>([newItem('YES_NO')])
  const [changeNote, setChangeNote] = useState('')
  const [schema, setSchema] = useState(2)
  // No screen edits these yet; they ride through untouched so a save never
  // strips a procedure's existing scoring thresholds or cited documents.
  const [thresholds, setThresholds] = useState<Threshold[]>([])
  const [documents, setDocuments] = useState<DocumentRef[]>([])
  const rowVersionRef = useRef<number | null>(null)
  const saveQueue = useRef<Promise<unknown>>(Promise.resolve())
  const [savedSnapshot, setSavedSnapshot] = useState(() => snapshotOf(2, [newItem('YES_NO')]))
  const failedSnapshot = useRef<string | null>(null)
  const [autosave, setAutosave] = useState<{ phase: 'idle' | 'saving' | 'failed'; at: Date | null }>({
    phase: 'idle',
    at: null,
  })
  const [conflict, setConflict] = useState(false)
  const [error, setError] = useState<string | null>(null)
  const [refused, setRefused] = useState<string | null>(null)
  const [refusal, setRefusal] = useState<unknown>(null)
  const [busy, setBusy] = useState(false)
  const [loaded, setLoaded] = useState(!editing)
  const [original, setOriginal] = useState({ name: '', description: '', metaJson: '{}' })
  const [resultTypes, setResultTypes] = useState<ResultType[]>([])
  const [savingFor, setSavingFor] = useState<string | null>(null)
  /** The uid of the expanded card. Everything else shows as a summary. */
  const [active, setActive] = useState<string | null>(null)
  const [showErrors, setShowErrors] = useState(false)
  const [readyOpen, setReadyOpen] = useState(false)
  const [quick, setQuick] = useState('')
  const [quickType, setQuickType] = useState<ItemType>('YES_NO')
  /** Documents chosen before the procedure existed ride along until the docs tab can use them. */
  const initialDocIds = useRef<string[]>((location.state as PendingFollow | null)?.docIds ?? [])
  const [sideTab, setSideTab] = useState<SideTab>(
    (location.state as PendingFollow | null)?.sideTab ?? (initialDocIds.current.length ? 'docs' : 'details'),
  )

  useEffect(() => {
    listResultTypes(true)
      .then(setResultTypes)
      .catch(() => setResultTypes([]))
  }, [])

  useEffect(() => {
    if (!id) return
    rowVersionRef.current = null
    let cancelled = false
    const pending = location.state as PendingFollow | null
    Promise.all([getProcedure(id), getDraft(id)])
      .then(([procedure, draft]) => {
        if (cancelled) return
        setName(procedure.name)
        setDescription(procedure.description ?? '')
        setMeta(procedure.meta ?? {})
        setOriginal({
          name: procedure.name,
          description: procedure.description ?? '',
          metaJson: JSON.stringify(procedure.meta ?? {}),
        })
        setSchema(draft.definition.schema)
        rowVersionRef.current = draft.rowVersion
        const loadedThresholds = draft.definition.thresholds ?? []
        const loadedDocuments = draft.definition.documents ?? []
        setThresholds(loadedThresholds)
        setDocuments(loadedDocuments)
        let loadedItems = fromDocument(draft.definition)
        if (loadedItems.length === 0) loadedItems = [newItem('YES_NO')]
        setSavedSnapshot(snapshotOf(draft.definition.schema, loadedItems, loadedThresholds, loadedDocuments))
        failedSnapshot.current = null
        if (pending?.addFollowAt) {
          loadedItems = withFollowAt(loadedItems, pending.addFollowAt, pending.optionIndex)
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

  // --- the save queue (unchanged mechanics) ---------------------------------

  function enqueue<T>(job: () => Promise<T>): Promise<T> {
    const run = saveQueue.current.then(job)
    saveQueue.current = run.catch(() => undefined)
    return run
  }

  const latest = useRef({
    id, editing, items, schema, thresholds, documents, changeNote, conflict, busy, savingFor, savedSnapshot,
  })
  latest.current = {
    id, editing, items, schema, thresholds, documents, changeNote, conflict, busy, savingFor, savedSnapshot,
  }

  async function autosaveNow() {
    const s = latest.current
    const snapshot = snapshotOf(s.schema, s.items, s.thresholds, s.documents)
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

    const sent = s.items
    setAutosave((a) => ({ ...a, phase: 'saving' }))
    try {
      const saved = await enqueue(() =>
        saveDraft(s.id!, {
          definition: toDocument(s.schema, sent, s.thresholds, s.documents),
          rowVersion: rowVersionRef.current!,
          changeNote: s.changeNote.trim() || undefined,
        }),
      )
      rowVersionRef.current = saved.rowVersion
      const minted = keysFromSaved(sent, saved.definition.items)
      setItems((prev) => applyKeys(prev, minted))
      setSavedSnapshot(snapshotOf(s.schema, applyKeys(sent, minted), s.thresholds, s.documents))
      failedSnapshot.current = null
      setAutosave({ phase: 'idle', at: new Date() })
    } catch (e) {
      if (e instanceof ApiError && e.status === 409) {
        setConflict(true)
        setError('Someone else saved this draft while you were editing it.')
        setAutosave((a) => ({ ...a, phase: 'idle' }))
      } else if (e instanceof ApiError && e.status === 401) {
        setAutosave((a) => ({ ...a, phase: 'idle' }))
      } else {
        failedSnapshot.current = snapshot
        setAutosave((a) => ({ ...a, phase: 'failed' }))
      }
    }
  }

  useEffect(() => {
    // Without canManageTemplates the form never renders, so there is nothing
    // for autosave to do — but the interval is wired here regardless of
    // render state, so the check still has to be explicit.
    if (!editing || !permissions.canManageTemplates) return
    const timer = window.setInterval(() => {
      void autosaveNow()
    }, AUTOSAVE_MS)
    return () => window.clearInterval(timer)
  }, [editing, id, permissions.canManageTemplates])

  const docDirty = useMemo(
    () => snapshotOf(schema, items, thresholds, documents) !== savedSnapshot,
    [schema, items, thresholds, documents, savedSnapshot],
  )
  const identityDirty = editing
    ? name.trim() !== original.name || description.trim() !== original.description
    : name.trim() !== '' || description.trim() !== ''
  const dirty = docDirty || identityDirty

  useEffect(() => {
    if (!dirty) return
    const warn = (e: BeforeUnloadEvent) => {
      e.preventDefault()
      e.returnValue = ''
    }
    window.addEventListener('beforeunload', warn)
    return () => window.removeEventListener('beforeunload', warn)
  }, [dirty])

  // --- edits ----------------------------------------------------------------

  function select(uid: string | null) {
    setActive(uid)
    if (uid) {
      requestAnimationFrame(() => {
        document.getElementById(`card-${uid}`)?.scrollIntoView({ block: 'center', behavior: 'smooth' })
      })
    }
  }

  function onPatch(uid: string, patch: Partial<ItemDraft>) {
    setItems((prev) => patchIn(prev, uid, patch))
  }

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
    if (active === uid) setActive(null)
    setItems((prev) => {
      const next = removeIn(prev, uid)
      return next.length === 0 ? [newItem('YES_NO')] : next
    })
  }

  function onMove(uid: string, delta: number) {
    setItems((prev) => moveIn(prev, uid, delta))
  }

  /**
   * Adds a follow-up pointed at `whenKey` (the per-answer button), or at the
   * first keyed answer when none is named. Answers typed a moment ago have no
   * key yet, so that path saves the draft first and carries the click across.
   */
  async function onAddFollow(uid: string, whenKey?: string) {
    setRefused(null)
    const parent = findIn(items, uid)
    if (!parent) return

    const when = whenKey ?? triggerOptions(parent)[0]?.key
    if (when) {
      const follow = { ...newItem(), when }
      setItems((prev) => addFollowIn(prev, uid, follow))
      select(follow.uid)
      return
    }

    if (!parent.options.some((o) => o.label.trim() !== '')) {
      setRefused('Write at least one answer first — a follow-up is shown when one of them is picked.')
      return
    }

    const path = pathTo(items, uid)
    if (!path) return
    const optionIndex = whenKey === undefined ? undefined : parent.options.findIndex((o) => o.key === whenKey)

    setSavingFor(uid)
    setRefusal(null)
    try {
      if (!editing) {
        const created = await createProcedure({
          name: name.trim() || 'Untitled procedure',
          description: description.trim() || undefined,
          meta,
          definition: toDocument(schema, items, thresholds, documents),
        })
        navigate(`/templates/${created.id}/edit`, { state: { addFollowAt: path, optionIndex } })
        return
      }

      const definition = toDocument(schema, items, thresholds, documents)
      const saved = await enqueue(() =>
        saveDraft(id!, {
          definition,
          rowVersion: rowVersionRef.current!,
          changeNote: changeNote.trim() || undefined,
        }),
      )
      rowVersionRef.current = saved.rowVersion
      const reloaded = fromDocument(saved.definition)
      setThresholds(saved.definition.thresholds ?? [])
      setDocuments(saved.definition.documents ?? [])
      setSavedSnapshot(snapshotOf(schema, reloaded, saved.definition.thresholds, saved.definition.documents))
      failedSnapshot.current = null
      setItems(withFollowAt(reloaded, path, optionIndex))
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

  function onRemoveOption(uid: string, option: string) {
    const item = findIn(items, uid)
    if (!item) return
    const blocked = optionRemovalBlockedBy(item, option)
    setRefused(blocked)
    if (blocked) return
    setItems((prev) => removeOptionIn(prev, uid, option))
  }

  function addQuestion(type: ItemType = 'YES_NO', text = '') {
    const q = { ...newItem(type), text }
    // The untouched starter question is a placeholder, not content — the first
    // real question takes its place instead of stacking under it.
    setItems((prev) => (isEffectivelyEmpty(prev) ? [q] : [...prev, q]))
    select(q.uid)
    return q
  }

  function quickAdd(e: React.FormEvent) {
    e.preventDefault()
    const lines = quick
      .split(/\n+/)
      .map((line) => line.replace(/^\s*(\d+[.)]|[-*•])\s*/, '').trim())
      .filter(Boolean)
    if (lines.length === 0) return
    const added = lines.map((text) => ({ ...newItem(quickType), text }))
    setItems((prev) => (isEffectivelyEmpty(prev) ? added : [...prev, ...added]))
    setQuick('')
  }

  /**
   * What goes to the server. The untouched starter question is a placeholder
   * the canvas no longer even shows — sending it would fail the server's
   * blank-text validation, so an effectively-empty checklist is sent empty.
   */
  function currentDefinition() {
    return toDocument(schema, isEffectivelyEmpty(items) ? [] : items, thresholds, documents)
  }

  /**
   * The docs/suggest save-first path: generation and chat are template-scoped,
   * so a brand-new procedure is created as a draft and the builder reopens on
   * the same tab, with any picked documents riding along in navigation state.
   */
  async function saveAndContinue(docIds: string[], tab: SideTab) {
    setBusy(true)
    setRefusal(null)
    try {
      const created = await createProcedure({
        name: name.trim() || 'Untitled procedure',
        description: description.trim() || undefined,
        meta,
        definition: currentDefinition(),
      })
      navigate(`/templates/${created.id}/edit`, { state: { docIds, sideTab: tab } })
    } catch (e) {
      setRefusal(e instanceof Error ? e : new Error('Could not create the procedure'))
      setBusy(false)
    }
  }

  // --- save / publish -------------------------------------------------------

  /** Name, description or meta differ from what the server has. */
  function identityChanged(): boolean {
    return (
      name.trim() !== original.name ||
      description.trim() !== original.description ||
      JSON.stringify(meta) !== original.metaJson
    )
  }

  async function onSave(leaveAfter = false) {
    setBusy(true)
    setError(null)
    setRefusal(null)
    setConflict(false)
    try {
      const definition = currentDefinition()
      if (!editing) {
        const created = await createProcedure({
          name: name.trim(),
          description: description.trim() || undefined,
          meta,
          definition,
        })
        navigate(`/templates/${created.id}${leaveAfter ? '' : '/edit'}`)
        return
      }

      if (identityChanged()) {
        await updateProcedure(id!, { name: name.trim(), description: description.trim() || undefined, meta })
        setOriginal({ name: name.trim(), description: description.trim(), metaJson: JSON.stringify(meta) })
      }
      const saved = await enqueue(() =>
        saveDraft(id!, {
          definition,
          rowVersion: rowVersionRef.current!,
          changeNote: changeNote.trim() || undefined,
        }),
      )
      rowVersionRef.current = saved.rowVersion
      const minted = keysFromSaved(items, saved.definition.items)
      setItems((prev) => applyKeys(prev, minted))
      setThresholds(saved.definition.thresholds ?? [])
      setDocuments(saved.definition.documents ?? [])
      setSavedSnapshot(snapshotOf(schema, applyKeys(items, minted), saved.definition.thresholds, saved.definition.documents))
      failedSnapshot.current = null
      setAutosave({ phase: 'idle', at: new Date() })
      if (leaveAfter) navigate(`/templates/${id}`)
    } catch (e) {
      if (e instanceof ApiError && e.status === 409) {
        setConflict(true)
        setError('Someone else saved this draft while you were editing it.')
      } else {
        setRefusal(e instanceof Error ? e : new Error('Could not save'))
      }
    } finally {
      setBusy(false)
    }
  }

  /** Saves, then publishes. The server's blockers come back as one refusal list. */
  async function onPublish() {
    if (!editing) return
    setShowErrors(true)
    setBusy(true)
    setError(null)
    setRefusal(null)
    try {
      const definition = currentDefinition()
      if (identityChanged()) {
        await updateProcedure(id!, { name: name.trim(), description: description.trim() || undefined, meta })
      }
      const saved = await enqueue(() =>
        saveDraft(id!, {
          definition,
          rowVersion: rowVersionRef.current!,
          changeNote: changeNote.trim() || undefined,
        }),
      )
      rowVersionRef.current = saved.rowVersion
      await publishProcedure(id!, saved.rowVersion)
      navigate(`/templates/${id}`)
    } catch (e) {
      if (e instanceof ApiError && e.status === 409) {
        setConflict(true)
        setError('Someone else saved this draft while you were editing it.')
      } else {
        setRefusal(e instanceof Error ? e : new Error('Could not publish'))
        setReadyOpen(true)
      }
      setBusy(false)
    }
  }

  async function onAiImport(candidateIds: string[]): Promise<ImportResult> {
    try {
      return await enqueue(async () => {
        const s = latest.current
        if (snapshotOf(s.schema, s.items, s.thresholds, s.documents) !== s.savedSnapshot) {
          const saved = await saveDraft(s.id!, {
            definition: toDocument(s.schema, s.items, s.thresholds, s.documents),
            rowVersion: rowVersionRef.current!,
            changeNote: s.changeNote.trim() || undefined,
          })
          rowVersionRef.current = saved.rowVersion
        }
        const result = await importQuestions(s.id!, candidateIds)
        rowVersionRef.current = result.draft.rowVersion
        const reloaded = fromDocument(result.draft.definition)
        setThresholds(result.draft.definition.thresholds ?? [])
        setDocuments(result.draft.definition.documents ?? [])
        setSavedSnapshot(snapshotOf(
          result.draft.definition.schema, reloaded,
          result.draft.definition.thresholds, result.draft.definition.documents,
        ))
        failedSnapshot.current = null
        setItems(reloaded)
        return result
      })
    } catch (e) {
      if (e instanceof ApiError && e.status === 409) {
        setConflict(true)
        setError('Someone else saved this draft while you were editing it.')
      }
      throw e
    }
  }

  // --- derived --------------------------------------------------------------

  const numbers = numberItems(items)
  const flat = useMemo(() => flattenDrafts(items), [items])
  const issues = useMemo(() => clientIssues(items), [items])
  const questionCount = flat.filter((f) => f.item.type !== 'SECTION').length
  const issueCount = issues.size
  const firstIssue = flat.find((f) => issues.has(f.item.uid))

  const checks: { label: string; ok: boolean; fix?: () => void }[] = [
    { label: 'Procedure name', ok: name.trim() !== '' },
    { label: 'At least one question', ok: questionCount > 0, fix: () => addQuestion() },
    {
      label: issueCount
        ? `${issueCount} question${issueCount > 1 ? 's need' : ' needs'} attention`
        : 'All questions complete',
      ok: issueCount === 0,
      fix: firstIssue
        ? () => {
            setShowErrors(true)
            select(firstIssue.item.uid)
          }
        : undefined,
    },
  ]
  const readyN = checks.filter((c) => !c.ok).length

  const waitingOn = docDirty ? autosaveBlockedBy(items) : null
  let saveStatus: string
  if (!editing) saveStatus = 'Not saved yet'
  else if (conflict) saveStatus = 'Not saved — reload to see their version'
  else if (autosave.phase === 'saving' || busy) saveStatus = 'Saving…'
  else if (autosave.phase === 'failed' && docDirty) saveStatus = 'Could not autosave — use Save draft'
  else if (waitingOn) saveStatus = `Waiting: ${waitingOn}`
  else if (dirty) saveStatus = 'Unsaved changes — saving shortly'
  else if (autosave.at)
    saveStatus = `Saved ${autosave.at.toLocaleTimeString([], { hour: '2-digit', minute: '2-digit' })}`
  else saveStatus = 'No unsaved changes'

  const activeNumber = active ? numbers.get(findIn(items, active) ?? ({} as ItemDraft)) : undefined

  if (!loaded) {
    return (
      <div className="v3 v3-full" style={{ padding: 24 }}>
        {error ? <div className="alert-error">{error}</div> : <p className="muted">Loading…</p>}
      </div>
    )
  }

  // The form never renders without this — closes the route someone reaches
  // by typing /templates/new or /templates/:id/edit directly, not just the
  // button that would otherwise get them here.
  if (permissionsLoaded && !permissions.canManageTemplates) {
    return (
      <div className="v3 v3-full" style={{ padding: 24 }}>
        <div className="empty">
          <h2 style={{ fontSize: 16 }}>You can't author procedures</h2>
          <p className="muted">Ask an organization admin or template author for access.</p>
          <button className="btn btn-primary" onClick={() => navigate(editing ? `/templates/${id}` : '/templates')}>
            Back
          </button>
        </div>
      </div>
    )
  }

  const ctx: BuilderCtx = {
    numbers,
    resultTypes,
    active,
    issues,
    savingFor,
    showErrors,
    onSelect: select,
    onPatch,
    onRetype,
    onRemove,
    onMove,
    onAddFollow: (uid, whenKey) => void onAddFollow(uid, whenKey),
    onAddOption,
    onPatchOption,
    onRemoveOption,
  }

  function tryClose() {
    if (dirty && !confirm('Leave the builder? Unsaved changes are kept locally until the next autosave, but the safest path is Save draft first.')) {
      return
    }
    navigate(editing ? `/templates/${id}` : '/templates')
  }

  return (
    <div className="v3 v3-full">
      <div className="bshell">
        <header className="bhead">
          <button className="ibtn xl" aria-label="Close builder" onClick={tryClose}>
            ‹
          </button>
          <div style={{ display: 'flex', flexDirection: 'column', gap: 2 }}>
            <span className="small muted">{editing ? 'Editing draft' : 'New procedure'}</span>
            <input
              className="title-input"
              aria-label="Procedure name"
              value={name}
              maxLength={200}
              placeholder="Untitled procedure"
              onChange={(e) => setName(e.target.value)}
            />
          </div>
          <span className="saved" role="status" aria-live="polite">
            {saveStatus}
          </span>
          <div className="row" style={{ marginLeft: 'auto', gap: 10 }}>
            <div style={{ position: 'relative' }}>
              <button
                className={`ready${readyN ? '' : ' ok'}`}
                aria-expanded={readyOpen}
                onClick={() => setReadyOpen(!readyOpen)}
              >
                {readyN ? `⚠ ${readyN} to fix` : '✓ Ready to publish'}
              </button>
              {readyOpen && (
                <div
                  className="popover"
                  style={{ right: 0, top: 40, width: 320, padding: 14, display: 'flex', flexDirection: 'column', gap: 10 }}
                >
                  <h3 style={{ fontSize: 14 }}>{readyN ? 'Before you publish' : 'Everything is ready'}</h3>
                  {checks.map((c) => (
                    <div key={c.label} className="check">
                      {c.ok ? <span className="ok">✓</span> : <span className="no">⚠</span>}
                      <span style={{ flex: 1 }}>{c.label}</span>
                      {!c.ok && c.fix && (
                        <button
                          className="link"
                          onClick={() => {
                            setReadyOpen(false)
                            c.fix!()
                          }}
                        >
                          Fix
                        </button>
                      )}
                    </div>
                  ))}
                  <p className="hint">Publishing also runs the server's own checks; anything it refuses is listed here.</p>
                </div>
              )}
            </div>
            <button className="btn btn-quiet" disabled={busy || conflict} onClick={() => void onSave(true)}>
              Save draft
            </button>
            {editing && (
              <button className="btn btn-primary" disabled={busy || conflict} onClick={() => void onPublish()}>
                Publish
              </button>
            )}
            {!editing && (
              <button className="btn btn-primary" disabled={busy || !name.trim()} onClick={() => void onSave(false)}>
                Create procedure
              </button>
            )}
          </div>
        </header>

        <div className="bbody">
          <BuilderNavigator
            flat={flat}
            numbers={numbers}
            active={active}
            issues={issues}
            onSelect={select}
            onAdd={() => addQuestion()}
          />

          <main className="canvas" aria-label="Checklist">
            <div className="canvas-in">
              <div className="stepper">
                <span style={{ fontWeight: 600, color: 'var(--heading)', fontSize: 16 }}>Checklist</span>
                <span className="small muted" style={{ fontVariantNumeric: 'tabular-nums' }}>
                  {activeNumber
                    ? `Question ${activeNumber} of ${questionCount}`
                    : `${questionCount} question${questionCount === 1 ? '' : 's'}`}
                </span>
                <div className="row" style={{ marginLeft: 'auto', gap: 8 }}>
                  {editing && (
                    <button
                      type="button"
                      className="btn btn-outline"
                      title="Generate questions from a document or by chatting"
                      onClick={() => setSideTab('docs')}
                    >
                      ✦ Generate with AI
                    </button>
                  )}
                  <button
                    type="button"
                    className="btn btn-quiet"
                    onClick={() => setItems((prev) => [...prev, newItem('SECTION')])}
                  >
                    § Section
                  </button>
                  <button type="button" className="btn btn-primary" onClick={() => addQuestion()}>
                    + Question
                  </button>
                </div>
              </div>

              {isEffectivelyEmpty(items) && (
                <div className="empty" style={{ padding: '28px 0 8px' }}>
                  <h2 style={{ fontSize: 20 }}>Build your checklist</h2>
                  <p className="muted" style={{ maxWidth: 460 }}>
                    Start from a template or a standards document, or type questions straight in below. You can
                    mix all three.
                  </p>
                  <div className="start" role="group" aria-label="Ways to build the checklist">
                    <button type="button" onClick={() => setSideTab('templates')}>
                      <span style={{ color: 'var(--action)', fontSize: 20 }}>▤</span>
                      <span style={{ fontWeight: 600 }}>Use a template</span>
                      <span className="small muted">Ready-made checklists for common standards.</span>
                    </button>
                    <button type="button" onClick={() => setSideTab('docs')}>
                      <span style={{ color: 'var(--action)', fontSize: 20 }}>✦</span>
                      <span style={{ fontWeight: 600 }}>Use a document</span>
                      <span className="small muted">Generate questions from a standard or your SOP.</span>
                    </button>
                    <button
                      type="button"
                      onClick={() => document.querySelector<HTMLTextAreaElement>('.quick textarea')?.focus()}
                    >
                      <span style={{ color: 'var(--action)', fontSize: 20 }}>✎</span>
                      <span style={{ fontWeight: 600 }}>Type questions</span>
                      <span className="small muted">Add them one by one, or paste a whole list below.</span>
                    </button>
                  </div>
                </div>
              )}

              {conflict ? (
                <div className="alert-error">
                  {error} Reload to see their version — saving over it would lose their work.{' '}
                  <button type="button" className="btn btn-quiet btn-xs" onClick={() => window.location.reload()}>
                    Reload
                  </button>
                </div>
              ) : (
                error && <div className="alert-error">{error}</div>
              )}
              {refused && <div className="alert-amber">{refused}</div>}
              <Refusal error={refusal} />

              {!isEffectivelyEmpty(items) &&
                items.map((item, index) => (
                  <BuilderQuestionCard
                    key={item.uid}
                    item={item}
                    parent={null}
                    index={index}
                    siblings={items.length}
                    depth={0}
                    ctx={ctx}
                  />
                ))}

              <form className="quick" onSubmit={quickAdd} aria-label="Quick add">
                <div className="row between">
                  <span style={{ fontWeight: 600 }}>Quick add</span>
                  <span className="hint">One question per line; numbers and bullets are stripped</span>
                </div>
                <div className="row" style={{ alignItems: 'flex-start' }}>
                  <textarea
                    className="textarea"
                    rows={2}
                    style={{ flex: 1 }}
                    value={quick}
                    placeholder={'Type a question — or paste a list:\nIs the exit sign lit?\nIs the fire door closed?'}
                    onChange={(e) => setQuick(e.target.value)}
                    onKeyDown={(e) => {
                      if (e.key === 'Enter' && !e.shiftKey) {
                        e.preventDefault()
                        quickAdd(e)
                      }
                    }}
                  />
                  <select
                    className="select"
                    style={{ width: 170, flexShrink: 0 }}
                    aria-label="Answer type"
                    value={quickType}
                    onChange={(e) => setQuickType(e.target.value as ItemType)}
                  >
                    {(['YES_NO', 'YES_NO_NA', 'RADIO', 'CHECKBOX', 'DROPDOWN', 'TEXT', 'INTEGER'] as ItemType[]).map(
                      (t) => (
                        <option key={t} value={t}>
                          {t === 'YES_NO' ? 'Yes / No' : t === 'YES_NO_NA' ? 'Yes / No / N/A' : t.charAt(0) + t.slice(1).toLowerCase()}
                        </option>
                      ),
                    )}
                  </select>
                  <button className="btn btn-primary" type="submit" disabled={!quick.trim()}>
                    Add
                  </button>
                </div>
              </form>

              {resultTypes.length === 0 && (
                <p className="hint warn">
                  No active result types, so no answer can be given a meaning. Add them under Settings → Result types.
                </p>
              )}
            </div>
          </main>

          <BuilderSidePanel
            templateId={editing ? id! : null}
            initialDocIds={initialDocIds.current}
            tab={sideTab}
            onTab={setSideTab}
            details={{
              name,
              description,
              changeNote,
              meta,
              onName: setName,
              onDescription: setDescription,
              onChangeNote: setChangeNote,
              onMeta: (patch) => setMeta((m) => ({ ...m, ...patch })),
            }}
            onImport={onAiImport}
            onAddItems={(added) =>
              setItems((prev) => (isEffectivelyEmpty(prev) ? added : [...prev, ...added]))
            }
            onRequireTemplate={(docIds, tab) => void saveAndContinue(docIds, tab)}
            procedureName={name}
          />
        </div>
      </div>
    </div>
  )
}

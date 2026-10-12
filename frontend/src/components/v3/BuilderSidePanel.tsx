import { useEffect, useRef, useState } from 'react'
import {
  createChatSession,
  discardQuestionDraft,
  generateFromDocument,
  getAiDocument,
  getGenerationJob,
  listQuestionDrafts,
  sendChatMessage,
  uploadAiDocument,
  type AiDocument,
  type CandidateQuestion,
  type ImportResult,
  type QuestionDraft,
} from '../../api/ai'
import { ApiError } from '../../api/client'
import { typeLabel } from '../../api/document'
import { DocumentLibraryDialog, formatBytes } from './DocumentLibraryDialog'
import { resultDot } from './ReadOnlyChecklist'

const POLL_MS = 1500

export interface SidePanelDetails {
  name: string
  description: string
  changeNote: string
  onName: (v: string) => void
  onDescription: (v: string) => void
  onChangeNote: (v: string) => void
}

export type SideTab = 'details' | 'docs' | 'suggest'

/**
 * The builder's right rail (docs/procedures-v3.html SidePanel): Details, and
 * the two AI sources — Documents and Suggestions. Both sources end in the
 * review dialog, and nothing enters the draft except through `onImport`,
 * which the editor page owns so imports ride its save queue.
 *
 * All three tabs are always visible — a hidden feature reads as a missing
 * one. When a source cannot run yet (procedure not created, feature flag off
 * on the server, availability still being checked) its tab says so instead
 * of disappearing.
 */
export function BuilderSidePanel({
  templateId,
  initialDocIds = [],
  tab,
  onTab,
  details,
  onImport,
}: {
  /** Null while creating: the procedure does not exist yet, so neither can AI candidates. */
  templateId: string | null
  /** Library documents picked before the builder opened ("create from documents"). */
  initialDocIds?: string[]
  tab: SideTab
  onTab: (tab: SideTab) => void
  details: SidePanelDetails
  onImport: (candidateIds: string[]) => Promise<ImportResult>
}) {
  /** null = still probing; false = the server answered 404, so the flag is off. */
  const [aiAvailable, setAiAvailable] = useState<boolean | null>(null)
  const [candidates, setCandidates] = useState<QuestionDraft[]>([])
  const [review, setReview] = useState(false)
  const [error, setError] = useState<string | null>(null)
  const [notice, setNotice] = useState<string | null>(null)

  const alive = useRef(true)
  useEffect(() => {
    alive.current = true
    return () => {
      alive.current = false
    }
  }, [])

  useEffect(() => {
    if (!templateId) return
    listQuestionDrafts(templateId, 'DRAFT')
      .then((r) => {
        if (!alive.current) return
        setAiAvailable(true)
        setCandidates(r.data)
      })
      .catch((e) => {
        if (!alive.current) return
        setAiAvailable(!(e instanceof ApiError && e.status === 404))
      })
  }, [templateId])

  async function refreshCandidates() {
    if (!templateId) return
    const r = await listQuestionDrafts(templateId, 'DRAFT')
    if (alive.current) setCandidates(r.data)
  }

  const tabs: [SideTab, string][] = [
    ['details', 'Details'],
    ['docs', '✦ Documents'],
    ['suggest', '✦ Suggestions'],
  ]
  const aiReady = templateId != null && aiAvailable === true

  return (
    <aside className="side" aria-label="Procedure details and question sources">
      <div className="stabs" role="tablist" style={{ gridTemplateColumns: `repeat(${tabs.length},minmax(0,1fr))` }}>
        {tabs.map(([k, label]) => (
          <button key={k} type="button" role="tab" className="stab" aria-selected={tab === k} onClick={() => onTab(k)}>
            {label}
            {k !== 'details' && candidates.length > 0 && <span className="count-badge">{candidates.length}</span>}
          </button>
        ))}
      </div>

      {error && (
        <div style={{ padding: '12px 20px 0' }}>
          <div className="alert-error">{error}</div>
        </div>
      )}
      {notice && (
        <div style={{ padding: '12px 20px 0' }}>
          <div className="alert-ok">{notice}</div>
        </div>
      )}
      {candidates.length > 0 && tab !== 'details' && (
        <div style={{ padding: '12px 20px 0' }}>
          <button type="button" className="btn btn-primary" style={{ width: '100%' }} onClick={() => setReview(true)}>
            Review {candidates.length} suggested question{candidates.length === 1 ? '' : 's'}
          </button>
        </div>
      )}

      {tab === 'details' && <DetailsTab details={details} />}
      {tab !== 'details' && !aiReady && (
        <AiUnavailable templateId={templateId} aiAvailable={aiAvailable} />
      )}
      {tab === 'docs' && aiReady && (
        <DocsTab
          templateId={templateId}
          initialDocIds={initialDocIds}
          alive={alive}
          onError={setError}
          onNotice={setNotice}
          onCandidates={() => {
            void refreshCandidates().then(() => setReview(true))
          }}
        />
      )}
      {tab === 'suggest' && aiReady && (
        <SuggestTab templateId={templateId} alive={alive} onError={setError} onCandidates={refreshCandidates} />
      )}

      {review && templateId && (
        <ReviewDialog
          templateId={templateId}
          candidates={candidates}
          onClose={() => setReview(false)}
          onImport={onImport}
          onChanged={refreshCandidates}
          onDone={(message) => {
            setReview(false)
            setNotice(message)
          }}
        />
      )}
    </aside>
  )
}

/** Why a source tab cannot run yet, said plainly instead of hiding the tab. */
function AiUnavailable({
  templateId,
  aiAvailable,
}: {
  templateId: string | null
  aiAvailable: boolean | null
}) {
  return (
    <div className="side-body">
      {templateId == null ? (
        <>
          <PanelIntro
            title="Create the procedure first"
            text="AI generation works on a saved procedure. A name is enough — click Create procedure in the header, then come back to this tab."
          />
        </>
      ) : aiAvailable == null ? (
        <div className="row" style={{ gap: 10 }}>
          <span className="spin" />
          <span className="small muted">Checking whether AI generation is available…</span>
        </div>
      ) : (
        <PanelIntro
          title="AI generation is switched off"
          text="The procedure service is running without AI_QUESTION_GENERATION_ENABLED=true (and an ANTHROPIC_API_KEY). Enable it and restart the service; this panel picks it up on the next load."
        />
      )}
    </div>
  )
}

function PanelIntro({ title, text }: { title: string; text: string }) {
  return (
    <div style={{ display: 'flex', flexDirection: 'column', gap: 2 }}>
      <h3 style={{ fontSize: 15 }}>{title}</h3>
      <span className="small muted">{text}</span>
    </div>
  )
}

function DetailsTab({ details }: { details: SidePanelDetails }) {
  return (
    <div className="side-body">
      <PanelIntro title="Procedure details" text="The name is needed to publish; the rest helps people find it." />
      <div className="field">
        <label htmlFor="f-name">
          Procedure name <span className="req">*</span>
        </label>
        <input
          id="f-name"
          className="input"
          value={details.name}
          maxLength={200}
          placeholder="e.g. Fire Extinguisher – Monthly"
          onChange={(e) => details.onName(e.target.value)}
        />
      </div>
      <div className="field">
        <label htmlFor="f-desc">Description</label>
        <textarea
          id="f-desc"
          className="textarea"
          rows={3}
          value={details.description}
          maxLength={2000}
          placeholder="What this procedure checks and the standard it follows"
          onChange={(e) => details.onDescription(e.target.value)}
        />
      </div>
      <div className="field">
        <label htmlFor="f-note">What changed</label>
        <input
          id="f-note"
          className="input"
          value={details.changeNote}
          maxLength={2000}
          placeholder="Shown in the version history"
          onChange={(e) => details.onChangeNote(e.target.value)}
        />
      </div>
    </div>
  )
}

const MAX_DOCS = 5

function DocsTab({
  templateId,
  initialDocIds = [],
  alive,
  onError,
  onNotice,
  onCandidates,
}: {
  templateId: string
  initialDocIds?: string[]
  alive: React.MutableRefObject<boolean>
  onError: (e: string | null) => void
  onNotice: (n: string | null) => void
  onCandidates: () => void
}) {
  const [docs, setDocs] = useState<AiDocument[]>([])

  useEffect(() => {
    if (initialDocIds.length === 0) return
    Promise.allSettled(initialDocIds.map((docId) => getAiDocument(docId))).then((results) => {
      if (!alive.current) return
      addDocs(results.flatMap((r) => (r.status === 'fulfilled' ? [r.value] : [])))
    })
    // Pre-picked documents load once; the list is the author's to edit after that.
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [])
  const [instructions, setInstructions] = useState('')
  const [phase, setPhase] = useState<'idle' | 'uploading' | 'generating'>('idle')
  const [progress, setProgress] = useState<string | null>(null)
  const [libOpen, setLibOpen] = useState(false)
  const fileInput = useRef<HTMLInputElement | null>(null)
  const sleep = (ms: number) => new Promise((resolve) => setTimeout(resolve, ms))

  function addDocs(added: AiDocument[]) {
    setDocs((d) => {
      const seen = new Set(d.map((x) => x.id))
      return [...d, ...added.filter((x) => !seen.has(x.id))].slice(0, MAX_DOCS)
    })
  }

  async function onUpload(files: FileList | null) {
    if (!files || files.length === 0) return
    onError(null)
    setPhase('uploading')
    try {
      const results = await Promise.allSettled(Array.from(files).map((f) => uploadAiDocument(f)))
      if (!alive.current) return
      addDocs(results.flatMap((r) => (r.status === 'fulfilled' ? [r.value] : [])))
      const failed = results.flatMap((r) =>
        r.status === 'rejected' ? [r.reason instanceof Error ? r.reason.message : 'Upload failed'] : [],
      )
      if (failed.length) onError(failed.join(' · '))
    } finally {
      if (alive.current) setPhase('idle')
    }
  }

  async function onGenerate() {
    if (docs.length === 0) return
    onError(null)
    onNotice(null)
    setPhase('generating')
    try {
      let produced = 0
      for (const doc of docs) {
        setProgress(`Reading ${doc.filename}…`)
        let document = doc
        while (alive.current && (document.status === 'PENDING' || document.status === 'EXTRACTING')) {
          await sleep(POLL_MS)
          document = await getAiDocument(doc.id)
        }
        if (!alive.current) return
        if (document.status === 'FAILED') {
          throw new Error(`${document.filename}: ${document.error ?? 'the document could not be read'}`)
        }

        setProgress(`Generating questions from ${document.filename}…`)
        let job = await generateFromDocument(templateId, {
          documentId: document.id,
          instructions: instructions.trim() || undefined,
        })
        while (alive.current && (job.status === 'QUEUED' || job.status === 'RUNNING')) {
          await sleep(POLL_MS)
          job = await getGenerationJob(job.id)
        }
        if (!alive.current) return
        if (job.status === 'FAILED') throw new Error(`${document.filename}: ${job.error ?? 'generation failed'}`)
        produced += job.candidates.length
      }

      setDocs([])
      if (fileInput.current) fileInput.current.value = ''
      if (produced === 0) {
        onNotice('The documents produced no questions — they may not contain inspectable requirements.')
      } else {
        onCandidates()
      }
    } catch (e) {
      if (alive.current) onError(e instanceof Error ? e.message : 'Generation failed')
    } finally {
      if (alive.current) {
        setPhase('idle')
        setProgress(null)
      }
    }
  }

  return (
    <div className="side-body">
      <PanelIntro
        title="Generate from documents"
        text="Upload a standard or SOP, or reuse one from the library — documents are kept, so questions can be generated from them again later. Nothing joins the checklist until you add it."
      />
      <input
        ref={fileInput}
        type="file"
        accept=".pdf,.docx,.txt"
        multiple
        style={{ display: 'none' }}
        aria-label="Upload documents"
        onChange={(e) => {
          void onUpload(e.target.files)
          e.target.value = ''
        }}
      />
      <div className="row" style={{ gap: 8 }}>
        <button
          type="button"
          className="btn btn-outline btn-sm"
          style={{ flex: 1 }}
          disabled={phase !== 'idle' || docs.length >= MAX_DOCS}
          onClick={() => fileInput.current?.click()}
        >
          ⇧ Upload
        </button>
        <button
          type="button"
          className="btn btn-outline btn-sm"
          style={{ flex: 1 }}
          disabled={phase !== 'idle' || docs.length >= MAX_DOCS}
          onClick={() => setLibOpen(true)}
        >
          ⌸ From library
        </button>
      </div>
      <div style={{ display: 'flex', flexDirection: 'column', gap: 8 }}>
        <span className="small muted">
          Added documents ({docs.length}/{MAX_DOCS})
        </span>
        {docs.length === 0 && (
          <p
            className="small muted"
            style={{ border: '1px dashed var(--dim)', borderRadius: 4, padding: 14, textAlign: 'center' }}
          >
            Upload a standard or SOP, or pick one already in the library.
          </p>
        )}
        {docs.map((doc) => (
          <div
            key={doc.id}
            className="row"
            style={{ minHeight: 44, border: '1px solid var(--line)', borderRadius: 4, padding: '4px 6px 4px 10px', gap: 10 }}
          >
            <span className="tag fail" style={{ fontSize: 9 }}>
              {doc.filename.slice(doc.filename.lastIndexOf('.') + 1).toUpperCase().slice(0, 4)}
            </span>
            <span className="ellipsis" style={{ flex: 1 }}>
              {doc.filename}
            </span>
            <span className="small muted">{formatBytes(doc.sizeBytes)}</span>
            <button
              type="button"
              className="ibtn"
              aria-label={`Remove ${doc.filename}`}
              disabled={phase === 'generating'}
              onClick={() => setDocs((d) => d.filter((x) => x.id !== doc.id))}
            >
              ×
            </button>
          </div>
        ))}
      </div>
      <div className="field">
        <label htmlFor="ai-instr">Instructions (optional)</label>
        <textarea
          id="ai-instr"
          className="textarea"
          rows={2}
          value={instructions}
          maxLength={2000}
          placeholder="e.g. only the monthly checks, one question per requirement"
          disabled={phase !== 'idle'}
          onChange={(e) => setInstructions(e.target.value)}
        />
      </div>
      <button
        type="button"
        className="btn btn-primary"
        disabled={docs.length === 0 || phase !== 'idle'}
        onClick={() => void onGenerate()}
      >
        {phase === 'generating' ? 'Generating…' : phase === 'uploading' ? 'Uploading…' : '✦ Generate questions'}
      </button>
      {progress && (
        <div className="row" style={{ gap: 10 }}>
          <span className="spin" />
          <span className="small muted">{progress} This can take a minute.</span>
        </div>
      )}
      {libOpen && (
        <DocumentLibraryDialog
          confirmLabel="Add"
          onClose={() => setLibOpen(false)}
          onConfirm={(picked) => {
            setLibOpen(false)
            addDocs(picked)
          }}
        />
      )}
    </div>
  )
}

interface ChatEntry {
  role: 'user' | 'assistant'
  text: string
}

function SuggestTab({
  templateId,
  alive,
  onError,
  onCandidates,
}: {
  templateId: string
  alive: React.MutableRefObject<boolean>
  onError: (e: string | null) => void
  onCandidates: () => Promise<void>
}) {
  const [sessionId, setSessionId] = useState<string | null>(null)
  const [log, setLog] = useState<ChatEntry[]>([])
  const [input, setInput] = useState('')
  const [sending, setSending] = useState(false)

  async function onSend() {
    const message = input.trim()
    if (!message || sending) return
    onError(null)
    setSending(true)
    setLog((l) => [...l, { role: 'user', text: message }])
    setInput('')
    try {
      let session = sessionId
      if (!session) {
        session = (await createChatSession(templateId)).id
        setSessionId(session)
      }
      const turn = await sendChatMessage(session, message)
      if (!alive.current) return
      setLog((l) => [...l, { role: 'assistant', text: turn.reply }])
      await onCandidates()
    } catch (e) {
      if (alive.current) {
        onError(e instanceof Error ? e.message : 'The message could not be sent')
        setLog((l) => l.slice(0, -1))
        setInput(message)
      }
    } finally {
      if (alive.current) setSending(false)
    }
  }

  return (
    <div className="side-body">
      <PanelIntro
        title="Suggest with AI"
        text="Describe what the procedure is for — asset, standard, depth — and refine the suggestions turn by turn."
      />
      {log.length > 0 && (
        <div className="chatlog">
          {log.map((entry, i) => (
            <div key={i} className={`cmsg ${entry.role}`}>
              {entry.text}
            </div>
          ))}
          {sending && <div className="cmsg assistant muted">Thinking…</div>}
        </div>
      )}
      <div style={{ display: 'flex', gap: 8, alignItems: 'flex-end' }}>
        <textarea
          className="textarea"
          rows={2}
          style={{ flex: 1 }}
          value={input}
          maxLength={4000}
          placeholder="e.g. Weekly fire-extinguisher checklist for a warehouse"
          disabled={sending}
          onChange={(e) => setInput(e.target.value)}
          onKeyDown={(e) => {
            if (e.key === 'Enter' && !e.shiftKey) {
              e.preventDefault()
              void onSend()
            }
          }}
        />
        <button type="button" className="btn btn-primary" disabled={!input.trim() || sending} onClick={() => void onSend()}>
          {sending ? '…' : 'Send'}
        </button>
      </div>
    </div>
  )
}

/** "Yes → Pass · No → Fail" chips plus follow-up and provenance tags for one candidate. */
function candidateChips(q: CandidateQuestion) {
  return (
    <span className="opts-line" style={{ marginTop: 0 }}>
      {q.options.map((o, i) => (
        <span key={i} className="ochip">
          <span className="dot" style={{ background: resultDot(o.result) }} />
          {o.label}
        </span>
      ))}
      {q.options.length === 0 && <span className="ochip muted">{typeLabel(q.type)}</span>}
      {q.followUps.length > 0 && (
        <span className="tag sel">
          ↳ {q.followUps.length} follow-up{q.followUps.length > 1 ? 's' : ''}
        </span>
      )}
      {q.standard && <span className="tag line">{q.standard}</span>}
    </span>
  )
}

/**
 * docs/procedures-v3.html's ReviewDialog over real candidates: everything is
 * selected except likely duplicates, Add selected goes through the one shared
 * import path, Discard removes a suggestion without adding it.
 */
function ReviewDialog({
  templateId,
  candidates,
  onClose,
  onImport,
  onChanged,
  onDone,
}: {
  templateId: string
  candidates: QuestionDraft[]
  onClose: () => void
  onImport: (candidateIds: string[]) => Promise<ImportResult>
  onChanged: () => Promise<void>
  onDone: (message: string) => void
}) {
  const [selected, setSelected] = useState<Set<string>>(
    () => new Set(candidates.filter((c) => !c.similarExisting).map((c) => c.id)),
  )
  const [busy, setBusy] = useState(false)
  const [error, setError] = useState<string | null>(null)
  const n = selected.size

  function toggle(id: string) {
    setSelected((prev) => {
      const next = new Set(prev)
      if (next.has(id)) next.delete(id)
      else next.add(id)
      return next
    })
  }

  async function doImport() {
    if (n === 0 || busy) return
    setBusy(true)
    setError(null)
    try {
      const result = await onImport([...selected])
      await onChanged()
      const added = result.imported.length
      onDone(
        added === 0
          ? 'Those questions were already in the checklist.'
          : `${added} question${added === 1 ? '' : 's'} added to the checklist.`,
      )
    } catch (e) {
      setError(e instanceof Error ? e.message : 'Import failed')
      setBusy(false)
    }
  }

  async function doDiscard(id: string) {
    setError(null)
    try {
      await discardQuestionDraft(templateId, id)
      setSelected((prev) => {
        const next = new Set(prev)
        next.delete(id)
        return next
      })
      await onChanged()
    } catch (e) {
      setError(e instanceof Error ? e.message : 'Could not discard the suggestion')
    }
  }

  return (
    <div
      className="scrim"
      onMouseDown={(e) => {
        if (e.target === e.currentTarget && !busy) onClose()
      }}
    >
      <div className="dialog" role="dialog" aria-modal="true" aria-labelledby="rv-t" style={{ width: 880, height: '90%' }}>
        <div className="dhead">
          <span aria-hidden="true" style={{ color: 'var(--action)', fontSize: 20 }}>
            ✦
          </span>
          <h2 id="rv-t">Suggested questions</h2>
          <button className="ibtn xl" style={{ marginLeft: 'auto' }} aria-label="Close" onClick={onClose} disabled={busy}>
            ✕
          </button>
        </div>
        <div className="dstrip">
          <span>Choose what to add. Follow-ups come with their question, and everything stays editable afterwards.</span>
          <label className="row" style={{ gap: 8, color: 'var(--text)', cursor: 'pointer', flexShrink: 0 }}>
            <input
              type="checkbox"
              checked={n === candidates.length && n > 0}
              onChange={() =>
                setSelected(n === candidates.length ? new Set() : new Set(candidates.map((c) => c.id)))
              }
            />
            Select all ({candidates.length})
          </label>
        </div>
        <div className="dbody" style={{ display: 'flex', flexDirection: 'column', gap: 10, paddingTop: 16 }}>
          {error && <div className="alert-error">{error}</div>}
          {candidates.length === 0 && <p className="muted">Nothing left to review.</p>}
          {candidates.map((c, i) => (
            <label key={c.id} className={`topic${selected.has(c.id) ? ' on' : ''}`} style={{ padding: '14px 16px' }}>
              <span style={{ flex: 1, display: 'flex', flexDirection: 'column', gap: 8, minWidth: 0 }}>
                <span className="row" style={{ gap: 8, flexWrap: 'wrap' }}>
                  <span style={{ fontWeight: 500, fontSize: 15 }}>
                    {i + 1}. {c.question.text}
                  </span>
                  {c.similarExisting && <span className="tag fail">Already in “{c.similarExisting.templateName}”</span>}
                  {c.confidence != null && (
                    <span className="small muted" style={{ marginLeft: 'auto' }}>
                      {Math.round(c.confidence * 100)}%
                    </span>
                  )}
                </span>
                {candidateChips(c.question)}
                {c.sourceSpan && <span className="small muted">“{c.sourceSpan}”</span>}
                <span>
                  <button
                    type="button"
                    className="link"
                    style={{ color: 'var(--muted)' }}
                    disabled={busy}
                    onClick={(e) => {
                      e.preventDefault()
                      void doDiscard(c.id)
                    }}
                  >
                    Discard this suggestion
                  </button>
                </span>
              </span>
              <input
                type="checkbox"
                aria-label="Include"
                checked={selected.has(c.id)}
                disabled={busy}
                onChange={() => toggle(c.id)}
                style={{ width: 18, height: 18, marginTop: 4 }}
              />
            </label>
          ))}
        </div>
        <div className="dfoot">
          <span className="muted">
            {n} of {candidates.length} selected
          </span>
          <div className="row">
            <button className="btn btn-quiet" onClick={onClose} disabled={busy}>
              Close
            </button>
            <button className="btn btn-primary" disabled={n === 0 || busy} onClick={() => void doImport()}>
              {busy ? 'Adding…' : `Add ${n} question${n === 1 ? '' : 's'}`}
            </button>
          </div>
        </div>
      </div>
    </div>
  )
}

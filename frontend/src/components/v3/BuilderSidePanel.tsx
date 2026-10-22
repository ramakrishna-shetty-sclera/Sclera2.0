import { useEffect, useRef, useState } from 'react'
import {
  createChatSession,
  discardQuestionDraft,
  extractTopics,
  generateFromDocument,
  getAiDocument,
  getGenerationJob,
  listAiDocuments,
  listQuestionDrafts,
  sendChatMessage,
  uploadAiDocument,
  type AiDocument,
  type CandidateQuestion,
  type DocumentTopicGroup,
  type ImportResult,
  type QuestionDraft,
} from '../../api/ai'
import { ApiError } from '../../api/client'
import { typeLabel } from '../../api/document'
import type { ResultType, TemplateMeta, Threshold } from '../../api/types'
import type { ItemDraft } from '../itemTree'
import { DocumentLibraryDialog, formatBytes } from './DocumentLibraryDialog'
import { CATEGORIES, FOLDERS, FREQUENCIES, PRIORITIES, STANDARDS, SUB_CATEGORIES } from './procedureVocab'
import { resultDot } from './ReadOnlyChecklist'
import { STARTER_TEMPLATES, toItemDraft, type StarterTemplate } from './starterTemplates'
import { ThresholdsEditor } from './ThresholdsEditor'

const POLL_MS = 1500

export interface SidePanelDetails {
  name: string
  description: string
  changeNote: string
  meta: TemplateMeta
  onName: (v: string) => void
  onDescription: (v: string) => void
  onChangeNote: (v: string) => void
  onMeta: (patch: Partial<TemplateMeta>) => void
  /** Document-level — rides here because `details` is the one channel by which document state already reaches a side-panel tab. */
  thresholds: Threshold[]
  resultTypes: ResultType[]
  hasSections: boolean
  onThresholds: (next: Threshold[]) => void
  /** No property is selected — there is nothing for "This property" to mean, so it is not offered. */
  atOrgLevel: boolean
}

export type SideTab = 'details' | 'templates' | 'docs' | 'suggest'

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
  onAddItems,
  onRequireTemplate,
  procedureName,
}: {
  /** Null while creating: the procedure does not exist yet, so neither can AI candidates. */
  templateId: string | null
  /** Library documents picked before the builder opened ("create from documents"). */
  initialDocIds?: string[]
  tab: SideTab
  onTab: (tab: SideTab) => void
  details: SidePanelDetails
  onImport: (candidateIds: string[]) => Promise<ImportResult>
  /** Starter-template questions join the checklist client-side, like typed ones. */
  onAddItems: (items: ItemDraft[]) => void
  /** Create the procedure now and reopen the builder on `tab` (docs/chat are template-scoped). */
  onRequireTemplate: (docIds: string[], tab: SideTab) => void
  procedureName: string
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
    // The feature-flag probe. With a template, the drafts list doubles as the
    // candidate load; without one, the org-level document list answers the
    // same question (a 404 means the flag is off on the server).
    const probe = templateId
      ? listQuestionDrafts(templateId, 'DRAFT').then((r) => {
          if (!alive.current) return
          setAiAvailable(true)
          setCandidates(r.data)
        })
      : listAiDocuments().then(() => {
          if (alive.current) setAiAvailable(true)
        })
    probe.catch((e: unknown) => {
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
    ['templates', 'Templates'],
    ['docs', '✦ Documents'],
    ['suggest', '✦ Suggestions'],
  ]
  const aiTab = tab === 'docs' || tab === 'suggest'
  // Uploading, the library and topic extraction are org-scoped — they work
  // before the procedure exists. Only generation and chat are template-scoped.
  const docsReady = aiAvailable === true
  const suggestReady = aiAvailable === true && templateId != null

  return (
    <aside className="side" aria-label="Procedure details and question sources">
      <div className="stabs" role="tablist" style={{ gridTemplateColumns: `repeat(${tabs.length},minmax(0,1fr))` }}>
        {tabs.map(([k, label]) => (
          <button key={k} type="button" role="tab" className="stab" aria-selected={tab === k} onClick={() => onTab(k)}>
            {label}
            {(k === 'docs' || k === 'suggest') && candidates.length > 0 && (
              <span className="count-badge">{candidates.length}</span>
            )}
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
      {candidates.length > 0 && aiTab && (
        <div style={{ padding: '12px 20px 0' }}>
          <button type="button" className="btn btn-primary" style={{ width: '100%' }} onClick={() => setReview(true)}>
            Review {candidates.length} suggested question{candidates.length === 1 ? '' : 's'}
          </button>
        </div>
      )}

      {tab === 'details' && <DetailsTab details={details} />}
      {tab === 'templates' && <TemplatesTab onAddItems={onAddItems} />}
      {tab === 'docs' && !docsReady && <AiUnavailable aiAvailable={aiAvailable} />}
      {tab === 'suggest' && !suggestReady && aiAvailable !== true && (
        <AiUnavailable aiAvailable={aiAvailable} />
      )}
      {tab === 'suggest' && !suggestReady && aiAvailable === true && (
        <div className="side-body">
          <PanelIntro
            title="Save the procedure to get suggestions"
            text="Suggestions are tailored to this procedure's name, category and standard, so it has to exist first. Saving keeps everything you have typed."
          />
          <button type="button" className="btn btn-primary" onClick={() => onRequireTemplate([], 'suggest')}>
            Save and get suggestions
          </button>
        </div>
      )}
      {tab === 'docs' && docsReady && (
        <DocsTab
          templateId={templateId}
          initialDocIds={initialDocIds}
          alive={alive}
          onError={setError}
          onNotice={setNotice}
          onRequireTemplate={onRequireTemplate}
          onCandidates={() => {
            void refreshCandidates().then(() => setReview(true))
          }}
        />
      )}
      {tab === 'suggest' && suggestReady && (
        <SuggestTab
          templateId={templateId!}
          procedureName={procedureName}
          meta={details.meta}
          alive={alive}
          onError={setError}
          onImport={onImport}
          onChanged={refreshCandidates}
        />
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
function AiUnavailable({ aiAvailable }: { aiAvailable: boolean | null }) {
  return (
    <div className="side-body">
      {aiAvailable == null ? (
        <div className="row" style={{ gap: 10 }}>
          <span className="spin" />
          <span className="small muted">Checking whether AI generation is available…</span>
        </div>
      ) : (
        <PanelIntro
          title="AI generation is switched off"
          text="The procedure service is running without AI_QUESTION_GENERATION_ENABLED=true (and an API key). Enable it and restart the service; this panel picks it up on the next load."
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

/** A labelled select over a suggested vocabulary; a stored value outside it still shows. */
function VocabSelect({
  id,
  label,
  value,
  options,
  emptyLabel,
  onChange,
}: {
  id: string
  label: string
  value: string
  options: string[]
  emptyLabel?: string
  onChange: (v: string) => void
}) {
  const known = options.includes(value)
  return (
    <div className="field" style={{ flex: 1 }}>
      <label htmlFor={id}>{label}</label>
      <select id={id} className="select" value={value} onChange={(e) => onChange(e.target.value)}>
        <option value="">{emptyLabel ?? 'Select…'}</option>
        {!known && value !== '' && <option value={value}>{value}</option>}
        {options.map((o) => (
          <option key={o} value={o}>
            {o}
          </option>
        ))}
      </select>
    </div>
  )
}

function DetailsTab({ details }: { details: SidePanelDetails }) {
  const meta = details.meta
  const set = (patch: Partial<TemplateMeta>) => details.onMeta(patch)
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
      <div className="row" style={{ gap: 12 }}>
        <VocabSelect id="f-cat" label="Category" value={meta.category ?? ''} options={CATEGORIES}
          onChange={(v) => set({ category: v || null })} />
        <VocabSelect id="f-sub" label="Sub category" value={meta.subCategory ?? ''} options={SUB_CATEGORIES}
          onChange={(v) => set({ subCategory: v || null })} />
      </div>
      <div className="row" style={{ gap: 12 }}>
        <VocabSelect id="f-freq" label="Frequency" value={meta.frequency ?? ''} options={FREQUENCIES}
          emptyLabel="Not set" onChange={(v) => set({ frequency: v || null })} />
        <VocabSelect id="f-pri" label="Priority" value={meta.priority ?? ''} options={PRIORITIES}
          emptyLabel="Not set" onChange={(v) => set({ priority: v || null })} />
      </div>
      <div className="row" style={{ gap: 12 }}>
        <div className="field" style={{ flex: 1 }}>
          <label htmlFor="f-code">Code</label>
          <input
            id="f-code"
            className="input"
            value={meta.code ?? ''}
            maxLength={30}
            placeholder="FE-M-01"
            onChange={(e) => set({ code: e.target.value || null })}
          />
        </div>
        <VocabSelect id="f-std" label="Standard" value={meta.standard ?? ''} options={STANDARDS}
          emptyLabel="None" onChange={(v) => set({ standard: v || null })} />
      </div>
      <div role="radiogroup" aria-label="Availability" style={{ display: 'flex', flexDirection: 'column', gap: 8 }}>
        <span className="small muted">Availability</span>
        {(
          [
            ['PROPERTY', 'This property', 'Only the property it was authored in'],
            ['ORG', 'Entire organization', 'All properties'],
          ] as ['PROPERTY' | 'ORG', string, string][]
        )
          .filter(([key]) => !details.atOrgLevel || key !== 'PROPERTY')
          .map(([key, label, sub]) => (
          <button
            key={key}
            type="button"
            role="radio"
            className="choice"
            aria-checked={(meta.availability ?? 'PROPERTY') === key}
            onClick={() => set({ availability: key })}
          >
            <span style={{ display: 'flex', flexDirection: 'column' }}>
              <span style={{ fontWeight: 600 }}>{label}</span>
              <span className="small muted">{sub}</span>
            </span>
          </button>
        ))}
      </div>
      <VocabSelect id="f-fold" label="Folder" value={meta.folder ?? ''} options={FOLDERS}
        onChange={(v) => set({ folder: v || null })} />
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
      <div className="divider" />
      <ThresholdsEditor
        thresholds={details.thresholds}
        resultTypes={details.resultTypes}
        hasSections={details.hasSections}
        onChange={details.onThresholds}
      />
    </div>
  )
}

function TemplatesTab({ onAddItems }: { onAddItems: (items: ItemDraft[]) => void }) {
  const [query, setQuery] = useState('')
  const [preview, setPreview] = useState<StarterTemplate | null>(null)
  const [selected, setSelected] = useState<boolean[]>([])
  const needle = query.trim().toLowerCase()
  const list = STARTER_TEMPLATES.filter(
    (t) => !needle || `${t.name} ${t.standard} ${t.category}`.toLowerCase().includes(needle),
  )
  const picked = selected.filter(Boolean).length

  return (
    <div className="side-body">
      <PanelIntro
        title="Add questions from a template"
        text="Pick a ready-made checklist, choose the questions you want, and they are added to yours."
      />
      <div className="field">
        <label htmlFor="tsearch">Search templates</label>
        <input
          id="tsearch"
          type="search"
          className="input"
          placeholder="Name or standard, e.g. NFPA 72"
          value={query}
          onChange={(e) => setQuery(e.target.value)}
        />
      </div>
      {list.map((t) => (
        <div key={t.key} className="tcard" style={{ display: 'flex', flexDirection: 'column', gap: 10 }}>
          <div style={{ display: 'flex', flexDirection: 'column', gap: 4 }}>
            <div className="row" style={{ gap: 4, flexWrap: 'wrap' }}>
              <span className="tag line">{t.category}</span>
              <span className="tag line">{t.standard}</span>
            </div>
            <span style={{ fontWeight: 600 }}>{t.name}</span>
            <span className="small muted">{t.description}</span>
          </div>
          <div className="row between">
            <span className="small muted">{t.specs.length} questions</span>
            <button
              type="button"
              className="btn btn-primary btn-sm"
              onClick={() => {
                setPreview(t)
                setSelected(t.specs.map(() => true))
              }}
            >
              Preview & add
            </button>
          </div>
        </div>
      ))}
      {list.length === 0 && <p className="muted">No templates match “{query}”.</p>}

      {preview && (
        <div
          className="scrim"
          onMouseDown={(e) => {
            if (e.target === e.currentTarget) setPreview(null)
          }}
        >
          <div className="dialog" role="dialog" aria-modal="true" aria-labelledby="tp-t" style={{ width: 760, maxHeight: '90%' }}>
            <div className="dhead">
              <h2 id="tp-t" style={{ fontSize: 20 }}>
                {preview.name}
              </h2>
              <span className="tag line">{preview.standard}</span>
              <button className="ibtn lg" style={{ marginLeft: 'auto' }} aria-label="Close" onClick={() => setPreview(null)}>
                ×
              </button>
            </div>
            <div className="dstrip row between">
              <span>Choose what to add. You can edit everything afterwards.</span>
              <label className="row small" style={{ gap: 8, cursor: 'pointer' }}>
                <input
                  type="checkbox"
                  checked={picked === selected.length}
                  onChange={() => setSelected(selected.map(() => picked !== selected.length))}
                />
                Select all ({selected.length})
              </label>
            </div>
            <div className="dbody" style={{ display: 'flex', flexDirection: 'column', gap: 10 }}>
              {preview.specs.map((spec, i) => (
                <label key={spec.text} className={`topic${selected[i] ? ' on' : ''}`} style={{ alignItems: 'center' }}>
                  <input
                    type="checkbox"
                    checked={selected[i] ?? false}
                    onChange={() => setSelected(selected.map((v, j) => (j === i ? !v : v)))}
                  />
                  <span style={{ flex: 1, display: 'flex', flexDirection: 'column', gap: 2 }}>
                    <span style={{ fontWeight: 500 }}>
                      {i + 1}. {spec.text}
                      {spec.required && <span className="req"> *</span>}
                    </span>
                    {spec.help && <span className="small muted">{spec.help}</span>}
                  </span>
                  <span className="small muted">{typeLabel(spec.type)}</span>
                </label>
              ))}
            </div>
            <div className="dfoot">
              <span className="muted">
                {picked} of {selected.length} selected
              </span>
              <button
                className="btn btn-primary"
                disabled={picked === 0}
                onClick={() => {
                  onAddItems(
                    preview.specs.filter((_, i) => selected[i]).map((spec) => toItemDraft(spec, preview.standard)),
                  )
                  setPreview(null)
                }}
              >
                Add {picked} question{picked === 1 ? '' : 's'}
              </button>
            </div>
          </div>
        </div>
      )}
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
  onRequireTemplate,
  onCandidates,
}: {
  /** Null while creating — upload/library/topics still work; Generate saves first. */
  templateId: string | null
  initialDocIds?: string[]
  alive: React.MutableRefObject<boolean>
  onError: (e: string | null) => void
  onNotice: (n: string | null) => void
  onRequireTemplate: (docIds: string[], tab: SideTab) => void
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
  const [phase, setPhase] = useState<'idle' | 'uploading' | 'topics' | 'generating'>('idle')
  const [progress, setProgress] = useState<string | null>(null)
  const [libOpen, setLibOpen] = useState(false)
  /** docId → extracted topic groups; set only once extraction is READY. */
  const [docTopics, setDocTopics] = useState<Record<string, DocumentTopicGroup[]>>({})
  /** docIds whose topic extraction FAILED — they generate from the whole document. */
  const [topicsFailed, setTopicsFailed] = useState<Record<string, string>>({})
  /** `${docId}|${group}|${topic}` → picked. Everything starts picked, per the design. */
  const [sel, setSel] = useState<Record<string, boolean>>({})
  const topicsReady = Object.keys(docTopics).length + Object.keys(topicsFailed).length === docs.length && docs.length > 0
  const fileInput = useRef<HTMLInputElement | null>(null)
  const sleep = (ms: number) => new Promise((resolve) => setTimeout(resolve, ms))

  /** Any change to the document list invalidates the extracted topic view. */
  function resetTopics() {
    setDocTopics({})
    setTopicsFailed({})
    setSel({})
  }

  function addDocs(added: AiDocument[]) {
    resetTopics()
    setDocs((d) => {
      const seen = new Set(d.map((x) => x.id))
      return [...d, ...added.filter((x) => !seen.has(x.id))].slice(0, MAX_DOCS)
    })
  }

  const topicKey = (docId: string, g: number, t: number) => `${docId}|${g}|${t}`
  const allKeys = docs.flatMap((doc) =>
    (docTopics[doc.id] ?? []).flatMap((group, g) => group.topics.map((_, t) => topicKey(doc.id, g, t))),
  )
  const pickedCount = allKeys.filter((k) => sel[k]).length

  /** The topic-selection step: extract once per document (cached server-side), everything pre-selected. */
  async function onExtractTopics() {
    onError(null)
    setPhase('topics')
    try {
      const groups: Record<string, DocumentTopicGroup[]> = {}
      const failed: Record<string, string> = {}
      for (const doc of docs) {
        setProgress(`Reading ${doc.filename}…`)
        let document = doc
        while (alive.current && (document.status === 'PENDING' || document.status === 'EXTRACTING')) {
          await sleep(POLL_MS)
          document = await getAiDocument(doc.id)
        }
        if (!alive.current) return
        if (document.status === 'FAILED') {
          failed[doc.id] = document.error ?? 'the document could not be read'
          continue
        }
        setProgress(`Finding topics in ${document.filename}…`)
        document = await extractTopics(doc.id)
        while (alive.current && document.topicsStatus === 'EXTRACTING') {
          await sleep(POLL_MS)
          document = await getAiDocument(doc.id)
        }
        if (!alive.current) return
        if (document.topicsStatus === 'READY') {
          groups[doc.id] = document.topics?.groups ?? []
        } else {
          failed[doc.id] = document.topicsError ?? 'topic extraction failed'
        }
      }
      setDocTopics(groups)
      setTopicsFailed(failed)
      const all: Record<string, boolean> = {}
      for (const [docId, docGroups] of Object.entries(groups)) {
        docGroups.forEach((group, g) => group.topics.forEach((_, t) => (all[topicKey(docId, g, t)] = true)))
      }
      setSel(all)
    } catch (e) {
      if (alive.current) onError(e instanceof Error ? e.message : 'Topic extraction failed')
    } finally {
      if (alive.current) {
        setPhase('idle')
        setProgress(null)
      }
    }
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
    if (!topicsReady) return
    // Generation is template-scoped: a brand-new procedure is saved first and
    // the builder reopens here with the documents carried over (topics are
    // cached server-side, so re-extracting them is instant).
    if (templateId == null) {
      onRequireTemplate(docs.map((d) => d.id), 'docs')
      return
    }
    onError(null)
    onNotice(null)
    setPhase('generating')
    try {
      let produced = 0
      for (const doc of docs) {
        // Selected topics for this document; a doc whose extraction failed
        // falls back to whole-document generation.
        const picked = (docTopics[doc.id] ?? []).flatMap((group, g) =>
          group.topics.filter((_, t) => sel[topicKey(doc.id, g, t)]),
        )
        if (picked.length === 0 && !topicsFailed[doc.id]) continue

        setProgress(`Generating questions from ${doc.filename}…`)
        let job = await generateFromDocument(templateId, {
          documentId: doc.id,
          instructions: instructions.trim() || undefined,
          topics: picked.length > 0 ? picked : undefined,
        })
        while (alive.current && (job.status === 'QUEUED' || job.status === 'RUNNING')) {
          await sleep(POLL_MS)
          job = await getGenerationJob(job.id)
        }
        if (!alive.current) return
        if (job.status === 'FAILED') throw new Error(`${doc.filename}: ${job.error ?? 'generation failed'}`)
        produced += job.candidates.length
      }

      setDocs([])
      resetTopics()
      if (fileInput.current) fileInput.current.value = ''
      if (produced === 0) {
        onNotice('The selected topics produced no questions — try widening the selection.')
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
              onClick={() => {
                resetTopics()
                setDocs((d) => d.filter((x) => x.id !== doc.id))
              }}
            >
              ×
            </button>
          </div>
        ))}
      </div>

      {docs.length > 0 && !topicsReady && (
        <button
          type="button"
          className="btn btn-tonal"
          disabled={phase !== 'idle'}
          onClick={() => void onExtractTopics()}
        >
          {phase === 'topics' ? 'Extracting topics…' : 'Extract topics'}
        </button>
      )}

      {topicsReady && (
        <>
          <div className="row between">
            <span style={{ fontWeight: 600 }}>Topics ({allKeys.length})</span>
            <label className="row small muted" style={{ gap: 6, cursor: 'pointer' }}>
              <input
                type="checkbox"
                checked={allKeys.length > 0 && pickedCount === allKeys.length}
                onChange={() => {
                  const next: Record<string, boolean> = {}
                  const on = pickedCount !== allKeys.length
                  for (const k of allKeys) next[k] = on
                  setSel(next)
                }}
              />
              Select all
            </label>
          </div>
          {docs.map((doc) =>
            topicsFailed[doc.id] ? (
              <p key={doc.id} className="small warn">
                {doc.filename}: topics could not be extracted ({topicsFailed[doc.id]}) — generation will
                cover the whole document.
              </p>
            ) : (
              (docTopics[doc.id] ?? []).map((group, g) => (
                <div key={`${doc.id}-${g}`} style={{ display: 'flex', flexDirection: 'column', gap: 6 }}>
                  <span className="section-nav" style={{ padding: '2px 0' }}>
                    {group.title}
                    {docs.length > 1 ? ` · ${doc.filename}` : ''}
                  </span>
                  {group.topics.map((topic, t) => {
                    const k = topicKey(doc.id, g, t)
                    return (
                      <label key={k} className={`topic${sel[k] ? ' on' : ''}`}>
                        <input
                          type="checkbox"
                          checked={!!sel[k]}
                          style={{ marginTop: 2 }}
                          onChange={() => setSel((s) => ({ ...s, [k]: !s[k] }))}
                        />
                        <span style={{ display: 'flex', flexDirection: 'column', gap: 4 }}>
                          <span className="row" style={{ gap: 6, flexWrap: 'wrap' }}>
                            {topic.standard && (
                              <span className="tag line" style={{ height: 18, fontSize: 10 }}>
                                {topic.standard}
                              </span>
                            )}
                            <span style={{ fontWeight: 500 }}>{topic.title}</span>
                          </span>
                          {topic.quote && <span className="small muted">{topic.quote}</span>}
                        </span>
                      </label>
                    )
                  })}
                </div>
              ))
            ),
          )}
        </>
      )}

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
      {topicsReady && (
        <button
          type="button"
          className="btn btn-primary"
          disabled={phase !== 'idle' || (pickedCount === 0 && Object.keys(topicsFailed).length === 0)}
          onClick={() => void onGenerate()}
        >
          {phase === 'generating'
            ? 'Generating…'
            : `✦ ${templateId == null ? 'Save & generate' : 'Generate'} questions${
                pickedCount > 0 ? ` (${pickedCount} topic${pickedCount === 1 ? '' : 's'})` : ''
              }`}
        </button>
      )}
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

/** The suggestion lenses (docs/procedures-v3.html SUGGEST): generic inspectable angles. */
const SUGGEST_LENSES = [
  'Visual inspection',
  'Safety & access',
  'Readings & measurements',
  'Records & evidence',
  'Housekeeping & signage',
]

/**
 * Smart suggestions, per the design: no free text — pick a topic pill and
 * questions tailored to the procedure's name, category and standard appear,
 * each with its own Add. The chat backend powers it (one scripted turn per
 * pill, results cached), and Add is the same single import path as everything
 * else.
 */
function SuggestTab({
  templateId,
  procedureName,
  meta,
  alive,
  onError,
  onImport,
  onChanged,
}: {
  templateId: string
  procedureName: string
  meta: TemplateMeta
  alive: React.MutableRefObject<boolean>
  onError: (e: string | null) => void
  onImport: (candidateIds: string[]) => Promise<ImportResult>
  onChanged: () => Promise<void>
}) {
  const [sessionId, setSessionId] = useState<string | null>(null)
  const [active, setActive] = useState<string | null>(null)
  const [byLens, setByLens] = useState<Record<string, QuestionDraft[]>>({})
  const [loading, setLoading] = useState(false)
  const [added, setAdded] = useState<Record<string, boolean>>({})
  const [addingId, setAddingId] = useState<string | null>(null)

  async function pick(lens: string) {
    setActive(lens)
    if (byLens[lens] || loading) return
    onError(null)
    setLoading(true)
    try {
      let session = sessionId
      if (!session) {
        session = (await createChatSession(templateId)).id
        setSessionId(session)
      }
      const detailBits = [
        meta.category ? `category ${meta.category}` : null,
        meta.subCategory ? `sub category ${meta.subCategory}` : null,
        meta.standard ? `following ${meta.standard}` : null,
      ]
        .filter(Boolean)
        .join(', ')
      const nameText = procedureName.trim() || 'this procedure'
      const before = new Set(
        Object.values(byLens)
          .flat()
          .map((c) => c.id),
      )
      const turn = await sendChatMessage(
        session,
        `Suggest 3 to 5 new checklist questions about "${lens}" for the procedure "${nameText}"` +
          `${detailBits ? ` (${detailBits})` : ''}. ` +
          'Add them as new candidate questions; do not revise or remove existing candidates.',
      )
      if (!alive.current) return
      setByLens((m) => ({
        ...m,
        [lens]: turn.candidates.filter((c) => c.status === 'DRAFT' && !before.has(c.id)),
      }))
      void onChanged()
    } catch (e) {
      if (alive.current) onError(e instanceof Error ? e.message : 'Suggestions could not be fetched')
    } finally {
      if (alive.current) setLoading(false)
    }
  }

  async function add(candidate: QuestionDraft) {
    onError(null)
    setAddingId(candidate.id)
    try {
      await onImport([candidate.id])
      if (!alive.current) return
      setAdded((a) => ({ ...a, [candidate.id]: true }))
      void onChanged()
    } catch (e) {
      if (alive.current) onError(e instanceof Error ? e.message : 'The question could not be added')
    } finally {
      if (alive.current) setAddingId(null)
    }
  }

  const items = active ? byLens[active] : null

  return (
    <div className="side-body">
      <PanelIntro
        title="Smart suggestions"
        text="Suggested from the name, category and standard. Choose a topic and add what you need."
      />
      <div className="row" style={{ flexWrap: 'wrap', gap: 6 }}>
        {SUGGEST_LENSES.map((lens) => (
          <button
            key={lens}
            type="button"
            className={`pill${active === lens ? ' on' : ''}`}
            aria-pressed={active === lens}
            disabled={loading && active !== lens}
            onClick={() => void pick(lens)}
          >
            {lens}
          </button>
        ))}
      </div>
      {active == null && (
        <p className="small muted">Pick a topic above — suggestions are generated for this procedure.</p>
      )}
      {active != null && loading && !items && (
        <div className="row" style={{ gap: 10 }}>
          <span className="spin" />
          <span className="small muted">Suggesting {active.toLowerCase()} questions… This can take a minute.</span>
        </div>
      )}
      {items != null && items.length === 0 && (
        <p className="small muted">Nothing new to suggest for this topic — the checklist may already cover it.</p>
      )}
      {items?.map((candidate) => (
        <div
          key={candidate.id}
          className="row"
          style={{ border: '1px solid var(--line)', borderRadius: 4, padding: 12, alignItems: 'flex-start' }}
        >
          <div style={{ flex: 1, display: 'flex', flexDirection: 'column', gap: 4 }}>
            <span>{candidate.question.text}</span>
            <span className="small muted">
              {typeLabel(candidate.question.type)}
              {candidate.question.standard ? ` · ${candidate.question.standard}` : ''}
              {candidate.question.help ? ` — ${candidate.question.help}` : ''}
            </span>
          </div>
          {added[candidate.id] || candidate.status === 'IMPORTED' ? (
            <span className="small" style={{ color: 'var(--pass)', fontWeight: 500, paddingTop: 6 }}>
              Added
            </span>
          ) : (
            <button
              type="button"
              className="btn btn-outline btn-xs"
              disabled={addingId != null}
              onClick={() => void add(candidate)}
            >
              {addingId === candidate.id ? 'Adding…' : 'Add'}
            </button>
          )}
        </div>
      ))}
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

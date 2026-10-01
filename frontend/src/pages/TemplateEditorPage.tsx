import { useEffect, useState, type FormEvent } from 'react'
import { useNavigate, useParams } from 'react-router-dom'
import { createProcedure, getDraft, getProcedure, saveDraft, updateProcedure } from '../api/templates'
import { ApiError } from '../api/client'
import type { DefinitionDocument, QuestionType } from '../api/types'

const QUESTION_TYPES: QuestionType[] = [
  'TEXT',
  'NUMBER',
  'BOOLEAN',
  'SINGLE_CHOICE',
  'MULTI_CHOICE',
  'DATE',
  'PHOTO',
  'SIGNATURE',
]

/**
 * Editing state mirrors the document, with one addition: `key`.
 *
 * Keys are minted by the server and are what make a procedure's history
 * meaningful — a stored answer, a rule and a diff all refer to a question by
 * key. So an existing question carries its key through the edit and back,
 * unchanged, and a new one has none until the server assigns it. Dropping a key
 * on the way through would read as "that question was deleted and another one
 * appeared", silently orphaning everything pointing at it.
 */
interface QuestionDraft {
  key?: string
  text: string
  helpText: string
  type: QuestionType
  required: boolean
}

interface CategoryDraft {
  key?: string
  name: string
  questions: QuestionDraft[]
}

const emptyQuestion = (): QuestionDraft => ({ text: '', helpText: '', type: 'TEXT', required: false })
const emptyCategory = (): CategoryDraft => ({ name: '', questions: [emptyQuestion()] })

/**
 * Authors the one editable thing a procedure has: its draft.
 *
 * Two modes. Creating makes the procedure and its draft v1 in a single call.
 * Editing loads the open draft and replaces it wholesale, under an optimistic
 * lock — name and description are identity rather than version content, so they
 * go through a separate call and only when they actually changed.
 */
export function TemplateEditorPage() {
  const { id } = useParams<{ id: string }>()
  const navigate = useNavigate()
  const editing = Boolean(id)

  const [name, setName] = useState('')
  const [description, setDescription] = useState('')
  const [categories, setCategories] = useState<CategoryDraft[]>([emptyCategory()])
  const [changeNote, setChangeNote] = useState('')
  const [schema, setSchema] = useState(1)
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
        setCategories(
          draft.definition.categories.map((c) => ({
            key: c.key,
            name: c.name,
            questions: c.questions.map((q) => ({
              key: q.key,
              text: q.text,
              helpText: q.helpText ?? '',
              type: q.type,
              required: q.required,
            })),
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

  function patchCategory(ci: number, patch: Partial<CategoryDraft>) {
    setCategories((prev) => prev.map((c, i) => (i === ci ? { ...c, ...patch } : c)))
  }

  function patchQuestion(ci: number, qi: number, patch: Partial<QuestionDraft>) {
    setCategories((prev) =>
      prev.map((c, i) =>
        i === ci
          ? { ...c, questions: c.questions.map((q, j) => (j === qi ? { ...q, ...patch } : q)) }
          : c,
      ),
    )
  }

  /** Keys ride through untouched; a blank one is simply absent, so the server mints it. */
  function toDocument(): DefinitionDocument {
    return {
      schema,
      categories: categories.map((c) => ({
        key: c.key,
        name: c.name.trim(),
        questions: c.questions.map((q) => ({
          key: q.key,
          text: q.text.trim(),
          helpText: q.helpText.trim() || undefined,
          type: q.type,
          required: q.required,
        })),
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

      {categories.map((category, ci) => (
        <div className="card" key={category.key ?? `new-${ci}`}>
          <div className="section-head">
            <label className="grow">
              Category {ci + 1} *
              <input
                value={category.name}
                onChange={(e) => patchCategory(ci, { name: e.target.value })}
                required
                maxLength={200}
              />
            </label>
            {category.key && <span className="rt-key-inline">{category.key}</span>}
            {categories.length > 1 && (
              <button
                type="button"
                className="btn btn-danger"
                onClick={() => setCategories((prev) => prev.filter((_, i) => i !== ci))}
              >
                Remove category
              </button>
            )}
          </div>

          {category.questions.map((q, qi) => (
            <div className="question-editor" key={q.key ?? `new-${qi}`}>
              <div className="form-grid">
                <label className="grow">
                  Question {qi + 1} *
                  <input
                    value={q.text}
                    onChange={(e) => patchQuestion(ci, qi, { text: e.target.value })}
                    required
                    maxLength={1000}
                  />
                </label>
                <label>
                  Type
                  <select
                    value={q.type}
                    onChange={(e) => patchQuestion(ci, qi, { type: e.target.value as QuestionType })}
                  >
                    {QUESTION_TYPES.map((t) => (
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
                    value={q.helpText}
                    onChange={(e) => patchQuestion(ci, qi, { helpText: e.target.value })}
                    maxLength={1000}
                  />
                </label>
                <label className="checkbox">
                  <input
                    type="checkbox"
                    checked={q.required}
                    onChange={(e) => patchQuestion(ci, qi, { required: e.target.checked })}
                  />
                  Required
                </label>
              </div>
              <div className="rt-actions">
                {q.key && <span className="rt-key-inline">{q.key}</span>}
                {category.questions.length > 1 && (
                  <button
                    type="button"
                    className="btn btn-ghost small"
                    onClick={() =>
                      patchCategory(ci, { questions: category.questions.filter((_, j) => j !== qi) })
                    }
                  >
                    Remove question
                  </button>
                )}
              </div>
            </div>
          ))}

          <button
            type="button"
            className="btn"
            onClick={() => patchCategory(ci, { questions: [...category.questions, emptyQuestion()] })}
          >
            + Add question
          </button>
        </div>
      ))}

      <button
        type="button"
        className="btn"
        onClick={() => setCategories((prev) => [...prev, emptyCategory()])}
      >
        + Add category
      </button>

      <p className="muted small">
        Choice questions have no options yet — those arrive with the answer model, along with
        sub-questions and scoring.
      </p>
    </form>
  )
}

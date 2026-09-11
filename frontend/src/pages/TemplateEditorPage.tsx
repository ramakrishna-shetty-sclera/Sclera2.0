import { useEffect, useState, type FormEvent } from 'react'
import { useNavigate, useParams } from 'react-router-dom'
import { createTemplate, getTemplate, updateTemplate } from '../api/templates'
import type { QuestionType, TemplateRequest } from '../api/types'

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

const CHOICE_TYPES: QuestionType[] = ['SINGLE_CHOICE', 'MULTI_CHOICE']

interface QuestionDraft {
  text: string
  helpText: string
  type: QuestionType
  required: boolean
  options: string
  scoreWeight: string
}

interface SectionDraft {
  title: string
  questions: QuestionDraft[]
}

const emptyQuestion = (): QuestionDraft => ({
  text: '',
  helpText: '',
  type: 'TEXT',
  required: false,
  options: '',
  scoreWeight: '',
})

const emptySection = (): SectionDraft => ({ title: '', questions: [emptyQuestion()] })

export function TemplateEditorPage() {
  const { id } = useParams<{ id: string }>()
  const navigate = useNavigate()
  const editing = Boolean(id)

  const [name, setName] = useState('')
  const [description, setDescription] = useState('')
  const [category, setCategory] = useState('')
  const [sections, setSections] = useState<SectionDraft[]>([emptySection()])
  const [error, setError] = useState<string | null>(null)
  const [busy, setBusy] = useState(false)
  const [loaded, setLoaded] = useState(!editing)

  useEffect(() => {
    if (!id) return
    getTemplate(id)
      .then((t) => {
        setName(t.name)
        setDescription(t.description ?? '')
        setCategory(t.category ?? '')
        setSections(
          [...t.sections]
            .sort((a, b) => a.displayOrder - b.displayOrder)
            .map((s) => ({
              title: s.title,
              questions: [...s.questions]
                .sort((a, b) => a.displayOrder - b.displayOrder)
                .map((q) => ({
                  text: q.text,
                  helpText: q.helpText ?? '',
                  type: q.type,
                  required: q.required,
                  options: q.options?.join(', ') ?? '',
                  scoreWeight: q.scoreWeight != null ? String(q.scoreWeight) : '',
                })),
            })),
        )
        setLoaded(true)
      })
      .catch((e) => setError(e.message))
  }, [id])

  function patchSection(si: number, patch: Partial<SectionDraft>) {
    setSections((prev) => prev.map((s, i) => (i === si ? { ...s, ...patch } : s)))
  }

  function patchQuestion(si: number, qi: number, patch: Partial<QuestionDraft>) {
    setSections((prev) =>
      prev.map((s, i) =>
        i === si
          ? { ...s, questions: s.questions.map((q, j) => (j === qi ? { ...q, ...patch } : q)) }
          : s,
      ),
    )
  }

  async function onSubmit(e: FormEvent) {
    e.preventDefault()
    const body: TemplateRequest = {
      name: name.trim(),
      description: description.trim() || undefined,
      category: category.trim() || undefined,
      sections: sections.map((s, si) => ({
        title: s.title.trim(),
        displayOrder: si + 1,
        questions: s.questions.map((q, qi) => ({
          text: q.text.trim(),
          helpText: q.helpText.trim() || undefined,
          type: q.type,
          required: q.required,
          displayOrder: qi + 1,
          options: CHOICE_TYPES.includes(q.type)
            ? q.options
                .split(/[,\n]/)
                .map((o) => o.trim())
                .filter(Boolean)
            : undefined,
          scoreWeight: q.scoreWeight.trim() ? Number(q.scoreWeight) : undefined,
        })),
      })),
    }
    setBusy(true)
    setError(null)
    try {
      const saved = id ? await updateTemplate(id, body) : await createTemplate(body)
      navigate(`/templates/${saved.id}`)
    } catch (err) {
      setError(err instanceof Error ? err.message : 'Save failed')
      setBusy(false)
    }
  }

  if (!loaded) {
    return error ? <div className="alert alert-error">{error}</div> : <p className="muted">Loading…</p>
  }

  return (
    <form onSubmit={onSubmit}>
      <div className="page-head">
        <h1>{editing ? 'Edit template' : 'New template'}</h1>
        <div className="page-actions">
          <button type="button" className="btn" onClick={() => navigate(-1)}>
            Cancel
          </button>
          <button type="submit" className="btn btn-primary" disabled={busy}>
            {busy ? 'Saving…' : 'Save'}
          </button>
        </div>
      </div>

      {error && <div className="alert alert-error">{error}</div>}

      <div className="card">
        <div className="form-grid">
          <label>
            Name *
            <input value={name} onChange={(e) => setName(e.target.value)} required maxLength={200} />
          </label>
          <label>
            Category
            <input value={category} onChange={(e) => setCategory(e.target.value)} maxLength={100} />
          </label>
        </div>
        <label>
          Description
          <textarea
            value={description}
            onChange={(e) => setDescription(e.target.value)}
            maxLength={2000}
            rows={2}
          />
        </label>
      </div>

      {sections.map((section, si) => (
        <div className="card" key={si}>
          <div className="section-head">
            <label className="grow">
              Section {si + 1} title *
              <input
                value={section.title}
                onChange={(e) => patchSection(si, { title: e.target.value })}
                required
                maxLength={200}
              />
            </label>
            {sections.length > 1 && (
              <button
                type="button"
                className="btn btn-danger"
                onClick={() => setSections((prev) => prev.filter((_, i) => i !== si))}
              >
                Remove section
              </button>
            )}
          </div>

          {section.questions.map((q, qi) => (
            <div className="question-editor" key={qi}>
              <div className="form-grid">
                <label className="grow">
                  Question {qi + 1} *
                  <input
                    value={q.text}
                    onChange={(e) => patchQuestion(si, qi, { text: e.target.value })}
                    required
                    maxLength={1000}
                  />
                </label>
                <label>
                  Type
                  <select
                    value={q.type}
                    onChange={(e) => patchQuestion(si, qi, { type: e.target.value as QuestionType })}
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
                    onChange={(e) => patchQuestion(si, qi, { helpText: e.target.value })}
                    maxLength={1000}
                  />
                </label>
                <label>
                  Score weight
                  <input
                    type="number"
                    value={q.scoreWeight}
                    onChange={(e) => patchQuestion(si, qi, { scoreWeight: e.target.value })}
                  />
                </label>
                <label className="checkbox">
                  <input
                    type="checkbox"
                    checked={q.required}
                    onChange={(e) => patchQuestion(si, qi, { required: e.target.checked })}
                  />
                  Required
                </label>
              </div>
              {CHOICE_TYPES.includes(q.type) && (
                <label>
                  Options (comma-separated) *
                  <input
                    value={q.options}
                    onChange={(e) => patchQuestion(si, qi, { options: e.target.value })}
                    placeholder="Pass, Fail, N/A"
                    required
                  />
                </label>
              )}
              {section.questions.length > 1 && (
                <button
                  type="button"
                  className="btn btn-ghost small"
                  onClick={() =>
                    patchSection(si, { questions: section.questions.filter((_, j) => j !== qi) })
                  }
                >
                  Remove question
                </button>
              )}
            </div>
          ))}

          <button
            type="button"
            className="btn"
            onClick={() => patchSection(si, { questions: [...section.questions, emptyQuestion()] })}
          >
            + Add question
          </button>
        </div>
      ))}

      <button type="button" className="btn" onClick={() => setSections((prev) => [...prev, emptySection()])}>
        + Add section
      </button>
    </form>
  )
}

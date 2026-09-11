import { useEffect, useState } from 'react'
import { Link, useNavigate, useParams } from 'react-router-dom'
import { archiveTemplate, getTemplate, publishTemplate } from '../api/templates'
import { createInspection } from '../api/inspections'
import type { QuestionTemplate } from '../api/types'
import { StatusBadge } from '../components/StatusBadge'

export function TemplateDetailPage() {
  const { id } = useParams<{ id: string }>()
  const navigate = useNavigate()
  const [template, setTemplate] = useState<QuestionTemplate | null>(null)
  const [error, setError] = useState<string | null>(null)
  const [busy, setBusy] = useState(false)

  useEffect(() => {
    if (!id) return
    getTemplate(id).then(setTemplate).catch((e) => setError(e.message))
  }, [id])

  async function run(action: () => Promise<QuestionTemplate>) {
    setBusy(true)
    setError(null)
    try {
      setTemplate(await action())
    } catch (e) {
      setError(e instanceof Error ? e.message : 'Action failed')
    } finally {
      setBusy(false)
    }
  }

  async function startInspection() {
    if (!template) return
    setBusy(true)
    setError(null)
    try {
      const inspection = await createInspection({ templateId: template.id })
      navigate(`/inspections/${inspection.id}`)
    } catch (e) {
      setError(e instanceof Error ? e.message : 'Could not create inspection')
      setBusy(false)
    }
  }

  if (!template) {
    return error ? <div className="alert alert-error">{error}</div> : <p className="muted">Loading…</p>
  }

  const sections = [...template.sections].sort((a, b) => a.displayOrder - b.displayOrder)

  return (
    <div>
      <div className="page-head">
        <div>
          <h1>{template.name}</h1>
          <p className="muted">
            <StatusBadge status={template.status} /> · v{template.version}
            {template.category ? ` · ${template.category}` : ''}
          </p>
        </div>
        <div className="page-actions">
          {template.status === 'DRAFT' && (
            <>
              <Link className="btn" to={`/templates/${template.id}/edit`}>
                Edit
              </Link>
              <button
                className="btn btn-primary"
                disabled={busy}
                onClick={() => run(() => publishTemplate(template.id))}
              >
                Publish
              </button>
            </>
          )}
          {template.status === 'PUBLISHED' && (
            <button className="btn btn-primary" disabled={busy} onClick={startInspection}>
              New inspection from this template
            </button>
          )}
          {template.status !== 'ARCHIVED' && (
            <button
              className="btn btn-danger"
              disabled={busy}
              onClick={() => {
                if (confirm('Archive this template? It can no longer be used for new inspections.')) {
                  run(() => archiveTemplate(template.id))
                }
              }}
            >
              Archive
            </button>
          )}
        </div>
      </div>

      {error && <div className="alert alert-error">{error}</div>}
      {template.description && <p>{template.description}</p>}

      {sections.map((section) => (
        <div className="card" key={section.id}>
          <h2>{section.title}</h2>
          <ol className="question-list">
            {[...section.questions]
              .sort((a, b) => a.displayOrder - b.displayOrder)
              .map((q) => (
                <li key={q.id}>
                  <div className="q-text">
                    {q.text} {q.required && <span className="req">*</span>}
                  </div>
                  <div className="muted small">
                    {q.type}
                    {q.options?.length ? ` · options: ${q.options.join(', ')}` : ''}
                    {q.scoreWeight != null ? ` · weight ${q.scoreWeight}` : ''}
                  </div>
                  {q.helpText && <div className="muted small">{q.helpText}</div>}
                </li>
              ))}
          </ol>
        </div>
      ))}
    </div>
  )
}

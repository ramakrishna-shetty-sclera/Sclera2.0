import { useEffect, useState, type FormEvent } from 'react'
import { useNavigate } from 'react-router-dom'
import { browseLibrary, getLibraryTemplate, searchQuestionBank } from '../api/library'
import { importProcedure, listProcedures } from '../api/templates'
import { getSelectedProperty } from '../api/client'
import type {
  GlobalTemplateDetail,
  GlobalTemplateSummary,
  Pagination,
  ProcedureTemplate,
  QuestionBankEntry,
} from '../api/types'
import { useAuth } from '../auth/AuthContext'
import { Modal } from '../components/Modal'
import { Pager } from '../components/Pager'
import { DefinitionView } from '../components/DefinitionView'
import { Refusal } from '../components/Refusal'

type Tab = 'templates' | 'questions'

/**
 * The Sclera-wide library: what Sclera publishes, browsable and searchable,
 * plus the cross-template question bank. Read-only — importing is the one
 * action, and it hands off to the caller's own Procedures list afterward.
 *
 * Deliberately never shows anything about one of your organization's own
 * procedures — the update lifecycle, fork and unlink all act on a procedure
 * you already have, and live on TemplateDetailPage instead.
 */
export function LibraryPage() {
  const navigate = useNavigate()
  const { permissions } = useAuth()
  const [tab, setTab] = useState<Tab>('templates')

  const [searchInput, setSearchInput] = useState('')
  const [search, setSearch] = useState('')
  const [consumer, setConsumer] = useState('')
  const [standard, setStandard] = useState('')
  const [page, setPage] = useState(0)

  const [templates, setTemplates] = useState<GlobalTemplateSummary[]>([])
  const [questions, setQuestions] = useState<QuestionBankEntry[]>([])
  const [pagination, setPagination] = useState<Pagination | null>(null)
  const [loading, setLoading] = useState(true)
  const [error, setError] = useState<string | null>(null)

  const [detail, setDetail] = useState<GlobalTemplateDetail | null>(null)
  const [detailError, setDetailError] = useState<string | null>(null)

  const [showImport, setShowImport] = useState<GlobalTemplateSummary | GlobalTemplateDetail | null>(null)
  const [importMode, setImportMode] = useState<'new' | 'existing'>('new')
  const [importName, setImportName] = useState('')
  const [importTargetId, setImportTargetId] = useState('')
  const [myProcedures, setMyProcedures] = useState<ProcedureTemplate[]>([])
  const [importError, setImportError] = useState<unknown>(null)
  const [importing, setImporting] = useState(false)

  const canImport = permissions.canImportTemplates && getSelectedProperty() == null

  function onSearchSubmit(e: FormEvent) {
    e.preventDefault()
    setSearch(searchInput)
    setPage(0)
  }

  useEffect(() => {
    let cancelled = false
    setLoading(true)
    const call =
      tab === 'templates'
        ? browseLibrary({ search: search || undefined, consumer: consumer || undefined, page, size: 20 })
        : searchQuestionBank({ search: search || undefined, standard: standard || undefined, page, size: 20 })
    call
      .then((r) => {
        if (cancelled) return
        if (tab === 'templates') setTemplates(r.data as GlobalTemplateSummary[])
        else setQuestions(r.data as QuestionBankEntry[])
        setPagination(r.pagination)
        setError(null)
      })
      .catch((e) => !cancelled && setError(e instanceof Error ? e.message : 'Could not search the library'))
      .finally(() => !cancelled && setLoading(false))
    return () => {
      cancelled = true
    }
  }, [tab, search, consumer, standard, page])

  async function openDetail(id: string) {
    setDetailError(null)
    try {
      setDetail(await getLibraryTemplate(id))
    } catch (e) {
      setDetail(null)
      setDetailError(e instanceof Error ? e.message : 'Could not load this template')
    }
  }

  function openImport(template: GlobalTemplateSummary | GlobalTemplateDetail) {
    setImportError(null)
    setImportMode('new')
    setImportName(template.name)
    setImportTargetId('')
    setShowImport(template)
    listProcedures({ status: 'ACTIVE', size: 100 }).then((r) => setMyProcedures(r.data)).catch(() => {})
  }

  async function onImport(e: FormEvent) {
    e.preventDefault()
    if (!showImport) return
    setImporting(true)
    setImportError(null)
    try {
      const result = await importProcedure({
        globalTemplateId: showImport.id,
        templateId: importMode === 'existing' ? importTargetId || undefined : undefined,
        name: importMode === 'new' ? importName : undefined,
      })
      navigate(`/templates/${result.id}`)
    } catch (e) {
      setImportError(e)
    } finally {
      setImporting(false)
    }
  }

  return (
    <div>
      <div className="page-head">
        <div>
          <h1>Library</h1>
          <p className="muted small">
            Sclera's shared procedures and question bank. Import a template to start from it, or browse
            the question bank to see how other procedures ask the same thing.
          </p>
        </div>
      </div>

      <div className="page-actions">
        <button
          className={`btn ${tab === 'templates' ? 'btn-primary' : ''}`}
          onClick={() => {
            setTab('templates')
            setPage(0)
          }}
        >
          Templates
        </button>
        <button
          className={`btn ${tab === 'questions' ? 'btn-primary' : ''}`}
          onClick={() => {
            setTab('questions')
            setPage(0)
          }}
        >
          Question bank
        </button>
      </div>

      <form className="page-actions" onSubmit={onSearchSubmit}>
        <input
          type="text"
          placeholder={tab === 'templates' ? 'Search templates…' : 'Search questions…'}
          value={searchInput}
          onChange={(e) => setSearchInput(e.target.value)}
        />
        {tab === 'templates' ? (
          <input
            type="text"
            placeholder="Consumer (optional)"
            value={consumer}
            onChange={(e) => {
              setConsumer(e.target.value)
              setPage(0)
            }}
          />
        ) : (
          <input
            type="text"
            placeholder="Standard (optional)"
            value={standard}
            onChange={(e) => {
              setStandard(e.target.value)
              setPage(0)
            }}
          />
        )}
        <button className="btn" type="submit">
          Search
        </button>
      </form>

      {error && <div className="alert alert-error">{error}</div>}

      {loading ? (
        <p className="muted">Loading…</p>
      ) : tab === 'templates' ? (
        templates.length === 0 ? (
          <p className="muted">No templates match.</p>
        ) : (
          <table className="table">
            <thead>
              <tr>
                <th>Name</th>
                <th>Consumer</th>
                <th>Current version</th>
                <th>Published</th>
                <th className="rt-actions">Actions</th>
              </tr>
            </thead>
            <tbody>
              {templates.map((t) => (
                <tr key={t.id} className="clickable" onClick={() => openDetail(t.id)}>
                  <td>
                    <div className="asset-name">{t.name}</div>
                    {t.description && <div className="muted small">{t.description}</div>}
                  </td>
                  <td className="muted small">{t.consumerKey ?? '—'}</td>
                  <td>{t.currentVersionNo != null ? `v${t.currentVersionNo}` : '—'}</td>
                  <td className="muted small">
                    {t.publishedAt ? new Date(t.publishedAt).toLocaleDateString() : '—'}
                  </td>
                  <td className="rt-actions">
                    {canImport && (
                      <button
                        className="btn btn-primary small"
                        onClick={(e) => {
                          e.stopPropagation()
                          openImport(t)
                        }}
                      >
                        Import
                      </button>
                    )}
                  </td>
                </tr>
              ))}
            </tbody>
          </table>
        )
      ) : questions.length === 0 ? (
        <p className="muted">No questions match.</p>
      ) : (
        <ul className="question-list">
          {questions.map((q) => (
            <li key={`${q.templateId}-${q.questionKey}`}>
              <div className="q-text">{q.text}</div>
              <div className="muted small">
                {q.sectionText && `under: ${q.sectionText} · `}
                {q.standard && `${q.standard} · `}
                <button className="btn btn-ghost small" onClick={() => openDetail(q.templateId)}>
                  View template
                </button>
                {' '}({q.templateName}, v{q.versionNo})
              </div>
            </li>
          ))}
        </ul>
      )}

      <Pager pagination={pagination} onPage={setPage} />

      {(detail || detailError) && (
        <Modal title={detail?.name ?? 'Template'} wide onClose={() => { setDetail(null); setDetailError(null) }}>
          {detailError && <div className="alert alert-error">{detailError}</div>}
          {detail && (
            <>
              <p className="muted small">
                {detail.description}
                {detail.changeNote && ` — ${detail.changeNote}`}
              </p>
              <div className="page-actions">
                {canImport && (
                  <button className="btn btn-primary" onClick={() => openImport(detail)}>
                    Import
                  </button>
                )}
              </div>
              <DefinitionView definition={detail.definition} />
            </>
          )}
        </Modal>
      )}

      {showImport && (
        <Modal title={`Import — ${showImport.name}`} onClose={() => setShowImport(null)}>
          <Refusal error={importError} />
          <form onSubmit={onImport}>
            <label className="checkbox">
              <input
                type="radio"
                name="import-mode"
                checked={importMode === 'new'}
                onChange={() => setImportMode('new')}
              />
              Create a new procedure
            </label>
            {importMode === 'new' && (
              <label>
                Name
                <input value={importName} onChange={(e) => setImportName(e.target.value)} required />
              </label>
            )}
            <label className="checkbox">
              <input
                type="radio"
                name="import-mode"
                checked={importMode === 'existing'}
                onChange={() => setImportMode('existing')}
              />
              Update an existing procedure
            </label>
            {importMode === 'existing' && (
              <label>
                Procedure
                <select
                  value={importTargetId}
                  onChange={(e) => setImportTargetId(e.target.value)}
                  required
                >
                  <option value="">Select…</option>
                  {myProcedures.map((p) => (
                    <option key={p.id} value={p.id}>
                      {p.name}
                    </option>
                  ))}
                </select>
              </label>
            )}
            <div className="page-actions">
              <button className="btn btn-primary" type="submit" disabled={importing}>
                {importing ? 'Importing…' : 'Import'}
              </button>
            </div>
          </form>
        </Modal>
      )}
    </div>
  )
}

import { useEffect, useState, type FormEvent } from 'react'
import { useNavigate, useParams } from 'react-router-dom'
import {
  CATEGORIES,
  createInspectionConfig,
  FREQUENCIES,
  frequencyLabel,
  getInspectionConfig,
  PRIORITIES,
  updateInspectionConfig,
  WEEKDAYS,
  type Frequency,
  type InspectionConfigRequest,
  type Priority,
  type Weekday,
} from '../api/inspectionConfigs'

interface Toggle {
  key: 'bypassScan' | 'enableCheckInOut' | 'enablePoints' | 'mergedView'
  label: string
  help: string
}

const TOGGLES: Toggle[] = [
  { key: 'bypassScan', label: 'Bypass Scan', help: 'On: direct checklist access. Off: locked until scanned.' },
  { key: 'enableCheckInOut', label: 'Check-in / Check-out', help: 'Measures fill duration from check-in to check-out.' },
  { key: 'enablePoints', label: 'Points', help: 'Shows passed-question data and awards points.' },
  { key: 'mergedView', label: 'Merged View', help: 'All records of the same asset shown in one view.' },
]

export function InspectionConfigFormPage() {
  const { id } = useParams<{ id: string }>()
  const navigate = useNavigate()
  const editing = Boolean(id)

  const [name, setName] = useState('')
  const [code, setCode] = useState('')
  const [description, setDescription] = useState('')
  const [assigneeEmail, setAssigneeEmail] = useState('')
  const [secondaryAssigneeEmail, setSecondaryAssigneeEmail] = useState('')
  const [category, setCategory] = useState('Generic')
  const [priority, setPriority] = useState<Priority | ''>('')
  const [frequency, setFrequency] = useState<Frequency | ''>('')
  const [scheduleDays, setScheduleDays] = useState<Set<Weekday>>(new Set(WEEKDAYS))
  const [toggles, setToggles] = useState({
    bypassScan: false,
    enableCheckInOut: false,
    enablePoints: false,
    mergedView: false,
  })

  const [error, setError] = useState<string | null>(null)
  const [busy, setBusy] = useState(false)
  const [loaded, setLoaded] = useState(!editing)

  useEffect(() => {
    if (!id) return
    getInspectionConfig(id)
      .then((c) => {
        setName(c.name)
        setCode(c.code ?? '')
        setDescription(c.description ?? '')
        setAssigneeEmail(c.assigneeEmail)
        setSecondaryAssigneeEmail(c.secondaryAssigneeEmail ?? '')
        setCategory(c.category)
        setPriority(c.priority ?? '')
        setFrequency(c.frequency)
        setScheduleDays(new Set(c.scheduleDays))
        setToggles({
          bypassScan: c.bypassScan,
          enableCheckInOut: c.enableCheckInOut,
          enablePoints: c.enablePoints,
          mergedView: c.mergedView,
        })
        setLoaded(true)
      })
      .catch((e) => setError(e.message))
  }, [id])

  function toggleDay(day: Weekday) {
    setScheduleDays((prev) => {
      const next = new Set(prev)
      if (next.has(day)) next.delete(day)
      else next.add(day)
      return next
    })
  }

  async function onSubmit(e: FormEvent) {
    e.preventDefault()
    if (scheduleDays.size === 0) {
      setError('Select at least one schedule day')
      return
    }
    if (!frequency) {
      setError('Frequency is required')
      return
    }
    const body: InspectionConfigRequest = {
      name: name.trim(),
      code: code.trim() || undefined,
      description: description.trim() || undefined,
      assigneeEmail: assigneeEmail.trim(),
      secondaryAssigneeEmail: secondaryAssigneeEmail.trim() || undefined,
      category,
      priority: priority || undefined,
      frequency,
      scheduleDays: WEEKDAYS.filter((d) => scheduleDays.has(d)),
      ...toggles,
    }
    setBusy(true)
    setError(null)
    try {
      const saved = id ? await updateInspectionConfig(id, body) : await createInspectionConfig(body)
      navigate(`/inspection-configs/${saved.id}`)
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
        <div>
          <h1>{editing ? 'Edit inspection' : 'New inspection'}</h1>
          <p className="muted">Configure the inspection, then tag procedures &amp; assets (next step).</p>
        </div>
        <div className="page-actions">
          <button type="button" className="btn" onClick={() => navigate(-1)}>
            Cancel
          </button>
          <button type="submit" className="btn btn-primary" disabled={busy}>
            {busy ? 'Saving…' : editing ? 'Save' : 'Finish'}
          </button>
        </div>
      </div>

      {error && <div className="alert alert-error">{error}</div>}

      <div className="card">
        <h2>Details</h2>
        <div className="form-grid">
          <label className="grow">
            Name *
            <input value={name} onChange={(e) => setName(e.target.value)} required maxLength={200} autoFocus />
          </label>
          <label>
            Code
            <input value={code} onChange={(e) => setCode(e.target.value)} maxLength={50} placeholder="unique" />
          </label>
        </div>
        <label>
          Description
          <textarea value={description} onChange={(e) => setDescription(e.target.value)} rows={2} maxLength={2000} />
        </label>
      </div>

      <div className="card">
        <h2>Assignees</h2>
        <div className="form-grid">
          <label className="grow">
            Assignee email *
            <input
              type="email"
              value={assigneeEmail}
              onChange={(e) => setAssigneeEmail(e.target.value)}
              required
              maxLength={255}
            />
          </label>
          <label className="grow">
            Secondary assignee (optional)
            <input
              type="email"
              value={secondaryAssigneeEmail}
              onChange={(e) => setSecondaryAssigneeEmail(e.target.value)}
              maxLength={255}
            />
          </label>
        </div>
      </div>

      <div className="card">
        <h2>Classification &amp; schedule</h2>
        <div className="form-grid">
          <label>
            Category
            <select value={category} onChange={(e) => setCategory(e.target.value)}>
              {CATEGORIES.map((c) => (
                <option key={c} value={c}>
                  {c}
                </option>
              ))}
            </select>
          </label>
          <label>
            Priority
            <select value={priority} onChange={(e) => setPriority(e.target.value as Priority | '')}>
              <option value="">Select</option>
              {PRIORITIES.map((p) => (
                <option key={p} value={p}>
                  {p.charAt(0) + p.slice(1).toLowerCase()}
                </option>
              ))}
            </select>
          </label>
          <label>
            Frequency *
            <select value={frequency} onChange={(e) => setFrequency(e.target.value as Frequency | '')} required>
              <option value="">Select…</option>
              {FREQUENCIES.map((f) => (
                <option key={f} value={f}>
                  {frequencyLabel(f)}
                </option>
              ))}
            </select>
          </label>
        </div>
        <div className="field-label">Schedule days *</div>
        <div className="day-picker">
          {WEEKDAYS.map((d) => (
            <button
              type="button"
              key={d}
              className={`day-chip ${scheduleDays.has(d) ? 'day-on' : ''}`}
              onClick={() => toggleDay(d)}
            >
              {d}
            </button>
          ))}
        </div>
      </div>

      <div className="card">
        <h2>Options</h2>
        <div className="toggle-list">
          {TOGGLES.map((t) => (
            <label key={t.key} className="toggle-row">
              <input
                type="checkbox"
                className="switch"
                checked={toggles[t.key]}
                onChange={(e) => setToggles((prev) => ({ ...prev, [t.key]: e.target.checked }))}
              />
              <span>
                <span className="toggle-title">{t.label}</span>
                <span className="toggle-help">{t.help}</span>
              </span>
            </label>
          ))}
        </div>
      </div>
    </form>
  )
}

import { useNavigate, useParams } from 'react-router-dom'
import { ChecklistFillPanel } from '../components/ChecklistFillPanel'

/** Standalone route wrapper — the actual UI lives in ChecklistFillPanel. */
export function ChecklistFillPage() {
  const { id } = useParams<{ id: string }>()
  const navigate = useNavigate()

  if (!id) return null

  return (
    <div>
      <div className="page-head">
        <span />
        <button className="btn" onClick={() => navigate(-1)}>
          ← Back
        </button>
      </div>
      <ChecklistFillPanel checklistId={id} onExit={() => navigate(-1)} />
    </div>
  )
}

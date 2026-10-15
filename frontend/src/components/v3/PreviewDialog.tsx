import { useState } from 'react'
import type { DocumentRef, ResultType, Threshold } from '../../api/types'
import { EvaluationPreview } from '../EvaluationPreview'
import { toDocument, type ItemDraft } from '../itemTree'
import { ReadOnlyChecklist } from './ReadOnlyChecklist'

type Mode = 'sim' | 'list'

/**
 * The checklist's Preview, in the Sclera 2.0 design (docs/procedures-v3.html):
 * "Try it as an inspector" (answer live, watch the score) beside "Full
 * checklist" (every question, read-only, regardless of what the simulator
 * currently shows). Same `.scrim`/`.dialog` idiom BuilderSidePanel's own
 * preview dialogs already use.
 *
 * Opened only once the draft is saved — `versionNo` names a stored version,
 * and a follow-up's trigger only resolves once its parent's options have
 * real server keys, so the caller is expected to have saved first.
 */
export function PreviewDialog({
  procedureName,
  templateId,
  versionNo,
  items,
  schema,
  thresholds,
  documents,
  resultTypes,
  savedAt,
  onClose,
}: {
  procedureName: string
  templateId: string
  versionNo: number
  items: ItemDraft[]
  schema: number
  thresholds: Threshold[]
  documents: DocumentRef[]
  resultTypes: ResultType[]
  savedAt: Date | null
  onClose: () => void
}) {
  const [mode, setMode] = useState<Mode>('sim')

  return (
    <div
      className="scrim"
      onMouseDown={(e) => {
        if (e.target === e.currentTarget) onClose()
      }}
    >
      <div
        className="dialog"
        role="dialog"
        aria-modal="true"
        aria-labelledby="preview-t"
        style={{ width: 980, maxHeight: '90%' }}
      >
        <div className="dhead">
          <h2 id="preview-t" style={{ fontSize: 20, flex: 1 }}>
            Preview
          </h2>
          <div className="seg-tabs" role="tablist">
            {([
              ['sim', 'Try it as an inspector'],
              ['list', 'Full checklist'],
            ] as [Mode, string][]).map(([k, label]) => (
              <button key={k} type="button" role="tab" aria-selected={mode === k} aria-pressed={mode === k} onClick={() => setMode(k)}>
                {label}
              </button>
            ))}
          </div>
          <button className="ibtn xl" aria-label="Close preview" onClick={onClose}>
            ✕
          </button>
        </div>
        <div className="dbody">
          {mode === 'sim' ? (
            <EvaluationPreview
              procedureName={procedureName}
              templateId={templateId}
              versionNo={versionNo}
              items={items}
              resultTypes={resultTypes}
              savedAt={savedAt}
            />
          ) : (
            <div style={{ maxWidth: 760, margin: '0 auto' }}>
              <ReadOnlyChecklist items={toDocument(schema, items, thresholds, documents).items} />
            </div>
          )}
        </div>
      </div>
    </div>
  )
}

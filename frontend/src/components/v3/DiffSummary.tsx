import type { DiffKind, ProcedureDiff } from '../../api/types'

const KIND_TAG: Record<DiffKind, string> = {
  ADDED: 'pass',
  MODIFIED: 'warn',
  REMOVED: 'fail',
}

/** 'workOrder' becomes 'work order', 'alertProfile' 'alert profile'. */
function readable(field: string): string {
  return field.replace(/([A-Z])/g, ' $1').toLowerCase()
}

/**
 * What changed between the version before this one and this one — shown
 * beside whichever version is open, matched on the document's stable keys so
 * a reworded question reads as one modification rather than a removal and an
 * addition the reader has to pair up.
 */
export function DiffSummary({ result }: { result: ProcedureDiff }) {
  const { diff, fromVersionNo, toVersionNo } = result

  if (diff.identical) {
    return <p className="small muted">No change from v{fromVersionNo}.</p>
  }

  return (
    <div style={{ display: 'flex', flexDirection: 'column', gap: 8 }}>
      <p className="small muted">
        Changed from v{fromVersionNo} to v{toVersionNo}:
      </p>
      {diff.orderChanged && <div className="alert-amber">The items were reordered.</div>}
      {diff.documentsChanged && (
        <div className="alert-amber">The documents this procedure cites changed.</div>
      )}
      {diff.items.length > 0 && (
        <div style={{ display: 'flex', flexDirection: 'column', gap: 8 }}>
          {diff.items.map((item) => (
            <div key={item.key} className="row" style={{ gap: 10, alignItems: 'flex-start' }}>
              <span className={`tag ${KIND_TAG[item.kind]}`} style={{ flexShrink: 0 }}>
                {item.kind}
              </span>
              <span style={{ flex: 1, minWidth: 0 }}>
                <span className="ellipsis" style={{ display: 'block' }}>
                  {item.text}
                </span>
                <span className="small muted">
                  {item.parentKey ? `under ${item.parentKey}` : 'top level'}
                  {item.changedFields.length > 0 &&
                    ` · changed: ${item.changedFields.map(readable).join(', ')}`}
                </span>
              </span>
            </div>
          ))}
        </div>
      )}
    </div>
  )
}

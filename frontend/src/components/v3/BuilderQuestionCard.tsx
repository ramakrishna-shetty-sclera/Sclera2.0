import { useState } from 'react'
import { ITEM_TYPES, hasFixedOptions, isChoice, typeLabel } from '../../api/document'
import type { ItemType, ResultType } from '../../api/types'
import { triggerOptions, type ItemDraft, type OptionDraft } from '../itemTree'
import { resultDot } from './ReadOnlyChecklist'

/**
 * Everything the builder canvas needs to render one item, passed once and
 * threaded through the recursion. The handlers are the editor page's existing
 * tree operations — this file is presentation for docs/procedures-v3.html's
 * question cards, not new state.
 */
export interface BuilderCtx {
  numbers: Map<ItemDraft, string>
  resultTypes: ResultType[]
  active: string | null
  issues: Map<string, string[]>
  savingFor: string | null
  showErrors: boolean
  onSelect: (uid: string | null) => void
  onPatch: (uid: string, patch: Partial<ItemDraft>) => void
  onRetype: (uid: string, type: ItemType) => void
  onRemove: (uid: string) => void
  onMove: (uid: string, delta: number) => void
  /** Adds a follow-up to `uid`, shown when the answer with `whenKey` is picked. */
  onAddFollow: (uid: string, whenKey?: string) => void
  onAddOption: (uid: string) => void
  onPatchOption: (uid: string, option: string, patch: Partial<OptionDraft>) => void
  onRemoveOption: (uid: string, option: string) => void
}

export function BuilderQuestionCard({
  item,
  parent,
  index,
  siblings,
  depth,
  ctx,
}: {
  item: ItemDraft
  parent: ItemDraft | null
  index: number
  siblings: number
  depth: number
  ctx: BuilderCtx
}) {
  if (item.type === 'SECTION') {
    return <SectionRow item={item} index={index} siblings={siblings} ctx={ctx} />
  }
  const active = ctx.active === item.uid
  return (
    <div>
      {parent && (
        <div className="when">
          <span aria-hidden="true" style={{ color: 'var(--action)' }}>
            ↳
          </span>
          If the answer to {parentNumber(ctx, item)} is
          <select
            className="select"
            aria-label="Show this follow-up when the answer is"
            value={item.when}
            onChange={(e) => ctx.onPatch(item.uid, { when: e.target.value })}
          >
            <option value="">— pick an answer —</option>
            {triggerOptions(parent).map((o) => (
              <option key={o.uid} value={o.key}>
                {o.label || 'Untitled answer'}
              </option>
            ))}
          </select>
          <span>then ask:</span>
        </div>
      )}
      <div id={`card-${item.uid}`} className={`qcard${active ? ' cur' : ''}`}>
        {active ? (
          <Expanded item={item} index={index} siblings={siblings} depth={depth} ctx={ctx} />
        ) : (
          <Summary item={item} ctx={ctx} />
        )}
      </div>
      {item.follow.length > 0 && (
        <div className="children">
          {item.follow.map((child, childIndex) => (
            <BuilderQuestionCard
              key={child.uid}
              item={child}
              parent={item}
              index={childIndex}
              siblings={item.follow.length}
              depth={depth + 1}
              ctx={ctx}
            />
          ))}
        </div>
      )}
    </div>
  )
}

function parentNumber(ctx: BuilderCtx, item: ItemDraft): string {
  const number = ctx.numbers.get(item)
  return number ? number.split('.').slice(0, -1).join('.') : 'the question above'
}

function SectionRow({
  item,
  index,
  siblings,
  ctx,
}: {
  item: ItemDraft
  index: number
  siblings: number
  ctx: BuilderCtx
}) {
  return (
    <div className="section-card" id={`card-${item.uid}`}>
      <span aria-hidden="true" className="muted">
        §
      </span>
      <input
        aria-label="Section title"
        value={item.text}
        placeholder="Section title"
        maxLength={1000}
        onChange={(e) => ctx.onPatch(item.uid, { text: e.target.value })}
      />
      <button className="ibtn" aria-label="Move section up" disabled={index === 0} onClick={() => ctx.onMove(item.uid, -1)}>
        ↑
      </button>
      <button
        className="ibtn"
        aria-label="Move section down"
        disabled={index === siblings - 1}
        onClick={() => ctx.onMove(item.uid, 1)}
      >
        ↓
      </button>
      <button className="ibtn danger" aria-label="Delete section" onClick={() => ctx.onRemove(item.uid)}>
        🗑
      </button>
    </div>
  )
}

function Summary({ item, ctx }: { item: ItemDraft; ctx: BuilderCtx }) {
  const number = ctx.numbers.get(item)
  const issues = ctx.issues.get(item.uid) ?? []
  return (
    <button type="button" className="qsum" onClick={() => ctx.onSelect(item.uid)} aria-label={`Edit question ${number}`}>
      <span className="qnum">{number}</span>
      <span style={{ flex: 1, minWidth: 0 }}>
        <span className={`qtext${item.text ? '' : ' empty'}`}>
          {item.text || 'Untitled question'}
          {item.required && <span className="req"> *</span>}
        </span>
        <span className="opts-line">
          {item.options.map((o) => (
            <span key={o.uid} className="ochip">
              <span className="dot" style={{ background: resultDot(o.result || null) }} />
              {o.label || '—'}
            </span>
          ))}
          {!isChoice(item.type) && (
            <span className="ochip muted">
              {typeLabel(item.type)}
              {item.unit ? ` (${item.unit})` : ''}
            </span>
          )}
          {item.workOrder && <span className="tag warn">Work order on fail</span>}
          {item.source && item.source !== 'MANUAL' && (
            <span className="tag line">
              {item.source === 'DOCUMENT' ? 'From document' : item.source === 'SUGGESTED' ? 'Suggested' : 'From template'}
              {item.standard ? ` · ${item.standard}` : ''}
            </span>
          )}
          {issues.length > 0 && <span className="tag fail">{issues[0]}</span>}
        </span>
      </span>
      <span className="small muted" style={{ flexShrink: 0 }}>
        {typeLabel(item.type)}
      </span>
    </button>
  )
}

/**
 * What an answer means. Organizations that only have the two system result
 * types get the prototype's Pass/Fail segmented control; a richer vocabulary
 * (or a mapping to a retired type, kept visible rather than dropped) falls
 * back to a picker that can say everything.
 */
function ResultControl({
  option,
  resultTypes,
  onChange,
}: {
  option: OptionDraft
  resultTypes: ResultType[]
  onChange: (result: string) => void
}) {
  const stale = option.result !== '' && !resultTypes.some((r) => r.key === option.result)
  const onlySystem =
    !stale &&
    resultTypes.length === 2 &&
    resultTypes.some((r) => r.key === 'PASS') &&
    resultTypes.some((r) => r.key === 'FAIL')

  if (onlySystem) {
    return (
      <div className="seg" role="group" aria-label={`Result when the answer is ${option.label || 'this'}`}>
        <button
          type="button"
          className="pass"
          aria-pressed={option.result === 'PASS'}
          onClick={() => onChange(option.result === 'PASS' ? '' : 'PASS')}
        >
          ✓ Pass
        </button>
        <button
          type="button"
          className="fail"
          aria-pressed={option.result === 'FAIL'}
          onClick={() => onChange(option.result === 'FAIL' ? '' : 'FAIL')}
        >
          ✕ Fail
        </button>
      </div>
    )
  }
  return (
    <select
      className={`select sm${stale ? ' invalid' : ''}`}
      style={{ width: 150, flexShrink: 0 }}
      aria-label={`Result when the answer is ${option.label || 'this'}`}
      value={option.result}
      onChange={(e) => onChange(e.target.value)}
    >
      <option value="">Decides nothing</option>
      {resultTypes.map((r) => (
        <option key={r.id} value={r.key}>
          {r.name}
        </option>
      ))}
      {stale && <option value={option.result}>{option.result} — not active</option>}
    </select>
  )
}

function Expanded({
  item,
  index,
  siblings,
  depth,
  ctx,
}: {
  item: ItemDraft
  index: number
  siblings: number
  depth: number
  ctx: BuilderCtx
}) {
  const number = ctx.numbers.get(item)
  const issues = ctx.issues.get(item.uid) ?? []
  const choice = isChoice(item.type)
  const fixed = hasFixedOptions(item.type)
  const saving = ctx.savingFor === item.uid
  const [showHelp, setShowHelp] = useState(Boolean(item.help))
  // A follow-up cannot be a heading, and follow-ups stop at the third level —
  // the same ceiling the prototype draws.
  const types = depth > 0 ? ITEM_TYPES.filter((t) => t !== 'SECTION') : ITEM_TYPES
  const canNest = depth < 2

  const followCount = (optionKey: string | undefined) =>
    optionKey ? item.follow.filter((c) => c.when === optionKey).length : 0

  return (
    <div className="qedit">
      <div className="qrow">
        <span className="qnum" style={{ marginTop: 14 }}>
          {number}
        </span>
        <div className="field" style={{ flex: 1 }}>
          <label htmlFor={`qt-${item.uid}`}>
            Question <span className="req">*</span>
          </label>
          <input
            id={`qt-${item.uid}`}
            className={`input${!item.text.trim() && ctx.showErrors ? ' invalid' : ''}`}
            style={{ fontSize: 15 }}
            value={item.text}
            maxLength={1000}
            placeholder="Type the question the inspector will answer"
            onChange={(e) => ctx.onPatch(item.uid, { text: e.target.value })}
          />
        </div>
        <div className="field" style={{ width: 170, flexShrink: 0 }}>
          <label htmlFor={`qty-${item.uid}`}>Answer type</label>
          <select
            id={`qty-${item.uid}`}
            className="select"
            value={item.type}
            onChange={(e) => ctx.onRetype(item.uid, e.target.value as ItemType)}
          >
            {types.map((t) => (
              <option key={t} value={t}>
                {typeLabel(t)}
              </option>
            ))}
          </select>
        </div>
      </div>

      {!showHelp && (
        <div className="indent" style={{ marginTop: -6 }}>
          <button type="button" className="link" onClick={() => setShowHelp(true)}>
            + Add helper text
          </button>
        </div>
      )}
      {showHelp && (
        <div className="field indent">
          <label htmlFor={`qh-${item.uid}`}>Helper text for the inspector</label>
          <input
            id={`qh-${item.uid}`}
            className="input soft sm"
            value={item.help}
            maxLength={1000}
            placeholder="e.g. Tap the gauge lightly before reading it"
            onChange={(e) => ctx.onPatch(item.uid, { help: e.target.value })}
          />
        </div>
      )}

      {choice && (
        <div className="indent" style={{ display: 'flex', flexDirection: 'column', gap: 8 }}>
          <div className="row small muted" style={{ gap: 10 }}>
            <span style={{ flex: 1, paddingLeft: 26 }}>Answers</span>
            <span>Result</span>
          </div>
          {item.options.map((option, optionIndex) => (
            <div key={option.uid} className="optrow">
              <span className={`glyph${item.type === 'CHECKBOX' ? ' sq' : ''}`} />
              <input
                aria-label={`Answer ${optionIndex + 1}`}
                className="input soft sm"
                style={{ flex: 1 }}
                value={option.label}
                maxLength={200}
                readOnly={fixed}
                title={fixed ? `${typeLabel(item.type)} comes with its answers` : undefined}
                onChange={(e) => ctx.onPatchOption(item.uid, option.uid, { label: e.target.value })}
              />
              <ResultControl
                option={option}
                resultTypes={ctx.resultTypes}
                onChange={(result) => ctx.onPatchOption(item.uid, option.uid, { result })}
              />
              {canNest ? (
                <button
                  type="button"
                  className={`fu-btn${followCount(option.key) ? ' has' : ''}`}
                  disabled={saving}
                  onClick={() => ctx.onAddFollow(item.uid, option.key)}
                  aria-label={`Add a follow-up when the answer is ${option.label || 'this answer'}`}
                >
                  {saving ? 'Saving…' : '+ Follow-up'}
                  {followCount(option.key) > 0 && (
                    <span
                      className="tag"
                      style={{ height: 16, padding: '0 5px', fontSize: 10, background: 'var(--action)', color: 'var(--on-action)' }}
                    >
                      {followCount(option.key)}
                    </span>
                  )}
                </button>
              ) : null}
              {!fixed && (
                <button
                  type="button"
                  className="ibtn danger"
                  aria-label={`Remove answer ${option.label || optionIndex + 1}`}
                  onClick={() => ctx.onRemoveOption(item.uid, option.uid)}
                >
                  🗑
                </button>
              )}
            </div>
          ))}
          {!fixed && (
            <button type="button" className="link" style={{ alignSelf: 'flex-start', marginLeft: 26 }} onClick={() => ctx.onAddOption(item.uid)}>
              + Add answer
            </button>
          )}
          {!canNest && <p className="hint">Follow-ups go three levels deep, and this question is on the third.</p>}
          {canNest && triggerOptions(item).length === 0 && (
            <p className="hint">
              A follow-up points at an answer by its server-minted key, so adding the first one saves the draft.
            </p>
          )}
        </div>
      )}

      {item.type === 'INTEGER' && (
        <div className="indent row" style={{ alignItems: 'flex-end' }}>
          {(
            [
              ['min', 'Min'],
              ['max', 'Max'],
              ['unit', 'Unit'],
            ] as ['min' | 'max' | 'unit', string][]
          ).map(([k, label]) => (
            <div key={k} className="field" style={{ width: 120 }}>
              <label htmlFor={`${k}-${item.uid}`}>{label}</label>
              <input
                id={`${k}-${item.uid}`}
                className="input sm"
                type={k === 'unit' ? 'text' : 'number'}
                value={item[k]}
                maxLength={k === 'unit' ? 20 : undefined}
                placeholder={k === 'unit' ? 'kg, psi, °C' : '—'}
                onChange={(e) => ctx.onPatch(item.uid, { [k]: e.target.value })}
              />
            </div>
          ))}
          <span className="hint" style={{ paddingBottom: 10 }}>
            Min and Max bound what the inspector may type.
          </span>
        </div>
      )}

      {!choice && item.type !== 'INTEGER' && item.type !== 'TEXT' && (
        <div
          className="indent"
          style={{
            height: 44,
            border: '1px dashed var(--dim)',
            borderRadius: 4,
            display: 'flex',
            alignItems: 'center',
            justifyContent: 'center',
            color: 'var(--muted)',
          }}
        >
          Inspector provides {typeLabel(item.type).toLowerCase()}
        </div>
      )}

      {issues.length > 0 && ctx.showErrors && (
        <div className="indent tag fail" style={{ height: 'auto', padding: '6px 10px', alignSelf: 'flex-start' }}>
          {issues.join(' · ')}
        </div>
      )}

      <div className="divider indent" />
      <div className="indent qfoot">
        <Switch on={item.required} label="Required" onChange={(v) => ctx.onPatch(item.uid, { required: v })} />
        {choice && (
          <Switch on={item.workOrder} label="Work order on fail" onChange={(v) => ctx.onPatch(item.uid, { workOrder: v })} />
        )}
        <div className="row" style={{ marginLeft: 'auto', gap: 2 }}>
          <button className="ibtn" type="button" aria-label="Move up" disabled={index === 0} onClick={() => ctx.onMove(item.uid, -1)}>
            ↑
          </button>
          <button
            className="ibtn"
            type="button"
            aria-label="Move down"
            disabled={index === siblings - 1}
            onClick={() => ctx.onMove(item.uid, 1)}
          >
            ↓
          </button>
          <button className="ibtn danger" type="button" aria-label="Delete question" onClick={() => ctx.onRemove(item.uid)}>
            🗑
          </button>
          <button className="btn btn-quiet" type="button" style={{ marginLeft: 8 }} onClick={() => ctx.onSelect(null)}>
            Done
          </button>
        </div>
      </div>
      {choice && item.workOrder && (
        <div className="field indent" style={{ maxWidth: 420 }}>
          <label htmlFor={`ap-${item.uid}`}>Alert profile</label>
          <input
            id={`ap-${item.uid}`}
            className="input sm"
            value={item.alertProfile}
            maxLength={50}
            placeholder="Who is told, and how fast (recorded, not resolved)"
            onChange={(e) => ctx.onPatch(item.uid, { alertProfile: e.target.value })}
          />
        </div>
      )}
    </div>
  )
}

export function Switch({ on, label, onChange }: { on: boolean; label: string; onChange: (v: boolean) => void }) {
  return (
    <button type="button" className="sw" role="switch" aria-checked={on} onClick={() => onChange(!on)}>
      <span className="track">
        <span className="knob" />
      </span>
      {label}
    </button>
  )
}

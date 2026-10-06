import { ITEM_TYPES, hasFixedOptions, isChoice, typeLabel } from '../api/document'
import type { ItemType, ResultType } from '../api/types'
import { triggerOptions, type ItemDraft, type OptionDraft } from './itemTree'

export interface ItemEditorProps {
  item: ItemDraft
  /** The question this one hangs off, or null at the top level. */
  parent: ItemDraft | null
  index: number
  siblings: number
  /** '1', '2.1', … per item. Sections are absent: a heading carries no number. */
  numbers: Map<ItemDraft, string>
  /** The organization's active result types, most severe first. */
  resultTypes: ResultType[]
  /** True while a click is saving the draft to mint answer keys. */
  savingFor: string | null
  onPatch: (uid: string, patch: Partial<ItemDraft>) => void
  onRetype: (uid: string, type: ItemType) => void
  onRemove: (uid: string) => void
  onMove: (uid: string, delta: number) => void
  onAddFollow: (uid: string) => void
  onAddOption: (uid: string) => void
  onPatchOption: (uid: string, option: string, patch: Partial<OptionDraft>) => void
  onRemoveOption: (uid: string, option: string) => void
}

/**
 * What an answer means, picked from the organization's own result types.
 *
 * A mapping the organization no longer has is kept and marked rather than
 * silently dropped: it is a real publish blocker, and clearing it here would
 * hide the problem instead of showing it.
 */
function ResultPicker({
  option,
  resultTypes,
  onChange,
}: {
  option: OptionDraft
  resultTypes: ResultType[]
  onChange: (result: string) => void
}) {
  const stale = option.result !== '' && !resultTypes.some((r) => r.key === option.result)
  const swatch = resultTypes.find((r) => r.key === option.result)?.color

  return (
    <>
      {swatch && <span className="rt-swatch" style={{ background: swatch }} />}
      <select
        className={stale ? 'field-stale' : undefined}
        value={option.result}
        onChange={(e) => onChange(e.target.value)}
      >
        <option value="">— decides nothing —</option>
        {resultTypes.map((r) => (
          <option key={r.id} value={r.key}>
            {r.name}
          </option>
        ))}
        {stale && <option value={option.result}>{option.result} — not an active result type</option>}
      </select>
    </>
  )
}

/**
 * One item and everything hanging off it.
 *
 * Recursive, because follow-ups nest to any depth and a follow-up is an
 * ordinary question in every other respect — the only difference is that it
 * names the answer that shows it.
 */
export function ItemEditor(props: ItemEditorProps) {
  const {
    item,
    parent,
    index,
    siblings,
    numbers,
    resultTypes,
    savingFor,
    onPatch,
    onRetype,
    onRemove,
    onMove,
    onAddFollow,
    onAddOption,
    onPatchOption,
    onRemoveOption,
  } = props

  const section = item.type === 'SECTION'
  const choice = isChoice(item.type)
  const fixed = hasFixedOptions(item.type)
  const triggers = triggerOptions(item)
  // A follow-up cannot be a heading: a section is never answered, so it can
  // never be conditional on an answer.
  const types = parent ? ITEM_TYPES.filter((t) => t !== 'SECTION') : ITEM_TYPES

  // A section is a heading and carries no number; everything else is numbered
  // by the document, not by its position in this list.
  const label = section ? 'Section' : parent ? 'Follow-up' : 'Question'
  const number = numbers.get(item)
  const saving = savingFor === item.uid

  return (
    <div className={`question-editor${section ? ' item-section' : ''}`}>
      <div className="form-grid">
        <label className="grow">
          {label}
          {number && ` ${number}`} *
          <input
            value={item.text}
            onChange={(e) => onPatch(item.uid, { text: e.target.value })}
            required
            maxLength={1000}
            placeholder={section ? 'Fire safety' : 'Is the fire exit clear?'}
          />
        </label>
        <label>
          Type
          <select value={item.type} onChange={(e) => onRetype(item.uid, e.target.value as ItemType)}>
            {types.map((t) => (
              <option key={t} value={t}>
                {typeLabel(t)}
              </option>
            ))}
          </select>
        </label>
      </div>

      {parent && (
        <label>
          Shown when the answer is
          <select value={item.when} onChange={(e) => onPatch(item.uid, { when: e.target.value })}>
            <option value="">— pick an answer —</option>
            {triggerOptions(parent).map((option) => (
              <option key={option.uid} value={option.key}>
                {option.label}
              </option>
            ))}
          </select>
        </label>
      )}

      <div className="form-grid">
        <label className="grow">
          Help text
          <input
            value={item.help}
            onChange={(e) => onPatch(item.uid, { help: e.target.value })}
            maxLength={1000}
            placeholder={section ? '' : 'Shown under the question while answering'}
          />
        </label>
        {!section && (
          <label className="checkbox">
            <input
              type="checkbox"
              checked={item.required}
              onChange={(e) => onPatch(item.uid, { required: e.target.checked })}
            />
            Required
          </label>
        )}
      </div>

      {choice && (
        <div className="answers">
          <div className="field-label">Answers</div>
          {item.options.map((option, optionIndex) => (
            <div className="answer-row" key={option.uid}>
              <input
                value={option.label}
                onChange={(e) => onPatchOption(item.uid, option.uid, { label: e.target.value })}
                maxLength={200}
                placeholder={`Option ${optionIndex + 1}`}
                readOnly={fixed}
                title={fixed ? `${typeLabel(item.type)} comes with its answers` : undefined}
              />
              <ResultPicker
                option={option}
                resultTypes={resultTypes}
                onChange={(result) => onPatchOption(item.uid, option.uid, { result })}
              />
              {option.key && <span className="rt-key-inline">{option.key}</span>}
              {!fixed && (
                <button
                  type="button"
                  className="btn btn-ghost small"
                  onClick={() => onRemoveOption(item.uid, option.uid)}
                >
                  Remove
                </button>
              )}
            </div>
          ))}
          {!fixed && (
            <button
              type="button"
              className="btn btn-ghost small"
              onClick={() => onAddOption(item.uid)}
            >
              + Add answer
            </button>
          )}
        </div>
      )}

      {item.type === 'INTEGER' && (
        <div className="form-grid">
          <label>
            Unit
            <input
              value={item.unit}
              onChange={(e) => onPatch(item.uid, { unit: e.target.value })}
              maxLength={20}
              placeholder="psi"
            />
          </label>
          <label>
            Minimum
            <input
              type="number"
              value={item.min}
              onChange={(e) => onPatch(item.uid, { min: e.target.value })}
            />
          </label>
          <label>
            Maximum
            <input
              type="number"
              value={item.max}
              onChange={(e) => onPatch(item.uid, { max: e.target.value })}
            />
          </label>
        </div>
      )}

      {choice && (
        <div className="form-grid">
          <label className="checkbox">
            <input
              type="checkbox"
              checked={item.workOrder}
              onChange={(e) => onPatch(item.uid, { workOrder: e.target.checked })}
            />
            Raise a work order when this fails
          </label>
          {item.workOrder && (
            <label className="grow">
              Alert profile
              <input
                value={item.alertProfile}
                onChange={(e) => onPatch(item.uid, { alertProfile: e.target.value })}
                maxLength={50}
                placeholder="Who is told, and how fast"
              />
            </label>
          )}
        </div>
      )}

      <div className="rt-actions">
        {item.key && <span className="rt-key-inline">{item.key}</span>}
        <button
          type="button"
          className="btn btn-ghost small"
          onClick={() => onMove(item.uid, -1)}
          disabled={index === 0}
          title="Move up"
        >
          ▲
        </button>
        <button
          type="button"
          className="btn btn-ghost small"
          onClick={() => onMove(item.uid, 1)}
          disabled={index === siblings - 1}
          title="Move down"
        >
          ▼
        </button>
        {choice && (
          <button
            type="button"
            className="btn btn-ghost small"
            onClick={() => onAddFollow(item.uid)}
            disabled={saving}
          >
            {saving ? 'Saving…' : '+ Follow-up'}
          </button>
        )}
        <button type="button" className="btn btn-ghost small" onClick={() => onRemove(item.uid)}>
          Remove
        </button>
      </div>

      {choice && triggers.length === 0 && (
        <p className="field-hint">
          A follow-up points at an answer by the key the server mints, and these answers have none
          yet — so adding one saves the draft first.
        </p>
      )}

      {item.workOrder && (
        <p className="field-hint">
          Work orders are raised during an inspection and carried out elsewhere. The alert profile is
          recorded, not resolved — profiles are a later feature.
        </p>
      )}

      {item.follow.length > 0 && (
        <div className="doc-follow">
          {item.follow.map((child, childIndex) => (
            <ItemEditor
              {...props}
              key={child.uid}
              item={child}
              parent={item}
              index={childIndex}
              siblings={item.follow.length}
            />
          ))}
        </div>
      )}
    </div>
  )
}

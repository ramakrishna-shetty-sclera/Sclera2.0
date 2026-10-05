import { ITEM_TYPES, isChoice, typeLabel } from '../api/document'
import type { ItemType } from '../api/types'
import { triggerOptions, type ItemDraft } from './itemTree'

export interface ItemEditorProps {
  item: ItemDraft
  /** The question this one hangs off, or null at the top level. */
  parent: ItemDraft | null
  index: number
  siblings: number
  onPatch: (uid: string, patch: Partial<ItemDraft>) => void
  onRetype: (uid: string, type: ItemType) => void
  onRemove: (uid: string) => void
  onMove: (uid: string, delta: number) => void
  onAddFollow: (uid: string) => void
}

/**
 * One item and everything hanging off it.
 *
 * Recursive, because follow-ups nest to any depth and a follow-up is an
 * ordinary question in every other respect — the only difference is that it
 * names the answer that shows it.
 */
export function ItemEditor(props: ItemEditorProps) {
  const { item, parent, index, siblings, onPatch, onRetype, onRemove, onMove, onAddFollow } = props
  const section = item.type === 'SECTION'
  const triggers = triggerOptions(item)
  // A follow-up cannot be a heading: a section is never answered, so it can
  // never be conditional on an answer.
  const types = parent ? ITEM_TYPES.filter((t) => t !== 'SECTION') : ITEM_TYPES

  return (
    <div className={`question-editor${section ? ' item-section' : ''}`}>
      <div className="form-grid">
        <label className="grow">
          {section ? 'Section' : 'Question'} {index + 1} *
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

      {item.options.length > 0 && (
        <ul className="doc-options">
          {item.options.map((option) => (
            <li key={option.uid}>
              {option.label}
              {option.key && <span className="rt-key-inline"> {option.key}</span>}
            </li>
          ))}
        </ul>
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
        {isChoice(item.type) && (
          <button
            type="button"
            className="btn btn-ghost small"
            onClick={() => onAddFollow(item.uid)}
            disabled={triggers.length === 0}
          >
            + Follow-up
          </button>
        )}
        <button type="button" className="btn btn-ghost small" onClick={() => onRemove(item.uid)}>
          Remove
        </button>
      </div>

      {isChoice(item.type) && triggers.length === 0 && (
        <p className="field-hint">
          Save the draft before adding a follow-up. A follow-up points at an answer by the key the
          server mints, and these answers have none yet.
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

import { useEffect, useRef, useState, type ChangeEvent } from 'react'
import type { Question } from '../api/types'

interface Props {
  question: Question
  value: unknown
  disabled: boolean
  onChange: (value: unknown) => void
}

export function QuestionInput({ question, value, disabled, onChange }: Props) {
  switch (question.type) {
    case 'TEXT':
      return (
        <textarea
          rows={2}
          value={typeof value === 'string' ? value : ''}
          disabled={disabled}
          onChange={(e) => onChange(e.target.value)}
        />
      )
    case 'NUMBER':
      return (
        <input
          type="number"
          value={typeof value === 'number' ? value : ''}
          disabled={disabled}
          onChange={(e) => onChange(e.target.value === '' ? null : Number(e.target.value))}
        />
      )
    case 'BOOLEAN':
      return (
        <div className="choice-row">
          {[true, false].map((v) => (
            <label key={String(v)} className="checkbox">
              <input
                type="radio"
                checked={value === v}
                disabled={disabled}
                onChange={() => onChange(v)}
              />
              {v ? 'Yes' : 'No'}
            </label>
          ))}
        </div>
      )
    case 'SINGLE_CHOICE':
      return (
        <div className="choice-row">
          {(question.options ?? []).map((opt) => (
            <label key={opt} className="checkbox">
              <input
                type="radio"
                checked={value === opt}
                disabled={disabled}
                onChange={() => onChange(opt)}
              />
              {opt}
            </label>
          ))}
        </div>
      )
    case 'MULTI_CHOICE': {
      const selected = Array.isArray(value) ? (value as string[]) : []
      return (
        <div className="choice-row">
          {(question.options ?? []).map((opt) => (
            <label key={opt} className="checkbox">
              <input
                type="checkbox"
                checked={selected.includes(opt)}
                disabled={disabled}
                onChange={(e) =>
                  onChange(
                    e.target.checked ? [...selected, opt] : selected.filter((o) => o !== opt),
                  )
                }
              />
              {opt}
            </label>
          ))}
        </div>
      )
    }
    case 'DATE':
      return (
        <input
          type="date"
          value={typeof value === 'string' ? value : ''}
          disabled={disabled}
          onChange={(e) => onChange(e.target.value || null)}
        />
      )
    case 'PHOTO':
      return <PhotoInput value={value} disabled={disabled} onChange={onChange} />
    case 'SIGNATURE':
      return <SignaturePad value={value} disabled={disabled} onChange={onChange} />
  }
}

function PhotoInput({
  value,
  disabled,
  onChange,
}: {
  value: unknown
  disabled: boolean
  onChange: (value: unknown) => void
}) {
  function onFile(e: ChangeEvent<HTMLInputElement>) {
    const file = e.target.files?.[0]
    if (!file) return
    const reader = new FileReader()
    reader.onload = () => onChange(reader.result)
    reader.readAsDataURL(file)
  }

  return (
    <div>
      {typeof value === 'string' && value.startsWith('data:') && (
        <img className="photo-preview" src={value} alt="Captured" />
      )}
      {!disabled && (
        <div className="choice-row">
          <input type="file" accept="image/*" onChange={onFile} />
          {value != null && (
            <button type="button" className="btn btn-ghost small" onClick={() => onChange(null)}>
              Remove
            </button>
          )}
        </div>
      )}
    </div>
  )
}

function SignaturePad({
  value,
  disabled,
  onChange,
}: {
  value: unknown
  disabled: boolean
  onChange: (value: unknown) => void
}) {
  const canvasRef = useRef<HTMLCanvasElement>(null)
  const [drawing, setDrawing] = useState(false)

  useEffect(() => {
    const canvas = canvasRef.current
    if (!canvas) return
    const ctx = canvas.getContext('2d')!
    ctx.clearRect(0, 0, canvas.width, canvas.height)
    if (typeof value === 'string' && value.startsWith('data:')) {
      const img = new Image()
      img.onload = () => ctx.drawImage(img, 0, 0)
      img.src = value
    }
    // Only redraw when the pad mounts or an external value arrives while idle.
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [disabled])

  function pos(e: React.PointerEvent<HTMLCanvasElement>) {
    const rect = canvasRef.current!.getBoundingClientRect()
    return { x: e.clientX - rect.left, y: e.clientY - rect.top }
  }

  function start(e: React.PointerEvent<HTMLCanvasElement>) {
    if (disabled) return
    setDrawing(true)
    const ctx = canvasRef.current!.getContext('2d')!
    ctx.lineWidth = 2
    ctx.lineCap = 'round'
    ctx.strokeStyle = '#1f2937'
    const { x, y } = pos(e)
    ctx.beginPath()
    ctx.moveTo(x, y)
    canvasRef.current!.setPointerCapture(e.pointerId)
  }

  function move(e: React.PointerEvent<HTMLCanvasElement>) {
    if (!drawing) return
    const ctx = canvasRef.current!.getContext('2d')!
    const { x, y } = pos(e)
    ctx.lineTo(x, y)
    ctx.stroke()
  }

  function end() {
    if (!drawing) return
    setDrawing(false)
    onChange(canvasRef.current!.toDataURL('image/png'))
  }

  function clear() {
    const canvas = canvasRef.current!
    canvas.getContext('2d')!.clearRect(0, 0, canvas.width, canvas.height)
    onChange(null)
  }

  return (
    <div>
      <canvas
        ref={canvasRef}
        width={400}
        height={140}
        className="signature-pad"
        onPointerDown={start}
        onPointerMove={move}
        onPointerUp={end}
        onPointerLeave={end}
      />
      {!disabled && (
        <div>
          <button type="button" className="btn btn-ghost small" onClick={clear}>
            Clear signature
          </button>
        </div>
      )}
    </div>
  )
}

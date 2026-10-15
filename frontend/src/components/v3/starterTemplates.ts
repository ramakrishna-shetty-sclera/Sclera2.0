import type { ItemType } from '../../api/types'
import { newItem, newOption, type ItemDraft } from '../itemTree'

// The ready-made checklists from docs/procedures-v3.html (TEMPLATES): one per
// category, picked apart question by question before anything joins the draft.
// Client-side data by design — they are starting points, not managed content.
//
// Follow-ups in the prototype arrive here as ordinary questions right after
// their parent: a follow-up must point at a minted option key, and nothing is
// minted until the draft is saved. Authors nest them afterwards if they want.

export interface StarterSpec {
  text: string
  type: ItemType
  help?: string
  required?: boolean
  workOrder?: boolean
  /** Yes means FAIL (e.g. "Is there any sign of overheating?"). */
  invert?: boolean
  unit?: string
}

export interface StarterTemplate {
  key: string
  name: string
  category: string
  standard: string
  frequency: string
  description: string
  specs: StarterSpec[]
}

const q = (text: string, type: ItemType, extra: Omit<StarterSpec, 'text' | 'type'> = {}): StarterSpec => ({
  text,
  type,
  ...extra,
})

export const STARTER_TEMPLATES: StarterTemplate[] = [
  {
    key: 'fe',
    name: 'Fire Extinguisher – Monthly Inspection',
    category: 'Inspection',
    standard: 'NFPA 10',
    frequency: 'Monthly',
    description: 'Charged, accessible and in proper condition.',
    specs: [
      q('Is the extinguisher in its designated location?', 'YES_NO', { required: true }),
      q('Is access unobstructed and the signage visible?', 'YES_NO', { required: true }),
      q('Is the pressure gauge needle in the operable (green) range?', 'YES_NO_NA', {
        required: true,
        workOrder: true,
        help: 'Tap the gauge lightly before reading it.',
      }),
      q('Record the gauge reading', 'INTEGER', { unit: 'psi' }),
      q('Are the safety pin and tamper seal intact?', 'YES_NO', { required: true, workOrder: true }),
      q('Is the extinguisher free of damage, corrosion or leakage?', 'YES_NO', { required: true, workOrder: true }),
      q('Are the hose and nozzle clear of blockage and cracks?', 'YES_NO'),
      q('Are the operating instructions legible and facing outward?', 'YES_NO'),
      q('Record the gross weight', 'INTEGER', { unit: 'kg' }),
      q('Is the extinguisher mounted at the correct height?', 'YES_NO'),
      q('Is the inspection tag present and current?', 'YES_NO', { required: true }),
      q('Capture a photo of the inspection tag', 'IMAGE', { required: true }),
      q('Inspector remarks', 'TEXT'),
    ],
  },
  {
    key: 'alarm',
    name: 'Fire Alarm System – Weekly',
    category: 'Inspection',
    standard: 'NFPA 72',
    frequency: 'Weekly',
    description: 'Control panel, pull stations and backup power.',
    specs: [
      q('Does the control panel show a normal condition?', 'YES_NO', { required: true }),
      q('Photograph the panel display if abnormal', 'IMAGE'),
      q('Are manual pull stations accessible and undamaged?', 'YES_NO'),
      q('Is the secondary power supply charged and connected?', 'YES_NO', { workOrder: true }),
    ],
  },
  {
    key: 'boiler',
    name: 'Boiler Inspection – Monthly',
    category: 'Project Management (PM)',
    standard: 'ASME BPVC',
    frequency: 'Monthly',
    description: 'Operation and maintenance per Sections VI & VII.',
    specs: [
      q('Is the operating pressure within the rated range?', 'YES_NO', { required: true }),
      q('Record the operating pressure', 'INTEGER', { unit: 'psi' }),
      q('Are there leaks or corrosion around fittings?', 'YES_NO', { invert: true, workOrder: true }),
      q('Is the low-water cut-off tested and working?', 'YES_NO_NA'),
    ],
  },
  {
    key: 'elec',
    name: 'Electrical Panel Inspection – Monthly',
    category: 'Inspection',
    standard: 'NFPA 70',
    frequency: 'Monthly',
    description: 'Labelling, heat damage and working clearance.',
    specs: [
      q('Is the panel door closed and labelled?', 'YES_NO'),
      q('Is there any sign of overheating or burning?', 'YES_NO', { invert: true, workOrder: true }),
      q('Photograph the affected area if overheating is found', 'IMAGE'),
      q('Is 36 in. of clear working space maintained?', 'YES_NO'),
      q('Photograph the panel schedule', 'IMAGE'),
    ],
  },
  {
    key: 'eyewash',
    name: 'Eyewash Station – Weekly',
    category: 'Health & Safety',
    standard: 'ANSI Z358.1',
    frequency: 'Weekly',
    description: 'Activation, flow and access for emergency eyewash.',
    specs: [
      q('Does the unit activate in one second or less?', 'YES_NO'),
      q('Does flushing fluid flow for the full test?', 'YES_NO'),
      q('Is the path to the unit free of obstructions?', 'YES_NO'),
    ],
  },
]

/** One spec → one builder item, Yes/No results mapped to PASS/FAIL (swapped when inverted). */
export function toItemDraft(spec: StarterSpec, standard: string): ItemDraft {
  const item = newItem(spec.type)
  item.text = spec.text
  item.help = spec.help ?? ''
  item.required = spec.required ?? false
  item.workOrder = spec.workOrder ?? false
  item.unit = spec.unit ?? ''
  item.standard = standard
  if (spec.type === 'YES_NO' || spec.type === 'YES_NO_NA') {
    const yes = newOption('Yes')
    const no = newOption('No')
    yes.result = spec.invert ? 'FAIL' : 'PASS'
    no.result = spec.invert ? 'PASS' : 'FAIL'
    item.options = spec.type === 'YES_NO_NA' ? [yes, no, newOption('N/A')] : [yes, no]
  }
  return item
}

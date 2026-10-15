// The suggested vocabularies from docs/procedures-v3.html. Suggestions, not an
// enum: the backend stores free text, so these lists can grow without a
// migration — keep them in one place so every screen offers the same words.

export const CATEGORIES = [
  'Inspection',
  'Project Management (PM)',
  'Rounds',
  'Dispatch',
  'Health & Safety',
  'Custom',
]

export const SUB_CATEGORIES = [
  'Fire Protection',
  'Plumbing',
  'Boiler',
  'Cleaning',
  'Electrical',
  'Quick Maintenance',
]

export const FREQUENCIES = ['Daily', 'Weekly', 'Monthly', 'Quarterly', 'Semi-annual', 'Annual']

export const PRIORITIES = ['Critical', 'High', 'Medium', 'Low']

export const STANDARDS = ['NFPA 10', 'NFPA 72', 'NFPA 70', 'NFPA 80', 'ASME BPVC', 'ANSI Z358.1', 'OSHA 1910']

export const FOLDERS = ['Inspection', 'Rounds', 'PM', 'Health & Safety', 'Custom']

/** Priority → status-chip class, per the design's PRI_CLASS. */
export const PRIORITY_CLASS: Record<string, string> = {
  Critical: 'fail',
  High: 'warn',
  Medium: 'sel',
  Low: 'line',
}

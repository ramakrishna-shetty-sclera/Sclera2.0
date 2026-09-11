const COLORS: Record<string, string> = {
  DRAFT: 'badge-gray',
  IN_PROGRESS: 'badge-blue',
  COMPLETED: 'badge-green',
  CANCELLED: 'badge-red',
  PUBLISHED: 'badge-green',
  ARCHIVED: 'badge-red',
}

export function StatusBadge({ status }: { status: string }) {
  return <span className={`badge ${COLORS[status] ?? 'badge-gray'}`}>{status.replace('_', ' ')}</span>
}

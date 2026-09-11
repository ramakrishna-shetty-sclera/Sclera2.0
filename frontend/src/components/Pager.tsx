import type { Pagination } from '../api/types'

export function Pager({
  pagination,
  onPage,
}: {
  pagination: Pagination | null
  onPage: (page: number) => void
}) {
  if (!pagination || pagination.totalPages <= 1) return null
  return (
    <div className="pager">
      <button disabled={!pagination.hasPrevious} onClick={() => onPage(pagination.page - 1)}>
        ‹ Prev
      </button>
      <span>
        Page {pagination.page + 1} of {pagination.totalPages} ({pagination.totalElements} total)
      </span>
      <button disabled={!pagination.hasNext} onClick={() => onPage(pagination.page + 1)}>
        Next ›
      </button>
    </div>
  )
}

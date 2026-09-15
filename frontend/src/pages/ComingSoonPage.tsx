import { Link } from 'react-router-dom'

/**
 * Placeholder for sections that have a card on the home page but aren't built
 * yet (Locations, Assets, Task Dashboard). Keeps navigation from 404-ing and
 * documents the intended scope so we can flesh each one out step by step.
 */
export function ComingSoonPage({
  title,
  icon,
  scope,
}: {
  title: string
  icon: string
  scope: string[]
}) {
  return (
    <div>
      <div className="page-head">
        <div>
          <h1>
            <span aria-hidden style={{ marginRight: 8 }}>
              {icon}
            </span>
            {title}
          </h1>
          <p className="muted">This section isn't built yet — here's what it will cover.</p>
        </div>
        <Link className="btn" to="/">
          ← Home
        </Link>
      </div>

      <div className="card">
        <ul className="scope-list">
          {scope.map((item) => (
            <li key={item}>{item}</li>
          ))}
        </ul>
      </div>
    </div>
  )
}

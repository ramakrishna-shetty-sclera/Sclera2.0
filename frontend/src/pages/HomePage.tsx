import { Link } from 'react-router-dom'

interface DashboardCard {
  to: string
  title: string
  icon: string
  blurb: string
  /** false = section not built yet; card still shows but is flagged */
  ready: boolean
}

const CARDS: DashboardCard[] = [
  {
    to: '/locations',
    title: 'Locations',
    icon: '🏢',
    blurb: 'Buildings, floors and locations — the physical hierarchy everything else hangs off.',
    ready: false,
  },
  {
    to: '/assets',
    title: 'Assets',
    icon: '🖥️',
    blurb: 'IP and non-IP assets, optionally tagged to a location.',
    ready: false,
  },
  {
    to: '/templates',
    title: 'Procedures',
    icon: '📋',
    blurb: 'Author question templates: draft → publish → archive.',
    ready: true,
  },
  {
    to: '/inspection-configs',
    title: 'Inspections',
    icon: '🔍',
    blurb: 'Configure inspections: assignees, schedule, frequency and options.',
    ready: true,
  },
  {
    to: '/tasks',
    title: 'Task Dashboard',
    icon: '✅',
    blurb: 'Track work across locations, assets and inspections in one place.',
    ready: false,
  },
]

export function HomePage() {
  return (
    <div>
      <div className="page-head">
        <div>
          <h1>Home</h1>
          <p className="muted">Pick an area to work in.</p>
        </div>
      </div>

      <div className="card-grid">
        {CARDS.map((card) => (
          <Link key={card.to} to={card.to} className="dash-card">
            <div className="dash-card-icon" aria-hidden>
              {card.icon}
            </div>
            <div className="dash-card-body">
              <div className="dash-card-title">
                {card.title}
                {!card.ready && <span className="badge badge-gray">Soon</span>}
              </div>
              <p className="dash-card-blurb">{card.blurb}</p>
            </div>
          </Link>
        ))}
      </div>
    </div>
  )
}

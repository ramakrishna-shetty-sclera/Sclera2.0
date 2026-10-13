# Calendar Service — Design

`sclera-calendar-service` is the application-plane source of truth for **date/time rules per
property**: timezone + locale, working hours, working/non-working days, fiscal year, week
numbering, downtime windows, and holidays. Any feature that depends on "is this a usable
moment at this property?" asks this service instead of hardcoding rules.

Status: implemented (this repo), reference integration: inspection-service checklist due dates.

---

## 1. Discovery summary (what exists today)

| Area | Finding |
|---|---|
| Timezone | Jackson `time-zone: UTC` hardcoded in every service yml; 15+ bare `OffsetDateTime.now()` calls (JVM default zone) across inspection (13), helper (4), procedure (6) services |
| Scheduling | inspection-service has `Frequency` (ONCE…YEARLY) and `Weekday` enums on `InspectionConfig` + `scheduleDays` CSV converter — **no consuming logic** computes next runs |
| Due dates | `Checklist.dueDate`, `Inspection.scheduledFor` are informational; overdue = `new Date(d) < new Date()` in the frontend only |
| Working hours / holidays / downtime | Nothing, anywhere |
| Locale/format | Frontend uses scattered `toLocaleDateString()` / `toLocaleString()` (10+ components, browser-locale dependent); no shared formatter |
| Fiscal year / week numbering | Nothing |

Consequences: no per-property timezone (a 02:00 UTC due date renders as "yesterday 21:00"
for a New York property with zero awareness), no way to avoid scheduling inspections on
holidays/closures, and three services each re-invent "now".

The bare `OffsetDateTime.now()` audit stamps are *not* migrated — audit timestamps are
instants and belong in UTC; the fix there is display-side formatting, not calendar rules.
They are listed in §10 as follow-ups (inject `Clock` for testability).

## 2. Placement and shape

A **new Maven module `sclera-calendar-service`** (port 8098, DB `sclera_calendar`), cloned
from the helper-service skeleton — the smallest conforming service: schema-per-tenant
Postgres, Flyway (public copy + `db/tenant`), Keycloak JWT + `ScleraJwtConverter`,
OpenFGA `@fga` bean, envelope responses, Dapr + HMAC `/internal/**`.

Why a service and not a shared library: rules are *data* (per org/property, admin-edited,
audited); a library would force every service to own calendar tables and migrations and
would fragment the cache. Consumers get a thin Dapr client instead (see §8).

**Tenancy**: org = schema (`t_<uuid>`), property = `property_id` column (`NULL` = org-wide
default). The calendar follows helper-service's simpler model (no PropertyContext/RLS
split); property scoping is explicit in the API. Settings resolve property → org default →
built-in defaults.

## 3. Domain model

```
CalendarSettings 1 (per property; property_id NULL = org default)
 ├─ timezone (IANA), use24HourClock, dateFormatPattern, firstDayOfWeek
 ├─ weekNumbering (ISO | US), fiscalYearStartMonth/Day
 └─ workingHours JSONB: { "MONDAY": [ {start:"09:00", end:"17:00"}, … ], … }
       · multiple shifts per day; empty/missing day = non-working
       · end <= start ⇒ shift crosses midnight into the next day

DowntimeWindow *                      Holiday *
 ├─ kind: ONE_OFF | RECURRING          ├─ dateType: FIXED(month,day)
 ├─ ONE_OFF: startAt/endAt (UTC)       │           | FLOATING(month,ordinal,weekday)
 ├─ RECURRING: recurrence JSONB rule   │           | ONE_OFF(onDate)
 │   + localStartTime + durationMin    ├─ category (PUBLIC/COMPANY/…), source (MANUAL|region)
 │   (evaluated in property tz)        ├─ enabled, property_id (NULL = org-wide)
 ├─ type: MAINTENANCE|BLACKOUT|        └─ HolidayOverride * (per property):
 │        CLOSURE|OTHER                     action DISABLE | MOVE(movedTo), year (NULL=all)
 ├─ scopeType PROPERTY|LOCATION|ASSET + scopeId (sub-resource downtime)
 └─ active, reason

FeatureCalendarPolicy * (opt-in registry)
 └─ featureCode ("inspection.checklist"), property_id (NULL = org default),
    respectWorkingHours / respectDowntime / respectHolidays,
    downtimeTypes CSV (empty = all), holidayCategories CSV (empty = all)
```

## 4. Database schema & migration strategy

Follows the helper-service pattern exactly: `db/migration/V1__init_calendar.sql` +
`V2__tenant_registry.sql` are the public copies (keeps `ddl-auto: validate` green);
`db/tenant/V1__init_tenant.sql` is the per-tenant DDL applied by `TenantRegistryService`
(idempotent provisioning per request + startup sweep of ACTIVE schemas). UUID PKs,
`TIMESTAMPTZ` audit columns, `JSONB` for working hours and recurrence rules, CHECK
constraints on enums. One partial unique index guarantees a single org-default settings
row (`WHERE property_id IS NULL`) and one per property.

New local infra: `sclera_calendar` database added to `docker/postgres-init/01-create-databases.sql`
(existing volumes need `docker compose down -v` or a manual `CREATE DATABASE`).

## 5. API contract (envelope-wrapped, `/api/v1/calendar/**`)

| Endpoint | Verb | Permission | Notes |
|---|---|---|---|
| `/settings` | GET | `can_view` | list all settings rows (org default + per property) |
| `/settings/effective?propertyId=` | GET | `can_view` | resolved settings (property → org → defaults) incl. source |
| `/settings` | PUT | `can_manage_calendar` | upsert by propertyId (null ⇒ org default) |
| `/settings/{id}` | DELETE | `can_manage_calendar` | |
| `/downtimes` (+`/{id}`) | CRUD | manage; list = `can_view` | `?from&to&propertyId&scopeType&scopeId` expands recurrences |
| `/holidays` (+`/{id}`) | CRUD | manage; list = `can_view` | |
| `/holidays/import` | POST | manage | `{region, propertyId?}` from bundled regional packs (US/GB/IN) |
| `/holidays/resolved?from&to&propertyId` | GET | `can_view` | concrete dates after overrides |
| `/holidays/{id}/overrides` | POST/DELETE | manage | per-property disable/move |
| `/policies` | GET / PUT | view / manage | feature opt-in upsert by `featureCode`+`propertyId` |
| `/availability/check?propertyId&at&featureCode&scopeType&scopeId` | GET | `can_view` | `isAvailable` + first blocking reason |
| `/availability/next-slot?propertyId&from&durationMinutes&featureCode…` | GET | `can_view` | earliest fully-available window (≤ 366-day scan) |
| `/availability/unavailable?propertyId&from&to&featureCode…` | GET | `can_view` | merged ranges with causes (range ≤ 93 days) |
| `/availability/working-hours?propertyId&date` | GET | `can_view` | resolved shifts as zoned instants + holiday flag |

Service-to-service (Dapr + HMAC, orgId in path, wrapped in `TenantContext.runAs`):
`/internal/api/v1/calendar/orgs/{orgId}/availability/check|next-slot|working-hours`.

## 6. Recurrence approach — structured rule, not an RRULE library

**Decision: custom `RecurrenceRule` (JSONB) supporting the RFC 5545 subset
`FREQ=DAILY|WEEKLY|MONTHLY|YEARLY`, `INTERVAL`, `BYDAY`, `BYMONTHDAY`, `BYSETPOS`
(nth/last weekday of month), `UNTIL`, `COUNT`** — expanded by `RecurrenceEngine`
(bounded day-walk, hard 10-year horizon).

Why not ical4j / lib-recur:
- sclera-common is consumed as a **bare jar**; this repo's dependency policy is "declare
  every transitive explicitly" — ical4j drags a large transitive tree for generality
  (full RRULE, VTIMEZONE parsing) nothing here needs.
- Facilities downtime/holidays are expressible in the subset above; the UI can only ever
  author that subset; `BYSECOND`-grade generality is untestable surface.
- A structured rule validates at the API boundary (Bean Validation) instead of failing
  deep inside a string parser, and serializes cleanly to JSONB.

Trade-off accepted: no import of arbitrary `.ics` feeds (future: adapter that *down-converts*
an RRULE string into the structured rule, rejecting unsupported parts loudly).

## 7. Timezone strategy

- **Store UTC, resolve at query time.** One-off downtime is absolute (`TIMESTAMPTZ`).
- **Recurring rules and working hours are wall-clock local** (property timezone), because
  "09:00–17:00" must stay 09:00 across DST. They are resolved to instants per query via
  `ZonedDateTime` with java.time's standard gap/overlap semantics:
  - *Spring-forward gap* (02:30 doesn't exist): shift start moves forward to the first
    valid instant (java.time default) — a 7h working day that DST-day, never a crash.
  - *Fall-back overlap* (01:30 happens twice): the **earlier** offset is used.
- Cross-midnight shifts (`22:00→06:00`) resolve end on the following date.
- API accepts/returns ISO-8601 with offset (`OffsetDateTime`), consistent with every
  existing endpoint; Jackson stays on UTC.

## 8. Integration pattern & rollout

Consumers never read calendar tables. Two paths:
1. **User-facing frontends** call `/api/v1/calendar/**` through the gateway (route added).
2. **Services** use a small client over Dapr (same shape as `ProcedureTemplateClient`) with
   a **fail-open gate**: if calendar-service is down or `sclera.calendar.enabled=false`,
   the consumer behaves exactly as before. This keeps each adoption step independently
   mergeable and makes the calendar non-load-bearing until trusted.

Feature opt-in: a consumer declares a `featureCode` and the admin configures a
`FeatureCalendarPolicy` for it (which layers and which downtime types / holiday categories
apply). Unregistered feature codes get the implicit default policy (respect everything).

**Reference integration (shipped)**: inspection-service `ChecklistService.generate` routes
requested due dates through `CalendarGate.adjustDueDate(...)` (feature code
`inspection.checklist`): a due date falling in unavailable time moves to the next available
slot and the adjustment is logged in checklist history. Rollout plan for the rest:
inspection `scheduledFor` validation → recurrence-driven checklist auto-generation (finally
consuming `Frequency`/`scheduleDays`) → frontend `/settings/effective`-driven formatting →
overdue (`INCOMPLETE`) transitions computed in property time.

## 9. Precedence, validation, caching, audit, permissions

**Precedence** (first block wins): active **downtime** (hard block, feature-filtered by
type) → **holiday** (makes the day non-working, feature-filtered by category, after
per-property overrides) → **working hours** (outside any shift = unavailable). Overlapping
unavailable intervals are unioned; `getUnavailableRanges` merges adjacent/overlapping
ranges and keeps the highest-precedence cause.

**Validation**: IANA zone must resolve via `ZoneId.of`; shifts must parse `HH:mm`; one-off
downtime needs `startAt < endAt`; recurring needs rule + positive duration; FIXED holidays
validate month/day (Feb 29 allowed — observed Feb 28 in non-leap years, documented);
FLOATING validates ordinal ∈ {1..4, -1}; query ranges are capped (§5) to bound work.

**Caching**: read-heavy, write-rare. Per-service Caffeine (same pattern as FGA decision
cache): effective-settings cache and a resolved-calendar cache (holidays + downtime per
property+month), TTL 60 s + explicit invalidation on any write in the same instance.
Cross-instance staleness is bounded by the TTL — acceptable for admin-edited config; a
Redis pub/sub invalidation (sclera-common already ships the listener) is the designated
upgrade if 60 s ever matters.

**Audit**: `created_by/created_at/updated_at` on every row plus a `calendar_audit` table
(entity type/id, action, actor, JSONB diff snapshot) written in the same transaction for
settings/downtime/holiday/policy mutations — config changes here silently change downstream
scheduling behaviour, so "who turned off the holiday" must be answerable.

**Permissions**: reads `@fga.checkOrg('can_view')`; writes `@fga.checkOrg('can_manage_calendar')`.
New `calendar_manager` role + `can_manage_calendar` relation added to `docker/openfga/model.fga`
and `authorization-model.json` (**re-run `setup-openfga.ps1`**; `admin` implies it).

## 10. Edge cases covered by tests

- DST spring-forward: downtime/shift starting inside the 02:00–03:00 gap; day length 23 h.
- DST fall-back: 25 h day; ambiguous local times take the earlier offset.
- Cross-midnight shifts, including shifts whose tail lands on a holiday/downtime day.
- Overlapping downtime windows (union, no double counting), zero-length rejection.
- Leap years: FIXED Feb 29 holiday observed Feb 28 on non-leap years; YEARLY recurrence.
- FLOATING holidays: "3rd Monday of January", "last Friday of May" (ordinal −1).
- Week numbering ISO vs US; fiscal year start applied to fiscal-year/quarter helpers.
- Property with no settings row → org default → built-in defaults (UTC, Mon–Fri 09–17).
- `getNextAvailableSlot` skipping a multi-day closure; no-slot-within-horizon ⇒ 404-style error.

## 11. Rejected alternatives

| Option | Why rejected |
|---|---|
| Calendar module inside each service (shared lib) | Duplicate tables/caches per service; config drift; no single audit trail |
| Full RRULE via ical4j/lib-recur | Transitive-dependency policy pain; generality beyond UI needs; see §6 |
| Materializing occurrences into a table | Write amplification, horizon management, invalidation on every rule edit; expansion is cheap (< 1 ms for a month window) |
| Postgres-only computation (SQL calendars) | DST/zone logic in SQL is where correctness goes to die; java.time is tested for this |
| Storing working hours as instants | Breaks across DST; wall-clock semantics are the requirement |
| Redis shared cache now | Premature; Caffeine + TTL bounds staleness at 60 s for admin config |

## 12. Deliberately out of scope (flagged, not changed)

- The 15+ bare `OffsetDateTime.now()` audit stamps (should become injected `Clock`s; UTC
  semantics already correct).
- Frontend formatting consolidation onto `/settings/effective` (next rollout step).
- Non-Gregorian calendar systems (`calendarSystem` column reserved, ISO-8601 only).
- Scheduled jobs (overdue sweeps) — the calendar exposes queries; schedulers come later.
- PDF export date formatting in procedure-service.

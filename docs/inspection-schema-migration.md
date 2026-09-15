# Inspection module — legacy → microservice schema & data migration

Source: `sclera-vdms-edge-server` (main, Java/Hibernate/MySQL). Target: the
Sclera 2.0 application-plane microservices (Spring Boot/Postgres): procedure-service,
inspection-service, helper-service (→ future asset/location services).

**Multi-tenancy: Postgres, SCHEMA-PER-TENANT.** Every tenant (org) gets its own
Postgres schema containing an identical set of tables; the JWT's `org_id`
resolves to a schema at connection time. See §6 for wiring. Consequence for
the DDL below: tables do NOT need an `org_id` column for isolation (the schema
IS the tenant boundary) — we keep a nullable `org_id` only as a defense-in-depth
cross-check, never in business keys or filters.

## 1. Legacy model (what actually exists)

Two parallel trees with identical shape — a **template** tree and a **full clone
per filled instance**:

```
TEMPLATE (design time)                     INSTANCE (runtime, cloned per record)
global_checklist                           record_checklist  (status, result, answers live here)
└─ global_checklist_items                  └─ record_checklist_items   (value/result = the answer)
   └─ global_checklist_options                └─ record_checklist_options (selected 0/1, value)
      └─ (recursion: items whose                 └─ (same recursion via
          global_checklist_option_id                  record_checklist_option_id)
          = parent option id)
```

Around them:

| Legacy table | Purpose |
|---|---|
| `global_inspection_record` | inspection config: frequency, next_occurrence, toggles (bypass_scan, check_in_out, score, merged_view, duplicate-workorder guard, result %) |
| `global_inspection_relation` (+assignees) | binds checklist × device/location × assignee under a config (`association_type` 0..3) |
| `global_checklist_conditions` | alert rules (exception/fail/pass/completed → alert_profile, priority) |
| `inspection_record` (+assignees, history) | one spawned run of a config; umbrella over its record_checklists |
| `record_checklist_assignees` / `_history` | multi-assignee, status audit |
| `workorder` | link checklist/inspection → external WO (Corrigo) |

Legacy characteristics that shape the migration:
- **Recursion** is an adjacency list *through options*: an item with
  `*_option_id != NULL` is a conditional child shown when that option is picked.
  Unbounded in schema, 1–2 levels in practice.
- **Answers are embedded in the cloned tree** (`items.value/result`,
  `options.selected/value`) — no separate answers table. Row explosion:
  every run copies the whole tree.
- String PKs, epoch-millis `BigInteger` timestamps, `is_removed` soft deletes
  everywhere, no org/tenant column (single-tenant edge deployments).
- Statuses: checklist `todo | in_progress | completed | exception |
  incomplete_expired`, result `Pass | Fail | NULL`; inspection `todo | completed`.

## 2. Ownership split across microservices

| Legacy | New owner | New concept |
|---|---|---|
| `global_checklist(+items,+options)` | **procedure-service** | procedure (checklist template) with item tree |
| `global_inspection_record` | **inspection-service** | `inspection_config` (already modeled: name/code/assignees/category/priority/frequency/schedule + toggles) |
| `global_inspection_relation(+assignees)` | **inspection-service** | tagging: tagged_procedure + procedure_target (already modeled) |
| `global_checklist_conditions` | **inspection-service** | outer conditions (already modeled; alert_profile stays a reference id until a notification service exists) |
| `inspection_record(+assignees,+history)` | **inspection-service** | inspection run (generation batch) — optional umbrella over checklists |
| `record_checklist(+items,+options,+assignees,+history)` | **inspection-service** | `checklist` instance = **template snapshot (JSONB) + flat answers + lifecycle columns** |
| `workorder` | **inspection-service** (table) → future workorder-service | `checklist_work_order` |
| `device` / `location` refs | **helper-service** (今 dummy) → asset/location services | UUID + denormalized name on the referencing row (no cross-service FK) |

Cross-service rule (already the pattern in this repo): store the foreign ID +
a denormalized display name; never a DB-level FK across services. The
inspection-service snapshots the procedure at generation time (same pattern as
the existing `templateSnapshot`), so procedure edits never corrupt in-flight runs.

## 3. New schema

### 3.1 procedure-service — template tree (relational, because it's edited)

Extends the existing `question_template` model to carry the legacy recursion
1:1, which makes migration mechanical:

```sql
CREATE TABLE checklist_item (
    id                UUID PRIMARY KEY,
    template_id       UUID NOT NULL REFERENCES question_template(id),
    parent_option_id  UUID NULL REFERENCES checklist_item_option(id), -- NULL = top level
    label             VARCHAR(2000) NOT NULL,
    item_type         VARCHAR(32)  NOT NULL,   -- TEXT | RADIO | CHECKBOX | IMAGE | VALUE | DATE | SIGNATURE ...
    position          INT          NOT NULL,
    is_required       BOOLEAN      NOT NULL DEFAULT FALSE,
    is_na_allowed     BOOLEAN      NOT NULL DEFAULT FALSE,
    display_conditions JSONB       NULL,       -- legacy `conditions` TEXT, parsed
    score_formula      JSONB       NULL,       -- legacy `formula` TEXT, parsed
    legacy_id         VARCHAR(64)  NULL,       -- migration provenance
    UNIQUE (template_id, legacy_id)
);
CREATE INDEX ix_ci_template ON checklist_item(template_id);
CREATE INDEX ix_ci_parent_option ON checklist_item(parent_option_id);

CREATE TABLE checklist_item_option (
    id          UUID PRIMARY KEY,
    item_id     UUID NOT NULL REFERENCES checklist_item(id) ON DELETE CASCADE,
    label       VARCHAR(500) NOT NULL,
    position    INT NOT NULL,
    result      VARCHAR(32) NULL,          -- outcome when picked (pass/fail/na)
    is_default  BOOLEAN NOT NULL DEFAULT FALSE,
    legacy_id   VARCHAR(64) NULL
);
```

The adjacency list *through options* is preserved (`parent_option_id`), so
conditional sub-questions keep working exactly as before. Depth stays
unbounded; queries use a recursive CTE only in the (rare) authoring paths —
runtime never walks it (see 3.2).

### 3.2 inspection-service — instance = snapshot + flat answers

The key structural change vs legacy: **stop cloning the tree per run.**

```sql
CREATE TABLE checklist (
    id                  UUID PRIMARY KEY,
    org_id              UUID NOT NULL,
    config_id           UUID NOT NULL,            -- inspection_config
    run_id              UUID NULL,                -- generation batch (inspection_record equivalent)
    procedure_id        UUID NOT NULL,            -- provenance only
    procedure_version   INT  NOT NULL,
    procedure_snapshot  JSONB NOT NULL,           -- full item/option tree at generation time
    target_type         VARCHAR(16) NULL,         -- ASSET | LOCATION | NULL
    target_id           UUID NULL,
    target_name         VARCHAR(200) NULL,        -- denormalized
    assignee_email      VARCHAR(255) NOT NULL,
    status              VARCHAR(24) NOT NULL,     -- TODO | IN_PROGRESS | COMPLETE | FAILED | EXCEPTION | INCOMPLETE
    result              VARCHAR(8)  NULL,         -- PASS | FAIL
    pass_percentage     INT NULL,
    due_at              TIMESTAMPTZ NULL,
    check_in_at         TIMESTAMPTZ NULL,
    check_out_at        TIMESTAMPTZ NULL,
    completed_at        TIMESTAMPTZ NULL,
    completed_by        VARCHAR(255) NULL,
    exception_reason    VARCHAR(1000) NULL,
    remarks             TEXT NULL,
    -- config snapshot flags (frozen at generation, like legacy record_checklist)
    check_in_required   BOOLEAN NOT NULL DEFAULT FALSE,
    score_enabled       BOOLEAN NOT NULL DEFAULT FALSE,
    bypass_scan         BOOLEAN NOT NULL DEFAULT FALSE,
    legacy_id           VARCHAR(64) NULL,
    created_at          TIMESTAMPTZ NOT NULL,
    updated_at          TIMESTAMPTZ NOT NULL
);
CREATE INDEX ix_cl_org_status ON checklist(org_id, status);
CREATE INDEX ix_cl_config ON checklist(config_id);
CREATE INDEX ix_cl_target ON checklist(target_type, target_id);

CREATE TABLE checklist_answer (
    id            UUID PRIMARY KEY,
    checklist_id  UUID NOT NULL REFERENCES checklist(id) ON DELETE CASCADE,
    item_id       UUID NOT NULL,        -- id inside procedure_snapshot
    value         JSONB NULL,           -- typed answer (text, number, bool, media ref, array)
    selected_option_ids UUID[] NULL,    -- for radio/checkbox
    result        VARCHAR(32) NULL,     -- pass/fail/na for this item
    failed        BOOLEAN NOT NULL DEFAULT FALSE,
    comment       VARCHAR(2000) NULL,
    answered_at   TIMESTAMPTZ NOT NULL,
    UNIQUE (checklist_id, item_id)
);

CREATE TABLE checklist_history (
    id            UUID PRIMARY KEY,
    checklist_id  UUID NOT NULL,
    at            TIMESTAMPTZ NOT NULL,
    action        VARCHAR(64) NOT NULL,   -- GENERATED/CHECK_IN/SAVE/SUBMIT/EXCEPTION/REOPEN/ASSIGNEE/WORK_ORDER/...
    previous_status VARCHAR(24) NULL,
    new_status    VARCHAR(24) NULL,
    actor         VARCHAR(255) NULL,
    detail        TEXT NULL
);
CREATE INDEX ix_ch_checklist ON checklist_history(checklist_id);

CREATE TABLE checklist_assignee (        -- legacy multi-assignee
    id UUID PRIMARY KEY,
    checklist_id UUID NOT NULL REFERENCES checklist(id) ON DELETE CASCADE,
    email VARCHAR(255) NOT NULL,
    role  VARCHAR(64) NULL
);

CREATE TABLE checklist_work_order (
    id UUID PRIMARY KEY,
    org_id UUID NOT NULL,
    checklist_id UUID NULL,
    config_id UUID NULL,
    external_wo_id VARCHAR(128) NULL,    -- Corrigo etc.
    wo_number VARCHAR(64) NULL,
    note VARCHAR(1000) NULL,
    created_at TIMESTAMPTZ NOT NULL
);
```

Why snapshot + flat answers instead of the legacy cloned tree:
- **Row-count**: legacy = (items + options) × every run. Snapshot = 1 JSONB per
  run + only-answered rows. Orders of magnitude fewer rows.
- **Reporting/ESG**: `checklist_answer` is flat and indexable (item_id, failed,
  result) — the thing the cloned tree made painful.
- **Immutability**: the snapshot can never drift from what the filler saw;
  template edits are irrelevant to history (legacy got this by cloning; we get
  it cheaper).
- Conditional rendering (option → child items) is evaluated client-side from
  the snapshot — no recursive SQL at runtime.

`inspection_config`, `tagged_procedure`, `procedure_target`, `outer_condition`
keep the shapes already implemented in-memory in this repo (they map 1:1 to
`global_inspection_record` / `global_inspection_relation` /
`global_checklist_conditions`), plus the legacy fields the current form lacks:
`prevent_duplicate_workorder`, `duplicate_workorder_days_limit`,
`enable_result_percentage`, `percentage_value`, `repeat_every`, `end_date`,
`next_occurrence_at`, `trigger_conditions JSONB`, `specific_date`.

### 3.3 Status mapping

| legacy record_checklist.status | new checklist.status |
|---|---|
| `todo` | `TODO` |
| `in_progress` | `IN_PROGRESS` (add to the current in-memory enum; today check_in_at≠null approximates it) |
| `completed` + result `Pass` | `COMPLETE`, result `PASS` |
| `completed` + result `Fail` | `FAILED` (or `COMPLETE`+`FAIL` — pick ONE; recommendation: keep status `COMPLETE` and result `FAIL`, and let "Failed" be a derived filter, since legacy treats failed as a completed outcome) |
| `exception` | `EXCEPTION` (remarks → exception_reason) |
| `incomplete_expired` | `INCOMPLETE` |

## 4. Migration (ETL) plan

Order matters because of cross-service ID mapping:

```
(0) Freeze window / read-replica of MySQL edge DB
(1) locations + devices  → location/asset services   → mapping tables legacy_id → new UUID
(2) global_checklist tree → procedure-service         → template + item + option rows (walk recursion)
(3) global_inspection_record → inspection_config      (epoch millis → timestamptz; frequency string → enum)
(4) global_inspection_relation → tagged_procedure + procedure_target
      association_type 1 → ASSET target, 2 → LOCATION target,
      0/3 → tagged procedure with no target
(5) global_checklist_conditions → outer_condition (alert_profile_id kept as opaque ref)
(6) inspection_record → checklist run batches (or drop the umbrella and keep run_id nullable)
(7) record_checklist tree → checklist + checklist_answer:
      a. serialize the record's OWN item/option tree → procedure_snapshot JSONB
         (do NOT re-point at the migrated template — per-record trees may have
         diverged from the template; the record's tree is the truth)
      b. extract answers: for each item with value/result → checklist_answer row;
         for options with selected=1 → selected_option_ids
      c. map status/result per §3.3; timestamps millis→timestamptz
(8) record_checklist_history / inspection_record_history → checklist_history
(9) workorder → checklist_work_order
```

Mechanics:
- **ID strategy**: mint new UUIDs; keep `legacy_id` on every migrated row and a
  `migration_map(entity, legacy_id, new_id)` table for FK rewiring and re-runs
  (idempotent upsert on legacy_id).
- **Soft deletes**: rows with `is_removed=1` are NOT migrated (or land in an
  archive schema if audit requires).
- **Tenancy**: each edge deployment is one tenant → one target **schema**. The
  migrator provisions the schema first (see §6.2), then loads into it with
  `search_path` pinned to that schema. `migration_map` lives INSIDE the tenant
  schema, so per-tenant re-runs are self-contained and tenants can be migrated
  in parallel with zero cross-talk.
- **Tooling**: a one-off Spring Batch/plain-JDBC migrator per service reading
  the MySQL replica and POSTing/inserting into the new stores; run per-tenant.
- **Validation**: per-table counts (minus is_removed), checksum of
  (status,result) distributions, random N deep-compare of legacy tree vs
  snapshot+answers rendered back, referential sweep (every target_id resolves
  in asset/location service).
- **Cutover**: migrate historical data first (repeatable), then a short freeze
  for the delta (sync_timestamp columns make incremental pulls easy), flip the
  edge server to read-only, final delta, switch clients to the gateway APIs.

## 5. Gaps this exposes in the current (dummy) implementation

1. `IN_PROGRESS` missing from ChecklistStatus (legacy distinguishes it).
2. Answers as a JSON string per question → move to typed `value JSONB` +
   `selected_option_ids` when persistence lands.
3. Procedure model (sections→questions, options as text list) can't express
   conditional child questions — needs `checklist_item(parent_option_id)` from §3.1.
4. Config form lacks: duplicate-workorder guard, result-percentage fields,
   repeat_every/end_date/specific_date, trigger_conditions.
5. Checklist result (`PASS/FAIL`) is separate from status in legacy; current
   code folds it into status (FAILED). Decide per §3.3.
6. Current services are shared-schema (`org_id` column + `WHERE org_id=` in every
   repository, Flyway on the default schema). Moving to schema-per-tenant
   replaces those filters with connection-level schema routing — §6.

## 6. Schema-per-tenant on Postgres — wiring

### 6.1 Layout & registry

- One Postgres **database per service** (unchanged: `sclera_procedure`,
  `sclera_inspection`, …). Inside each, one **schema per tenant** with the
  identical table set, plus a shared `public.tenant_registry`:

```sql
CREATE TABLE public.tenant_registry (
    org_id      UUID PRIMARY KEY,          -- as carried in the JWT (ScleraJwtConverter)
    schema_name VARCHAR(63) NOT NULL UNIQUE,  -- e.g. 't_1111111111114111...' (see naming)
    display_name VARCHAR(200),
    status      VARCHAR(16) NOT NULL DEFAULT 'ACTIVE',  -- ACTIVE | SUSPENDED | MIGRATING
    created_at  TIMESTAMPTZ NOT NULL DEFAULT now()
);
```

- **Naming**: `t_` + org UUID with dashes stripped (fits the 63-char identifier
  limit, valid identifier, no collision risk). Never derive from display names.
- Nothing tenant-scoped lives in `public`; `public` holds only the registry and
  Flyway's own history for it.

### 6.2 Provisioning + Flyway

- New tenant = insert into `tenant_registry` + `CREATE SCHEMA` + run the
  service's Flyway migrations against that schema. Flyway supports this
  directly: `Flyway.configure().schemas(schemaName).defaultSchema(schemaName)`
  — each tenant schema gets its OWN `flyway_schema_history` inside it.
- On service startup: loop `tenant_registry`, migrate every ACTIVE schema
  (fast no-op when up to date). Same loop = how schema upgrades roll out to all
  tenants; a failed migration on one tenant flips it to SUSPENDED instead of
  blocking the fleet.
- The per-tenant Flyway loop replaces the single default-schema Flyway bean
  (disable `spring.flyway.enabled` and drive it programmatically).

### 6.3 Request-time routing (Spring/Hibernate)

Two workable options; **(a) is recommended** for these services:

a. **Hibernate SCHEMA multi-tenancy** —
   `CurrentTenantIdentifierResolver` returns the schema for
   `OrgContext.getOrgId()` (lookup via cached `tenant_registry`);
   `MultiTenantConnectionProvider` acquires a pooled connection and issues
   `SET search_path TO <schema>` on checkout, `SET search_path TO public` on
   release. Properties: `hibernate.multiTenancy=SCHEMA` plus the two provider
   classes. Entities lose their `org_id` filters — repositories become plain
   `findById`, etc.

b. **Plain search_path filter** — a servlet filter/`DataSource` delegate doing
   the same `SET search_path` without Hibernate's MT machinery. Simpler, works
   with the existing JPA config, but easier to bypass accidentally.

Gotchas to engineer for:
- **Pool hygiene**: search_path MUST be reset on connection release, or a
  pooled connection leaks one tenant's schema to the next request. (This is the
  classic schema-per-tenant bug.) HikariCP: do it in the MT connection
  provider's `releaseConnection`.
- **PgBouncer**: transaction-pooling mode discards `SET search_path` between
  transactions — use session pooling, or `SET LOCAL` inside the transaction.
- **Prepared-statement caching** (JDBC + Hibernate) caches plans per
  connection+SQL, and unqualified table names resolve per search_path —
  Postgres handles re-resolution correctly (plans are search_path-sensitive
  since PG 9.x), but keep `prepareThreshold` defaults and never schema-qualify
  table names in JPQL/SQL.
- **The missing-context case**: requests with no resolvable org (internal HMAC
  endpoints, actuator) must pin `search_path=public` and never touch tenant
  tables implicitly.
- **Scale envelope**: schema-per-tenant is comfortable to ~low thousands of
  tenants per DB (catalogs bloat beyond that). Edge-migrated tenant counts fit
  easily; revisit only if tenant count explodes.
- **Cross-tenant reporting** becomes a deliberate act (UNION ALL views over
  schemas / FDW / export to a warehouse) — an advantage for isolation, a cost
  for global dashboards like platform-admin views.

### 6.4 What changes in the existing service code

| Today (shared-schema) | Schema-per-tenant |
|---|---|
| `org_id` column on every table, part of lookups | column optional (cross-check only); lookups by PK |
| `repository.findByIdAndOrgId(id, OrgContext.getOrgId())` | `repository.findById(id)` — isolation is the connection's search_path |
| Single Flyway run on default schema | per-tenant Flyway loop keyed off `tenant_registry` |
| One shared `flyway_schema_history` | one per tenant schema |
| Gateway relays JWT → service filters rows | unchanged JWT flow; `ScleraJwtConverter`/`OrgContext` now feed the tenant resolver instead of row filters |

The in-memory dummies in this repo emulate the OLD pattern (org filter in the
repository). When persistence lands, implement §6.3(a) in each service and the
dummy repositories' org filtering simply disappears.
```

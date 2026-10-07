# OpenFGA — fine-grained authorization

The app-plane services use [OpenFGA](https://openfga.dev) for role-based and
per-object data access, layered on top of the existing Keycloak JWT + `org_id`
tenant isolation. Keycloak answers *who you are*; OpenFGA answers *what you may
do*.

## Running it

```powershell
docker compose up -d          # includes openfga (:8085 HTTP, :8086 gRPC) + its Postgres DB
.\setup-keycloak.ps1          # if Keycloak is fresh
.\setup-openfga.ps1           # store + model + grants testuser org admin
```

`setup-openfga.ps1` is idempotent: it creates the `sclera_openfga` database if
the postgres volume predates it, creates the `sclera` store, writes
`docker/openfga/authorization-model.json` as the active model, and seeds
`user:<testuser-sub> admin organization:1111…`.

## The model

Source of truth: `docker/openfga/authorization-model.json` (posted by the setup
script). `docker/openfga/model.fga` is the readable DSL mirror — keep both in
sync.

Identity mapping (from `ScleraJwtConverter` / `OrgContext`):

| FGA object | Comes from |
|---|---|
| `user:<uuid>` | JWT `sub` (Keycloak user id) |
| `organization:<uuid>` | `org_id` claim (the tenant) |
| `inspection:<uuid>`, `procedure_template:<uuid>`, … | domain object ids |

Org-level roles (assigned as tuples on `organization`):

| Role | Gives |
|---|---|
| `admin` | everything in the organization |
| `viewer` | read-only |
| `inspector` | run checklists (`can_manage_inspections`) |
| `supervisor` | review completed work (`can_review`) |
| `template_author` | write and edit procedures (`can_manage_templates`) |
| `template_publisher` | publish a draft as a version (`can_publish_templates`) |
| `template_importer` | import and export (`can_import_templates`) |
| `result_type_manager` | change the organization's result types (`can_manage_result_types`) |
| `asset_manager` | manage locations and assets (`can_manage_assets`) |

Each permission is `admin or <its role>`, and `can_administer` is `admin`
alone. `can_view` derives from `member`, which lists **every** role: a role
missing from it can act but cannot read, so a new role must be added there too.
`can_import_templates` and `can_review` are declared but not yet checked by any
endpoint.

The four tiers of the role matrix are bundles of these: an organization admin
holds `admin`; a supervisor holds `inspector` **and** `supervisor` (running
comes from the first, reviewing from the second); an inspector holds
`inspector`; a Sclera admin holds nothing and bypasses checks through the
`is_platform_admin` claim.

**Properties** (`property:<uuid>`) belong to an organization through an `org`
tuple. `viewer` on a property lets someone open it; an organization admin
reaches every property of their organization without one. The procedure
service checks `can_view` on the property named in `X-Sclera-Property`, and
`GET /api/v1/me/properties` lists the properties the caller may open.

Per-object types add `org` / `creator` / `assignee` relations, so e.g.
`inspection.can_edit` = creator **or** assignee **or** anyone with
`can_manage_inspections` on the owning org.

### Demo accounts

`.\setup-demo-users.ps1` (idempotent) creates these accounts, password =
username, all in org `1111…`, and two properties, VDMS001 and VDMS002. Run
`setup-openfga.ps1` first: against an older model the newer tuples are
rejected and the script stops partway.

| Account | Mechanism | Effective access |
|---|---|---|
| `demo-user` | FGA `viewer` tuple | read everything, mutate nothing (unless made creator/assignee of an object); no property |
| `demo-admin` | FGA `admin` tuple | full org control; both properties |
| `demo-superadmin` | `is_platform_admin=true` JWT claim | bypasses FGA entirely (no tuples needed); still subject to row-level security |
| `demo-inspector` | `inspector` + `viewer` on VDMS001 | runs checklists; VDMS001 only |
| `demo-inspector2` | `inspector` + `viewer` on VDMS002 | runs checklists; VDMS002 only |
| `demo-supervisor` | `inspector` + `supervisor` | runs and reviews; cannot author, publish or change result types |
| `demo-author` | `template_author` | drafts procedures; cannot publish them or change result types |

`template_publisher`, `template_importer` and `result_type_manager` have no demo
account; `AuthorizationModelIT` covers them.

The script also adds the boolean `is_platform_admin` claim mapper to the
`sclera-app`/`sclera-bff` clients (read by `ScleraJwtConverter` into
`OrgContext.isPlatformAdmin()`).

### Granting roles

```powershell
$store = '<store id printed by setup-openfga.ps1>'
Invoke-RestMethod -Method Post -Uri "http://localhost:8085/stores/$store/write" `
  -ContentType application/json -Body (@{ writes = @{ tuple_keys = @(
    @{ user = 'user:<keycloak-sub>'; relation = 'inspector'
       object = 'organization:11111111-1111-1111-1111-111111111111' }
  ) } } | ConvertTo-Json -Depth 6)
```

## How the services enforce it

Each service has an `authz` package (duplicated per service, same as
`SecurityConfig`, because sclera-common is a prebuilt jar):

- `FgaProperties` — `sclera.fga.*` config (see application.yml).
- `FgaConfig` — builds the `OpenFgaClient` (SDK `dev.openfga:openfga-sdk`).
- `FgaAuthorizationService` — Spring bean named **`fga`**, used from
  `@PreAuthorize` (enabled via `@EnableMethodSecurity` on each SecurityConfig):

```java
@PreAuthorize("@fga.checkOrg('can_manage_inspections')")   // org-level
@PreAuthorize("@fga.check('inspection', #id, 'can_view')") // per-object
```

The store is resolved **by name** on first use and pre-warmed at startup (no
store-id copying between machines); checks **fail closed** if OpenFGA is
unreachable; platform admins bypass checks. Tuple writes on create
(`InspectionService.create`, `ProcedureTemplateService.create` via
`fga.grantCreated(...)`, one batched write) throw on failure so the DB
transaction rolls back.

**Latency:** check decisions are cached in-process (Caffeine) per
user/relation/object for `check-cache-ttl-seconds` (default 30s), so repeated
requests skip the FGA round trip entirely. The trade-off: role/tuple changes
take up to the TTL to be reflected. Set it to `0` to disable caching.

Coverage:

| Service | Enforcement |
|---|---|
| inspection-service | per-object on `/inspections/{id}` (creator/assignee/org role); org-level on create/list, inspection-configs, checklists, tagging |
| procedure-service | per-object on `/procedure-templates/{id}` (edit; publish needs `can_publish_templates`; delete); org-level on create/list; result types read with `can_view`, changed with `can_manage_result_types`; `X-Sclera-Property` checked against `property.can_view`; `/me/properties` and `/me/permissions` with `can_view` |
| helper-service | org-level on locations & assets (`can_manage_assets` / `can_view`) — in-memory resources, no per-object tuples |

`/internal/**` (Dapr + HMAC service-to-service) and actuator/swagger endpoints
are untouched by FGA.

## Config knobs (all services)

```yaml
sclera:
  fga:
    enabled: ${SCLERA_FGA_ENABLED:true}        # false = all checks allow (JWT + org scoping still apply)
    api-url: ${SCLERA_FGA_API_URL:http://localhost:8085}
    store-name: ${SCLERA_FGA_STORE:sclera}
    store-id: ${SCLERA_FGA_STORE_ID:}          # optional, skips lookup by name
    authorization-model-id: ${SCLERA_FGA_MODEL_ID:}  # optional pin, default = latest
    check-cache-ttl-seconds: 30                # decision cache TTL; 0 = check FGA every request
```

In the fully-containerized profile the services get
`SCLERA_FGA_API_URL=http://openfga:8080`.

## Backfilling pre-existing rows

Rows created before this integration have no tuples, so per-object checks on
them deny (org-level endpoints still work). Backfill by writing the `org` (and
optionally `creator`/`assignee`) tuples, e.g. for inspections:

```powershell
$store = '<store id>'
$rows = docker exec sclera-postgres psql -U sclera -d sclera_inspection -tAc `
  "SELECT id || '|' || org_id || '|' || COALESCE(created_by::text,'') FROM inspection"
foreach ($row in $rows) {
  $id, $org, $creator = $row.Split('|')
  $keys = @(@{ user = "organization:$org"; relation = 'org'; object = "inspection:$id" })
  if ($creator) { $keys += @{ user = "user:$creator"; relation = 'creator'; object = "inspection:$id" } }
  Invoke-RestMethod -Method Post -Uri "http://localhost:8085/stores/$store/write" `
    -ContentType application/json -Body (@{ writes = @{ tuple_keys = $keys } } | ConvertTo-Json -Depth 6)
}
```

Same shape for `procedure_template` against `sclera_procedure`.

## Extending

New resource type → add it to both model files (org/creator relations + `can_*`
permissions), re-run `setup-openfga.ps1` (writes a new model version; services
pick up the latest automatically), annotate the controller, and call
`fga.grantCreated(type, id, creatorId, assigneeId)` where the entity is created.

New role → add it to `member` as well as defining it, in both files, and under
`metadata` in the JSON.

The procedure service's tests hold the model to this:

- `AuthorizationModelTest` — the two files define the same relations, every
  JSON relation has its metadata entry, and every assignable role is in
  `member`. No container.
- `AuthorizationModelIT` — posts the JSON to a throwaway OpenFGA, exactly as
  the setup script does, and checks the role matrix answer by answer.
- `ControllerAuthorizationTest` — every endpoint has a `@PreAuthorize`, and
  every relation one names exists in the model. A misspelt relation would
  otherwise pass every test and deny everyone in production.

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
| `inspection:<uuid>`, `question_template:<uuid>`, … | domain object ids |

Org-level roles (assigned as tuples on `organization`): `admin`, `inspector`,
`template_author`, `asset_manager`, `viewer`. They derive the permissions the
services check: `can_view`, `can_manage_inspections`, `can_manage_templates`,
`can_manage_assets`, `can_administer`.

Per-object types add `org` / `creator` / `assignee` relations, so e.g.
`inspection.can_edit` = creator **or** assignee **or** anyone with
`can_manage_inspections` on the owning org.

### Demo accounts

`.\setup-demo-users.ps1` (idempotent) creates one account per authorization
tier, password = username, all in org `1111…`:

| Account | Mechanism | Effective access |
|---|---|---|
| `demo-user` | FGA `viewer` tuple | read everything, mutate nothing (unless made creator/assignee of an object) |
| `demo-admin` | FGA `admin` tuple | full org control |
| `demo-superadmin` | `is_platform_admin=true` JWT claim | bypasses FGA entirely (no tuples needed) |

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
(`InspectionService.create`, `QuestionTemplateService.create` via
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
| procedure-service | per-object on `/question-templates/{id}` (edit/publish/delete); org-level on create/list |
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

Same shape for `question_template` against `sclera_procedure`.

## Extending

New resource type → add it to both model files (org/creator relations + `can_*`
permissions), re-run `setup-openfga.ps1` (writes a new model version; services
pick up the latest automatically), annotate the controller, and call
`fga.grantCreated(type, id, creatorId, assigneeId)` where the entity is created.

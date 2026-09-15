# Sclera Application Plane — VDMS Inspection Module

Spring Boot microservices built on the shared `sclera-common` library
(see `sclera-common-guide.html`), implementing the VDMS Inspection Module:
locations & assets, procedures, inspection configuration + tagging, the
checklist lifecycle (To-Do → Complete / Failed / Exception / Incomplete),
Task Dashboard, Task Map, Reactive Service (QR) and Tagged Procedures.

| Service | Port | Dapr app-id | Database | Purpose |
|---|---|---|---|---|
| `sclera-procedure-service` | 8095 | `sclera-procedure-service` | `sclera_procedure` | Author question templates (procedures): draft → publish → archive |
| `sclera-inspection-service` | 8096 | `sclera-inspection-service` | `sclera_inspection` | Inspection configs, tagging, checklist lifecycle, reactive services (QR), tagged procedures — plus the original template-run flow |
| `sclera-helper-service` | 8097 | — (no sidecar) | `sclera_helper` | Locations (building → floor → location) and assets (IP / non-IP) |
| `sclera2.0v-api-gateway` | 8080 | — (no sidecar locally) | — (Redis sessions) | Single entry point: BFF login (session cookie + CSRF), JWT relay, routing |

## Architecture

```
  React SPA (frontend/, :5173)
        │  BFF session cookie + X-CSRF-Token (no tokens in the browser)
        ▼
  api-gateway (:8080) ── Keycloak (:8180, realm sclera; sclera-bff code+PKCE,
        │                 sclera-app password grant for the dev login)
        │  relays Authorization: Bearer <user JWT> downstream
        │  routes: /question-templates → 8095 · /inspections|/inspection-configs
        │          /checklists|/reactive-services|/tagged-procedures → 8096
        │          /helper/** → 8097
        ▼
  procedure-service (8095)   inspection-service (8096)   helper-service (8097)
  templates CRUD/publish     configs·tagging·checklists  locations & assets
        │                        │  ▲                        │
        │ Kafka                  │  │ Dapr invocation        │
        │ template-events.v1 ────┘  │ (HMAC): template       │
        │                           │ snapshot by org        │
        ▼                           ▼                        ▼
  PostgreSQL sclera_procedure   sclera_inspection        sclera_helper
        └────────────── schema-per-tenant in every DB ──────────────┘
                (public.tenant_registry → t_<org-uuid> schemas)

  Cross-cutting: OpenFGA (:8085) fine-grained authz (@PreAuthorize "@fga…"),
  inspection-service publishes sclera.inspection.events.v1 on completion.
```

Design decisions baked in:

- **Schema-per-tenant** — each org gets its own Postgres schema per service
  database, resolved from the JWT at connection time (`SET search_path`,
  reset on pool release). Tenants are auto-provisioned on first request:
  registry row → `CREATE SCHEMA` → per-schema Flyway (`db/tenant`, own
  history). `org_id` columns/filters remain as defense-in-depth only.
  Design + legacy migration plan: `docs/inspection-schema-migration.md`.
- **Gateway as the only door** — the SPA never calls services directly and
  never holds tokens; the gateway's BFF session (HttpOnly cookie + CSRF
  double-submit) is exchanged for the user's Keycloak JWT on every
  downstream call, so `ScleraJwtConverter`/`OrgContext` work unchanged.
- **Template snapshotting** — when an inspection is created, the full template
  (fetched from procedure-service over Dapr) is copied into the inspection row
  as JSONB. Later template edits never corrupt in-flight or historical inspections.
- **Checklists are the common currency** — inspections, reactive-service
  requests and tagged procedures all produce `checklist` rows distinguished
  by `source`, so the Task Dashboard, Task Map and lifecycle actions work
  for every origin with no special cases.
- **OpenFGA for fine-grained authz** — controllers guard with
  `@PreAuthorize("@fga.checkOrg(...)")`; per-object tuples are written on
  create (see `docs/openfga.md`, `setup-openfga.ps1`).
- **Kafka for facts, Dapr for questions** — lifecycle facts (template published,
  inspection completed) stream through Kafka; synchronous lookups (fetch template
  by id) go over Dapr service invocation via `DaprInvocationHelper`; internal
  endpoints are HMAC-signed and carry the org id explicitly (no user token).

## Prerequisites

1. **JDK 21** (e.g. Temurin) and **Maven 3.9+** on PATH
2. **Docker Desktop** (for Postgres/Redis/Kafka/Keycloak) — must be **running** before `docker compose` or `dapr init`
3. **Dapr CLI** initialized: `dapr init`

On this machine these are installed portably (no admin) under `C:\Users\RamakrishnaShetty\tools\`
(`jdk-21.0.12.1+1`, `apache-maven-3.9.11`, `dapr`) and added to the **user** PATH +
`JAVA_HOME`. Terminals opened before that change need to be restarted to pick it up.

> `dapr init` note: the compose file's Redis and Dapr's default Redis both want port 6379.
> If `dapr init` fails with "Bind for 0.0.0.0:6379 failed", run
> `docker stop sclera-redis; dapr init; docker rm -f dapr_redis; docker start sclera-redis`
> — Dapr's default components simply use whatever Redis is on localhost:6379.

## First-time setup

```powershell
# 1. Install the shared library into your local Maven repo (repeat when you get a new jar)
#    NOTE 1: the quotes around each -D argument are required in PowerShell — without them
#    PowerShell splits the dotted version numbers and Maven sees a truncated file name.
#    NOTE 2: -DgeneratePom=true is required — the jar's embedded POM declares parent
#    com.sclera:sclera-control-plane, which is not published, so builds fail with
#    "Failed to read artifact descriptor" unless a minimal POM is generated instead.
mvn install:install-file "-Dfile=jars/sclera-common-0.1.0-SNAPSHOT.jar" `
  "-DgroupId=com.sclera" "-DartifactId=sclera-common" "-Dversion=0.1.0-SNAPSHOT" `
  "-Dpackaging=jar" "-DgeneratePom=true"

# 2. Start infrastructure (Postgres + per-service DBs, Redis, Kafka, Keycloak on :8180)
docker compose up -d

# 3. Build everything
mvn clean package -DskipTests

# 4. Run each service with its Dapr sidecar (two terminals)
.\run-procedure-service.ps1
.\run-inspection-service.ps1
```

Verify: `http://localhost:8095/actuator/health/readiness` and
`http://localhost:8096/actuator/health/readiness`; Swagger UI at
`/swagger-ui/index.html` on both (dev profile only).

## Running the project (day-to-day)

After first-time setup, this is all you need:

```powershell
# 1. Make sure Docker Desktop is running, then start the infrastructure
docker compose up -d

# 2. If Keycloak was recreated (first run, or after `docker compose down`),
#    re-create the realm + test user (idempotent, takes a few seconds)
.\setup-keycloak.ps1

# 2b. Bootstrap OpenFGA (authorization store + model + testuser org-admin grant).
#     Idempotent; needed on first run and after the openfga store is wiped.
#     See docs/openfga.md for the model and how roles are granted.
.\setup-openfga.ps1

# 3. Start the services — one terminal each
.\run-procedure-service.ps1     # procedure-service  on :8095 (Dapr sidecar)
.\run-inspection-service.ps1    # inspection-service on :8096 (Dapr sidecar)
.\run-helper-service.ps1        # helper-service     on :8097 (plain jar)
.\run-api-gateway.ps1           # api-gateway        on :8080 (plain jar)

# 4. Frontend
cd frontend; npm run dev        # http://localhost:5173 → testuser / testuser
```

Wait until both readiness probes report `UP` (first start takes ~1 min):

- http://localhost:8095/actuator/health/readiness
- http://localhost:8096/actuator/health/readiness

Then grab a token and call the APIs (see the token snippet below and the
"Typical flow" section). Swagger UI: `http://localhost:8095/swagger-ui/index.html`
and `http://localhost:8096/swagger-ui/index.html`.

To stop: `Ctrl+C` in the service terminals (or `dapr stop --app-id sclera-procedure-service`
/ `--app-id sclera-inspection-service`), then `docker compose down` if you also want
the infrastructure gone (note: this wipes Keycloak — re-run `setup-keycloak.ps1` next time).

### Fully containerized alternative

No JDK/Maven/Dapr CLI needed on the host — everything (services, Dapr sidecars,
infrastructure) runs in Docker:

```powershell
docker compose --profile app up -d --build    # first build takes a few minutes
.\setup-keycloak.ps1                          # if Keycloak is fresh
```

Ports and usage are identical to host mode (8095/8096, tokens from
`localhost:8180`). Don't mix modes — the host `run-*.ps1` scripts and the `app`
profile fight over the same ports. Notes on how it works:

- `Dockerfile` is a multi-stage build (`MODULE` build-arg selects the service);
  it installs the sclera-common jar into the build container itself.
- Each service gets a `daprd` sidecar sharing its network namespace, mirroring
  what `dapr run` does on the host.
- Sidecars discover each other through a sqlite name-resolution database on a
  shared volume (`docker/dapr/config.yaml`) — the self-hosted mDNS default
  doesn't work across containers.

Stop with `docker compose --profile app down`.

## Frontend (`frontend/`)

A React + Vite + TypeScript app covering the full flow: login (Keycloak) →
author/publish templates → create, run and complete inspections.

```powershell
cd frontend
npm install      # first time only
npm run dev      # http://localhost:5173
```

Sign in with the Keycloak test user (`testuser` / `testuser` from
`setup-keycloak.ps1`). The backend services and Keycloak must be running.

The frontend talks to everything through the **API gateway** (see the "API
gateway integration" section below) — it never calls the services directly.

Notes:

- The Vite dev server proxies `/api` → the gateway (8080) and `/auth` →
  Keycloak (8180, used only by the dev login path). The browser stays
  same-origin, so no backend CORS changes are needed.
- Auth is the gateway's BFF model: an HttpOnly `sclera-session` cookie plus a
  readable `sclera-csrf` cookie mirrored into the `X-CSRF-Token` header (with
  the `X-Requested-With: sclera-spa` sentinel) on every mutating request. No
  tokens are stored in the browser. `frontend/src/api/client.ts` handles this.
- The login page offers two paths, both ending in a gateway session cookie:
  the **dev direct login** (Keycloak password grant → the gateway's
  `POST /api/auth/test-exchange`, which mints a session from the token) and
  the **SSO redirect** button (production `POST /api/v1/auth/login` →
  full-page redirect to Keycloak → `/api/v1/auth/callback`).
- All responses are unwrapped from the sclera-common
  `{success, data, pagination, error, meta}` envelope in
  `frontend/src/api/client.ts`.

Keycloak note: the compose file starts a blank Keycloak at `http://localhost:8180`
(admin/admin). Run `.\setup-keycloak.ps1` to create the `sclera` realm, the
`sclera-app` client (password grant enabled) and a `testuser`/`testuser` user with
the `org_id`/`org_type` claims `ScleraJwtConverter` requires. Keycloak has no
compose volume, so re-run the script after `docker compose down`. Without a valid
JWT every `/api/**` request returns 401 (actuator, swagger and `/internal/**` stay
open). Get a token:

```powershell
$tok = (Invoke-RestMethod -Method Post `
  -Uri http://localhost:8180/realms/sclera/protocol/openid-connect/token `
  -Body @{ grant_type='password'; client_id='sclera-app'; username='testuser'; password='testuser' }).access_token
```

## API gateway integration (`sclera2.0v-api-gateway/`)

The control-plane Spring Cloud Gateway is the single entry point for the SPA.
Two routes were added to `sclera2.0v-api-gateway/src/main/resources/application.yml`
for the application-plane services:

| Route id | Path predicate | Downstream (env var) |
|---|---|---|
| `procedure-service` | `/api/v1/question-templates/**` | `PROCEDURE_SERVICE_URL` (`:8095`) |
| `inspection-service` | `/api/v1/inspections/**` | `INSPECTION_SERVICE_URL` (`:8096`) |

The gateway validates the BFF session, relays the user's Keycloak access token
to the service as `Authorization: Bearer` (so `ScleraJwtConverter` still reads
`org_id` from the JWT), and enforces CSRF on mutating requests.

### Building the gateway locally

The gateway's Maven parent (`com.sclera:sclera-control-plane`) is not published
in this workspace. A local stub lives at `jars/sclera-control-plane-parent.pom`
(Spring Boot 3.3.7 + Spring Cloud 2023.0.6 BOM, plus the transitive deps the
shipped `sclera-common` jar needs at runtime but doesn't declare: `spring-boot-
starter-web` for the servlet `ResponseEnvelopeAdvice`, `caffeine`, `spring-boot-
starter-aop`, and `nimbus-jose-jwt` 10.x for `JWSAlgorithm.Ed25519`). Install it,
plus an empty `sclera-common-test` stub, then build (tests are skipped — they
need ArchUnit/Spring-Cloud-Contract not on the local classpath):

```powershell
mvn install:install-file "-Dfile=jars/sclera-control-plane-parent.pom" `
  "-DgroupId=com.sclera" "-DartifactId=sclera-control-plane" `
  "-Dversion=0.1.0-SNAPSHOT" "-Dpackaging=pom"
mvn -f sclera2.0v-api-gateway/pom.xml clean package "-Dmaven.test.skip=true"
```

### Running the gateway

```powershell
.\setup-keycloak.ps1        # creates the confidential sclera-bff client (secret: dev-bff-secret)
.\run-api-gateway.ps1       # starts it on :8080
```

`run-api-gateway.ps1` sets the dev environment: downstream URLs, `KEYCLOAK_URL`,
the `sclera-bff` client secret, non-Secure cookies (`BFF_COOKIE_SECURE=false`),
`SCLERA_BFF_TEST_EXCHANGE_ENABLED=true` (for the SPA dev login), and the HMAC
`sclera.event-listener.signing-secret`. The gateway runs **without** a Dapr
sidecar locally; use `/actuator/health/readiness` (plain `/actuator/health`
reports DOWN without the sidecar).

Full local stack order: `docker compose up -d` → `.\setup-keycloak.ps1` →
`.\run-procedure-service.ps1` + `.\run-inspection-service.ps1` →
`.\run-api-gateway.ps1` → `cd frontend; npm run dev`.

## Typical flow

```
# 1. Author a template (procedure-service)
POST /api/v1/question-templates
{
  "name": "Forklift Daily Check",
  "category": "safety",
  "sections": [{
    "title": "Pre-operation",
    "displayOrder": 1,
    "questions": [
      { "text": "Tires in good condition?", "type": "SINGLE_CHOICE",
        "required": true, "displayOrder": 1, "options": ["Pass", "Fail", "N/A"] },
      { "text": "Fork damage notes", "type": "TEXT", "required": false, "displayOrder": 2 }
    ]
  }]
}

# 2. Publish it (bumps version, emits Kafka event)
POST /api/v1/question-templates/{id}/publish

# 3. Create an inspection from it (inspection-service — snapshots the template via Dapr)
POST /api/v1/inspections            { "templateId": "..." }

# 4. Execute
POST /api/v1/inspections/{id}/start
PUT  /api/v1/inspections/{id}/answers
     { "answers": [ { "questionId": "...", "value": "Pass" } ] }
POST /api/v1/inspections/{id}/complete    # validates required questions, emits Kafka event
```

## Kafka topics

| Topic | Producer | Consumers | Payload |
|---|---|---|---|
| `sclera.procedure.template-events.v1` | procedure-service | inspection-service (`TemplateEventListener`) | `QuestionTemplateEvent` (PUBLISHED / UPDATED / ARCHIVED) |
| `sclera.inspection.events.v1` | inspection-service | (future: metrics, notification) | `InspectionCompletedEvent` |

## Key environment variables

Both services follow the sclera-common conventions (full list in the guide):

| Var | Default (dev) | Notes |
|---|---|---|
| `SCLERA_SERVICE_NAME` | `procedure-service` / `inspection-service` | already defaulted per service |
| `SPRING_PROFILES_ACTIVE` | `dev` | **always** set `prod` outside local dev |
| `KEYCLOAK_URL` / `KEYCLOAK_REALM` | `http://localhost:8180` / `sclera` | drives issuer + JWKS |
| `SCLERA_DB_NAME` (dev) / `SCLERA_DB_URL` (prod) | per-service DB | |
| `SCLERA_KAFKA_BOOTSTRAP_SERVERS` | `localhost:9092` | required in prod |
| `SCLERA_INTERNAL_SIGNING_SECRET` | `dev-internal-signing-secret` | **must be identical in both services** or internal calls fail `401 HMAC_FAILED`; required in prod |
| `SCLERA_DAPR_HEALTH_ENABLED` | `false` | set `true` when running under `dapr run` |

## About the Spring Boot version

`sclera-common 0.1.0-SNAPSHOT` is compiled against **Spring Boot 3.3.7 / Java 21**,
so these services are pinned to 3.3.7 (see root `pom.xml`). Running them on Spring
Boot 4.x would break the shared security filters and auto-configuration at runtime.
When the platform team ships a Boot 4.x `sclera-common`, bump only the parent
version in the root `pom.xml`.

## Repo layout

```
pom.xml                          aggregator (Boot 3.3.7 parent, shared versions)
docker-compose.yml               Postgres, Redis, Kafka (KRaft), Keycloak
docker/postgres-init/            creates sclera_procedure + sclera_inspection DBs
run-*.ps1                        dapr run wrappers per service
jars/                            the shipped sclera-common jar
sclera-procedure-service/        template authoring service
sclera-inspection-service/       inspection execution service
```

Each service follows the sclera-common checklist: common package in
`scanBasePackages`, `SecurityConfig` with `ScleraJwtConverter` + `MdcFilter` +
`InternalEndpointFilter`, `GlobalExceptionHandler extends GlobalExceptionHandlerBase`,
config + logback templates copied in, Flyway migration under `db/migration`.

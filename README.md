# Sclera Application Plane — Inspection & Procedure Services

Two Spring Boot microservices built on the shared `sclera-common` library
(see `sclera-common-guide.html` for the library's full documentation):

| Service | Port | Dapr app-id | Database | Purpose |
|---|---|---|---|---|
| `sclera-procedure-service` | 8095 | `sclera-procedure-service` | `sclera_procedure` | Author question templates (procedures): draft → publish → archive |
| `sclera-inspection-service` | 8096 | `sclera-inspection-service` | `sclera_inspection` | Execute inspections against **published** templates |

## Architecture

```
                 Keycloak JWT (user traffic)
                        │
        ┌───────────────┴────────────────┐
        ▼                                ▼
  procedure-service (8095)        inspection-service (8096)
  templates: CRUD/publish         inspections: create/answer/complete
        │                                │  ▲
        │ Kafka                          │  │ Dapr invocation (HMAC-signed)
        │ sclera.procedure.              │  │ GET /internal/api/v1/
        │   template-events.v1  ─────────┘  │   question-templates/{id}/orgs/{orgId}
        │                                   │
        └── PostgreSQL (sclera_procedure)   └── PostgreSQL (sclera_inspection)

  inspection-service also publishes sclera.inspection.events.v1 on completion.
```

Design decisions baked in:

- **Template snapshotting** — when an inspection is created, the full template
  (fetched from procedure-service over Dapr) is copied into the inspection row
  as JSONB. Later template edits never corrupt in-flight or historical inspections.
- **Tenant isolation** — every query filters by `OrgContext.getOrgId()` (filled
  from the JWT by `ScleraJwtConverter`). Internal Dapr calls carry the org id
  explicitly since they have no user token; HMAC + `InternalEndpointFilter`
  guard the transport.
- **Kafka for facts, Dapr for questions** — lifecycle facts (template published,
  inspection completed) stream through Kafka; synchronous lookups (fetch template
  by id) go over Dapr service invocation via `DaprInvocationHelper`.

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

# 3. Start each service with its Dapr sidecar — one terminal each
.\run-procedure-service.ps1     # procedure-service  on :8095
.\run-inspection-service.ps1    # inspection-service on :8096
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

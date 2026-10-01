# Sclera Procedure Service

Owns **procedure templates**: versioned, immutable definitions of what an inspection asks, how it is
scored, what results it can produce and what evidence it collects. Templates are authored once,
shared across organizations, and pinned by whoever runs them — so a change to a template can never
rewrite what somebody already answered.

Procedures are not only for inspections. The same structure serves case services, ERP workflows and
task procedures; the consumer is a configurable value on the template, not a hardcoded enum.

Java 21 · Spring Boot 3.3.7 · Postgres (`sclera_procedure`, schema-per-tenant) · Kafka · Dapr ·
OpenFGA · port **8095** · package root `com.sclera.applicationplane.procedure`

---

## What this service owns, and what it does not

| Owns | Does not own |
|---|---|
| Procedure templates and their versions | Inspections, records, checklists, answers |
| The question tree: categories, questions, sub-questions, options | Evidence captured during an inspection |
| Result types per organization (Pass, Fail, Amber, …) | Signature capture and record locking |
| Scoring configuration and the evaluation of answers into results | Schedules, assignees, downtime |
| Reference documents attached to a template or question | The property hierarchy and its vocabulary |
| The global template library, import and export | Cases and work orders |

The rule across Sclera: **store the foreign id plus a denormalised display name, never a database
foreign key across services.**

---

## Concepts

| Term | Meaning |
|---|---|
| **Procedure template** | The identity of a procedure — its name, description and consumer. Holds no content. One row, forever, however many times it is edited. |
| **Version** | The content: an immutable, content-addressed definition of the form. A template has many versions over its life. |
| **Category** | A grouping of questions inside a version, carrying an optional scoring weight. |
| **Question / sub-question** | A single item to answer. Questions nest to any depth; a sub-question appears when its display condition matches the parent's answer. |
| **Option** | One possible answer to a choice question, carrying a stable key, a score and the result it produces. |
| **Result type** | An outcome an answer, question, category or whole inspection can produce. Defined per organization, with a colour and a severity rank where **1 is most severe**. |
| **Consumer** | Who the template is for: inspection, case, ERP, task procedure. A configurable value. |
| **Target type** | What the template applies to — a hierarchy level, location type, asset class or tag. |
| **Global copy** | The platform-level master of a template. Organizations import it and are notified when it changes; each decides when to apply the update. |

---

## The central design decision

### A version is a definition, and a definition is immutable

Editing a template never rewrites history. A **draft** version is mutable and there is at most one
per template. Publishing freezes it: the state flips to `PUBLISHED`, the document is hashed, and that
row is never modified again. Consumers pin a version id, so whatever they pinned still renders the
questions that were actually asked.

Every published version is **content-addressed**. `definition_hash` is the SHA-256 of
`definition_json`, and a template cannot hold two published versions with the same hash — so
publishing a draft that changed nothing is a no-op rather than a spurious new version.

### Two pointers, and neither derives the other

```
procedure_template  "Extinguisher check"
    current_published_version_id ──► v3      MOVING. Always the current form.
                                             Repointed on every publish.

checklist (inspection service, Jan 2024)
    procedure_id ────────────────► the template   which procedure is this?
    procedure_version_id ────────► v1             FROZEN. Which form was filled in?
```

Resolving the second through the first would render today's questions over yesterday's answers.
Both pointers are permanent; neither is scaffolding.

### One representation of the form

There is no `question` table and no `question_option` table. A version's entire content — categories
and weights, the nested question tree, options with their result mapping and scores, numeric and date
rules, thresholds, target types, signature requirements — lives in `definition_json` and nowhere else.

The two things that must be queried independently of the document get small derived index tables,
written at publish time. Everything else is read with its version or not at all.

`definition_json` is stored as **`TEXT`**, not `jsonb`. Postgres `jsonb` sorts keys and strips
whitespace, which would break the hash on a round trip. The document is canonicalised on every write
— one line, no whitespace, fixed key order, empty values omitted — so publishing is a state flip plus
a hash rather than a separate materialisation step.

Structural rules that a relational schema would enforce are enforced instead by a strict validator
that runs on every write, backed by a JSON Schema: key uniqueness across the whole version, a parent
that exists, options present on choice types, and a `result_type_key` that exists and is active.

---

## Data model

Ten tables, all inside the per-tenant schema except the global library.

### `procedure_template` — identity

| Column | Notes |
|---|---|
| `id`, `org_id`, `name`, `description` | |
| `consumer_key` | → `procedure_consumer`. Not part of the definition document: it is identity and does not vary by version. |
| `status` | `ACTIVE`, `ARCHIVED`. Draft and published belong to the *version*, not here. |
| `current_published_version_id` | the moving pointer |
| `key_seq` | per-template counter for minting question keys. Lives here, not on the version, because a key must never be reused across **any** version. |
| `global_template_id` | an immutable breadcrumb to the global copy. The **link state is not here** — it lives once, on `global_template_org_copy`, because the global side has to find who is linked without scanning every tenant schema. |
| `legacy_id` | provenance from 1.0. `UNIQUE(org_id, legacy_id)` |
| `created_by`, `created_at`, `updated_at` | |

### `procedure_template_version` — the definition

| Column | Notes |
|---|---|
| `id`, `template_id`, `version_no` | `UNIQUE(template_id, version_no)` |
| `state` | `DRAFT`, `PUBLISHED`, `ARCHIVED`. At most one `DRAFT` per template. |
| `origin` | `AUTHORED`, `MIGRATED`, `IMPORTED`, `GLOBAL_PUSH` |
| `definition_json` | **TEXT** — the whole form, canonical bytes |
| `definition_hash` | SHA-256 of those bytes. `UNIQUE(template_id, definition_hash)` where published. |
| `first_seen_at`, `change_note` | |
| `created_by`, `created_at`, `published_by`, `published_at` | |
| `row_version` | optimistic lock — the draft is one document, so concurrent edits must not be last-write-wins |

### `result_type` — organization vocabulary

`id, org_id, key, name, color, description, severity_order, is_system, active, created_at, updated_at`
· `UNIQUE(org_id, key)`

Pass and Fail are seeded in the tenant migration, so a newly provisioned organization has them
without an extra step. `severity_order` ranks results with **1 as most severe**, which is what makes
rollup possible: the result of a category, a checklist or a whole record is the most severe of its
parts.

**Deletion is conditional**, decided by one query against `version_result_type_ref`:

| Situation | Behaviour |
|---|---|
| `is_system` — Pass and Fail | neither deleted nor deactivated |
| Never referenced by a published version | hard delete allowed, with confirmation |
| Referenced by any published version | delete refused; deactivate instead |

**Name and colour are resolved live, never copied.** Renaming or recolouring a result type changes it
everywhere, including completed inspections and historical reports — that consistency is the point.
What never changes is the **meaning**, which is `result_type_key`, and that is immutable on every
record that stores one. The single exception is the inspection service's
`checklist_signature.result_type_name_at_signing`, kept to answer "what did the signer see" and
never used for display.

### `version_result_type_ref` and `version_target_type` — derived indexes

`version_result_type_ref(version_id, result_type_key)` answers *"is this result type still in use?"*
— the query that decides whether a delete is allowed or must become a deactivate.

`version_target_type(version_id, kind, key)` answers *"which published procedures apply to target
type Y?"*, with `kind ∈ HIERARCHY_LEVEL, LOCATION_TYPE, ASSET_CLASS, ASSET_TAG`. Keys are validated
against the property vocabulary but carry no cross-service foreign key.

Both are written at publish from the document. They exist because `definition_json` is `TEXT` and
therefore unqueryable.

### `procedure_document` — reference documents

`id, version_id, question_key NULL, name, mime_type, size_bytes, location, display_order,
created_by, created_at`

A null `question_key` means the document is attached to the template rather than to one question.
`location` is opaque — the bytes live behind the storage port and this service never touches a file.
The definition document references documents by id and name only, never by content, so re-uploading
the same file cannot change the hash.

### `procedure_consumer`, `procedure_usage` and `procedure_favourite`

`procedure_consumer(key, name, active)` — the configurable consumer list.

`procedure_usage(id, template_id, version_id, consumer_key, consumer_ref_id, target_type_key,
recorded_at)` — what consumers report back about which template version they use, so an author can
see the impact before publishing. `UNIQUE(consumer_key, consumer_ref_id, template_id)` makes
re-reporting idempotent. Note it deliberately records the version a consumer is *on*, not the current
one — that is what makes "before you publish v4, here are the 12 configurations still on v3" possible.

`procedure_favourite(user_id, template_id, created_at)` — the library's star. A join table, nothing more.

### Global library

Held outside any single tenant:

| Table | Key columns |
|---|---|
| `global_procedure_template` | `id, name, description, consumer_key, current_published_version_id, status, created_by, created_at, updated_at` |
| `global_procedure_template_version` | `id, global_template_id, version_no, state, definition_json, definition_hash, change_note, published_by, published_at` |
| `global_template_org_copy` | `global_template_id, org_id, template_id, applied_version_no, link_state, deferred_version_no, deferred_at, linked_at` |

`link_state ∈ LINKED, DEFERRED, STANDALONE`. **"Update available" is derived**, by comparing
`applied_version_no` against the global template's current version — never stored, so it cannot go
stale.

---

## The definition document

Shown pretty-printed; the stored bytes are one line.

```json
{
  "schema": 1,
  "categories": [
    { "key": "c1", "name": "Fire safety", "weight": 2, "questions": [

      { "key": "q1", "text": "Is the fire exit clear?", "type": "YES_NO",
        "required": true, "critical": true, "subquestionRollup": "WORST",
        "options": [
          { "key": "o1", "label": "Yes", "result": "PASS", "score": 10 },
          { "key": "o2", "label": "No",  "result": "FAIL", "score": 0 }
        ],
        "questions": [
          { "key": "q2", "text": "Describe the obstruction", "type": "TEXT",
            "required": true,
            "displayCondition": { "question": "q1", "op": "IN", "options": ["o2"] } },
          { "key": "q3", "text": "Attach a photo", "type": "FILE",
            "required": true, "evidenceRequired": true,
            "displayCondition": { "question": "q1", "op": "IN", "options": ["o2"] } }
        ] },

      { "key": "q4", "text": "Extinguisher inspection date", "type": "DATE",
        "required": true,
        "rules": [
          { "op": "WITHIN_MONTHS",  "max": 12,            "result": "PASS",     "score": 10 },
          { "op": "BETWEEN_MONTHS", "min": 12, "max": 15, "result": "AMBER",    "score": 5  },
          { "op": "OVER_MONTHS",    "min": 15,            "result": "REQUIRED", "score": 0  }
        ],
        "options": [
          { "key": "o9", "label": "Not applicable", "result": "NOT_APPLICABLE",
            "excludeFromScoring": true }
        ] }
    ] }
  ],
  "thresholds": [
    { "scope": "VERSION", "min": 90,            "result": "PASS"  },
    { "scope": "VERSION", "min": 70, "max": 90, "result": "AMBER" },
    { "scope": "VERSION",            "max": 70, "result": "FAIL"  }
  ],
  "targetTypes":   [ { "kind": "ASSET_CLASS", "key": "EXTINGUISHER" } ],
  "signatureMode": "SEQUENTIAL",
  "signatureRequirements": [
    { "role": "INSPECTOR",  "required": true,  "format": "DRAWN" },
    { "role": "SUPERVISOR", "required": false, "format": "TYPED" }
  ],
  "documents":     [ { "id": "d1", "name": "BS 5306 extract.pdf" } ]
}
```

**Options nest inside their question.** They have no independent existence, which is why they are not
a table.

**Sub-questions nest inside their parent question** and reference the parent's *option keys* through
`displayCondition`. A sub-question is a child of the question, not of an option, so the condition can
also express numeric ranges, dates, multi-select and AND/OR triggers.

**Keys are stable across versions.** `q1` means the same question in v1 and v7, which is what lets
rules, reporting and amendments refer to the same question over time. Keys are minted from
`procedure_template.key_seq` and never reused, even after a question is deleted.

**Sibling order is the array index.** There is no `order` field to renumber, and no way for two
siblings to claim the same position.

**Question types:** `YES_NO`, `SINGLE_CHOICE`, `MULTI_CHOICE`, `NUMBER`, `TEXT`, `DATE`, `FILE`. A
rating or scale is a single choice with ordered options. Per-question `config` and `validation` hold
type-specific settings, so adding a type does not change the schema.

**`evidenceRequired` is per question, not a question type.** A `YES_NO` question can demand a photo
without becoming a `FILE` question; submission is blocked until something is attached. Requiring
evidence and asking for a file are different things, and conflating them is how the old model lost
the distinction.

**Not applicable is an option, not a flag.** An option carrying `excludeFromScoring` produces the
`NOT_APPLICABLE` result and is left out of the score. This records that the inspector *chose* N/A,
which a per-question boolean could not — it would leave "not applicable" indistinguishable from
"nobody answered" — and the evaluator needs no special case for it.

**`thresholds[].scope`** lets a threshold attach to the whole version or, later, to a category —
without a schema change.

**Signatures** carry `required` (an optional signature is collected if available), a `format` of
drawn, typed or photo, and a version-level `signatureMode` of sequential or parallel. Roles are open:
inspector, supervisor, manager, witness or anything an organization defines.

---

## Multi-tenancy

Schema-per-tenant. The organization id comes from the JWT `org_id` claim, and every request runs
against schema `t_<uuid-without-dashes>`.

- `TenantIdentifierResolver` reads the current organization; `SchemaMultiTenantConnectionProvider`
  issues `SET search_path` on checkout and resets to `public` on release.
- `TenantProvisioningFilter` runs inside the security chain, after authorization, so `OrgContext` is
  populated. On first sight of an organization, `TenantRegistryService` registers it in
  `public.tenant_registry`, creates the schema, and runs Flyway against it.
- Each tenant schema carries its own `flyway_schema_history`. At boot, every active schema is
  migrated; a failure on one tenant is logged and does not stop startup.
- `org_id` columns remain as a defence-in-depth cross-check. Isolation comes from the schema.

**Every schema change is written twice** — `db/migration/` for the public schema and `db/tenant/` for
per-tenant schemas — and the two must stay column-for-column identical.

### Properties, inside an organization

An organization contains properties (VDMS sites). A procedure belongs either to one of them or to the
whole organization — `property_id` set, or null for shared — and the second boundary is enforced by
**Postgres row-level security**, not by application code.

- `X-Sclera-Property` says which property a request is looking at. `PropertyScopeFilter` checks it
  against the caller's grants and answers 403 if they have none, then puts it in `PropertyContext`.
- `SchemaMultiTenantConnectionProvider` writes it to `sclera.property_ids` on checkout beside
  `search_path`, and clears it on release. The policy on each property-scoped table reads it.
- No header means organization level: only shared procedures are visible, with no filtering in the
  application at all.
- A procedure authored inside a property belongs to it; one authored at organization level is shared.
  Where the author was standing already answers it, so there is no flag to set.

**This only works because requests arrive on a role that owns nothing.** Postgres ignores row-level
security for superusers and for a table's owner, so the policies would be inert if the application
connected as the owner — present, and doing nothing. `spring.datasource` is the non-owner role;
`sclera.datasource.owner` runs Flyway and tenant provisioning, which need privileges the first
deliberately lacks. `PropertyIsolationIT` asserts `current_user` for that reason: without it, every
isolation test would pass with no isolation in place.

**A new property-scoped table needs its own policy.** Row-level security is per table, and one
without a policy is wide open with no error to notice. It belongs on the same checklist as writing
the migration twice.

Internal endpoints carry no JWT, so they pin the tenant explicitly:

```java
tenantRegistry.ensureTenant(orgId);
return TenantContext.runAs(orgId, () -> service.getForOrg(id, orgId));
```

---

## API

Public routes reach the service through the gateway on `:8080`; `/internal/**` is never routed
publicly.

| Area | Endpoints |
|---|---|
| Templates | create, list, get, duplicate, archive; list versions |
| Versions | get, save draft, publish, new draft from version, diff two versions |
| Result types | create, list, get, update, reorder, activate, deactivate, delete (conditional) |
| Evaluation | evaluate answers against a version |
| Discovery | published procedures by consumer and target type |
| Documents | attach to a version or question, list, remove |
| Library | browse, search and filter global templates; favourite; import; export; link, unlink |
| Updates | check availability, view diff, apply, defer |
| Usage | record and query which consumers use which version |

**Publish does not return a boolean.** It returns the list of reasons a version cannot be published —
answer options not yet mapped to a result type, thresholds unset while scoring is configured, a
document pointing at a question key that no longer exists. An author needs to see everything blocking
them at once, not discover the problems one refused publish at a time.

Responses are wrapped by sclera-common's envelope — `{success, data, pagination, error, meta}` —
applied automatically. Controllers return the bare DTO or `Page<DTO>`, never the envelope. Errors use
the shared exception types so they map onto the common error codes.

Internal endpoints are invoked over Dapr (app-id `sclera-procedure-service`), HMAC-signed with the
shared `sclera.event-listener.signing-secret`, and guarded by `InternalEndpointFilter`. Dapr rejects
`?` in an invocation method name, so internal endpoints take every parameter in the path.

### A template's life, end to end

```
# 1. Create a template. This also opens draft v1 — a template without a draft
#    is not a thing you can have. Category and question keys are minted here
#    and kept across every later edit.
POST /api/v1/procedure-templates
{
  "name": "Fire safety walk",
  "description": "Daily walk-through",
  "definition": {
    "categories": [
      { "name": "Fire exits", "questions": [
          { "text": "Is the fire exit clear?", "type": "BOOLEAN", "required": true },
          { "text": "Describe any obstruction", "type": "TEXT" }
      ] }
    ]
  }
}

# 2. Edit the draft. rowVersion is an optimistic lock; a stale one is a 409
#    rather than a silent overwrite of whatever the other author just saved.
PUT  /api/v1/procedure-templates/{id}/draft   { "definition": {…}, "rowVersion": 0 }

# 3. Publish. Freezes the draft as an immutable version, writes its hash and
#    repoints current_published_version_id. Publishing a draft whose hash
#    already exists returns that version instead of minting a duplicate.
POST /api/v1/procedure-templates/{id}/publish

# 4. Editing after publishing means opening a new draft from a published
#    version. A published version is never edited in place.
POST /api/v1/procedure-templates/{id}/draft   { "fromVersionNo": 1 }

# 5. History and diff, both computed on stable keys.
GET  /api/v1/procedure-templates/{id}/versions
GET  /api/v1/procedure-templates/{id}/diff?from=1&to=2
```

`http/procedure-templates.http` runs all of this against a live gateway, including the calls that are
meant to fail — a stale `rowVersion`, and a republished unchanged draft. It exercises an endpoint in
isolation rather than through five layers of screen, which is why it stays useful alongside a UI
rather than being replaced by one.

**There is no compatibility facade.** The `/api/v1/question-templates` API was deleted along with the
two-level `question_template → template_section → question` model it served, rather than kept alive
or projected into the old shape.

### Gateway routing

**Every top-level path this service exposes must also be added to the gateway**, or requests are
refused with a 404 before they ever reach us. The paths live in the `procedure-service` route's
`Path=` predicate:

```yaml
- id: procedure-service
  uri: ${PROCEDURE_SERVICE_URL:http://localhost:8095}
  predicates:
    - Path=/api/v1/procedure-templates/**,/api/v1/result-types/**
```

The gateway is a **separate repository** — `ScleraHoldingsLLC/sclera2.0v-api-gateway` — and the copy
in this working tree is gitignored, so a route change cannot be committed alongside the service that
needs it. Adding an endpoint therefore has two halves: the service change here, and a matching commit
there. They are easy to separate and easy to forget, and the symptom of forgetting is a 404 that
looks like a missing controller.

Two things that follow from the gateway being a separate build:

- Editing the local copy's `application.yml` fixes local runs, but the change only reaches a
  deployment after `mvn clean package -Dmaven.test.skip=true` in that directory. A stale jar serves
  the old routes while the source on disk shows the new ones.
- `/internal/**` is deliberately **not** routed. Those endpoints are reached over Dapr and are not
  meant to be publicly addressable.

That rebuild needs two stub artifacts installed first — the gateway's Maven parent and an empty
`sclera-common-test`, neither published in this workspace. Without them it fails on an unresolvable
parent POM, and then, less obviously, on a missing test jar. The root README's *Building the gateway
locally* section has both commands.

---

## Evaluation and scoring

Scoring lives here, in one place, rather than being reimplemented by every consumer.

The evaluation endpoint takes a version id and a set of answers and returns the result and score for
every question, every category and the version as a whole. It accepts **partial** answers and returns
a provisional result, so an inspector can see a running score while filling a checklist in.

How a result is reached:

1. **Per question** — a choice answer takes the `result` on the option that was picked; a numeric,
   date or text answer is matched against the question's `rules` in order. An option marked
   `excludeFromScoring` produces its result but contributes nothing to the score, and its weight is
   removed from the denominator so a not-applicable question cannot drag a percentage down.
2. **Sub-questions** contribute to their parent according to `subquestionRollup`.
3. **Per category** — question scores are weighted by the question's `weight`, then the category's own
   `weight` applies when it rolls up.
4. **Overall** — the total score is matched against `thresholds`, expressed in the organization's own
   result types, so an inspection can end as Pass, Amber, Fail or anything else defined.
5. **Critical questions** short-circuit all of it: one failure fails the whole result regardless of
   score.

Where several results have to become one — a category from its questions, a record from its
checklists — the **most severe wins**, which is `MIN(severity_order)` since 1 ranks most severe.

Evaluation is a pure function over an immutable version, so a parsed version is cached indefinitely
and the endpoint is trivially testable.

---

## Authorization

OpenFGA, with the store resolved by name and decisions cached briefly in Caffeine. Checks **fail
closed** — if OpenFGA is unreachable, access is denied. Platform admins bypass checks via the
`is_platform_admin` JWT claim.

Keycloak defines no realm roles; all authorization is OpenFGA tuples. "Role-based" means an
organization-level relation.

Four roles, in what each may do:

| Role | Author | Import / export | Configure scoring | Run checklists |
|---|---|---|---|---|
| Sclera admin | yes, including global | yes | yes, including global | yes |
| Organization admin | yes, own org | yes | yes, own org | yes |
| Manager / supervisor | no | no | no | yes, and review |
| Inspector | no | no | no | yes |

```
type organization
  relations
    define template_author:      [user]
    define template_publisher:   [user]
    define template_importer:    [user]
    define result_type_manager:  [user]
    define supervisor:           [user]
    define can_manage_templates:     admin or template_author
    define can_publish_templates:    admin or template_publisher
    define can_import_templates:     admin or template_importer
    define can_manage_result_types:  admin or result_type_manager
    define can_review:               admin or supervisor

type procedure_template
  relations
    define org: [organization]
    define creator: [user]
    define can_view:    can_view from org
    define can_edit:    creator or can_manage_templates from org
    define can_publish: can_publish_templates from org
    define can_delete:  can_manage_templates from org
```

Tuples are written when a template is created, in the same transaction as the insert, so a failed
tuple write rolls the insert back. The model lives in both `docker/openfga/model.fga` and
`docker/openfga/authorization-model.json`; they are kept in sync by hand and applied by
`setup-openfga.ps1`.

Only Sclera administrators can publish a global copy.

---

## Events

Kafka carries facts; Dapr carries questions.

Topic **`sclera.procedure.template-events.v1`**, keyed by template id so every event for one template
stays ordered within a partition. Events are emitted when a version is published, when a template is
archived, and when a global version is published — the last one carrying the linked organizations to
notify, since applying it is their decision rather than something this service does to them.

Consumers read the JSON shape rather than a shared Java class, so adding a field is safe.

---

## Sharing across organizations

Every template has one **global copy**. Importing it creates an **organization copy** linked back to
it, and the link records which global version that copy has applied.

**Updates are offered, never imposed.** Publishing a global version does not reach into any
organization's data. It notifies the linked organizations; each sees an "update available" badge —
derived by comparing the version it has applied against the global current — and then chooses:

- **Apply** — the global version is copied into the organization copy as a new published version.
- **Defer** — recorded on the link, so the badge can distinguish "not seen" from "seen and declined".
- **View diff** — the same document diff the authoring screen uses, computed on stable question keys.

Updating affects records generated from then on. A checklist already in progress is pinned to the
version it started with and is untouched.

Two ways a copy stops tracking the global one: **explicit unlink**, and **fork on edit** — editing a
linked copy sets it standalone, because a local change and an incoming global change cannot both be
true of the same version. The global template id survives either way, so relinking stays possible.

**Export** takes a version selection, defaulting to the current published one, with a toggle for
whether documents travel with it. **Import** either creates a new template or updates an existing one,
and maps result type keys the target organization lacks onto its own or creates them.

The **Sclera organization** is the reference library: generic templates and generic question sets per
category that any organization can browse, copy whole, or pull individual questions from.

---

## How other services relate to this one

**Inspection service.** Reads a version over Dapr and pins `procedure_version_id` onto each checklist
at generation. Because the version is immutable, the checklist needs no snapshot of its own — the
form is stored once here, not copied per run. On submit it calls the evaluation endpoint and stores
the returned result and score. It reports usage back.

**Property vocabulary.** `version_target_type` keys are validated against the hierarchy levels,
location types, asset classes and tags the property vocabulary provides.

**Storage.** Reference documents are written and read through the storage port; this service stores
only metadata and an opaque `location`.

Both of the last two are reached through the same client pattern: an interface and DTOs owned by this
service, with the target service configured per domain, so a change of provider is configuration
rather than code.

---

## Legacy provenance

Sclera 1.0 stored a copy of the full question and option tree on every checklist ever run. 2.0 stores
the form once and has each checklist point at it, which is why `procedure_template_version` carries
the same `definition_json` and `definition_hash` names its 1.0 counterpart used — a 2.0 published
version is the same object.

Migrated data is marked `origin = MIGRATED` and carries `legacy_id` for idempotent re-runs. One limit
is worth knowing: 1.0's question keys are positional within a single definition and carry no identity
across definitions, so for migrated versions a question's identity is scoped to its own version.
Cross-version question identity holds from the first version authored in 2.0 onward.

---

## Running locally

```powershell
docker compose up -d          # postgres, redis, kafka, keycloak, openfga
.\setup-keycloak.ps1
.\setup-openfga.ps1           # re-run whenever the authorization model changes
.\setup-demo-users.ps1
.\run-procedure-service.ps1   # service on 8095, Dapr sidecar on 3500 / 50001
```

Swagger UI is on under the `dev` profile at `http://localhost:8095/swagger-ui.html`.

Tests run against a real Postgres. H2 cannot do `CREATE SCHEMA`, `search_path` and JSONB, so the
tenancy layer needs the real database.

Configuration worth knowing: `SCLERA_DB_URL` / `_USER` / `_PASSWORD`, `KEYCLOAK_URL`,
`SCLERA_KAFKA_BOOTSTRAP_SERVERS`, `SCLERA_FGA_ENABLED`, and `SCLERA_INTERNAL_SIGNING_SECRET`, which
must match every service that calls this one or internal requests fail with `401 HMAC_FAILED`.

---

## Project layout

```
com.sclera.applicationplane.procedure
├── authz/        OpenFGA client, the `fga` bean used by @PreAuthorize
├── client/       outbound clients, one package per external domain
├── config/       security, Kafka topics, Dapr
├── controller/   public and internal endpoints
├── definition/   the document: schema, validator, canonicaliser, hashing
├── domain/       JPA entities and enums
├── dto/          request and response records
├── evaluation/   scoring and result resolution
├── event/        Kafka events and publisher
├── exception/    GlobalExceptionHandler extends the shared base
├── mapper/       entity ↔ DTO
├── repository/   Spring Data repositories
├── service/      transactional application services
└── tenancy/      schema-per-tenant resolution and provisioning
```

Flow is Controller → Service → Repository. Controllers carry `@PreAuthorize` and `@Valid` and nothing
else. Services are `@Transactional` at class level with `@Transactional(readOnly = true)` on reads.

`com.sclera.controlplane.common` must stay in `scanBasePackages` — without it the shared response
envelope, filters and configuration silently never load.

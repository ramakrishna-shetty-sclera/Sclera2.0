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

The refusal names the cost and the way out — *"Amber is used by 2 published versions, so it cannot
be deleted; deactivate it instead"*. What counts as a reference:

- **Any published version**, old ones and those of archived procedures included. They are the record
  of what past inspections decided, and deleting the type they name would leave that record pointing
  at nothing.
- **Answers and bands alike** — an option's `result` and a number question's band `result`.
- **Every property's versions**, counted from organization level too, so an admin is never told a type
  is unused because the version using it belongs to a property.
- **Not drafts.** A draft commits to nothing, so a type only a draft names still deletes.

Deactivate is deliberately not guarded: it is the way out the refusal points to. No new version can
use a deactivated type, and every version that already names it keeps rendering.

**Name and colour are resolved live, never copied.** Renaming or recolouring a result type changes it
everywhere, including completed inspections and historical reports — that consistency is the point.
What never changes is the **meaning**, which is `result_type_key`, and that is immutable on every
record that stores one. The single exception is the inspection service's
`checklist_signature.result_type_name_at_signing`, kept to answer "what did the signer see" and
never used for display.

### `version_result_type_ref` and `version_target_type` — derived indexes

`version_result_type_ref(version_id, result_type_key)` answers *"is this result type still in use?"*
— the query that decides whether a delete is allowed or must become a deactivate. Built: one row per
distinct key per version, written in the same transaction as the publish and only when a publish
creates a new version, so republishing unchanged content adds nothing. It has no row-level security
on purpose — the delete guard must see every property's versions — and no foreign key to
`result_type`, since the guard is what stops a referenced type disappearing. There is no backfill:
versions published before the table existed held test data only and are not counted.

`version_target_type(version_id, kind, key)` answers *"which published procedures apply to target
type Y?"*, with `kind ∈ HIERARCHY_LEVEL, LOCATION_TYPE, ASSET_CLASS, ASSET_TAG` (a database check
constraint). Built, and written the same way as the result-type index: one row per distinct
`(kind, key)` per version, in the same transaction as the publish and only when a publish creates a
new version. It follows the same three decisions:

- **A foreign key to the version, none to the vocabulary.** The vocabulary is another service's, and
  the index has to outlive a decision to retire a key.
- **No row-level security**, so discovery sees every property's versions. A property's procedure is
  still hidden from organization level, because the discovery query joins back to
  `procedure_template`, which does have it.
- **No backfill.** Nothing published before the table existed is counted.

**No rows is not "applies to nothing" — it is "applies to anything".** That is what every procedure
authored before this feature is, so the discovery query reads the absence of rows as a match.

Both indexes exist because `definition_json` is `TEXT` and therefore unqueryable.

### `procedure_document` — reference documents

`id, version_id, question_key NULL, name, mime_type, size_bytes, location, display_order,
created_by, created_at`

A null `question_key` means the document is attached to the template rather than to one question.
`location` is opaque — the bytes live behind the storage port and this service never touches a file.
The definition document references documents by id and name only, never by content, so re-uploading
the same file cannot change the hash.

### `procedure_consumer`, `procedure_usage` and `procedure_favourite`

`procedure_consumer(key, name, active)` — the configurable consumer list. Built, and seeded in the
tenant migration with `INSPECTION` and `TASK`. `INSPECTION` has to stay in the seed: it is the
default `consumer_key` of every procedure, so a seed without it would invalidate everything already
authored. `TASK` is the second, and its name is still unsettled — which is why this is a table and
not an enum.

Creating a procedure validates `consumerKey` against the **active** rows (trimmed and upper-cased),
and the refusal lists the valid ones. Only creation is checked: the consumer is identity — set at
create and never changed, and `UpdateTemplateRequest` carries none — so retiring a consumer later
leaves every procedure that names it exactly as it was, and a clone keeps the one it copies.

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

One flat list. Shown pretty-printed; the stored bytes are one line.

```json
{
  "schema": 2,
  "items": [
    { "key": "s1", "text": "Location and access", "type": "SECTION" },

    { "key": "q2", "text": "Is the fire exit clear?", "type": "YES_NO",
      "required": true, "workOrder": true, "alertProfile": "ap-fire",
      "standard": "NFPA 10", "source": "DOCUMENT",
      "options": [
        { "key": "o3", "label": "Yes", "result": "PASS" },
        { "key": "o4", "label": "No",  "result": "FAIL" }
      ],
      "follow": [
        { "key": "q5", "text": "Describe the obstruction", "type": "TEXT",
          "required": true, "when": "o4" },
        { "key": "q6", "text": "Photograph it", "type": "IMAGE",
          "required": true, "when": "o4" }
      ] },

    { "key": "s7", "text": "Condition", "type": "SECTION" },

    { "key": "q8", "text": "Record the gauge reading", "type": "INTEGER",
      "unit": "psi", "min": 100, "max": 175,
      "help": "Tap the gauge lightly before reading it.",
      "rules": [
        { "max": 149, "result": "PASS" },
        { "min": 150, "max": 160, "result": "AMBER" },
        { "min": 161, "result": "FAIL" }
      ] }
  ]
}
```

The same document once scoring is configured — every scoring field is optional, so this is the
one above with more on it, not a different shape:

```json
{
  "schema": 2,
  "items": [
    { "key": "s1", "text": "Location and access", "type": "SECTION", "weight": 2 },

    { "key": "q2", "text": "Is the fire exit clear?", "type": "YES_NO",
      "required": true, "critical": true, "weight": 3, "followRollup": "WORST",
      "options": [
        { "key": "o3", "label": "Yes", "result": "PASS", "score": 10 },
        { "key": "o4", "label": "No",  "result": "FAIL", "score": 0 },
        { "key": "o9", "label": "Not applicable", "excludeFromScoring": true }
      ],
      "follow": [
        { "key": "q5", "text": "Describe the obstruction", "type": "TEXT",
          "required": true, "when": "o4" }
      ] }
  ],
  "thresholds": [
    {                      "max": 69,  "result": "FAIL"  },
    { "min": 70,           "max": 89,  "result": "AMBER" },
    { "min": 90,                       "result": "PASS"  }
  ]
}
```

**A section is an item, not a level.** `s1` is a heading; the questions after it are its siblings.
There is no wrapper, so a document is one list and moving a question between sections is a move
within that list rather than a change of parent.

**Follow-ups nest, and point at an answer.** `q5` sits inside `q2`'s `follow` and names `o4` — the
option, not the label. Rewording "No" to "No — blocked" leaves `o4` alone, so the condition survives
an edit that changes what the inspector reads. They nest to any depth: a follow-up may have
follow-ups of its own.

**Options carry the meaning.** Each holds a result-type *key*, so an organization that later defines
Amber maps an answer to `AMBER` with no change here. An option with no result decides nothing, which
is how "Not applicable" works — it records that the inspector chose it, which a per-question flag
could not, because that leaves N/A indistinguishable from unanswered.

**A number question's bands carry its meaning.** `rules` on an `INTEGER` maps ranges of readings to
result types: in `q8`, up to 149 passes, 150 to 160 is Amber, 161 and above fails. A missing `min` is
"anything up to `max`", a missing `max` "anything from `min`". Both ends are inclusive and bands
share no number — after a band ending at 149 the next starts at 150 — the same convention as the
score thresholds that turn a percentage into a result. `min`/`max` on the question are something
else: they bound what the inspector may type, and the bands say what the typed value means.

At publish, every reading that can be typed must land in exactly one band. What can be typed is the
question's own `min`/`max`, or every whole number where it sets none, so an unbounded question's
first band must be open below and its last open above. Gaps and overlaps are reported as spans —
*"no band covers readings from 12 to 15"*, *"more than one band covers a reading of 11"* — so an
author fixes the edge rather than hunting for it. No bands at all is legitimate: the reading is
recorded and decides nothing, which is what a meter reading often should do.

**Keys are stable across versions and never reused.** `q2` means the same question in v1 and v7,
which is what lets reporting and amendments refer to one question over time. Minted from
`procedure_template.key_seq`: `s` for sections, `q` for questions, `o` for options, one counter
behind all three, so a key's prefix alone says what it names.

**Sibling order is the array index.** No `order` field to renumber, and no way for two siblings to
claim the same position.

**Types.** Choice — `YES_NO`, `YES_NO_NA`, `RADIO`, `CHECKBOX`, `DROPDOWN`, whose options carry the
result. Input — `TEXT`, `INTEGER`, the latter taking `unit`, `min`, `max` and `rules`. Media — `IMAGE`,
`MULTI_IMAGE`, `AUDIO`, `VIDEO`, `DOCUMENT`. Plus `SECTION` for a heading.

No date and no signature type. The prototype dropped date; signature is still R&D. Neither is ruled
out, both are simply outside what the product has committed to.

**`workOrder` and `alertProfile`** record that a failed answer should raise work. Only a choice
question can carry them. A number question's bands produce a result too, but raising work from a
reading has not been decided, so it is still refused rather than half-supported. The profile is a reference the
procedure service stores and does not resolve — alert profiles are a later feature, and executing
the work order belongs to the facilities layer, not here.

**`source`** says where a question came from: typed by an author, generated from an uploaded
standard, copied from a Sclera template, or taken from a suggestion. Cheap to record now and
impossible to reconstruct later, once a procedure has been edited a few times.

### What a version applies to

`targetTypes` is an optional document-level list naming the kinds of place and asset a version is for:

```json
"targetTypes": [
  { "kind": "ASSET_CLASS",   "key": "EXTINGUISHER" },
  { "kind": "LOCATION_TYPE", "key": "PLANT_ROOM"   }
]
```

It is part of the definition, so it is hashed, versioned and diffed like the questions — changing it
makes a new version, and the diff reports `targetTypesChanged`. Empty or absent means the version
applies to anything, which is the default and the common case.

The check is split the way the rest of the service splits structure from readiness:

| When | What is checked |
|---|---|
| Save | Shape only: each entry has a kind and a key, and none is listed twice. A draft naming a key the vocabulary lacks **saves** — a key that does not exist yet is a normal intermediate state. |
| Publish | Every key exists in the organization's vocabulary for its kind. Each missing one is a blocker naming it, e.g. *"The target type ASSET_CLASS EXTINGUISHER is not one of this organization's asset classes"*. |

Retired keys count as present, so retiring a key in the vocabulary does not block an unrelated edit to
a procedure that already names it.

**The vocabulary is read over Dapr, and only when a version declares target types.** A procedure that
applies to anything never calls out, so existing procedures publish as before and do not start
depending on the helper being up. If the vocabulary cannot be read, the publish is **refused with a
message about the vocabulary** — *"…the property vocabulary they are checked against could not be
read. Nothing is wrong with the procedure; try again shortly"* — never about the key, because telling
an author a key is wrong when the lookup failed sends them to fix something that is not broken. It is
a `BusinessRuleException` (422) rather than the shared external-service error, whose fixed generic
502 wording would throw the explanation away. Each organization's lists are cached for 90 seconds, so
a key added in that window can be refused until the cache expires; failures are never cached.

### Scoring — declared here, computed by the evaluator

Nothing in the document does arithmetic. It records what an organization decided; the evaluator in
`evaluation/` is the only thing that reads these and works out a number — see *Evaluation and
scoring*.

**`score` on an answer** is the points it is worth. Zero is a real score — the normal way to spell
"this is the wrong answer" — and is kept in the canonical form for that reason. An answer with no
score at all is different from one scoring zero.

**`excludeFromScoring`** is the not-applicable answer: no points, and it does not count towards the
total either, so choosing it cannot drag a score down. It replaced a per-question `naAllowed` flag,
and is better than one for the same reason the result key is: as an answer it records that the
inspector chose N/A, which a flag could not tell apart from nobody answering.

**`weight`** says how much something counts against its siblings. On a section it weights the whole
group; on a question it weights that question. Absent means 1, so an unweighted procedure scores
everything equally. A weight below 1 is refused — leaving a question out of the score is something
to say by not asking it.

**`critical`** fails the whole inspection on a single failure, whatever the score says. Never on a
section: a heading is never answered, so it can never fail.

**`followRollup`** is how a question's follow-ups contribute — `WORST` (one failed follow-up fails
the parent), `AVERAGE`, or `INDEPENDENT`, which is the default and means they score on their own.
It matters because a follow-up is conditional: counting it like an ordinary question would make a
procedure score differently depending on answers nobody controls.

**`thresholds`** turn a final score back into a result type, in the organization's own vocabulary.
Open-ended at both ends — no `min` is "anything up to `max`", no `max` is "anything from `min`" — so
three bands cover every score without boundary arithmetic. A band's `scope` is the whole inspection
unless it says `SECTION`, which reads each section's own score and decides that section's result.

**Scoring is opt-in, and silence publishes.** A procedure that configures none of this is complete,
not unfinished, and that is the rule every other scoring rule is written around. Once an author
starts — any score, any weight, any band — the set has to be finished: bands must tile 0 to 100 with
no gap and no overlap, every band must name an active result type, and a question cannot score half
its answers. `critical` and `followRollup` are not scoring by this test; both decide a result rather
than a number, and either is sensible on a procedure that never scores anything.

**What is checked, and when.** Structure is enforced on every write and refused, because storing a
follow-up that points at a missing answer is storing corruption. Readiness — nothing authored yet, a
choice with one answer, an answer or band mapped to a result type the organization does not have,
bands that leave a reading uncovered or cover it twice — is checked at publish and returned as a list, so an author sees every reason at once instead of one per
attempt. The split is what lets a half-finished draft be saved, which is the normal way of working.

**Schema 2.** Schema 1 was `categories[] → questions[]` and is not readable. Nothing was carrying it
worth keeping. That was free exactly once: a published version is content-addressed, so once a real
organization has one, reshaping the document means reading both shapes forever.

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
return TenantContext.runAs(orgId, () -> service.evaluatePublished(versionId, orgId, request));
```

The transaction has to open inside `runAs`, which is why `open-in-view` stays off. And no
property is selected on such a call, so an internal endpoint must not read a property-scoped table
for something it needs regardless of property — see the internal evaluation endpoint.

---

## API

Public routes reach the service through the gateway on `:8080`; `/internal/**` is never routed
publicly.

| Area | Endpoints |
|---|---|
| Templates | create, list, get, duplicate, archive; list versions |
| Versions | get, save draft, publish, new draft from version, diff two versions |
| Result types | create, list, get, update, reorder, activate, deactivate, delete (conditional) |
| Evaluation | evaluate answers against any version, draft included (public); against a published version by id (internal, for the inspection service) |
| Discovery | `GET /api/v1/procedure-templates/discover?consumer=&kind=&key=` — the active procedures that apply to a target; see *Discovery* below |
| Documents | attach to a version or question, list, remove |
| Library | browse, search and filter global templates; favourite; import; export; link, unlink |
| Updates | check availability, view diff, apply, defer |
| Usage | record and query which consumers use which version |

**Publish does not return a boolean.** It returns the list of reasons a version cannot be published —
answer options not yet mapped to a result type, number bands with a gap or an overlap, thresholds
unset while scoring is configured, a
document pointing at a question key that no longer exists. An author needs to see everything blocking
them at once, not discover the problems one refused publish at a time.

Responses are wrapped by sclera-common's envelope — `{success, data, pagination, error, meta}` —
applied automatically. Controllers return the bare DTO or `Page<DTO>`, never the envelope. Errors use
the shared exception types so they map onto the common error codes.

Internal endpoints are invoked over Dapr (app-id `sclera-procedure-service`), HMAC-signed with the
shared `sclera.event-listener.signing-secret`, and guarded by `InternalEndpointFilter`. Dapr rejects
`?` in an invocation method name, so internal endpoints take every parameter in the path.

### Discovery

```
GET /api/v1/procedure-templates/discover?consumer=INSPECTION&kind=ASSET_CLASS&key=EXTINGUISHER
```

*"Which procedures does an extinguisher get?"* — all three parameters are required. `kind` is one of
`HIERARCHY_LEVEL`, `LOCATION_TYPE`, `ASSET_CLASS`, `ASSET_TAG`; `consumer` is trimmed and upper-cased as
on create, and `key` is only trimmed, because keys are stored as the author wrote them. The response is
the list's shape: a `Page<TemplateResponse>`, 20 to a page, with each template's current published
version number.

A procedure is returned when it is **active**, belongs to the asked **consumer**, has a **published**
version, and that version either names the asked `(kind, key)` or names **no target types at all**.

- **Only the current published version counts.** If v1 named extinguishers and v2 stopped, v1's rows
  stay in the index but the procedure is no longer found for extinguishers. An open draft changes
  nothing until it is published.
- **Never published, archived** and **another consumer's** procedures are not returned.
- **Organization level sees organization-wide procedures only.** A procedure authored inside a property
  is found from that property and from nowhere else.
- Authorization is `can_view` on the organization, as for the list; there is no per-procedure check.

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
    - Path=/api/v1/procedure-templates/**,/api/v1/result-types/**,/api/v1/me/properties,/api/v1/me/permissions
```

Each `/api/v1/me/*` endpoint is listed exactly rather than as `/api/v1/me/**`, so the rest of `/me`
stays free for other services. A new one under `MeController` needs its own entry here, not a
widened prefix.

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

Scoring lives here, in one place, rather than being reimplemented by every consumer. The code is the
`evaluation/` package: `Evaluator.evaluate(document, answers, ranks)` is a pure function — no Spring,
no repository, no request context — so nearly all of it is tested without a container
(`EvaluatorTest`).

### Two endpoints

```
POST /api/v1/procedure-templates/{id}/versions/{versionNo}/evaluate        public, can_view
POST /internal/api/v1/procedure-versions/{versionId}/orgs/{orgId}/evaluate  Dapr, HMAC
```

```json
{ "answers": { "q1": { "value": "o2" },          // single choice: an option key
               "q3": { "value": ["o7", "o8"] },  // CHECKBOX: option keys
               "q4": { "value": 140 } } }        // INTEGER: a whole number; text and media: anything
```

**The public one** is for the author's running-score preview and anything a person looks at. It is
addressed and resolved like the diff beside it, so it reaches exactly what every other read reaches,
and it **evaluates a draft too** — that is what the preview needs. No gateway change was needed: the
path sits under `/api/v1/procedure-templates/**`.

**The internal one** is for the inspection service. It takes a published version by id, every
parameter in the path, and refuses a draft: an inspection is only evaluated against the version it
pinned. It **reaches the version without reading `procedure_template`**. A Dapr call has no
`X-Sclera-Property`, so it lands at organization level, where row-level security on the template
hides every property's procedures; the version table has no row-level security and the schema is
already the organization boundary, so looking the version up directly keeps a property's procedure
evaluable. `InternalEvaluationIT` holds that.

Only `value` is read from an answer; anything beside it is ignored, so the inspection service can post
what it stores. Missing, null, blank and empty all mean *not answered yet* — the normal state of a
half-finished inspection. An answer that cannot belong to the version — an unknown key, an answer to
a section, an option the question does not have, a number that is not whole — is a 400 that names
every one at once.

The response is the version evaluated, then `overall` (`result`, `percentage`, `answered`, `scored`,
`complete`), `sections`, `questions`, `critical`, `workOrders`, `unanswered` and `ignored`.
**Result-type keys only** — names and colours resolve live, and a caller that shows a verdict already
has the organization's types to colour it with.

### How a result is reached

0. **What is showing.** A top-level question always is; a follow-up only when its parent is showing
   and was answered with the option its `when` names. A question that is not showing counts for
   nothing, and an answer given to it is reported in `ignored` rather than refused — it is what
   changing the parent's answer leaves behind.
1. **Per question.** A single choice takes the chosen option's `result` and `score`. A `CHECKBOX`
   takes the most severe result and the fewest points among its ticks, so one bad tick is not
   averaged away. An `INTEGER` takes the result of the one band in its `rules` the reading falls in,
   and **no points** — a band carries no score. Text and media decide nothing. A question is scored
   when any of its options carries points; its possible score is the best on offer, and an option
   without points earns zero. An `excludeFromScoring` answer keeps its result but leaves both earned
   and possible, so N/A cannot drag a percentage down.
2. **Follow-ups**, by `followRollup`. `INDEPENDENT` scores them beside their parent. `WORST` folds
   them in: the most severe result, the lowest score ratio. `AVERAGE` folds them in with the mean
   ratio and still the most severe result — there is no band at question level to turn a mean back
   into a result. Folded follow-ups are still listed, marked `counted: false`.
3. **Per section.** A question belongs to the nearest section heading above it
   (`DefinitionDocument.groups()`); questions before the first heading form one group of weight 1.
   Earned over possible, weighted by question. The section's result is its own `SECTION` band when one
   matches, otherwise its most severe question.
4. **Overall.** The sections' percentages, weighted by **section** weight — not by how many questions
   each holds. The result is the version band the percentage falls in, or, with no bands at all, the
   most severe section.
5. **Critical questions** override all of it: a failed critical question decides the overall result
   whatever the score said. The score is still reported — "92% but failed on a critical item" is
   what the inspector needs to see.

Where several results have to become one, the **most severe wins** — `MIN(severity_order)`, since 1
ranks most severe.

### Decisions worth knowing

- **A failure is a result at least as severe as `FAIL`.** A result type has no "fails" flag, so this
  is inferred rather than stated: an organization's `CRITICAL` ranked above Fail fails too, and Amber
  does not. It is what `critical` and `workOrder` both mean by failing.
- **Every result type ranks, inactive ones included.** A published version may name a type
  deactivated since — deactivating is what the delete guard tells an admin to do — and it must keep
  deciding what it decided. That is why evaluation does not use the active-only query publish uses.
- **A running score counts only what was answered**: three perfect answers out of six is 100%, not
  50%. `answered` and `complete` say how far along it is.
- **Nothing scorable answered is no score, not 0%.** Zero would read the bottom band and fail an
  inspection that has barely started.
- **A percentage is rounded down before it meets a band**: 89.5 sits in "up to 89", not "from 90".
  Bands are whole numbers that share no edge, and a score reaches a band only once it gets there.
  Reported percentages are cut to two decimals for the same reason.
- **With score bands, the score alone decides the overall result.** A reading carries no points, so a
  failing reading shows in its question and section but not overall; a reading that must be able to
  fail an inspection is marked `critical`. Without score bands, readings count like any result.
- **A draft degrades rather than fails.** It has not been through the publish checks, so a score or
  a reading may fall in no band; that gives no result, with a reason, not an error.

**Nothing is cached.** The parsed version is immutable and could be — keyed by `definition_hash`,
which is its content — but the result types are organization state an admin can reorder, and a
reorder changes a rollup, so a verdict never can be. Caching the parsed version was left out as an
optimisation with no measurement behind it; it can be added if evaluation ever shows up as slow.

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

The block shows only what differs from the original model. As built, `member` — which `can_view`
derives from — also lists all four new roles; without that, someone holding only one of them could
act but not read. Every change to a result type needs `can_manage_result_types`, reads need
`can_view`. `can_import_templates` and `can_review` are declared but not yet enforced: nothing calls
them until import/export and review exist. Publishing as a right separate from authoring is our
proposal rather than a written requirement, and is open with the lead.

A property is its own type: `viewer` on it, or being an organization admin, gives `can_view`, which
is what `X-Sclera-Property` is checked against and what `GET /api/v1/me/properties` lists.

Three tests hold the model to this design: `AuthorizationModelTest` (the two files agree, every
role is in `member`), `AuthorizationModelIT` (a real OpenFGA answers the role matrix) and
`ControllerAuthorizationTest` (every endpoint is guarded, and every relation a guard names exists).
The full role list and demo accounts are in `docs/openfga.md`.

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
form is stored once here, not copied per run. On submit it calls the internal evaluation endpoint and
stores the returned result and score. It reports usage back.

That is the design, and **not yet what it does.** Today it still decides pass and fail itself — the
browser sets a `failed` flag on each answer and submit sets `FAILED` if any is set — and its answers
are keyed by question UUIDs from the deleted two-level model, not by the document's `q<n>` keys.
Pinning `procedure_version_id`, re-keying answers and calling the endpoint is the integration
branch's work; the endpoint it will call exists and is tested.

**Property vocabulary.** A version's `targetTypes` are checked at publish against the hierarchy levels,
location types, asset classes and asset tags the property vocabulary provides. Today that is the helper
service, a temporary stand-in whose `/api/v1/helper/vocabulary` lists are real rows; the call goes
through `client/vocabulary/` over Dapr, and **which service answers is configuration**:
`sclera.external.vocabulary.app-id` (default `sclera-helper-service`). Cutting over to a real property
service is therefore a change of that value, not of code. The helper must be running with its Dapr
sidecar (`.\run-helper-service.ps1`) for a publish that names target types to succeed.

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

Permission decisions are cached for 30 seconds, so a role granted or a model re-posted while the
service runs can take that long to show.

Swagger UI is on under the `dev` profile at `http://localhost:8095/swagger-ui.html`.

Tests run against a real Postgres. H2 cannot do `CREATE SCHEMA`, `search_path` and JSONB, so the
tenancy layer needs the real database.

Configuration worth knowing: `SCLERA_DB_URL` / `_USER` / `_PASSWORD`, `KEYCLOAK_URL`,
`SCLERA_VOCABULARY_APP_ID` and `SCLERA_VOCABULARY_CACHE_TTL_SECONDS` (who answers vocabulary lookups, and
how long a lookup is kept; 0 turns the cache off),
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

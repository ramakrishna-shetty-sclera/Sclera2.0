# PLAN — AI-assisted question generation for sclera-procedure-service

Stack reality check vs the task spec: this is **Java 21 / Spring Boot 3.3.7 / Postgres (schema-per-tenant) / Flyway**,
not Node/Prisma. LLM provider: **Anthropic Java SDK** (`com.anthropic:anthropic-java`), default model
`claude-sonnet-4-6` (configurable). File storage: **Postgres** (no S3 in this stack; uploads are capped at 10MB by
the existing multipart config, so bytea + extracted text in the tenant schema is the consistent choice).

---

## 1. The existing Question schema (what the LLM must emit, 1:1)

Questions are **not rows** — they are items inside a template version's canonical JSON document
(`procedure_template_version.definition_json`, TEXT + SHA-256 hash). The authoritative shape is
`definition/DefinitionDocument.java`:

```
Item {
  key        String(20)      // s1/q2/o3 — MINTED SERVER-SIDE by KeyMinter, never by clients (or the LLM)
  text       String(1000)    // required
  help       String(1000)
  type       QuestionType    // SECTION | YES_NO | YES_NO_NA | RADIO | CHECKBOX | DROPDOWN | TEXT | INTEGER
                             // | IMAGE | MULTI_IMAGE | AUDIO | VIDEO | DOCUMENT
  required   boolean
  options    [Option]        // choice types only; Option { key, label(200), result(50) }
  unit/min/max               // INTEGER only
  rules      [RangeRule]     // INTEGER only; RangeRule { min, max, result } — result bands
  workOrder  boolean         // choice types only; raise work order on failed answer
  alertProfile String(50)
  source     ItemSource      // MANUAL | DOCUMENT | TEMPLATE | SUGGESTED  ← already reserved for this feature
  standard   String(50)      // e.g. "NFPA 10"
  when       String(20)      // follow-up: parent option key
  follow     [Item]          // nested follow-up questions
}
```

- `Option.result` / `RangeRule.result` reference the org's **result-type vocabulary**
  (`result_type` table, `/api/v1/result-types`; `PASS`/`FAIL` are system keys).
- Structure rules enforced on every save by `DefinitionValidator.validateStructure`
  (e.g. options only on choice types, bands only on INTEGER, follow-ups must name a parent option key).
- Publish-time rules (`publishBlockers`): ≥1 question, choice questions need ≥2 options and ≥1 result-mapped
  option, result keys must be active, INTEGER bands must cover the readable range with no gaps/overlaps.

**Key consequence:** the LLM is prompted to emit items *without* `key`s; keys are minted by the existing
`KeyMinter` when candidates are imported into the draft. We reuse `DefinitionValidator` as the "schema
validator" — no new validation language, 1:1 by construction.

## 2. Current "import path" (and what doesn't exist)

- **There is no question bank** and no per-question import anywhere in the repo. The spec's "existing question
  bank" maps to the org's existing template corpus — dedup will run against question texts across the org's
  template versions (normalized-text match; no embedding infra exists and adding one isn't justified here).
- Questions enter a procedure exactly one way: `PUT /api/v1/procedure-templates/{id}/draft`
  (`SaveDraftRequest { definition, rowVersion, changeNote }`) → structure validation → key minting →
  canonicalize + hash → draft row updated (optimistic lock via `rowVersion`, pessimistic template lock for
  `key_seq`).
- Therefore the shared `importQuestions` path is a **thin wrapper around the draft-save path**: append the
  selected candidates' Items (with `source=DOCUMENT|SUGGESTED`) to the current draft definition and run the
  exact same validate→mint→canonicalize pipeline inside the same transaction/locks.

## 3. Proposed design

### New package `…procedure.ai`

```
sclera-procedure-service/src/main/java/com/sclera/applicationplane/procedure/
  ai/
    LlmProperties.java            @ConfigurationProperties("sclera.ai") — model, maxTokens, timeout, enabled
    LlmClient.java                single wrapper over the Anthropic Java SDK: structured-output call,
                                  timeout, SDK retries, logs tokens-in/out + latency per call (slf4j + MDC)
    PromptLoader.java             loads/caches classpath prompts, substitutes variables
    DocumentExtractionService.java PDF (pdfbox) / DOCX (poi-ooxml) / TXT → text; chunking for large docs
    QuestionGenerationService.java two entry points:
                                  generateFromDocument(docId, templateId, opts) and
                                  generateFromChat(sessionId, message) → both produce GeneratedQuestionSet
    CandidateMapper.java          LLM output POJO → DefinitionDocument.Item (+ validateStructure per item;
                                  invalid items are repaired once via a follow-up LLM call, else rejected
                                  with reason — never persisted unvalidated)
    DedupService.java             normalized-text match against org's template corpus → similarExisting flag
  domain/      AiDocument, GenerationJob, ChatSession, ChatMessage, GeneratedQuestion (+status enums)
  repository/  one JpaRepository each (findByIdAndOrgId pattern)
  service/     QuestionImportService.java   ← the single shared import path
  controller/  AiGenerationController.java (+ DTOs in dto/AiGenerationDtos.java as records)
src/main/resources/prompts/
  question-generation.system.md   knows the Item schema, structure rules, org result-type keys
  document-generation.user.md     per-chunk user prompt template
  chat.system.md                  adds the procedure's current draft definition + candidate list
```

LLM call shape: Anthropic Java SDK **structured outputs** (`outputConfig(GeneratedCandidates.class)`) so the
response is schema-constrained JSON, then Bean Validation + `DefinitionValidator.validateStructure` on each
mapped Item. Prompt includes the org's *active* result-type keys so options/bands map to real vocabulary.

### Persistence (new tables, migrations in BOTH `db/migration` V8 and `db/tenant` V6, column-identical)

```
ai_document          id, org_id, filename, media_type, size_bytes, content bytea,
                     extracted_text text, extraction_status, error, created_by/at
generation_job       id, org_id, template_id, document_id, status QUEUED|RUNNING|COMPLETED|FAILED,
                     error, model, input_tokens, output_tokens, created_by/at, completed_at
chat_session         id, org_id, template_id, status, created_by/at
chat_message         id, session_id, role USER|ASSISTANT, content text, created_at
generated_question   id, org_id, template_id, source DOCUMENT|CHAT, source_ref (job_id|session_id),
                     turn_no / chunk_ref (text span), item_json text (one validated Item, no keys),
                     confidence numeric, similar_existing jsonb-ish text (template id + question key + text),
                     status DRAFT|IMPORTED|DISCARDED, imported_key, imported_at, created_at
```

`GeneratedQuestionSet` (the shared artifact both features return) = the `generated_question` rows for one
`sourceRef`, serialized through one response record.

### Async (Feature 1)

No queue exists in this repo (Kafka is used for cross-service facts, not work queues). Extraction + generation
run on a Spring `@Async` executor (`AiAsyncConfig`, bounded pool) with `generation_job` as the durable state —
`GET …/generation-jobs/{id}` polls it, matching the repo's "DB is the source of truth" style. Extracted text is
persisted once on `ai_document`; re-running generation reuses it without re-extracting. Org/tenant context is
captured before dispatch and re-established inside the async task (OrgContext is a ThreadLocal).

### Chat (Feature 2)

Session + messages persisted; each turn replays full history to the LLM; system prompt carries the Item schema,
the template's current draft definition, active result types, and the current candidate set so the model can
refine/add/remove candidates. Add/remove/refine operations are expressed in the structured output
(candidate list diff), applied to `generated_question` rows.

### Shared import path (both features; idempotent)

`QuestionImportService.importQuestions(templateId, List<UUID> candidateIds)`:
lock template → require ACTIVE + current draft → load candidates (org-scoped, must belong to template) →
skip any already `IMPORTED` (idempotent no-op; re-import returns current state, no duplicates) → append Items
(source set, keys absent) to draft definition → existing validate/mint/canonicalize/save (bumps `rowVersion`) →
mark candidates `IMPORTED` with their minted keys, same transaction.

### API (repo conventions: envelope via ResponseEnvelopeAdvice, records, FGA guards)

| Method/Path | Purpose | Guard |
|---|---|---|
| `POST /api/v1/ai/documents` (multipart) | upload → docId, async extraction | `@fga.checkOrg('can_manage_templates')` |
| `POST /api/v1/procedure-templates/{id}/generate/document` `{docId}` | start generation → jobId | same |
| `GET  /api/v1/ai/generation-jobs/{jobId}` | status + GeneratedQuestionSet | same |
| `POST /api/v1/procedure-templates/{id}/chat` | create chat session | same |
| `POST /api/v1/ai/chat/{sessionId}/messages` `{message}` | reply + updated candidates | same |
| `GET  /api/v1/procedure-templates/{id}/question-drafts` | list candidates for review | `@fga.checkOrg('can_view')` |
| `POST /api/v1/procedure-templates/{id}/questions/import` `{candidateIds[]}` | shared import | `@fga.checkOrg('can_manage_templates')` |

> Gateway note: the `/api/v1/ai/**` prefix must be added to the procedure-service route predicate in the
> gateway's `application.yml` (it's a gitignored clone — the README's predicate table is the source of truth
> and gets the new full value), plus a gateway rebuild. `/api/v1/procedure-templates/**` paths route already.

### Config / feature flag

```yaml
sclera:
  ai:
    enabled: ${AI_QUESTION_GENERATION_ENABLED:false}
    model: ${SCLERA_AI_MODEL:claude-sonnet-4-6}
    api-key: ${ANTHROPIC_API_KEY:}
    max-output-tokens: 8192
    request-timeout: 60s
```
All AI beans `@ConditionalOnProperty("sclera.ai.enabled")`; controller returns 404-equivalent when disabled
(beans absent). Dev profile may default `enabled: true`.

### Dependencies (root pom dependencyManagement + service pom)

`com.anthropic:anthropic-java:2.34.0`, `org.apache.pdfbox:pdfbox:3.0.x`, `org.apache.poi:poi-ooxml:5.x`.

### Tests

- Unit: `CandidateMapper` (valid/invalid/repair), `DedupService` normalization, `KeyMinter` interplay on import,
  idempotent re-import.
- IT (extend `PostgresIntegrationTest`, `LlmClient` as `@MockBean`): Feature 1 end-to-end
  (upload txt → job → candidates → import → draft contains items with minted keys + source=DOCUMENT);
  Feature 2 end-to-end (session → message → candidates → refine → import).

## 4. Implementation order

1. Shared types + `LlmClient` + prompts + `CandidateMapper` validation (+ flag/config/deps)
2. Migrations + entities/repos + `QuestionImportService` (shared import path) + drafts listing
3. Feature 1: upload/extraction/async job/generation
4. Feature 2: chat sessions/messages/refinement
5. Tests, gateway predicate update, frontend API stubs (optional follow-up)

## 5. Decisions (answered 2026-10-07)

1. **Result mapping** — LLM maps Yes/No-style options to the system `PASS`/`FAIL` keys (mirroring the
   frontend's existing Yes/No default); all other result mappings are left to the author in review.
2. **`workOrder` / `alertProfile`** — never set by the LLM (`false`/`null`); author decisions.
3. **Follow-ups** — the LLM may generate **one level** of `when`/`follow` nesting; deeper nesting is
   flattened/rejected by the candidate mapper.
4. **Model** — **dynamic, budget-driven.** No hard-coded model: `sclera.ai.model` property
   (`SCLERA_AI_MODEL` env var) selects the default at deploy time, and `LlmClient` accepts a per-call model
   override so callers can route cheap vs. expensive generations (e.g. Haiku for chat refinement, Sonnet/Opus
   for document extraction) without a code change. Every LLM log line records the model used alongside token
   counts so cost can be attributed per model.

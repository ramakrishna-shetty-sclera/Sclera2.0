# External services — the contracts this service stands in for

This file exists for one reason: when the real services arrive, whoever builds
them should be able to read this and build to the same contract the calling
services already code against, rather than guess at it. Each section names the
contract, what the stub actually does, and what is still unspecified. When a
domain cuts over to a real service, its section is replaced by a pointer to
that service and the stub is deleted.

See the root plan's Part C for why this service exists at all and what rule
decides whether something belongs here versus being called directly.

---

## Storage

**Contract.** Store a reference document's bytes and hand back an opaque
`location`; later, given that `location`, produce a URL to fetch it and answer
whether it still resolves. The calling service never parses `location` — it is
this service's own business what it means.

```java
public interface DocumentStore {
    StoredDocument store(UUID orgId, String filename, InputStream content);
    URI downloadUrl(String location);
    boolean exists(String location);
    boolean isStub();
}
```

**Both implementations must return a URL, never bytes.** That is what lets a
caller treat them identically: follow the URL. Branching on which one is
active anywhere downstream is the mistake this contract exists to prevent.

### Stub behaviour (`local`, the default)

Writes to a directory on disk — a Docker named volume in compose
(`helper-documents`), a relative `data/documents` on a bare run. The location
is `stub-t_<org-no-dashes>/<uuid>-<filename>`, reusing the tenant-schema naming
convention so it is readable on disk; the `stub-` prefix is mandatory on every
location this implementation ever returns.

`downloadUrl` has nothing to sign, so it returns a link back to this service's
own `GET /api/v1/helper/documents/content?location=…`, which streams the bytes
back. `exists` is a plain file check, confined to the configured root
regardless of what the location string claims.

Every response is marked: `stub: true` in the body, `X-Sclera-Stub: true` on
the response, and the `stub-` prefix on the location itself. That is what stops
a fake artifact reaching a customer unnoticed, and it is the one thing to
assert on before go-live.

### The real implementation — specified, not built

**Owner:** whichever team owns cloud infrastructure. **Takes over:** when a
bucket exists and this service is told to point at it.

Set `sclera.storage.provider=s3` (`SCLERA_STORAGE_PROVIDER=s3`). An
implementation registered under that value is required — there is
deliberately no fallback to `local` if one is missing, so a misconfigured
production environment fails to start rather than silently writing customer
documents to a container's local disk.

What it must do:

- `store` uploads to a bucket under the same key shape the local
  implementation uses (`t_<org-no-dashes>/<uuid>-<filename>`), so a path that
  already exists in the library never collides and the org scoping an admin
  can reason about on disk carries straight across.
- `downloadUrl` signs a short-lived GET directly against the bucket (minutes,
  not hours) and returns that — never a link back to this service. A signed
  URL is generated fresh on every call; **the location itself is a key, never
  a URL**, because a stored signed URL would be dead by the time anyone
  followed it.
- `exists` does a HEAD against the bucket.
- `isStub` returns `false`.

**Unspecified, and this service's own `local` implementation does not need an
answer:** bucket lifecycle policy, encryption at rest, retention, and what (if
anything) removes an object when the last citing procedure version is
archived. `procedure-service` never deletes a cited document while a published
version still references it (`version_document_ref`), so storage-side
retention is a policy question for whoever owns the bucket, not a correctness
requirement this contract depends on.

---

## Property vocabulary

See feature 8's own plan and the `external/vocabulary/` package for its
contract — not repeated here, since that feature's branch is the place that
contract is actually built and documented against real code.

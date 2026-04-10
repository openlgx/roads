# Hosted alpha API contract (normative)

This document defines the **HTTP + JSON contract** between the Android collector, Supabase Edge Functions, and operators. **Implementation must match** these shapes unless the contract version is bumped.

**Physical URLs:** Supabase exposes each function at:

`{SUPABASE_PROJECT_URL}/functions/v1/<function-name>`

| Logical route (documentation) | Function name | Method |
|-------------------------------|---------------|--------|
| `POST /v1/uploads/create` | `uploads-create` | POST |
| `POST /v1/uploads/complete` | `uploads-complete` | POST |
| Council manifest | `council-layers-manifest` | GET |
| Council roughness layer | `council-layers-roughness` | GET |
| Council anomalies layer | `council-layers-anomalies` | GET |
| Council consensus layer | `council-layers-consensus` | GET |
| Health | `healthz` | GET |

Example: `https://<project-ref>.supabase.co/functions/v1/uploads-create`

**API version:** Upload bodies use `apiVersion: 1`. Future breaking changes bump this field and `manifestVersion` where applicable.

---

## Shared headers

### Upload endpoints (`uploads-create`, `uploads-complete`)

- `Authorization: Bearer <api_key>` **or** `X-API-Key: <api_key>`
- `Content-Type: application/json`

The key must exist in Neon `api_keys` with `key_type = 'DEVICE_UPLOAD'` and scope matching `projectId` / `deviceId` in the body when those columns are set on the key row.

### Council layer endpoints

- Same auth header pattern; key must have `key_type = 'COUNCIL_READ'` and be scoped to the council being requested.

---

## Error envelope

Non-2xx responses use JSON:

```json
{
  "error": {
    "code": "string",
    "message": "string",
    "details": "optional string or object"
  }
}
```

Typical HTTP status codes: `400` (validation), `401` / `403` (auth), `404` (missing resource), `409` (conflict), `413` (payload too large), `500` (server).

---

## `POST …/uploads-create`

### Request body

| Field | Type | Required | Notes |
|-------|------|----------|-------|
| `apiVersion` | `1` | yes | Contract version |
| `projectId` | uuid | yes | Neon `projects.id` |
| `deviceId` | uuid | yes | Neon `devices.id` |
| `clientSessionUuid` | uuid | yes | App `RecordingSessionEntity.uuid` |
| `artifactKind` | string | yes | `RAW_UPLOAD` or `FILTERED_UPLOAD` |
| `exportSchemaVersion` | int | yes | Matches export / `SessionExporter` schema |
| `byteSize` | int | yes | Exact ZIP size in bytes |
| `contentChecksumSha256` | string | yes | Lowercase hex, 64 chars |
| `mimeType` | string | yes | e.g. `application/zip` |

Optional (alpha may omit): `startedAtEpochMs` (ms since epoch; used for storage path date partitioning), `multipart`, `chunkIndex`, `chunkTotal`.

**Chunking:** Alpha response includes implicit single-part upload (`multipart` not required). Future responses may add chunk manifest fields (`chunkId`, etc.) without changing `apiVersion` if backward compatible.

**Size limits:** Uploads are capped server-side (current implementation: **52,428,800** bytes ≈ 50 MiB). Document [Supabase Storage limits](https://supabase.com/docs/guides/storage) for your plan; very large bundles may require multipart in a later contract revision.

### Response `200`

| Field | Type | Notes |
|-------|------|-------|
| `uploadJobId` | uuid | Neon `upload_jobs.id` |
| `recordingSessionId` | uuid | Neon `recording_sessions.id` |
| `storageBucket` | string | e.g. `roads-alpha-raw` |
| `objectKey` | string | Full key per storage contract |
| `signedUploadUrl` | string | HTTPS target for **PUT** |
| `signedUploadMethod` | string | `PUT` |
| `signedUploadHeaders` | object | Headers client must send with PUT (e.g. `Content-Type`, `x-upsert`) |
| `signedUrlExpiresAt` | string | ISO-8601 expiry |
| `expiresAt` | string | Alias of `signedUrlExpiresAt` (same instant) |
| `maxBytes` | int | Must match or cap requested `byteSize` |
| `artifactKind` | string | Echo of request (`RAW_UPLOAD` / `FILTERED_UPLOAD`) |
| `bucket` | string | Alias of `storageBucket` |
| `multipart` | bool | `false` for single-part PUT |
| `checksumAlgorithm` | string | `sha256` |
| `requiredHeaders` | object | Echo of headers required on PUT |

**Multipart scaffold (future):** Chunk fields may be added without bumping `apiVersion`; clients should ignore unknown keys.

---

## `POST …/uploads-complete`

### Request body

| Field | Type | Required |
|-------|------|----------|
| `apiVersion` | `1` | yes |
| `uploadJobId` | uuid | yes |
| `objectKey` | string | yes; must match create response |
| `byteSize` | int | yes |
| `contentChecksumSha256` | string | yes; sha256 hex |
| `clientSessionUuid` | uuid | optional; must match session if present |
| `artifactKind` | string | optional; must match upload job if present |

### Response `200`

| Field | Type | Notes |
|-------|------|-------|
| `uploadJobId` | uuid | |
| `state` | string | `COMPLETED` when successful |
| `artifactId` | uuid | Neon `artifacts.id` |
| `processingJobId` | uuid \| null | New `processing_jobs` row when enqueued |

**Idempotency:** Repeating complete with the same checksum yields the same final state; duplicate artifacts are not created.

**Storage verification:** Before inserting artifacts, the function performs a **ranged read** against Storage to confirm the object exists and its **total byte length** matches `byteSize`. On mismatch or missing object, the API returns **`412 Precondition Failed`** (`precondition_failed`) and leaves the upload job incomplete.

---

## Storage key contract (two-digit `mm`)

All paths use **UTC** year/month with **two-digit month** `mm`.

- Raw ZIP: `raw/{councilSlug}/{projectSlug}/{deviceId}/{yyyy}/{mm}/{sessionUuid}.zip`
- Filtered ZIP: `filtered/{councilSlug}/{projectSlug}/{deviceId}/{yyyy}/{mm}/{sessionUuid}.zip`
- Road pack GeoJSON: `roadpacks/{councilSlug}/{version}/public-roads.geojson` (FlatGeobuf optional later)
- Published layers: `published/{councilSlug}/roughness/latest.geojson` (and `anomalies`, `consensus`)
- Published manifest: `published/{councilSlug}/manifest.json`

Buckets are **private**; uploads use short-lived signed PUT URLs; council reads use Edge Functions with service role and `COUNCIL_READ` validation.

---

## `GET …/healthz`

Returns `200` with small JSON body. May include a Neon connectivity check (`SELECT 1`).

---

## Council layer GET functions

Authenticated with `COUNCIL_READ`. Functions resolve the council (e.g. by slug query parameter), verify the key is scoped to that council, then return signed read URLs or stream content from `roads-alpha-published` per implementation.

Exact query parameters are defined alongside the Edge Function source under `backend/supabase/functions/` (e.g. `councilSlug`).

---

## Operator validation (pilot smoke)

The repo script `backend/scripts/pilot_smoke_e2e.py` exercises this contract against a live project (no new HTTP features; it is a client-style probe).

| Check | Expected signal |
|-------|-----------------|
| Wrong `COUNCIL_READ` / bogus Bearer on manifest | **HTTP 401** (`council_read_key_rejected` line) |
| Valid `COUNCIL_READ` on manifest | **HTTP 200** JSON with `manifestVersion`, `councilSlug`, `publishedAt`, `publishRunId`, `layerArtifacts`, or **HTTP 404** if publish has not written `manifest.json` yet (see `--require-published`; script also prints `council_read_key_accepted` when auth succeeds for 200/404) |
| Layer GETs (`council-layers-roughness`, …) | **HTTP 200** after redirects when objects exist, else **404** |
| `uploads-create` + Storage PUT + `uploads-complete` | **HTTP 200** on create/complete; response includes `artifactId`; `processingJobId` set when a new `processing_jobs` row is created |

Full operator sequence, Windows PowerShell examples, and pass/fail summary semantics: [pilot-readiness.md](pilot-readiness.md).

---

## Related

- [docs/setup-hosted-alpha.md](setup-hosted-alpha.md) — secrets, CLI, rotation
- [docs/backend.md](backend.md) — Neon vs Supabase layout
- [docs/council-publishing.md](council-publishing.md) — manifest shape for `published/.../manifest.json`

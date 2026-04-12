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
| `byteSize` | int | yes | Single ZIP: full file size. **Multipart:** size of **this part only** (bytes). |
| `contentChecksumSha256` | string | yes | Lowercase hex, 64 chars — **whole file** for single ZIP; **this part’s** SHA-256 for multipart parts |
| `mimeType` | string | yes | e.g. `application/zip` (single) or `application/octet-stream` (multipart parts) |

Optional: `startedAtEpochMs` (ms since epoch; used for storage path date partitioning).

**Multipart object** (`multipart`, optional): when set, this request creates one **part** of a logical ZIP. All parts share the same `multipart.groupId` (UUID), `multipart.partTotal`, `multipart.wholeFileBytes`, and `multipart.wholeFileChecksumSha256`. Each part has a distinct `multipart.partIndex` (0-based). Storage object keys are the normal `…/{sessionUuid}.zip` path with a **`.part0000`**, **`.part0001`**, … suffix.

**Size limits (two layers):** (1) **Logical ZIP** (`byteSize` for single-part, or `multipart.wholeFileBytes` for multipart): Edge rejects above **1 GiB**. (2) **Each Storage object** (each signed PUT): must stay **≤ 48 MiB** when using multipart (Edge `CHUNK_MAX_BYTES`, under Supabase Free **50 MiB** per object). Single-part uploads may request up to **1 GiB** `byteSize` at the Edge, but **Storage** still enforces its own cap — on **Free**, a single PUT **cannot exceed 50 MiB**; use **multipart** for larger bundles. **Pro+** can raise Storage’s global limit in the dashboard. See [setup-hosted-alpha.md](setup-hosted-alpha.md#storage-size-limit-vs-edge-uploads-create).

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
| `multipart` | bool | `true` when the request body included `multipart` |
| `multipartGroupId` | uuid \| null | Echo when multipart |
| `multipartPartIndex` | int \| null | Echo when multipart |
| `multipartPartTotal` | int \| null | Echo when multipart |
| `checksumAlgorithm` | string | `sha256` |
| `requiredHeaders` | object | Echo of headers required on PUT |

Clients should ignore unknown response keys.

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
| `state` | string | `COMPLETED` when the logical upload is finished; `PART_UPLOADED` when a multipart **part** is verified but more parts remain |
| `artifactId` | uuid \| null | Set when the logical artifact exists (`null` while multipart parts are still in flight) |
| `processingJobId` | uuid \| null | New `processing_jobs` row when enqueued |
| `multipartPending` | bool | `true` when `state` is `PART_UPLOADED` or artifact not yet created |
| `completedParts` | int | Parts verified so far (multipart) or `1` for single-blob |
| `totalParts` | int | Total parts (multipart) or `1` for single-blob |

**Idempotency:** Repeating complete with the same checksum yields the same final state; duplicate artifacts are not created. For multipart, completing the **last** part creates one `artifacts` row with `part_storage_keys_json` listing all part keys in order.

**Storage verification:** Before inserting artifacts, the function performs a **ranged read** against Storage to confirm the object exists and its **total byte length** matches `byteSize`. On mismatch or missing object, the API returns **`412 Precondition Failed`** (`precondition_failed`) and leaves the upload job incomplete.

---

## Storage key contract (two-digit `mm`)

All paths use **UTC** year/month with **two-digit month** `mm`.

- Raw ZIP: `raw/{councilSlug}/{projectSlug}/{deviceId}/{yyyy}/{mm}/{sessionUuid}.zip` (multipart: append `.part0000`, `.part0001`, …)
- Filtered ZIP: `filtered/{councilSlug}/{projectSlug}/{deviceId}/{yyyy}/{mm}/{sessionUuid}.zip` (multipart: same suffix pattern)
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

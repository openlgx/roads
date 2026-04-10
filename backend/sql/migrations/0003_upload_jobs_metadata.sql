ALTER TABLE upload_jobs
    ADD COLUMN IF NOT EXISTS artifact_kind text,
    ADD COLUMN IF NOT EXISTS object_key text,
    ADD COLUMN IF NOT EXISTS content_checksum_sha256 text,
    ADD COLUMN IF NOT EXISTS byte_size bigint;

CREATE INDEX IF NOT EXISTS idx_upload_jobs_artifact_kind ON upload_jobs (artifact_kind);

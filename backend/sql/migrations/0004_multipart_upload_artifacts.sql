-- Multipart session ZIP upload (each part ≤ Storage global limit, e.g. Free 50 MiB).
-- See uploads-create / uploads-complete multipart handling.

ALTER TABLE upload_jobs
  ADD COLUMN IF NOT EXISTS multipart_group_id uuid,
  ADD COLUMN IF NOT EXISTS part_index int,
  ADD COLUMN IF NOT EXISTS part_total int,
  ADD COLUMN IF NOT EXISTS whole_file_checksum_sha256 text,
  ADD COLUMN IF NOT EXISTS whole_file_bytes bigint;

CREATE INDEX IF NOT EXISTS idx_upload_jobs_multipart_group
  ON upload_jobs (multipart_group_id)
  WHERE multipart_group_id IS NOT NULL;

ALTER TABLE artifacts
  ADD COLUMN IF NOT EXISTS part_storage_keys_json jsonb;

COMMENT ON COLUMN artifacts.part_storage_keys_json IS
  'Ordered array of Storage object keys for ZIP parts; NULL = single storage_key blob.';

CREATE UNIQUE INDEX IF NOT EXISTS uq_upload_jobs_multipart_part
  ON upload_jobs (multipart_group_id, part_index)
  WHERE multipart_group_id IS NOT NULL AND part_index IS NOT NULL;

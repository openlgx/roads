-- Hosted alpha core schema (Neon). Depends on 0001_extensions.sql (pgcrypto, citext, postgis).
-- recording_sessions <-> artifacts: sessions first without FK to artifacts; artifacts link to sessions;
-- then add session -> artifact FKs.

CREATE TABLE IF NOT EXISTS councils (
    id uuid PRIMARY KEY DEFAULT gen_random_uuid(),
    slug citext NOT NULL UNIQUE,
    name text NOT NULL,
    created_at timestamptz NOT NULL DEFAULT now(),
    updated_at timestamptz NOT NULL DEFAULT now()
);

CREATE TABLE IF NOT EXISTS projects (
    id uuid PRIMARY KEY DEFAULT gen_random_uuid(),
    council_id uuid NOT NULL REFERENCES councils (id) ON DELETE RESTRICT,
    slug citext NOT NULL,
    name text NOT NULL,
    status text NOT NULL DEFAULT 'active',
    created_at timestamptz NOT NULL DEFAULT now(),
    updated_at timestamptz NOT NULL DEFAULT now(),
    UNIQUE (council_id, slug)
);

CREATE INDEX IF NOT EXISTS idx_projects_council_id ON projects (council_id);

CREATE TABLE IF NOT EXISTS lga_boundaries (
    id uuid PRIMARY KEY DEFAULT gen_random_uuid(),
    council_id uuid NOT NULL UNIQUE REFERENCES councils (id) ON DELETE CASCADE,
    source_name text NOT NULL,
    source_version text,
    geometry geometry(MultiPolygon, 4326),
    bbox double precision[],
    checksum text,
    created_at timestamptz NOT NULL DEFAULT now(),
    updated_at timestamptz NOT NULL DEFAULT now()
);

CREATE INDEX IF NOT EXISTS idx_lga_boundaries_council_id ON lga_boundaries (council_id);

CREATE TABLE IF NOT EXISTS road_packs (
    id uuid PRIMARY KEY DEFAULT gen_random_uuid(),
    council_id uuid NOT NULL REFERENCES councils (id) ON DELETE CASCADE,
    project_id uuid REFERENCES projects (id) ON DELETE SET NULL,
    version text NOT NULL,
    source_name text NOT NULL,
    source_version text,
    storage_key text NOT NULL,
    checksum text NOT NULL,
    format text NOT NULL,
    created_at timestamptz NOT NULL DEFAULT now(),
    updated_at timestamptz NOT NULL DEFAULT now(),
    UNIQUE (council_id, version)
);

CREATE INDEX IF NOT EXISTS idx_road_packs_council_id ON road_packs (council_id);

CREATE TABLE IF NOT EXISTS devices (
    id uuid PRIMARY KEY DEFAULT gen_random_uuid(),
    project_id uuid NOT NULL REFERENCES projects (id) ON DELETE CASCADE,
    stable_install_id text NOT NULL,
    platform text NOT NULL,
    manufacturer text,
    model text,
    os_version text,
    app_version text,
    road_pack_version text,
    created_at timestamptz NOT NULL DEFAULT now(),
    updated_at timestamptz NOT NULL DEFAULT now(),
    UNIQUE (project_id, stable_install_id)
);

CREATE INDEX IF NOT EXISTS idx_devices_project_id ON devices (project_id);
CREATE INDEX IF NOT EXISTS idx_devices_stable_install_id ON devices (stable_install_id);

CREATE TABLE IF NOT EXISTS api_keys (
    id uuid PRIMARY KEY DEFAULT gen_random_uuid(),
    council_id uuid REFERENCES councils (id) ON DELETE CASCADE,
    project_id uuid REFERENCES projects (id) ON DELETE CASCADE,
    device_id uuid REFERENCES devices (id) ON DELETE CASCADE,
    key_hash text NOT NULL,
    key_type text NOT NULL CHECK (key_type IN ('DEVICE_UPLOAD', 'COUNCIL_READ', 'INTERNAL_ADMIN')),
    status text NOT NULL DEFAULT 'active',
    created_at timestamptz NOT NULL DEFAULT now(),
    expires_at timestamptz,
    last_used_at timestamptz
);

CREATE INDEX IF NOT EXISTS idx_api_keys_key_hash ON api_keys (key_hash);
CREATE INDEX IF NOT EXISTS idx_api_keys_key_type ON api_keys (key_type);
CREATE INDEX IF NOT EXISTS idx_api_keys_project_id ON api_keys (project_id);
CREATE INDEX IF NOT EXISTS idx_api_keys_council_id ON api_keys (council_id);

CREATE TABLE IF NOT EXISTS artifacts (
    id uuid PRIMARY KEY DEFAULT gen_random_uuid(),
    council_id uuid NOT NULL REFERENCES councils (id) ON DELETE RESTRICT,
    project_id uuid NOT NULL REFERENCES projects (id) ON DELETE RESTRICT,
    recording_session_id uuid,
    artifact_kind text NOT NULL,
    storage_bucket text NOT NULL,
    storage_key text NOT NULL,
    mime_type text NOT NULL,
    byte_size bigint,
    checksum text,
    schema_version int,
    created_at timestamptz NOT NULL DEFAULT now()
);

CREATE INDEX IF NOT EXISTS idx_artifacts_council_project ON artifacts (council_id, project_id);
CREATE INDEX IF NOT EXISTS idx_artifacts_session ON artifacts (recording_session_id);
CREATE INDEX IF NOT EXISTS idx_artifacts_kind ON artifacts (artifact_kind);

CREATE TABLE IF NOT EXISTS recording_sessions (
    id uuid PRIMARY KEY DEFAULT gen_random_uuid(),
    project_id uuid NOT NULL REFERENCES projects (id) ON DELETE CASCADE,
    council_id uuid NOT NULL REFERENCES councils (id) ON DELETE RESTRICT,
    device_id uuid NOT NULL REFERENCES devices (id) ON DELETE RESTRICT,
    client_session_uuid uuid NOT NULL,
    started_at timestamptz,
    completed_at timestamptz,
    upload_state text NOT NULL DEFAULT 'NOT_STARTED',
    processing_state text NOT NULL DEFAULT 'NOT_STARTED',
    export_schema_version int,
    filtered_summary_json jsonb,
    raw_artifact_id uuid,
    filtered_artifact_id uuid,
    processing_summary_json jsonb,
    created_at timestamptz NOT NULL DEFAULT now(),
    updated_at timestamptz NOT NULL DEFAULT now(),
    UNIQUE (project_id, client_session_uuid)
);

CREATE INDEX IF NOT EXISTS idx_recording_sessions_council ON recording_sessions (council_id);
CREATE INDEX IF NOT EXISTS idx_recording_sessions_project ON recording_sessions (project_id);
CREATE INDEX IF NOT EXISTS idx_recording_sessions_device ON recording_sessions (device_id);
CREATE INDEX IF NOT EXISTS idx_recording_sessions_client_uuid ON recording_sessions (client_session_uuid);
CREATE INDEX IF NOT EXISTS idx_recording_sessions_upload_state ON recording_sessions (upload_state);
CREATE INDEX IF NOT EXISTS idx_recording_sessions_processing_state ON recording_sessions (processing_state);

-- Re-runnable: skip when constraint already present (partially applied or legacy DBs).
DO $$
BEGIN
  IF NOT EXISTS (
    SELECT 1 FROM pg_constraint WHERE conname = 'fk_artifacts_recording_session'
  ) THEN
    ALTER TABLE artifacts
      ADD CONSTRAINT fk_artifacts_recording_session
      FOREIGN KEY (recording_session_id) REFERENCES recording_sessions (id) ON DELETE SET NULL;
  END IF;
END $$;

DO $$
BEGIN
  IF NOT EXISTS (
    SELECT 1 FROM pg_constraint WHERE conname = 'fk_recording_sessions_raw_artifact'
  ) THEN
    ALTER TABLE recording_sessions
      ADD CONSTRAINT fk_recording_sessions_raw_artifact
      FOREIGN KEY (raw_artifact_id) REFERENCES artifacts (id) ON DELETE SET NULL;
  END IF;
END $$;

DO $$
BEGIN
  IF NOT EXISTS (
    SELECT 1 FROM pg_constraint WHERE conname = 'fk_recording_sessions_filtered_artifact'
  ) THEN
    ALTER TABLE recording_sessions
      ADD CONSTRAINT fk_recording_sessions_filtered_artifact
      FOREIGN KEY (filtered_artifact_id) REFERENCES artifacts (id) ON DELETE SET NULL;
  END IF;
END $$;

CREATE TABLE IF NOT EXISTS upload_jobs (
    id uuid PRIMARY KEY DEFAULT gen_random_uuid(),
    recording_session_id uuid NOT NULL REFERENCES recording_sessions (id) ON DELETE CASCADE,
    state text NOT NULL DEFAULT 'PENDING' CHECK (state IN ('PENDING', 'UPLOADING', 'COMPLETED', 'FAILED')),
    retry_count int NOT NULL DEFAULT 0,
    last_error text,
    created_at timestamptz NOT NULL DEFAULT now(),
    updated_at timestamptz NOT NULL DEFAULT now()
);

CREATE INDEX IF NOT EXISTS idx_upload_jobs_session ON upload_jobs (recording_session_id);
CREATE INDEX IF NOT EXISTS idx_upload_jobs_state ON upload_jobs (state);

CREATE TABLE IF NOT EXISTS processing_jobs (
    id uuid PRIMARY KEY DEFAULT gen_random_uuid(),
    recording_session_id uuid NOT NULL REFERENCES recording_sessions (id) ON DELETE CASCADE,
    state text NOT NULL DEFAULT 'PENDING' CHECK (state IN ('PENDING', 'RUNNING', 'COMPLETED', 'FAILED')),
    processor_version text,
    retry_count int NOT NULL DEFAULT 0,
    last_error text,
    started_at timestamptz,
    completed_at timestamptz,
    created_at timestamptz NOT NULL DEFAULT now(),
    updated_at timestamptz NOT NULL DEFAULT now()
);

CREATE INDEX IF NOT EXISTS idx_processing_jobs_session ON processing_jobs (recording_session_id);
CREATE INDEX IF NOT EXISTS idx_processing_jobs_state ON processing_jobs (state);

CREATE TABLE IF NOT EXISTS published_layer_runs (
    id uuid PRIMARY KEY DEFAULT gen_random_uuid(),
    council_id uuid NOT NULL REFERENCES councils (id) ON DELETE CASCADE,
    started_at timestamptz NOT NULL DEFAULT now(),
    completed_at timestamptz,
    state text NOT NULL DEFAULT 'RUNNING' CHECK (state IN ('PENDING', 'RUNNING', 'COMPLETED', 'FAILED')),
    publisher_version text,
    summary_json jsonb,
    created_at timestamptz NOT NULL DEFAULT now()
);

CREATE INDEX IF NOT EXISTS idx_published_layer_runs_council ON published_layer_runs (council_id);
CREATE INDEX IF NOT EXISTS idx_published_layer_runs_state ON published_layer_runs (state);

CREATE TABLE IF NOT EXISTS published_layer_artifacts (
    id uuid PRIMARY KEY DEFAULT gen_random_uuid(),
    published_layer_run_id uuid NOT NULL REFERENCES published_layer_runs (id) ON DELETE CASCADE,
    council_id uuid NOT NULL REFERENCES councils (id) ON DELETE CASCADE,
    layer_kind text NOT NULL CHECK (layer_kind IN ('ROUGHNESS', 'ANOMALIES', 'CONSENSUS', 'MANIFEST')),
    artifact_id uuid NOT NULL REFERENCES artifacts (id) ON DELETE CASCADE,
    created_at timestamptz NOT NULL DEFAULT now()
);

CREATE INDEX IF NOT EXISTS idx_published_layer_artifacts_run ON published_layer_artifacts (published_layer_run_id);
CREATE INDEX IF NOT EXISTS idx_published_layer_artifacts_council ON published_layer_artifacts (council_id);

-- Optional hosted derived (alpha scaffolding; populate from processing later)
CREATE TABLE IF NOT EXISTS derived_window_features_hosted (
    id uuid PRIMARY KEY DEFAULT gen_random_uuid(),
    recording_session_id uuid NOT NULL REFERENCES recording_sessions (id) ON DELETE CASCADE,
    council_id uuid NOT NULL REFERENCES councils (id) ON DELETE CASCADE,
    project_id uuid NOT NULL REFERENCES projects (id) ON DELETE CASCADE,
    payload_json jsonb NOT NULL,
    created_at timestamptz NOT NULL DEFAULT now()
);

CREATE INDEX IF NOT EXISTS idx_derived_hosted_session ON derived_window_features_hosted (recording_session_id);

CREATE TABLE IF NOT EXISTS anomaly_candidates_hosted (
    id uuid PRIMARY KEY DEFAULT gen_random_uuid(),
    recording_session_id uuid NOT NULL REFERENCES recording_sessions (id) ON DELETE CASCADE,
    council_id uuid NOT NULL REFERENCES councils (id) ON DELETE CASCADE,
    project_id uuid NOT NULL REFERENCES projects (id) ON DELETE CASCADE,
    payload_json jsonb NOT NULL,
    created_at timestamptz NOT NULL DEFAULT now()
);

CREATE INDEX IF NOT EXISTS idx_anomaly_hosted_session ON anomaly_candidates_hosted (recording_session_id);

CREATE TABLE IF NOT EXISTS segment_consensus_hosted (
    id uuid PRIMARY KEY DEFAULT gen_random_uuid(),
    recording_session_id uuid REFERENCES recording_sessions (id) ON DELETE SET NULL,
    council_id uuid NOT NULL REFERENCES councils (id) ON DELETE CASCADE,
    project_id uuid NOT NULL REFERENCES projects (id) ON DELETE CASCADE,
    payload_json jsonb NOT NULL,
    created_at timestamptz NOT NULL DEFAULT now()
);

CREATE INDEX IF NOT EXISTS idx_consensus_hosted_council ON segment_consensus_hosted (council_id);

-- updated_at maintenance (re-runnable)
CREATE OR REPLACE FUNCTION set_updated_at()
RETURNS TRIGGER AS $$
BEGIN
    NEW.updated_at = now();
    RETURN NEW;
END;
$$ LANGUAGE plpgsql;

DROP TRIGGER IF EXISTS tr_councils_updated ON councils;
CREATE TRIGGER tr_councils_updated BEFORE UPDATE ON councils FOR EACH ROW EXECUTE PROCEDURE set_updated_at();

DROP TRIGGER IF EXISTS tr_projects_updated ON projects;
CREATE TRIGGER tr_projects_updated BEFORE UPDATE ON projects FOR EACH ROW EXECUTE PROCEDURE set_updated_at();

DROP TRIGGER IF EXISTS tr_lga_updated ON lga_boundaries;
CREATE TRIGGER tr_lga_updated BEFORE UPDATE ON lga_boundaries FOR EACH ROW EXECUTE PROCEDURE set_updated_at();

DROP TRIGGER IF EXISTS tr_road_packs_updated ON road_packs;
CREATE TRIGGER tr_road_packs_updated BEFORE UPDATE ON road_packs FOR EACH ROW EXECUTE PROCEDURE set_updated_at();

DROP TRIGGER IF EXISTS tr_devices_updated ON devices;
CREATE TRIGGER tr_devices_updated BEFORE UPDATE ON devices FOR EACH ROW EXECUTE PROCEDURE set_updated_at();

DROP TRIGGER IF EXISTS tr_recording_sessions_updated ON recording_sessions;
CREATE TRIGGER tr_recording_sessions_updated BEFORE UPDATE ON recording_sessions FOR EACH ROW EXECUTE PROCEDURE set_updated_at();

DROP TRIGGER IF EXISTS tr_upload_jobs_updated ON upload_jobs;
CREATE TRIGGER tr_upload_jobs_updated BEFORE UPDATE ON upload_jobs FOR EACH ROW EXECUTE PROCEDURE set_updated_at();

DROP TRIGGER IF EXISTS tr_processing_jobs_updated ON processing_jobs;
CREATE TRIGGER tr_processing_jobs_updated BEFORE UPDATE ON processing_jobs FOR EACH ROW EXECUTE PROCEDURE set_updated_at();

-- OLGX Roads hosted alpha — Neon only. Apply in order on dev/prod branches.
-- Idempotent where possible.

CREATE EXTENSION IF NOT EXISTS pgcrypto;
CREATE EXTENSION IF NOT EXISTS citext;

-- PostGIS recommended for LGA boundaries + server-side clip. If this fails on your plan, skip and use 0002_geo_fallback notes.
CREATE EXTENSION IF NOT EXISTS postgis;

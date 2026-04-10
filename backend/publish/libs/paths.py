"""Storage key builders — keep aligned with supabase/functions/_shared/paths.ts"""

from datetime import datetime


def raw_session_zip_key(
    *,
    council_slug: str,
    project_slug: str,
    device_id: str,
    started_at: datetime,
    session_uuid: str,
) -> str:
    y = started_at.year
    m = f"{started_at.month:02d}"
    su = session_uuid.lower()
    return f"raw/{council_slug}/{project_slug}/{device_id}/{y}/{m}/{su}.zip"


def published_manifest_key(council_slug: str) -> str:
    return f"published/{council_slug}/manifest.json"


def published_roughness_geojson_key(council_slug: str) -> str:
    return f"published/{council_slug}/roughness/latest.geojson"


def published_anomalies_geojson_key(council_slug: str) -> str:
    return f"published/{council_slug}/anomalies/latest.geojson"


def published_consensus_geojson_key(council_slug: str) -> str:
    return f"published/{council_slug}/consensus/latest.geojson"


def road_pack_fgb_key(council_slug: str, version: str) -> str:
    return f"roadpacks/{council_slug}/{version}/public-roads.fgb"

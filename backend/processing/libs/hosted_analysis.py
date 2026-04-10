"""
Hosted analysis adapter — reuses analysis.roughness_lab pipeline on an extracted export root.

Expects PYTHONPATH to include repo root so `analysis.roughness_lab` resolves.
"""

from __future__ import annotations

import math
from pathlib import Path
from typing import Any

import pandas as pd

from analysis.roughness_lab.features import compute_windows
from analysis.roughness_lab.heuristics import add_cornering_shock_scores, add_heuristics


PROCESSOR_METHOD_VERSION = "roughness_lab_hosted_0.2"
WINDOW_S = 1.0


def run_roughness_on_extracted_root(
    root: Path,
    session_uuid: str,
) -> pd.DataFrame:
    """Load CSVs from bundle root and compute window features + heuristics."""
    loc_csv = root / "location_samples.csv"
    sen_csv = root / "sensor_samples.csv"
    loc = pd.read_csv(loc_csv) if loc_csv.is_file() else pd.DataFrame()
    sen = pd.read_csv(sen_csv) if sen_csv.is_file() else pd.DataFrame()
    df = compute_windows(session_uuid or "unknown", loc, sen, window_s=WINDOW_S)
    if df.empty:
        return df
    df = add_heuristics(df)
    df = add_cornering_shock_scores(df)
    return df


def anomalies_from_windows(df: pd.DataFrame) -> pd.DataFrame:
    """Rows flagged as vertical shock or vertical_impact primary."""
    if df.empty:
        return df
    mask_shock = df.get("heuristic_vertical_shock") == 1
    mask_pred = df.get("pred_primary", pd.Series(dtype=object)) == "vertical_impact"
    sub = df[mask_shock | mask_pred].copy()
    return sub


def _json_float(x: Any) -> float | None:
    try:
        v = float(x)
        if not math.isfinite(v):
            return None
        return v
    except (TypeError, ValueError):
        return None


def window_row_to_payload(
    row: pd.Series,
    council_id: str,
    project_id: str,
    recording_session_id: str,
    client_session_uuid: str,
) -> dict[str, Any]:
    mid_lat = float(row.get("mid_latitude", math.nan))
    mid_lon = float(row.get("mid_longitude", math.nan))
    geom: dict[str, Any]
    if math.isfinite(mid_lat) and math.isfinite(mid_lon):
        geom = {"type": "Point", "coordinates": [mid_lon, mid_lat]}
    else:
        geom = {"type": "Point", "coordinates": [0.0, 0.0]}

    return {
        "councilId": council_id,
        "projectId": project_id,
        "recordingSessionId": recording_session_id,
        "clientSessionUuid": client_session_uuid,
        "geometry": geom,
        "windowStartMs": float(row.get("window_start_ms", 0)),
        "windowEndMs": float(row.get("window_end_ms", 0)),
        "methodVersion": PROCESSOR_METHOD_VERSION,
        "isExperimental": True,
        "roughnessRms": _json_float(row.get("rms_linear_or_accel_mag")),
        "peakLinearMag": _json_float(row.get("peak_linear_or_accel_mag")),
        "predPrimary": row.get("pred_primary"),
        "scoreCornering": _json_float(row.get("score_cornering")),
        "scoreVerticalShock": _json_float(row.get("score_vertical_shock")),
        "speedMeanMps": _json_float(row.get("speed_mean_mps")),
    }


def anomaly_row_to_payload(
    row: pd.Series,
    council_id: str,
    project_id: str,
    recording_session_id: str,
    client_session_uuid: str,
) -> dict[str, Any]:
    mid_lat = float(row.get("mid_latitude", math.nan))
    mid_lon = float(row.get("mid_longitude", math.nan))
    geom = (
        {"type": "Point", "coordinates": [mid_lon, mid_lat]}
        if math.isfinite(mid_lat) and math.isfinite(mid_lon)
        else {"type": "Point", "coordinates": [0.0, 0.0]}
    )
    hvs = row.get("heuristic_vertical_shock", 0)
    try:
        hvs_i = int(hvs)
    except (TypeError, ValueError):
        hvs_i = 0
    return {
        "councilId": council_id,
        "projectId": project_id,
        "recordingSessionId": recording_session_id,
        "clientSessionUuid": client_session_uuid,
        "geometry": geom,
        "windowStartMs": float(row.get("window_start_ms", 0)),
        "windowEndMs": float(row.get("window_end_ms", 0)),
        "methodVersion": PROCESSOR_METHOD_VERSION,
        "isExperimental": True,
        "anomalyType": "IMPULSE_EVENT",
        "predPrimary": row.get("pred_primary"),
        "heuristicVerticalShock": hvs_i,
    }

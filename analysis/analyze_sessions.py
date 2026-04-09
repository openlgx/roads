#!/usr/bin/env python3
"""
Offline analysis of OLGX Roads session export bundles (zip or extracted folder).

All outputs are experimental exploratory summaries — not calibrated indices,
not IRI, and not intended for compliance or warranty without further validation.
"""

from __future__ import annotations

import argparse
import json
import math
import shutil
import sys
import tempfile
import zipfile
from dataclasses import dataclass
from pathlib import Path
from typing import Any

import matplotlib

matplotlib.use("Agg")
import matplotlib.pyplot as plt
import numpy as np
import pandas as pd

# --- Disclaimers (propagated into artifacts) ---
ANALYSIS_NOTE = (
    "experimental_offline_exploratory — not IRI, not road roughness certified, "
    "not calibration-validated; use for engineering inspection only"
)

# Android Sensor.TYPE ints observed in OLGX exports (extend as needed)
SENSOR_TYPE_NAMES: dict[int, str] = {
    1: "accelerometer",
    2: "magnetic_field",
    4: "gyroscope",
    9: "gravity",
    10: "linear_acceleration",
    11: "rotation_vector",
}


@dataclass
class SessionPaths:
    root: Path
    session_json: Path
    location_csv: Path | None
    sensor_csv: Path | None


@dataclass
class SessionSummary:
    source_path: str
    session_numeric_id: Any = None
    session_uuid: str = ""
    duration_s: float = math.nan
    location_sample_count: int = 0
    sensor_sample_count: int = 0
    sensor_types_distinct: str = ""
    avg_speed_mps: float = math.nan
    max_speed_mps: float = math.nan
    estimated_distance_m: float = math.nan
    wall_clock_monotonic_ok: bool = True
    wall_clock_monotonic_violations: int = 0
    elapsed_rt_monotonic_ok: bool = True
    elapsed_rt_monotonic_violations: int = 0
    location_max_gap_s: float = math.nan
    sensor_max_gap_s: float = math.nan
    sensor_wall_clock_monotonic_ok: bool = True
    sensor_wall_clock_monotonic_violations: int = 0
    approx_sample_rate_location_hz: float = math.nan
    approx_sample_rate_by_sensor_type: str = ""
    analysis_note: str = ANALYSIS_NOTE


def _find_bundle_roots(inputs: list[Path]) -> list[Path]:
    roots: list[Path] = []
    for p in inputs:
        p = p.resolve()
        if p.is_file() and p.suffix.lower() == ".zip":
            roots.append(p)
        elif p.is_dir():
            zips = sorted(p.glob("*.zip"))
            dirs_with_session = [
                sub for sub in sorted(p.iterdir()) if sub.is_dir() and (sub / "session.json").is_file()
            ]
            # If the folder contains zips, prefer them (avoids duplicating the same session as an extracted folder).
            if zips:
                roots.extend(zips)
            else:
                roots.extend(dirs_with_session)
        else:
            print(f"Warning: skip missing or unsupported path: {p}", file=sys.stderr)
    # de-dup while preserving order
    seen: set[str] = set()
    out: list[Path] = []
    for r in roots:
        key = str(r.resolve())
        if key not in seen:
            seen.add(key)
            out.append(r)
    return out


def _resolve_session_paths(bundle: Path) -> SessionPaths:
    if bundle.is_dir():
        root = bundle
    else:
        raise ValueError("Internal: expected directory for resolve_session_paths")

    loc = root / "location_samples.csv"
    if not loc.is_file():
        loc = None  # type: ignore[assignment]
    sen = root / "sensor_samples.csv"
    if not sen.is_file():
        sen = None  # type: ignore[assignment]
    return SessionPaths(
        root=bundle,
        session_json=root / "session.json",
        location_csv=loc,
        sensor_csv=sen,
    )


def _extract_zip(zip_path: Path) -> tuple[Path, Path]:
    """Returns (directory containing session.json, temp root to delete after processing)."""
    tmp = Path(tempfile.mkdtemp(prefix="olgx_export_"))
    with zipfile.ZipFile(zip_path, "r") as zf:
        zf.extractall(tmp)
    subs = [p for p in tmp.iterdir() if p.is_dir()]
    if len(subs) == 1 and (subs[0] / "session.json").is_file():
        return subs[0], tmp
    if (tmp / "session.json").is_file():
        return tmp, tmp
    shutil.rmtree(tmp, ignore_errors=True)
    raise FileNotFoundError(f"Could not find session folder inside {zip_path}")


def haversine_distance_m(lat1: np.ndarray, lon1: np.ndarray, lat2: np.ndarray, lon2: np.ndarray) -> np.ndarray:
    """Great-circle distance in metres between paired points (element-wise)."""
    r = 6_371_000.0
    p1 = np.radians(lat1)
    p2 = np.radians(lat2)
    dphi = np.radians(lat2 - lat1)
    dlmb = np.radians(lon2 - lon1)
    a = np.sin(dphi / 2) ** 2 + np.cos(p1) * np.cos(p2) * np.sin(dlmb / 2) ** 2
    c = 2 * np.arctan2(np.sqrt(a), np.sqrt(1.0 - a))
    return r * c


def _monotonicity_and_max_gap(t_ns_or_ms: np.ndarray, is_nanos: bool) -> tuple[bool, int, float]:
    if t_ns_or_ms.size < 2:
        return True, 0, math.nan
    dt = np.diff(t_ns_or_ms.astype(np.float64))
    violations = int(np.sum(dt < 0))
    ok = violations == 0
    if is_nanos:
        gaps_s = dt * 1e-9
    else:
        gaps_s = dt * 1e-3
    max_gap = float(np.max(gaps_s)) if gaps_s.size else math.nan
    return ok, violations, max_gap


def _approx_rate_hz(times_ms: np.ndarray) -> float:
    if times_ms.size < 2:
        return math.nan
    span_s = (times_ms[-1] - times_ms[0]) / 1000.0
    if span_s <= 0:
        return math.nan
    return (times_ms.size - 1) / span_s


def sensor_magnitude(df: pd.DataFrame) -> np.ndarray:
    return np.sqrt(df["x"].astype(float) ** 2 + df["y"].astype(float) ** 2 + df["z"].astype(float) ** 2)


def load_location(path: Path | None) -> pd.DataFrame:
    if path is None or not path.is_file():
        return pd.DataFrame()
    return pd.read_csv(path)


def load_sensors(path: Path | None) -> pd.DataFrame:
    if path is None or not path.is_file():
        return pd.DataFrame()
    return pd.read_csv(path)


def summarize_session(bundle_display: str, paths: SessionPaths) -> tuple[SessionSummary, pd.DataFrame, pd.DataFrame]:
    summary = SessionSummary(source_path=bundle_display)
    with open(paths.session_json, encoding="utf-8") as f:
        meta = json.load(f)
    summary.session_numeric_id = meta.get("id")
    summary.session_uuid = str(meta.get("uuid", ""))

    started = meta.get("startedAtEpochMs")
    ended = meta.get("endedAtEpochMs")
    if started is not None and ended is not None:
        summary.duration_s = max(0.0, (float(ended) - float(started)) / 1000.0)

    loc = load_location(paths.location_csv)
    sen = load_sensors(paths.sensor_csv)

    summary.location_sample_count = len(loc)
    summary.sensor_sample_count = len(sen)

    if not loc.empty:
        t0 = loc["wallClockUtcEpochMs"].min()
        t1 = loc["wallClockUtcEpochMs"].max()
        dur_samples = (t1 - t0) / 1000.0
        if math.isnan(summary.duration_s) or summary.duration_s <= 0:
            summary.duration_s = dur_samples
        else:
            summary.duration_s = max(summary.duration_s, dur_samples)

    if not loc.empty and "speedMps" in loc.columns:
        sp = loc["speedMps"].astype(float)
        summary.avg_speed_mps = float(sp.mean())
        summary.max_speed_mps = float(sp.max())

    if not loc.empty and len(loc) >= 2:
        lat = loc["latitude"].to_numpy(dtype=float)
        lon = loc["longitude"].to_numpy(dtype=float)
        d = haversine_distance_m(lat[:-1], lon[:-1], lat[1:], lon[1:])
        summary.estimated_distance_m = float(np.sum(d))

        wt = loc["wallClockUtcEpochMs"].to_numpy()
        ok_w, viol_w, gap_w = _monotonicity_and_max_gap(wt, is_nanos=False)
        summary.wall_clock_monotonic_ok = ok_w
        summary.wall_clock_monotonic_violations = viol_w
        summary.location_max_gap_s = gap_w
        summary.approx_sample_rate_location_hz = _approx_rate_hz(wt)

    if not sen.empty:
        st = sen["sensorType"].unique()
        names = sorted({SENSOR_TYPE_NAMES.get(int(s), f"type_{int(s)}") for s in st})
        summary.sensor_types_distinct = ";".join(names)

        et = sen["elapsedRealtimeNanos"].to_numpy()
        ok_e, viol_e, gap_e = _monotonicity_and_max_gap(et, is_nanos=True)
        summary.elapsed_rt_monotonic_ok = ok_e
        summary.elapsed_rt_monotonic_violations = viol_e
        summary.sensor_max_gap_s = gap_e

        wt_s = sen["wallClockUtcEpochMs"].to_numpy()
        ok_wsen, viol_wsen, _ = _monotonicity_and_max_gap(wt_s, is_nanos=False)
        summary.sensor_wall_clock_monotonic_ok = ok_wsen
        summary.sensor_wall_clock_monotonic_violations = viol_wsen

        rates_parts: list[str] = []
        for stype in sorted(sen["sensorType"].unique()):
            sub = sen[sen["sensorType"] == stype]
            t = sub["wallClockUtcEpochMs"].to_numpy()
            hz = _approx_rate_hz(t)
            label = SENSOR_TYPE_NAMES.get(int(stype), f"type_{int(stype)}")
            rates_parts.append(f"{label}~{hz:.2f}Hz")
        summary.approx_sample_rate_by_sensor_type = ";".join(rates_parts)

    return summary, loc, sen


def plot_series(
    out_dir: Path,
    session_label: str,
    loc: pd.DataFrame,
    sen: pd.DataFrame,
) -> None:
    plot_dir = out_dir / "plots"
    plot_dir.mkdir(parents=True, exist_ok=True)

    if not loc.empty and "wallClockUtcEpochMs" in loc.columns and "speedMps" in loc.columns:
        t0 = loc["wallClockUtcEpochMs"].min()
        t_s = (loc["wallClockUtcEpochMs"] - t0) / 1000.0
        fig, ax = plt.subplots(figsize=(9, 4))
        ax.plot(t_s, loc["speedMps"], color="C0", linewidth=0.8)
        ax.set_xlabel("Time from first fix (s)")
        ax.set_ylabel("Speed (m/s)")
        ax.set_title(f"Speed over time — {session_label}\n({ANALYSIS_NOTE})")
        ax.grid(True, alpha=0.3)
        fig.tight_layout()
        fig.savefig(plot_dir / "speed_over_time.png", dpi=120)
        plt.close(fig)

    acc = sen[sen["sensorType"] == 1] if not sen.empty else pd.DataFrame()
    if not acc.empty:
        t0 = acc["wallClockUtcEpochMs"].min()
        t_s = (acc["wallClockUtcEpochMs"] - t0) / 1000.0
        mag = sensor_magnitude(acc)
        fig, ax = plt.subplots(figsize=(9, 4))
        ax.plot(t_s, mag, color="C1", linewidth=0.5, alpha=0.85)
        ax.set_xlabel("Time from first sample in plot (s)")
        ax.set_ylabel("|a| (m/s²) — vector magnitude, sensor frame")
        ax.set_title(f"Accelerometer magnitude — {session_label}\n({ANALYSIS_NOTE})")
        ax.grid(True, alpha=0.3)
        fig.tight_layout()
        fig.savefig(plot_dir / "accelerometer_magnitude.png", dpi=120)
        plt.close(fig)

    gyro = sen[sen["sensorType"] == 4] if not sen.empty else pd.DataFrame()
    if not gyro.empty:
        t0 = gyro["wallClockUtcEpochMs"].min()
        t_s = (gyro["wallClockUtcEpochMs"] - t0) / 1000.0
        mag = sensor_magnitude(gyro)
        fig, ax = plt.subplots(figsize=(9, 4))
        ax.plot(t_s, mag, color="C2", linewidth=0.5, alpha=0.85)
        ax.set_xlabel("Time from first sample in plot (s)")
        ax.set_ylabel("|ω| (rad/s) — vector magnitude, sensor frame")
        ax.set_title(f"Gyroscope magnitude — {session_label}\n({ANALYSIS_NOTE})")
        ax.grid(True, alpha=0.3)
        fig.tight_layout()
        fig.savefig(plot_dir / "gyroscope_magnitude.png", dpi=120)
        plt.close(fig)


def compute_window_features_time(
    loc: pd.DataFrame,
    sen: pd.DataFrame,
    window_s: float,
) -> pd.DataFrame:
    """Fixed time windows in wall-clock ms from session min time."""
    if sen.empty:
        return pd.DataFrame()

    t_refs = []
    if not loc.empty:
        t_refs.append(loc["wallClockUtcEpochMs"].min())
    t_refs.append(sen["wallClockUtcEpochMs"].min())
    t0 = min(t_refs)
    t1 = max(sen["wallClockUtcEpochMs"].max(), loc["wallClockUtcEpochMs"].max() if not loc.empty else t0)
    edges = np.arange(t0, t1 + window_s * 1000, window_s * 1000)

    acc = sen[sen["sensorType"] == 1]
    gyro = sen[sen["sensorType"] == 4]
    # Prefer linear acceleration for "dynamic" magnitude if present
    lin = sen[sen["sensorType"] == 10]

    rows: list[dict[str, Any]] = []
    for i in range(len(edges) - 1):
        lo, hi = edges[i], edges[i + 1]
        win_lo_s = (lo - t0) / 1000.0
        win_hi_s = (hi - t0) / 1000.0

        def agg_motion(sub: pd.DataFrame, kind: str) -> dict[str, Any]:
            w = sub[(sub["wallClockUtcEpochMs"] >= lo) & (sub["wallClockUtcEpochMs"] < hi)]
            if w.empty:
                return {
                    f"{kind}_rms": math.nan,
                    f"{kind}_peak": math.nan,
                    f"{kind}_jerk_proxy_mean": math.nan,
                    f"{kind}_n": 0,
                }
            mag = sensor_magnitude(w)
            t = w["wallClockUtcEpochMs"].to_numpy(dtype=float) * 1e-3
            rms = float(np.sqrt(np.mean(mag**2)))
            peak = float(np.max(mag))
            jerk_mean = math.nan
            if len(mag) > 2:
                dt = np.diff(t)
                da = np.diff(mag)
                mask = dt > 1e-6
                if np.any(mask):
                    j = np.abs(da[mask] / dt[mask])
                    jerk_mean = float(np.mean(j))
            return {
                f"{kind}_rms": rms,
                f"{kind}_peak": peak,
                f"{kind}_jerk_proxy_mean": jerk_mean,
                f"{kind}_n": int(len(w)),
            }

        # Use linear accel if available for accel RMS/jerk; else raw accelerometer
        accel_source = lin if not lin.empty else acc
        kind = "linear_accel" if not lin.empty else "accel"

        row: dict[str, Any] = {
            "window_index": i,
            "t_start_s_from_session": win_lo_s,
            "t_end_s_from_session": win_hi_s,
            "window_duration_s": window_s,
        }
        row.update(agg_motion(accel_source, kind))
        row.update(agg_motion(gyro, "gyro"))

        if not loc.empty:
            lw = loc[(loc["wallClockUtcEpochMs"] >= lo) & (loc["wallClockUtcEpochMs"] < hi)]
            row["location_samples_in_window"] = len(lw)
            if not lw.empty and "speedMps" in lw.columns:
                row["mean_speed_mps_in_window"] = float(lw["speedMps"].mean())
        rows.append(row)

    df = pd.DataFrame(rows)
    df.insert(0, "analysis_note", ANALYSIS_NOTE)
    return df


def compute_window_features_distance(
    loc: pd.DataFrame,
    sen: pd.DataFrame,
    bin_m: float,
) -> pd.DataFrame:
    """Assign each sensor row to a distance bin by interpolating cumulative GNSS distance."""
    if loc.empty or len(loc) < 2 or sen.empty:
        return pd.DataFrame()

    loc = loc.sort_values("wallClockUtcEpochMs").reset_index(drop=True)
    lat = loc["latitude"].to_numpy(float)
    lon = loc["longitude"].to_numpy(float)
    t_loc = loc["wallClockUtcEpochMs"].to_numpy(float)

    seg = haversine_distance_m(lat[:-1], lon[:-1], lat[1:], lon[1:])
    cum = np.concatenate([[0.0], np.cumsum(seg)])
    t_sen = sen["wallClockUtcEpochMs"].to_numpy(float)
    cum_at_sensor = np.interp(t_sen, t_loc, cum)
    sen = sen.copy()
    sen["_cum_dist_m"] = cum_at_sensor
    sen["_dist_bin"] = (sen["_cum_dist_m"] // bin_m).astype(int)

    acc = sen[sen["sensorType"] == 1]
    lin = sen[sen["sensorType"] == 10]
    accel_source = lin if not lin.empty else acc
    kind = "linear_accel" if not lin.empty else "accel"

    def stats_motion(subdf: pd.DataFrame, prefix: str) -> dict[str, Any]:
        if subdf.empty:
            return {f"{prefix}_rms": math.nan, f"{prefix}_peak": math.nan, f"{prefix}_n": 0}
        mag = sensor_magnitude(subdf)
        return {
            f"{prefix}_rms": float(np.sqrt(np.mean(mag**2))),
            f"{prefix}_peak": float(np.max(mag)),
            f"{prefix}_n": len(subdf),
        }

    rows: list[dict[str, Any]] = []
    for b in sorted(sen["_dist_bin"].unique()):
        d0 = float(int(b) * bin_m)
        row = {
            "distance_bin_m_start": d0,
            "distance_bin_m_end": d0 + bin_m,
            "distance_bin_width_m": bin_m,
        }
        acc_w = accel_source[accel_source["_dist_bin"] == b]
        gyr_w = sen[(sen["sensorType"] == 4) & (sen["_dist_bin"] == b)]
        row.update(stats_motion(acc_w, kind))
        row.update(stats_motion(gyr_w, "gyro"))
        rows.append(row)

    df = pd.DataFrame(rows)
    df.insert(0, "analysis_note", ANALYSIS_NOTE)
    return df


def run(
    inputs: list[Path],
    output: Path,
    window_s: float,
    distance_bin_m: float,
) -> None:
    output = output.resolve()
    output.mkdir(parents=True, exist_ok=True)

    bundles = _find_bundle_roots(inputs)
    if not bundles:
        print("No session bundles found (zips or folders with session.json).", file=sys.stderr)
        sys.exit(1)

    summaries: list[SessionSummary] = []

    for bundle in bundles:
        extract_tmp: Path | None = None
        try:
            if bundle.is_file() and bundle.suffix.lower() == ".zip":
                root, extract_tmp = _extract_zip(bundle)
                display = str(bundle)
            else:
                root = bundle
                display = str(bundle)

            paths = _resolve_session_paths(root)

            if not paths.session_json.is_file():
                print(f"Skip (no session.json): {display}", file=sys.stderr)
                continue

            summary, loc, sen = summarize_session(display, paths)
            summaries.append(summary)

            safe = summary.session_uuid or paths.root.name.replace(" ", "_")
            sess_out = output / safe
            sess_out.mkdir(parents=True, exist_ok=True)

            plot_series(sess_out, safe, loc, sen)

            feat_t = compute_window_features_time(loc, sen, window_s)
            if not feat_t.empty:
                feat_t.to_csv(sess_out / "features_time_windows.csv", index=False)

            feat_d = compute_window_features_distance(loc, sen, distance_bin_m)
            if not feat_d.empty:
                feat_d.to_csv(sess_out / "features_distance_bins.csv", index=False)

            with open(sess_out / "export_source.txt", "w", encoding="utf-8") as f:
                f.write(f"{display}\n{ANALYSIS_NOTE}\n")

        finally:
            if extract_tmp is not None:
                shutil.rmtree(extract_tmp, ignore_errors=True)

    df_sum = pd.DataFrame([s.__dict__ for s in summaries])
    df_sum.to_csv(output / "summary_sessions.csv", index=False)

    readme_snip = output / "README_ANALYSIS_OUTPUT.txt"
    readme_snip.write_text(
        "\n".join(
            [
                "OLGX Roads — offline analysis output",
                "",
                ANALYSIS_NOTE,
                "",
                f"Files: summary_sessions.csv; per-session folders with plots/ and feature CSVs.",
                "",
            ]
        ),
        encoding="utf-8",
    )

    print(f"Wrote summary: {output / 'summary_sessions.csv'}")
    print(f"Per-session outputs under: {output}")


def main() -> None:
    parser = argparse.ArgumentParser(
        description="Analyze OLGX Roads exported session zips/folders (experimental offline tooling)."
    )
    parser.add_argument(
        "inputs",
        nargs="+",
        help="Export zip file(s) and/or folder(s) containing zips or extracted session folders",
    )
    parser.add_argument(
        "-o",
        "--output",
        type=Path,
        default=Path("analysis_output"),
        help="Output directory (default: ./analysis_output)",
    )
    parser.add_argument(
        "--window-s",
        type=float,
        default=1.0,
        help="Fixed time window length in seconds (default: 1.0)",
    )
    parser.add_argument(
        "--distance-bin-m",
        type=float,
        default=10.0,
        help="Distance bin width in metres for optional distance windows (default: 10)",
    )
    args = parser.parse_args()
    inputs = [Path(p) for p in args.inputs]
    run(inputs, args.output, args.window_s, args.distance_bin_m)


if __name__ == "__main__":
    main()

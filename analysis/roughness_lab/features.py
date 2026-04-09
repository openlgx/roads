"""Time-windowed GNSS + IMU features for offline roughness lab."""

from __future__ import annotations

import math
from typing import Any

import numpy as np
import pandas as pd

LAB_NOTE = (
    "experimental_roughness_lab — not IRI; heuristics for offline review only"
)


def motion_linear_or_accel(sen: pd.DataFrame) -> pd.DataFrame | None:
    if sen.empty:
        return None
    lin = sen[sen["sensorType"] == 10]
    if not lin.empty:
        return lin
    acc = sen[sen["sensorType"] == 1]
    if not acc.empty:
        return acc
    return None


def gyro_df(sen: pd.DataFrame) -> pd.DataFrame:
    if sen.empty:
        return pd.DataFrame()
    return sen[sen["sensorType"] == 4]


def bearing_deg(lat1: float, lon1: float, lat2: float, lon2: float) -> float:
    phi1 = math.radians(lat1)
    phi2 = math.radians(lat2)
    dl = math.radians(lon2 - lon1)
    y = math.sin(dl) * math.cos(phi2)
    x = math.cos(phi1) * math.sin(phi2) - math.sin(phi1) * math.cos(phi2) * math.cos(dl)
    brng = math.degrees(math.atan2(y, x))
    return (brng + 360.0) % 360.0


def bearing_rate_series(loc: pd.DataFrame) -> tuple[np.ndarray, np.ndarray]:
    """Times (ms) and heading rate (deg/s) at interior track points."""
    if loc.empty or len(loc) < 3:
        return np.array([]), np.array([])
    need = {"wallClockUtcEpochMs", "latitude", "longitude"}
    if not need.issubset(loc.columns):
        return np.array([]), np.array([])
    lat = loc["latitude"].to_numpy(dtype=float)
    lon = loc["longitude"].to_numpy(dtype=float)
    t = loc["wallClockUtcEpochMs"].to_numpy(dtype=float)
    times: list[float] = []
    rates: list[float] = []
    for i in range(1, len(loc) - 1):
        b0 = bearing_deg(float(lat[i - 1]), float(lon[i - 1]), float(lat[i]), float(lon[i]))
        b1 = bearing_deg(float(lat[i]), float(lon[i]), float(lat[i + 1]), float(lon[i + 1]))
        db = b1 - b0
        if db > 180:
            db -= 360
        if db < -180:
            db += 360
        dt_s = (float(t[i + 1]) - float(t[i - 1])) / 2000.0
        if dt_s > 1e-3:
            rates.append(db / dt_s)
            times.append(float(t[i]))
    return np.array(times, dtype=float), np.array(rates, dtype=float)


def window_stats_motion(motion: pd.DataFrame, t0: float, t1: float) -> dict[str, Any]:
    if motion.empty:
        return {}
    t = motion["wallClockUtcEpochMs"].to_numpy(dtype=float)
    mask = (t >= t0) & (t < t1)
    if not np.any(mask):
        return {"n": 0}
    sub = motion.iloc[np.where(mask)[0]]
    x = sub["x"].to_numpy(dtype=float)
    y = sub["y"].to_numpy(dtype=float)
    z = sub["z"].to_numpy(dtype=float)
    mag = np.sqrt(x * x + y * y + z * z)
    return {
        "rms_mag": float(np.sqrt(np.mean(mag**2))),
        "peak_mag": float(np.max(mag)),
        "energy_x": float(np.mean(x**2)),
        "energy_y": float(np.mean(y**2)),
        "energy_z": float(np.mean(z**2)),
        "n": int(len(mag)),
    }


def window_stats_gyro(gyro: pd.DataFrame, t0: float, t1: float) -> dict[str, Any]:
    if gyro.empty:
        return {"rms_gyro": math.nan, "peak_gyro": math.nan, "n_gyro": 0}
    t = gyro["wallClockUtcEpochMs"].to_numpy(dtype=float)
    mask = (t >= t0) & (t < t1)
    if not np.any(mask):
        return {"rms_gyro": math.nan, "peak_gyro": math.nan, "n_gyro": 0}
    sub = gyro.iloc[np.where(mask)[0]]
    x = sub["x"].to_numpy(dtype=float)
    y = sub["y"].to_numpy(dtype=float)
    z = sub["z"].to_numpy(dtype=float)
    mag = np.sqrt(x * x + y * y + z * z)
    return {
        "rms_gyro": float(np.sqrt(np.mean(mag**2))),
        "peak_gyro": float(np.max(mag)),
        "n_gyro": int(len(mag)),
    }


def heading_rate_in_window(
    t_rate: np.ndarray,
    r_rate: np.ndarray,
    t0: float,
    t1: float,
) -> tuple[float, float, float]:
    if t_rate.size == 0:
        return (math.nan, math.nan, math.nan)
    m = (t_rate >= t0) & (t_rate < t1)
    if not np.any(m):
        return (math.nan, math.nan, math.nan)
    rr = r_rate[m]
    return (float(np.mean(rr)), float(np.std(rr)), float(np.mean(np.abs(rr))))


def spectral_high_fraction(motion: pd.DataFrame, t0: float, t1: float) -> float:
    """Fraction of rFFT energy in upper third of bins (detrended magnitude)."""
    if motion.empty:
        return math.nan
    t = motion["wallClockUtcEpochMs"].to_numpy(dtype=float)
    mask = (t >= t0) & (t < t1)
    x = motion["x"].to_numpy(dtype=float)[mask]
    y = motion["y"].to_numpy(dtype=float)[mask]
    z = motion["z"].to_numpy(dtype=float)[mask]
    if x.size < 16:
        return math.nan
    mag = np.sqrt(x * x + y * y + z * z)
    mag = mag - np.mean(mag)
    spec = np.abs(np.fft.rfft(mag))
    energy = float(np.sum(spec**2))
    if energy <= 1e-12:
        return math.nan
    hi = max(1, int(len(spec) * 2 // 3))
    return float(np.sum(spec[hi:] ** 2) / energy)


def compute_windows(
    session_uuid: str,
    loc: pd.DataFrame,
    sen: pd.DataFrame,
    window_s: float = 1.0,
) -> pd.DataFrame:
    motion = motion_linear_or_accel(sen)
    gyro = gyro_df(sen)
    t_rate, r_rate = bearing_rate_series(loc)

    t_refs: list[float] = []
    if not loc.empty and "wallClockUtcEpochMs" in loc.columns:
        t_refs.extend(
            [
                float(loc["wallClockUtcEpochMs"].min()),
                float(loc["wallClockUtcEpochMs"].max()),
            ]
        )
    if motion is not None and not motion.empty:
        t_refs.extend(
            [
                float(motion["wallClockUtcEpochMs"].min()),
                float(motion["wallClockUtcEpochMs"].max()),
            ]
        )
    if not t_refs:
        return pd.DataFrame()

    t_min, t_max = min(t_refs), max(t_refs)
    step_ms = window_s * 1000.0
    if t_max <= t_min + step_ms * 0.5:
        return pd.DataFrame()

    edges = np.arange(t_min, t_max, step_ms)
    rows: list[dict[str, Any]] = []
    for w0 in edges:
        w1 = w0 + step_ms
        mid_lat = mid_lon = math.nan
        speed_mean = math.nan
        if not loc.empty and "wallClockUtcEpochMs" in loc.columns:
            lt = loc["wallClockUtcEpochMs"].to_numpy(dtype=float)
            lm = (lt >= w0) & (lt < w1)
            if np.any(lm):
                subl = loc.iloc[np.where(lm)[0]]
                mid_lat = float(subl["latitude"].astype(float).mean())
                mid_lon = float(subl["longitude"].astype(float).mean())
                if "speedMps" in subl.columns:
                    speed_mean = float(subl["speedMps"].astype(float).mean())

        mstats = window_stats_motion(motion, w0, w1) if motion is not None else {"n": 0}
        gstats = window_stats_gyro(gyro, w0, w1)
        h_mean, h_std, h_abs_mean = heading_rate_in_window(t_rate, r_rate, w0, w1)

        high_frac = math.nan
        if motion is not None and mstats.get("n", 0) >= 16:
            high_frac = spectral_high_fraction(motion, w0, w1)

        rows.append(
            {
                "session_uuid": session_uuid,
                "window_start_ms": w0,
                "window_end_ms": w1,
                "mid_latitude": mid_lat,
                "mid_longitude": mid_lon,
                "speed_mean_mps": speed_mean,
                "heading_rate_mean_deg_s": h_mean,
                "heading_rate_std_deg_s": h_std,
                "heading_rate_abs_mean_deg_s": h_abs_mean,
                "rms_linear_or_accel_mag": mstats.get("rms_mag", math.nan),
                "peak_linear_or_accel_mag": mstats.get("peak_mag", math.nan),
                "energy_acc_x": mstats.get("energy_x", math.nan),
                "energy_acc_y": mstats.get("energy_y", math.nan),
                "energy_acc_z": mstats.get("energy_z", math.nan),
                "motion_sample_count": mstats.get("n", 0),
                "rms_gyro_mag": gstats["rms_gyro"],
                "peak_gyro_mag": gstats["peak_gyro"],
                "gyro_sample_count": gstats["n_gyro"],
                "spectral_high_frac": high_frac,
                "analysis_note": LAB_NOTE,
            }
        )
    return pd.DataFrame(rows)

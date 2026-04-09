"""Rule-based cornering vs vertical-shock hints (not a classifier)."""

from __future__ import annotations

import math

import numpy as np
import pandas as pd


def add_heuristics(df: pd.DataFrame) -> pd.DataFrame:
    if df.empty:
        return df
    out = df.copy()
    ex = out["energy_acc_x"].to_numpy(dtype=float)
    ey = out["energy_acc_y"].to_numpy(dtype=float)
    ez = np.maximum(out["energy_acc_z"].to_numpy(dtype=float), 1e-9)
    lateral = ex + ey
    ratio_lat = lateral / ez

    hstd = out["heading_rate_std_deg_s"].to_numpy(dtype=float)
    hab = out["heading_rate_abs_mean_deg_s"].to_numpy(dtype=float)
    spd = out["speed_mean_mps"].to_numpy(dtype=float)
    peak = out["peak_linear_or_accel_mag"].to_numpy(dtype=float)
    rms = out["rms_linear_or_accel_mag"].to_numpy(dtype=float)
    pr = peak / np.maximum(rms, 1e-6)

    corner = (
        np.isfinite(hstd)
        & (hstd > 4.0)
        & np.isfinite(ratio_lat)
        & (ratio_lat > 0.85)
        & np.isfinite(spd)
        & (spd > 2.0)
    ) | (np.isfinite(hab) & (hab > 12.0) & np.isfinite(spd) & (spd > 2.0))

    shock = (
        np.isfinite(pr)
        & (pr > 3.5)
        & np.isfinite(peak)
        & (peak > 6.0)
        & (~corner | (pr > 6.0))
    )

    out["heuristic_cornering"] = corner.astype(np.int8)
    out["heuristic_vertical_shock"] = shock.astype(np.int8)

    pred: list[str] = []
    for i in range(len(out)):
        c = bool(corner[i])
        s = bool(shock[i])
        if s and not c:
            pred.append("vertical_impact")
        elif c:
            pred.append("cornering")
        elif np.isfinite(rms[i]) and rms[i] < 2.0 and np.isfinite(spd[i]) and spd[i] > 3.0:
            pred.append("stable_cruise")
        else:
            pred.append("unknown_high_energy")
    out["pred_primary"] = pred
    return out


def add_cornering_shock_scores(df: pd.DataFrame) -> pd.DataFrame:
    """Optional soft scores in [0,1] for debugging / eval."""
    if df.empty:
        return df
    o = df.copy()
    hstd = o["heading_rate_std_deg_s"].to_numpy(dtype=float)
    hab = o["heading_rate_abs_mean_deg_s"].to_numpy(dtype=float)
    ex = o["energy_acc_x"].to_numpy(dtype=float)
    ey = o["energy_acc_y"].to_numpy(dtype=float)
    ez = np.maximum(o["energy_acc_z"].to_numpy(dtype=float), 1e-9)
    ratio_lat = (ex + ey) / ez
    spd = o["speed_mean_mps"].to_numpy(dtype=float)

    corner_score = np.zeros(len(o))
    for i in range(len(o)):
        parts = []
        if np.isfinite(hstd[i]):
            parts.append(min(1.0, max(0.0, hstd[i] / 15.0)))
        if np.isfinite(hab[i]):
            parts.append(min(1.0, max(0.0, hab[i] / 25.0)))
        if np.isfinite(ratio_lat[i]) and ratio_lat[i] > 0:
            parts.append(min(1.0, max(0.0, (ratio_lat[i] - 0.5) / 1.5)))
        if np.isfinite(spd[i]) and spd[i] > 0:
            parts.append(min(1.0, spd[i] / 15.0))
        corner_score[i] = float(np.mean(parts)) if parts else math.nan

    peak = o["peak_linear_or_accel_mag"].to_numpy(dtype=float)
    rms = o["rms_linear_or_accel_mag"].to_numpy(dtype=float)
    pr = peak / np.maximum(rms, 1e-6)
    shock_score = np.clip((pr - 2.0) / 5.0, 0.0, 1.0) * np.clip((peak - 3.0) / 12.0, 0.0, 1.0)
    shock_score = np.where(np.isfinite(shock_score), shock_score, math.nan)

    o["score_cornering"] = corner_score
    o["score_vertical_shock"] = shock_score
    return o

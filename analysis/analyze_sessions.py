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
from matplotlib.colors import to_hex

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

# Speed display on plots (GNSS-reported speed is still stored as m/s in exports).
MPS_TO_KMH = 3.6


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
    x = df["x"].astype(float).to_numpy(dtype=float)
    y = df["y"].astype(float).to_numpy(dtype=float)
    z = df["z"].astype(float).to_numpy(dtype=float)
    return np.sqrt(x * x + y * y + z * z)


def motion_stream_for_roughness_proxy(sen: pd.DataFrame) -> pd.DataFrame | None:
    """Prefer linear acceleration (type 10); else raw accelerometer (1)."""
    if sen.empty:
        return None
    lin = sen[sen["sensorType"] == 10]
    if not lin.empty:
        return lin
    acc = sen[sen["sensorType"] == 1]
    if not acc.empty:
        return acc
    return None


def roughness_proxy_rms_at_locations(
    loc: pd.DataFrame,
    sen: pd.DataFrame,
    half_window_ms: float = 300.0,
) -> np.ndarray:
    """
    Per GNSS fix: RMS vector magnitude of motion samples in [t−w, t+w] (undocumented, not IRI).

    Arbitrary exploratory proxy only — within-session relative ordering for colouring.
    """
    n = len(loc)
    if n == 0:
        return np.array([])

    motion = motion_stream_for_roughness_proxy(sen)
    if motion is None or motion.empty:
        return np.full(n, np.nan)

    t_sens = motion["wallClockUtcEpochMs"].to_numpy(dtype=np.float64)
    mag = sensor_magnitude(motion)
    order = np.argsort(t_sens)
    t_s = t_sens[order]
    mag = mag[order]

    lt = loc["wallClockUtcEpochMs"].to_numpy(dtype=np.float64)
    out = np.empty(n, dtype=float)
    for i, t_center in enumerate(lt):
        lo = int(np.searchsorted(t_s, t_center - half_window_ms, side="left"))
        hi = int(np.searchsorted(t_s, t_center + half_window_ms, side="right"))
        if hi <= lo:
            out[i] = np.nan
        else:
            out[i] = float(np.sqrt(np.mean(mag[lo:hi] ** 2)))
    return out


def normalize_proxy_for_colormap(values: np.ndarray, p_low: float = 5.0, p_high: float = 95.0) -> np.ndarray:
    """Map to [0,1] using robust percentiles (flat line if no spread)."""
    valid = values[np.isfinite(values)]
    if valid.size == 0:
        return np.zeros(len(values), dtype=float)
    lo, hi = np.percentile(valid, [p_low, p_high])
    if hi <= lo + 1e-12:
        return np.zeros(len(values), dtype=float)
    return np.clip((values - lo) / (hi - lo), 0.0, 1.0)


def trip_time_zero_ms(loc: pd.DataFrame, sen: pd.DataFrame) -> float | None:
    """Earliest wall-clock time (ms) across GNSS and sensor rows — same origin as trip panels."""
    t_refs: list[float] = []
    if not loc.empty and "wallClockUtcEpochMs" in loc.columns:
        t_refs.append(float(loc["wallClockUtcEpochMs"].min()))
    if not sen.empty and "wallClockUtcEpochMs" in sen.columns:
        t_refs.append(float(sen["wallClockUtcEpochMs"].min()))
    return min(t_refs) if t_refs else None


def write_interactive_trip_html(
    plot_dir: Path,
    session_label: str,
    loc: pd.DataFrame,
    sen: pd.DataFrame,
) -> None:
    """
    Standalone HTML (Leaflet satellite + Plotly charts). Open in a browser; needs network for map/chart CDNs and tiles.
    """
    t0_ms = trip_time_zero_ms(loc, sen)
    if t0_ms is None:
        return

    location: list[dict[str, Any]] = []
    if not loc.empty and "latitude" in loc.columns and "longitude" in loc.columns:
        raw_r = roughness_proxy_rms_at_locations(loc, sen) if len(loc) > 0 else np.array([])
        norm_r = normalize_proxy_for_colormap(raw_r) if raw_r.size else np.array([])
        cmap = plt.colormaps["RdYlGn_r"]
        for j, (_, row) in enumerate(loc.iterrows()):
            wall = float(row["wallClockUtcEpochMs"])
            t_rel = (wall - t0_ms) / 1000.0
            spd = row.get("speedMps")
            entry: dict[str, Any] = {
                "t": t_rel,
                "lat": float(row["latitude"]),
                "lon": float(row["longitude"]),
                "speedKmh": float(spd) * MPS_TO_KMH if spd is not None and pd.notna(spd) else None,
            }
            if len(norm_r) == len(loc):
                entry["dotColor"] = to_hex(cmap(float(norm_r[j])))
            else:
                entry["dotColor"] = "#2b8aef"
            location.append(entry)

    acc = sen[sen["sensorType"] == 1] if not sen.empty else pd.DataFrame()
    acc_t: list[float] = []
    acc_mag: list[float] = []
    if not acc.empty:
        acc_t = ((acc["wallClockUtcEpochMs"].to_numpy(dtype=float) - t0_ms) / 1000.0).tolist()
        acc_mag = sensor_magnitude(acc).tolist()

    gyro = sen[sen["sensorType"] == 4] if not sen.empty else pd.DataFrame()
    gyr_t: list[float] = []
    gyr_mag: list[float] = []
    if not gyro.empty:
        gyr_t = ((gyro["wallClockUtcEpochMs"].to_numpy(dtype=float) - t0_ms) / 1000.0).tolist()
        gyr_mag = sensor_magnitude(gyro).tolist()

    payload = {
        "sessionLabel": session_label,
        "analysisNote": ANALYSIS_NOTE,
        "location": location,
        "accT": acc_t,
        "accMag": acc_mag,
        "gyrT": gyr_t,
        "gyrMag": gyr_mag,
        "hasMap": len(location) > 0,
    }
    data_json = json.dumps(payload, allow_nan=False)

    html = f"""<!DOCTYPE html>
<html lang="en">
<head>
<meta charset="utf-8"/>
<meta name="viewport" content="width=device-width, initial-scale=1"/>
<title>OLGX trip — {session_label[:16]}…</title>
<link rel="stylesheet" href="https://unpkg.com/leaflet@1.9.4/dist/leaflet.css"
  integrity="sha256-p4NxAoJBhIIN+hmNHrzRCf9tD/miZyoHS5obTRR9BMY=" crossorigin=""/>
<style>
  body {{ font-family: ui-sans-serif, system-ui, sans-serif; margin: 0; padding: 14px;
    background: #121212; color: #eaeaea; }}
  h1 {{ font-size: 1.05rem; font-weight: 600; margin: 0 0 6px 0; }}
  .note {{ font-size: 11px; color: #9aa0a6; max-width: 1100px; line-height: 1.45; margin-bottom: 12px; }}
  #map {{ height: 440px; width: 100%; max-width: 1100px; border-radius: 10px;
    border: 1px solid #333; margin-bottom: 12px; }}
  #plot {{ width: 100%; max-width: 1100px; height: 580px; border-radius: 10px;
    background: #fafafa; }}
  .hint {{ font-size: 11px; color: #7cb342; margin-top: 8px; }}
</style>
</head>
<body>
<h1 id="h1"></h1>
<p class="note" id="note"></p>
<div id="map"></div>
<p class="hint">Tip: move the mouse over the map or along the graphs — the red crosshair and map marker stay in sync (experimental).</p>
<div id="plot"></div>
<script src="https://unpkg.com/leaflet@1.9.4/dist/leaflet.js"
  integrity="sha256-20nQCchB9co0qIjJZRGuk2/Z9VM+kNiyxNV1lvTlZBo=" crossorigin=""></script>
<script src="https://cdn.plot.ly/plotly-2.35.2.min.js"></script>
<script>
const DATA = {data_json};

(function () {{
  document.getElementById('h1').textContent = 'Interactive trip: ' + DATA.sessionLabel;
  document.getElementById('note').textContent = DATA.analysisNote + ' — Requires internet for Esri tiles + Plotly/Leaflet CDNs.';

  let activeT = (DATA.location[0] && DATA.location[0].t) ?? ((DATA.accT[0] !== undefined) ? DATA.accT[0] : (DATA.gyrT[0] || 0));
  let markerHL = null;
  let rafMap = null;
  let plotDiv = null;

  function nearestLocByLatLng(lat, lng) {{
    if (!DATA.location.length) return -1;
    let best = 0, bestD = Infinity;
    for (let i = 0; i < DATA.location.length; i++) {{
      const p = DATA.location[i];
      const d = (p.lat - lat) * (p.lat - lat) + (p.lon - lng) * (p.lon - lng);
      if (d < bestD) {{ bestD = d; best = i; }}
    }}
    return best;
  }}

  function nearestLocByTime(t) {{
    if (!DATA.location.length) return 0;
    let best = 0, bestD = Infinity;
    for (let i = 0; i < DATA.location.length; i++) {{
      const d = Math.abs(DATA.location[i].t - t);
      if (d < bestD) {{ bestD = d; best = i; }}
    }}
    return best;
  }}

  function setCrosshair(t) {{
    if (t == null || !isFinite(t)) return;
    activeT = t;
    if (plotDiv && plotDiv.data) {{
      Plotly.relayout(plotDiv, {{
        shapes: [{{
          type: 'line', x0: t, x1: t, y0: 0, y1: 1, xref: 'x', yref: 'paper',
          line: {{ color: 'rgba(220,38,38,0.9)', width: 2.2 }}
        }}]
      }});
    }}
    if (DATA.hasMap && markerHL && DATA.location.length) {{
      const i = nearestLocByTime(t);
      const p = DATA.location[i];
      markerHL.setLatLng([p.lat, p.lon]);
    }}
  }}

  plotDiv = document.getElementById('plot');

  // --- Plotly (before map so relayout works when scrubbing map) ---
  const traces = [
    {{
      x: DATA.location.map(p => p.t),
      y: DATA.location.map(p => (p.speedKmh != null ? p.speedKmh : null)),
      name: 'Speed (km/h)',
      mode: 'lines',
      line: {{ color: '#1f77b4', width: 1.8 }},
      xaxis: 'x', yaxis: 'y'
    }},
    {{
      x: DATA.accT, y: DATA.accMag,
      name: '|a| accel', mode: 'lines',
      line: {{ color: '#ff7f0e', width: 1.2 }},
      xaxis: 'x', yaxis: 'y2'
    }},
    {{
      x: DATA.gyrT, y: DATA.gyrMag,
      name: '|ω| gyro', mode: 'lines',
      line: {{ color: '#2ca02c', width: 1.2 }},
      xaxis: 'x', yaxis: 'y3'
    }}
  ];

  const layout = {{
    paper_bgcolor: '#fafafa',
    plot_bgcolor: '#fafafa',
    height: 580,
    margin: {{ l: 58, r: 18, t: 28, b: 42 }},
    hovermode: 'x',
    showlegend: true,
    legend: {{ orientation: 'h', y: 1.02, x: 0 }},
    xaxis: {{ title: 'Time from trip start (s)', domain: [0.08, 0.98] }},
    yaxis: {{ title: 'km/h', domain: [0.71, 0.96] }},
    yaxis2: {{ title: '|a| (m/s²)', domain: [0.40, 0.66], anchor: 'x' }},
    yaxis3: {{ title: '|ω| (rad/s)', domain: [0.06, 0.35], anchor: 'x' }},
    shapes: [{{ type: 'line', x0: activeT, x1: activeT, y0: 0, y1: 1, xref: 'x', yref: 'paper',
      line: {{ color: 'rgba(220,38,38,0.9)', width: 2.2 }} }}]
  }};

  Plotly.newPlot(plotDiv, traces, layout, {{ responsive: true, displaylogo: false }});

  plotDiv.on('plotly_hover', (ev) => {{
    if (ev.points && ev.points.length) setCrosshair(ev.points[0].x);
  }});
  plotDiv.on('plotly_click', (ev) => {{
    if (ev.points && ev.points.length) setCrosshair(ev.points[0].x);
  }});

  // --- Map ---
  let map = null;
  if (DATA.hasMap) {{
    const latlngs = DATA.location.map(p => [p.lat, p.lon]);
    const b = L.latLngBounds(latlngs);
    map = L.map('map', {{ maxZoom: 19 }}).fitBounds(b.pad(0.12));
    L.tileLayer(
      'https://server.arcgisonline.com/ArcGIS/rest/services/World_Imagery/MapServer/tile/{{z}}/{{y}}/{{x}}',
      {{ maxZoom: 19, attribution: 'Tiles © Esri' }}
    ).addTo(map);
    L.polyline(latlngs, {{ color: '#ffffff', weight: 3, opacity: 0.85 }}).addTo(map);
    DATA.location.forEach((p, i) => {{
      const c = p.dotColor || '#2b8aef';
      L.circleMarker([p.lat, p.lon], {{
        radius: 3, color: '#111', weight: 0.4, fillColor: c, fillOpacity: 0.85
      }}).addTo(map).on('mouseover', () => setCrosshair(p.t));
    }});
    markerHL = L.circleMarker(latlngs[0], {{
      radius: 9, color: '#c00', weight: 2, fillColor: '#ff6b6b', fillOpacity: 0.95
    }}).addTo(map);
    map.on('mousemove', (e) => {{
      if (rafMap) return;
      rafMap = requestAnimationFrame(() => {{
        rafMap = null;
        const idx = nearestLocByLatLng(e.latlng.lat, e.latlng.lng);
        if (idx >= 0) setCrosshair(DATA.location[idx].t);
      }});
    }});
    map.on('click', (e) => {{
      const idx = nearestLocByLatLng(e.latlng.lat, e.latlng.lng);
      if (idx >= 0) setCrosshair(DATA.location[idx].t);
    }});
  }} else {{
    document.getElementById('map').innerHTML = '<p style="padding:20px;color:#888;">No GNSS points for this session.</p>';
  }}
}})();
</script>
</body>
</html>
"""

    (plot_dir / "interactive_trip.html").write_text(html, encoding="utf-8")


def plot_route_satellite_roughness_proxy(
    plot_dir: Path,
    session_label: str,
    loc: pd.DataFrame,
    sen: pd.DataFrame,
) -> None:
    """
    Satellite basemap + location dots coloured by an arbitrary within-session roughness proxy
    (green ≈ lower proxy, red ≈ higher proxy). Requires network to fetch tiles (first run).
    """
    if (
        loc.empty
        or "latitude" not in loc.columns
        or "longitude" not in loc.columns
        or "wallClockUtcEpochMs" not in loc.columns
    ):
        return

    try:
        import contextily as ctx
        from pyproj import Transformer
    except ImportError:
        print(
            "Skipping satellite map: install contextily and pyproj "
            "(see analysis/requirements.txt).",
            file=sys.stderr,
        )
        return

    raw_proxy = roughness_proxy_rms_at_locations(loc, sen)
    colour01 = normalize_proxy_for_colormap(raw_proxy)

    try:
        transformer = Transformer.from_crs("EPSG:4326", "EPSG:3857", always_xy=True)
        lon = loc["longitude"].to_numpy(dtype=float)
        lat = loc["latitude"].to_numpy(dtype=float)
        x, y = transformer.transform(lon, lat)
    except Exception as e:
        print(f"Skipping satellite map (projection failed): {e}", file=sys.stderr)
        return

    if len(x) < 2:
        return

    pad_x = max((x.max() - x.min()) * 0.12, 80.0)
    pad_y = max((y.max() - y.min()) * 0.12, 80.0)

    fig, ax = plt.subplots(figsize=(10, 10))
    ax.set_xlim(float(x.min() - pad_x), float(x.max() + pad_x))
    ax.set_ylim(float(y.min() - pad_y), float(y.max() + pad_y))
    ax.set_aspect("equal")

    try:
        ctx.add_basemap(
            ax,
            crs="EPSG:3857",
            source=ctx.providers.Esri.WorldImagery,
            attribution_size=5,
            zoom="auto",
            zorder=0,
        )
    except Exception as e:
        print(
            f"Skipping satellite basemap (tile download may need network): {e}",
            file=sys.stderr,
        )
        plt.close(fig)
        return

    sc = ax.scatter(
        x,
        y,
        c=colour01,
        s=18,
        cmap="RdYlGn_r",
        vmin=0.0,
        vmax=1.0,
        alpha=0.92,
        edgecolors="k",
        linewidths=0.15,
        zorder=2,
    )
    cbar = fig.colorbar(sc, ax=ax, shrink=0.55, pad=0.02)
    cbar.set_label(
        "Arbitrary proxy: green ≈ lower RMS motion in ±300 ms window, "
        "red ≈ higher (within this session only; not IRI)",
        fontsize=8,
    )
    ax.set_axis_off()
    ax.set_title(
        f"Route on satellite — {session_label}\n"
        f"({ANALYSIS_NOTE})",
        fontsize=10,
    )
    fig.tight_layout()
    try:
        fig.savefig(plot_dir / "route_satellite_roughness_proxy.png", dpi=144, bbox_inches="tight")
    except Exception as e:
        print(f"Could not save satellite map: {e}", file=sys.stderr)
    finally:
        plt.close(fig)


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


def plot_session_trip_panels_speed_accel_gyro(
    plot_dir: Path,
    session_label: str,
    loc: pd.DataFrame,
    sen: pd.DataFrame,
) -> None:
    """
    One figure per session: three stacked panels sharing the same time axis
    (seconds since the earliest wall-clock time in location or sensor data for this session).
    """
    t0_ms = trip_time_zero_ms(loc, sen)
    if t0_ms is None:
        return

    fig, axes = plt.subplots(3, 1, figsize=(10, 9), sharex=True, layout="constrained")

    ax0 = axes[0]
    if not loc.empty and "speedMps" in loc.columns and "wallClockUtcEpochMs" in loc.columns:
        t_rel = (loc["wallClockUtcEpochMs"].to_numpy(dtype=float) - t0_ms) / 1000.0
        speed_kmh = loc["speedMps"].astype(float).to_numpy() * MPS_TO_KMH
        ax0.plot(t_rel, speed_kmh, color="C0", linewidth=0.95)
        ax0.set_ylabel("Speed (km/h)")
    else:
        ax0.text(0.5, 0.5, "No GNSS speed series", ha="center", va="center", transform=ax0.transAxes)
        ax0.set_ylabel("Speed (km/h)")
    ax0.set_title(
        f"This session only — speed, accelerometer, gyro (aligned time; not comparable across trips)\n"
        f"{session_label}"
    )
    ax0.grid(True, alpha=0.3)

    ax1 = axes[1]
    acc = sen[sen["sensorType"] == 1] if not sen.empty else pd.DataFrame()
    if not acc.empty:
        t_rel = (acc["wallClockUtcEpochMs"].to_numpy(dtype=float) - t0_ms) / 1000.0
        ax1.plot(t_rel, sensor_magnitude(acc), color="C1", linewidth=0.5, alpha=0.88)
        ax1.set_ylabel("|a| (m/s²), accel")
    else:
        ax1.text(0.5, 0.5, "No accelerometer (type 1)", ha="center", va="center", transform=ax1.transAxes)
        ax1.set_ylabel("|a| (m/s²)")
    ax1.grid(True, alpha=0.3)

    ax2 = axes[2]
    gyro = sen[sen["sensorType"] == 4] if not sen.empty else pd.DataFrame()
    if not gyro.empty:
        t_rel = (gyro["wallClockUtcEpochMs"].to_numpy(dtype=float) - t0_ms) / 1000.0
        ax2.plot(t_rel, sensor_magnitude(gyro), color="C2", linewidth=0.5, alpha=0.88)
        ax2.set_ylabel("|ω| (rad/s), gyro")
    else:
        ax2.text(0.5, 0.5, "No gyroscope (type 4)", ha="center", va="center", transform=ax2.transAxes)
        ax2.set_ylabel("|ω| (rad/s)")
    ax2.set_xlabel("Time from earliest sample in this session (s)")
    ax2.grid(True, alpha=0.3)

    fig.text(0.5, 0.01, ANALYSIS_NOTE, ha="center", fontsize=7, color="0.35")
    fig.savefig(plot_dir / "trip_speed_accel_gyro_panels.png", dpi=120)
    plt.close(fig)


def plot_series(
    out_dir: Path,
    session_label: str,
    loc: pd.DataFrame,
    sen: pd.DataFrame,
) -> None:
    """Writes per-session PNGs (standalone series + trip panels + satellite map)."""
    plot_dir = out_dir / "plots"
    plot_dir.mkdir(parents=True, exist_ok=True)

    if not loc.empty and "wallClockUtcEpochMs" in loc.columns and "speedMps" in loc.columns:
        t0 = loc["wallClockUtcEpochMs"].min()
        t_s = ((loc["wallClockUtcEpochMs"] - t0) / 1000.0).to_numpy(dtype=float)
        speed_kmh = loc["speedMps"].astype(float).to_numpy() * MPS_TO_KMH
        fig, ax = plt.subplots(figsize=(9, 4))
        ax.plot(t_s, speed_kmh, color="C0", linewidth=0.8)
        ax.set_xlabel("Time from first fix (s)")
        ax.set_ylabel("Speed (km/h)")
        ax.set_title(
            f"Speed over time — {session_label} (GNSS speed × {MPS_TO_KMH})\n({ANALYSIS_NOTE})"
        )
        ax.grid(True, alpha=0.3)
        fig.tight_layout()
        fig.savefig(plot_dir / "speed_over_time.png", dpi=120)
        plt.close(fig)

    acc = sen[sen["sensorType"] == 1] if not sen.empty else pd.DataFrame()
    if not acc.empty:
        t0 = acc["wallClockUtcEpochMs"].min()
        t_s = ((acc["wallClockUtcEpochMs"] - t0) / 1000.0).to_numpy(dtype=float)
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
        t_s = ((gyro["wallClockUtcEpochMs"] - t0) / 1000.0).to_numpy(dtype=float)
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

    plot_session_trip_panels_speed_accel_gyro(plot_dir, session_label, loc, sen)
    plot_route_satellite_roughness_proxy(plot_dir, session_label, loc, sen)
    write_interactive_trip_html(plot_dir, session_label, loc, sen)


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
    print(
        f"Per-session outputs under: {output} "
        f"(PNG panels + interactive_trip.html in each plots/ folder)"
    )


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

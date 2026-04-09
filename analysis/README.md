# OLGX Roads — offline session analysis (experimental)

This folder contains a **local, offline** Python tool to inspect and compare **exported** session bundles from the Android app (zip or extracted folder). Outputs are **exploratory engineering summaries and plots only**:

- **Not** IRI or any certified roughness index.
- **Not** calibration-validated — do not use for compliance or warranty without further work.
- **Not** a substitute for controlled validation against reference instrumentation.

## What it reads

Each export is expected to include (as produced by the app today):

- `session.json` — session metadata (`id`, `uuid`, timestamps, state, …)
- `location_samples.csv` — GNSS/fused samples (timestamps, speed, lat/lon, …)
- `sensor_samples.csv` — motion samples (`sensorType`, `x`, `y`, `z`, timestamps, …)

Bundles can be passed as **`.zip` files** or as **directories** containing the files above.

## Setup on Windows

1. **Install Python 3.10+** from [python.org](https://www.python.org/downloads/) if you do not already have it. During setup, enable **“Add python.exe to PATH”**.

2. Open **PowerShell** or **Command Prompt** and go to the repository root (adjust the path if yours differs):

   ```powershell
   cd C:\cursor-dev\roads
   ```

3. **Create a virtual environment** (recommended):

   ```powershell
   python -m venv .venv_analysis
   .\.venv_analysis\Scripts\Activate.ps1
   ```

   If PowerShell blocks scripts, run once (as Administrator is not required for CurrentUser scope):

   ```powershell
   Set-ExecutionPolicy -ExecutionPolicy RemoteSigned -Scope CurrentUser
   ```

   For **cmd.exe** instead of PowerShell:

   ```bat
   cd C:\cursor-dev\roads
   python -m venv .venv_analysis
   .venv_analysis\Scripts\activate.bat
   ```

4. **Install dependencies**:

   ```powershell
   pip install -r analysis\requirements.txt
   ```

   The optional **satellite map** plots use `contextily` to download **Esri World Imagery** tiles; the first run for each map extent needs **internet access**. If tiles fail to download, other plots still generate.

## How to run

From the repo root (with the virtual environment activated):

```powershell
python analysis\analyze_sessions.py C:\cursor-dev\roads\exports\olgx_exports -o analysis_output
```

### Arguments

| Argument | Meaning |
|----------|---------|
| `inputs` | One or more **paths**: session `.zip` files and/or a **folder** (if the folder contains `.zip` files, those are used; otherwise subfolders with `session.json` are used). |
| `-o`, `--output` | Output directory (default: `analysis_output` under the current working directory). |
| `--window-s` | Fixed time window length in seconds for per-window features (default: `1.0`). |
| `--distance-bin-m` | Distance bin width in metres along the driven path for optional distance-based features (default: `10`). |

Examples:

```powershell
# Several zips explicitly
python analysis\analyze_sessions.py .\exports\olgx_exports\session_A.zip .\exports\olgx_exports\session_B.zip -o .\run1

# Single directory of exports (prefers zips when present)
python analysis\analyze_sessions.py .\exports\olgx_exports -o .\run2
```

## Expected outputs

Under the output directory (e.g. `analysis_output`):

| Path | Description |
|------|-------------|
| `summary_sessions.csv` | One row per session: ids, duration, counts, speeds, distance, monotonicity flags, max gaps, approximate sample rates, disclaimer column. |
| `README_ANALYSIS_OUTPUT.txt` | Short reminder that all metrics are experimental. |
| `<session_uuid>/plots/speed_over_time.png` | Speed vs time from GNSS CSV (**km/h** on the axis; export is still m/s). |
| `<session_uuid>/plots/trip_speed_accel_gyro_panels.png` | **One session at a time:** three stacked panels (**speed km/h**, **\|accel\|**, **\|gyro\|**) on a **shared time axis** (seconds from the earliest wall-clock sample in that session’s location or sensor data). Useful to relate motion to speed within the same trip — **not** meant to stack different sessions on one axis. |
| `<session_uuid>/plots/interactive_trip.html` | **Interactive view** (open in Chrome/Edge/Firefox): **Leaflet** satellite (Esri) + **Plotly** three-panel chart; **scrub the map or hover the charts** to sync a red crosshair and a highlight marker (offline data embedded; **needs internet** for map tiles and JS libraries from CDNs). |
| `<session_uuid>/plots/accelerometer_magnitude.png` | \\|accel\\| vs time (sensor type 1). |
| `<session_uuid>/plots/gyroscope_magnitude.png` | \\|gyro\\| vs time (sensor type 4). |
| `<session_uuid>/plots/route_satellite_roughness_proxy.png` | **Satellite basemap** (Web Mercator) with **GNSS points as dots** coloured by an **arbitrary within-session proxy** (RMS motion magnitude in a ±300 ms window; **green ≈ lower**, **red ≈ higher**). Not IRI, not calibrated — visualization only. |
| `<session_uuid>/features_time_windows.csv` | Per time window: RMS / peak accel (linear if present else raw accel), RMS / peak gyro, jerk proxy on magnitude, optional mean speed. |
| `<session_uuid>/features_distance_bins.csv` | Same style features by distance bin along the path (requires location + sensor data). |
| `<session_uuid>/export_source.txt` | Original path of the bundle and disclaimer. |

Open CSV files in Excel, LibreOffice, or pandas; open PNG files in any image viewer.

## Suggested next analysis step

Once this tooling runs reliably on your four exports:

1. **Reproducibility** — fix random seeds only if you add stochastic methods later; for now, document phone model, mount, and export `session.json` + `manifest.json` alongside results.
2. **Align streams** — refine time alignment between GNSS and IMU (interpolation, latency model) before interpreting window comparisons across trips.
3. **Controlled validation** — compare features against a known smooth vs rough segment or reference device; quantify repeatability across repeated passes *before* attaching roughness semantics (still not “IRI” until explicitly modeled and validated).

## Roughness vs cornering (confounders + lab)

Engineering note (GNSS + IMU features, trust table, exclusion-layer concept): **[ROUGHNESS_CONFOUNDERS.md](ROUGHNESS_CONFOUNDERS.md)** — aligned with the main README “Initial roughness methodology” phases A–F.

### `roughness_lab/` (experimental)

Time-windowed features, GNSS **heading-rate** proxies, per-axis accel energies, optional spectral high-band fraction, and **rule-based** `pred_primary` (`cornering`, `vertical_impact`, `stable_cruise`, `unknown_high_energy`).

```powershell
python -m analysis.roughness_lab.run .\exports\olgx_exports -o .\roughness_lab_output --window-s 1.0
```

Outputs:

| Path | Description |
|------|-------------|
| `roughness_lab_output/<session_uuid>/window_features.csv` | Per-window features + heuristics + `pred_primary`. |
| `roughness_lab_output/all_windows.csv` | All sessions concatenated. |

**Hand labels + precision/recall:** see [`roughness_lab/LABEL_FORMAT.md`](roughness_lab/LABEL_FORMAT.md) and:

```powershell
python -m analysis.roughness_lab.eval_labels --windows .\roughness_lab_output\all_windows.csv --labels .\my_labels.jsonl
```

### Geo exclusion masks (future council workflow)

JSON Schema and example GeoJSON: [`specs/geo_exclusion_mask.schema.json`](../specs/geo_exclusion_mask.schema.json), [`specs/examples/exclusion_mask.example.geojson`](../specs/examples/exclusion_mask.example.geojson).

Pull requests that keep this scope **offline** and **honest** about limitations are welcome.

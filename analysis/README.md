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
| `<session_uuid>/plots/speed_over_time.png` | Speed vs time from GNSS CSV. |
| `<session_uuid>/plots/accelerometer_magnitude.png` | \\|accel\\| vs time (sensor type 1). |
| `<session_uuid>/plots/gyroscope_magnitude.png` | \\|gyro\\| vs time (sensor type 4). |
| `<session_uuid>/features_time_windows.csv` | Per time window: RMS / peak accel (linear if present else raw accel), RMS / peak gyro, jerk proxy on magnitude, optional mean speed. |
| `<session_uuid>/features_distance_bins.csv` | Same style features by distance bin along the path (requires location + sensor data). |
| `<session_uuid>/export_source.txt` | Original path of the bundle and disclaimer. |

Open CSV files in Excel, LibreOffice, or pandas; open PNG files in any image viewer.

## Suggested next analysis step

Once this tooling runs reliably on your four exports:

1. **Reproducibility** — fix random seeds only if you add stochastic methods later; for now, document phone model, mount, and export `session.json` + `manifest.json` alongside results.
2. **Align streams** — refine time alignment between GNSS and IMU (interpolation, latency model) before interpreting window comparisons across trips.
3. **Controlled validation** — compare features against a known smooth vs rough segment or reference device; quantify repeatability across repeated passes *before* attaching roughness semantics (still not “IRI” until explicitly modeled and validated).

Pull requests that keep this scope **offline** and **honest** about limitations are welcome.

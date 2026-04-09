"""Load OLGX export bundles (zip or directory with session.json)."""

from __future__ import annotations

import json
import shutil
import tempfile
import zipfile
from dataclasses import dataclass
from pathlib import Path
import pandas as pd


@dataclass
class BundlePaths:
    root: Path
    session_json: Path
    location_csv: Path | None
    sensor_csv: Path | None


DISCLAIMER = (
    "experimental_roughness_lab — not IRI; heuristics for offline review only"
)


def find_bundle_roots(inputs: list[Path]) -> list[Path]:
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
            if zips:
                roots.extend(zips)
            else:
                roots.extend(dirs_with_session)
    seen: set[str] = set()
    out: list[Path] = []
    for r in roots:
        key = str(r.resolve())
        if key not in seen:
            seen.add(key)
            out.append(r)
    return out


def resolve_paths(root: Path) -> BundlePaths:
    loc = root / "location_samples.csv"
    sen = root / "sensor_samples.csv"
    return BundlePaths(
        root=root,
        session_json=root / "session.json",
        location_csv=loc if loc.is_file() else None,
        sensor_csv=sen if sen.is_file() else None,
    )


def extract_zip(zip_path: Path) -> tuple[Path, Path]:
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


def load_bundle(
    display: str,
    bundle: Path,
) -> tuple[str, str, pd.DataFrame, pd.DataFrame, Path | None]:
    """
    Returns (display_path, session_uuid, location_df, sensor_df, temp_dir_to_cleanup_or_none).
    """
    cleanup: Path | None = None
    try:
        if bundle.is_file() and bundle.suffix.lower() == ".zip":
            root, cleanup = extract_zip(bundle)
        else:
            root = bundle
        paths = resolve_paths(root)
        uuid = ""
        if paths.session_json.is_file():
            with open(paths.session_json, encoding="utf-8") as f:
                uuid = str(json.load(f).get("uuid", ""))
        loc = pd.read_csv(paths.location_csv) if paths.location_csv else pd.DataFrame()
        sen = pd.read_csv(paths.sensor_csv) if paths.sensor_csv else pd.DataFrame()
        return display, uuid, loc, sen, cleanup
    except Exception:
        if cleanup and cleanup.is_dir():
            shutil.rmtree(cleanup, ignore_errors=True)
        raise



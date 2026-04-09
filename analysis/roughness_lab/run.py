#!/usr/bin/env python3
"""
Run roughness lab on exported session bundles: window features + turn/shock heuristics.

  python -m analysis.roughness_lab.run path/to/export.zip -o roughness_lab_output

See analysis/README.md and analysis/ROUGHNESS_CONFOUNDERS.md.
"""

from __future__ import annotations

import argparse
import shutil
import sys
from pathlib import Path

import pandas as pd

from analysis.roughness_lab.features import compute_windows
from analysis.roughness_lab.heuristics import add_cornering_shock_scores, add_heuristics
from analysis.roughness_lab.io import DISCLAIMER, find_bundle_roots, load_bundle


def main() -> int:
    parser = argparse.ArgumentParser(
        description="OLGX roughness lab: window features and cornering vs shock heuristics (experimental).",
    )
    parser.add_argument(
        "inputs",
        nargs="+",
        type=Path,
        help="Session .zip files and/or folders (zips or subfolders with session.json).",
    )
    parser.add_argument(
        "-o",
        "--output",
        type=Path,
        default=Path("roughness_lab_output"),
        help="Output directory (default: ./roughness_lab_output).",
    )
    parser.add_argument(
        "--window-s",
        type=float,
        default=1.0,
        help="Time window length in seconds (default: 1.0).",
    )
    args = parser.parse_args()

    out_root = args.output.resolve()
    out_root.mkdir(parents=True, exist_ok=True)

    roots = find_bundle_roots(list(args.inputs))
    if not roots:
        print("No export bundles found.", file=sys.stderr)
        return 1

    all_parts: list[pd.DataFrame] = []
    for bundle in roots:
        display = str(bundle)
        cleanup = None
        try:
            _, uuid, loc, sen, cleanup = load_bundle(display, bundle)
            sid = uuid or "unknown_session"
            df = compute_windows(sid, loc, sen, window_s=args.window_s)
            if df.empty:
                print(f"Skip (no overlapping time windows): {display}", file=sys.stderr)
                continue
            df = add_heuristics(df)
            df = add_cornering_shock_scores(df)

            sub = out_root / sid
            sub.mkdir(parents=True, exist_ok=True)
            df.to_csv(sub / "window_features.csv", index=False)
            (sub / "source.txt").write_text(f"{display}\n{DISCLAIMER}\n", encoding="utf-8")
            all_parts.append(df)
        except Exception as e:
            print(f"Error processing {display}: {e}", file=sys.stderr)
            return 1
        finally:
            if cleanup is not None and cleanup.is_dir():
                shutil.rmtree(cleanup, ignore_errors=True)

    if all_parts:
        pd.concat(all_parts, ignore_index=True).to_csv(out_root / "all_windows.csv", index=False)
        (out_root / "README_LAB_OUTPUT.txt").write_text(
            "Experimental roughness_lab outputs — not IRI. "
            "See analysis/ROUGHNESS_CONFOUNDERS.md.\n",
            encoding="utf-8",
        )

    return 0


if __name__ == "__main__":
    raise SystemExit(main())

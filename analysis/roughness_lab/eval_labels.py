#!/usr/bin/env python3
"""
Score roughness_lab predictions against hand labels (JSONL).

  python -m analysis.roughness_lab.eval_labels --windows roughness_lab_output/all_windows.csv --labels my_labels.jsonl

Expects `pred_primary` on window rows (from run.py). Prints per-class precision/recall and counts.
"""

from __future__ import annotations

import argparse
import json
import sys
from collections import defaultdict
from pathlib import Path
from typing import Any

import pandas as pd

CANONICAL_LABELS = frozenset(
    {
        "stable_cruise",
        "braking",
        "cornering",
        "vertical_impact",
        "unknown_high_energy",
        "rail_crossing",
        "designed_rough",
    }
)


def _normalize_label(s: str) -> str:
    x = str(s).strip().lower().replace(" ", "_")
    if x not in CANONICAL_LABELS:
        raise ValueError(f"Unknown label {s!r}; use one of {sorted(CANONICAL_LABELS)}")
    return x


def _iou(a0: float, a1: float, b0: float, b1: float) -> float:
    """Intersection over union on half-open [start, end)."""
    inter = max(0.0, min(a1, b1) - max(a0, b0))
    uni = max(a1, b1) - min(a0, b0)
    if uni <= 0:
        return 0.0
    return inter / uni


def load_labels_jsonl(path: Path) -> list[dict[str, Any]]:
    rows: list[dict[str, Any]] = []
    with open(path, encoding="utf-8") as f:
        for ln, line in enumerate(f, 1):
            line = line.strip()
            if not line or line.startswith("#"):
                continue
            o = json.loads(line)
            rows.append(
                {
                    "session_uuid": str(o["session_uuid"]),
                    "window_start_ms": float(o["window_start_ms"]),
                    "window_end_ms": float(o["window_end_ms"]),
                    "label": _normalize_label(o["label"]),
                    "line": ln,
                }
            )
    return rows


def match_predictions(
    windows: pd.DataFrame,
    labels: list[dict[str, Any]],
    iou_min: float,
) -> pd.DataFrame:
    """Returns rows: label, pred, session_uuid, iou, matched_line."""
    out: list[dict[str, Any]] = []
    need = {"session_uuid", "window_start_ms", "window_end_ms", "pred_primary"}
    if windows.empty or not need.issubset(windows.columns):
        return pd.DataFrame()
    for lab in labels:
        su = lab["session_uuid"]
        l0, l1 = lab["window_start_ms"], lab["window_end_ms"]
        best: tuple[float, str, Any] | None = None
        sub = windows[windows["session_uuid"] == su]
        for _, row in sub.iterrows():
            w0 = float(row["window_start_ms"])
            w1 = float(row["window_end_ms"])
            iou = _iou(w0, w1, l0, l1)
            if iou >= iou_min and (best is None or iou > best[0]):
                best = (iou, str(row["pred_primary"]), row)
        if best is not None:
            raw_pred = str(best[1]).strip().lower().replace(" ", "_")
            try:
                pred_norm = _normalize_label(raw_pred)
            except ValueError:
                pred_norm = raw_pred
            out.append(
                {
                    "session_uuid": su,
                    "label": lab["label"],
                    "pred": pred_norm,
                    "iou": best[0],
                    "label_line": lab["line"],
                }
            )
        else:
            out.append(
                {
                    "session_uuid": su,
                    "label": lab["label"],
                    "pred": "__unmatched__",
                    "iou": 0.0,
                    "label_line": lab["line"],
                }
            )
    return pd.DataFrame(out)


def pr_per_class(matched: pd.DataFrame) -> None:
    if matched.empty:
        print("No matches.")
        return
    labels = matched["label"].unique()
    preds = matched["pred"].unique()
    classes = sorted(set(labels) | set(preds) - {"__unmatched__"})

    print("Matched rows:", len(matched))
    print()

    for c in classes:
        tp = int(((matched["label"] == c) & (matched["pred"] == c)).sum())
        fp = int(((matched["label"] != c) & (matched["pred"] == c)).sum())
        fn = int(((matched["label"] == c) & (matched["pred"] != c)).sum())
        prec = tp / (tp + fp) if (tp + fp) else float("nan")
        rec = tp / (tp + fn) if (tp + fn) else float("nan")
        print(f"class={c!r}  precision={prec:.3f}  recall={rec:.3f}  tp={tp} fp={fp} fn={fn}")

    print()
    print("Confusion (rows = label, cols = pred):")
    confusion: dict[tuple[str, str], int] = defaultdict(int)
    for _, r in matched.iterrows():
        confusion[(str(r["label"]), str(r["pred"]))] += 1
    col_keys = sorted(set(k[1] for k in confusion))
    row_keys = sorted(set(k[0] for k in confusion))
    header = " " * 14 + "".join(f"{ck:>16}" for ck in col_keys)
    print(header)
    for rk in row_keys:
        row = f"{rk:>14}" + "".join(f"{confusion.get((rk, ck), 0):>16}" for ck in col_keys)
        print(row)


def main() -> int:
    ap = argparse.ArgumentParser(description="Eval roughness_lab preds vs JSONL labels.")
    ap.add_argument("--windows", type=Path, required=True, help="all_windows.csv from roughness_lab run.")
    ap.add_argument("--labels", type=Path, required=True, help="JSONL label file (see LABEL_FORMAT.md).")
    ap.add_argument(
        "--iou-min",
        type=float,
        default=0.5,
        help="Min IoU to match a lab window to a row (default: 0.5).",
    )
    args = ap.parse_args()

    if not args.windows.is_file():
        print(f"Missing --windows file: {args.windows}", file=sys.stderr)
        return 1
    if not args.labels.is_file():
        print(f"Missing --labels file: {args.labels}", file=sys.stderr)
        return 1

    windows = pd.read_csv(args.windows)
    labels = load_labels_jsonl(args.labels)
    if not labels:
        print("No label rows loaded.", file=sys.stderr)
        return 1

    matched = match_predictions(windows, labels, args.iou_min)
    out_path = args.labels.with_suffix(".eval_pairs.csv")
    matched.to_csv(out_path, index=False)
    print(f"Wrote pairings: {out_path}")
    pr_per_class(matched)
    return 0


if __name__ == "__main__":
    raise SystemExit(main())

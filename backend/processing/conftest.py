"""Pytest path setup for processing package."""

from __future__ import annotations

import sys
from pathlib import Path

_root = Path(__file__).resolve().parents[2]
_proc = Path(__file__).resolve().parents[1]
if str(_root) not in sys.path:
    sys.path.insert(0, str(_root))
if str(_proc) not in sys.path:
    sys.path.insert(0, str(_proc))

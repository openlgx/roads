"""Pytest: ensure publish package root is importable."""

from __future__ import annotations

import sys
from pathlib import Path

_pub = Path(__file__).resolve().parents[1]
if str(_pub) not in sys.path:
    sys.path.insert(0, str(_pub))

"""Publish fail-closed: no LGA => no storage uploads."""

from __future__ import annotations

import sys
from types import ModuleType
from unittest.mock import MagicMock, patch


def test_no_boundary_no_storage_upload():
    """When geometry is missing, insert FAILED run only — never call Storage."""
    if "psycopg" not in sys.modules:
        sys.modules["psycopg"] = ModuleType("psycopg")

    import publish_council_layers as pcl
    from publish_council_layers import publish_council

    _ = pcl  # use package import side effects

    cur = MagicMock()
    cur.fetchone.return_value = None
    conn = MagicMock()

    with patch.object(pcl, "storage_upload") as up:
        publish_council(cur, conn, "00000000-0000-4000-8000-000000000001", "slug", "0.1.0")
        up.assert_not_called()

    executed = [str(c.args[0]) for c in cur.execute.call_args_list]
    assert any("FAILED" in s for s in executed)
    assert not any("published_layer_artifacts" in s for s in executed)

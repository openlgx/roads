"""Manifest JSON contract for council pilot (fields must stay stable for GIS operators)."""

from __future__ import annotations

from pathlib import Path


def test_publish_sources_define_manifest_freshness_fields():
    path = Path(__file__).resolve().parents[1] / "publish_council_layers.py"
    text = path.read_text(encoding="utf-8")
    for key in (
        '"manifestVersion"',
        '"councilSlug"',
        '"publishedAt"',
        '"publishRunId"',
        '"layerArtifacts"',
        '"consensusEmitted"',
        '"disclaimer"',
        '"refreshCadenceNote"',
    ):
        assert key in text, f"missing manifest field {key} in publish_council_layers.py"

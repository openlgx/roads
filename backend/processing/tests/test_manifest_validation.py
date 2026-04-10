"""Manifest validation (no DB / psycopg import chain)."""

from __future__ import annotations

from libs.manifest_validate import validate_manifest_session_uuid


def test_validate_manifest_uuid_match():
    validate_manifest_session_uuid(
        {"sessionUuid": "550e8400-e29b-41d4-a716-446655440000"},
        "550e8400-e29b-41d4-a716-446655440000",
    )


def test_validate_manifest_mismatch_raises():
    try:
        validate_manifest_session_uuid(
            {"sessionUuid": "550e8400-e29b-41d4-a716-446655440000"},
            "6ba7b810-9dad-11d1-80b4-00c04fd430c8",
        )
        raise AssertionError("expected ValueError")
    except ValueError as e:
        assert "match" in str(e).lower()

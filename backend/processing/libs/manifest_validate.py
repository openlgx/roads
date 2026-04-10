"""Manifest checks without heavy dependencies."""

from __future__ import annotations

import uuid as std_uuid


def validate_manifest_session_uuid(manifest: dict, expected_client_uuid: str) -> None:
    su = manifest.get("sessionUuid")
    if not su:
        raise ValueError("manifest missing sessionUuid")
    try:
        a = std_uuid.UUID(str(su))
        b = std_uuid.UUID(str(expected_client_uuid))
    except ValueError as e:
        raise ValueError("invalid session uuid in manifest") from e
    if a != b:
        raise ValueError("manifest sessionUuid does not match recording session")

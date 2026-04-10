"""Minimal Supabase Storage REST client (service role)."""

import os
from typing import Any, Optional

import httpx


def storage_upload(
    *,
    bucket: str,
    object_key: str,
    data: bytes,
    content_type: str,
    upsert: bool = True,
) -> None:
    url = os.environ["SUPABASE_PROJECT_URL"].rstrip("/")
    key = os.environ["SUPABASE_SECRET_KEY"]
    path = f"/storage/v1/object/{bucket}/{object_key}"
    headers = {
        "Authorization": f"Bearer {key}",
        "apikey": key,
        "Content-Type": content_type,
    }
    if upsert:
        headers["x-upsert"] = "true"
    with httpx.Client(timeout=120.0) as client:
        r = client.post(f"{url}{path}", content=data, headers=headers)
        r.raise_for_status()


def storage_download_bytes(*, bucket: str, object_key: str) -> bytes:
    url = os.environ["SUPABASE_PROJECT_URL"].rstrip("/")
    key = os.environ["SUPABASE_SECRET_KEY"]
    path = f"/storage/v1/object/{bucket}/{object_key}"
    headers = {"Authorization": f"Bearer {key}", "apikey": key}
    with httpx.Client(timeout=120.0) as client:
        r = client.get(f"{url}{path}", headers=headers)
        r.raise_for_status()
        return r.content


def storage_signed_upload_url(*, bucket: str, object_key: str) -> Optional[str]:
    """Reserved for tooling; Edge Functions createSignedUploadUrl preferred."""
    _ = (bucket, object_key)
    return None

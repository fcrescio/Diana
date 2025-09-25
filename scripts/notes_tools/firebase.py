"""Firebase helpers shared by note-management scripts."""

from __future__ import annotations

from pathlib import Path
from typing import Any, Mapping

import firebase_admin
from firebase_admin import App, credentials, firestore


def _normalize_service_account(path: str | Path) -> Path:
    candidate = Path(path).expanduser()
    if not candidate.is_file():
        raise FileNotFoundError(f"Service account key not found: {candidate}")
    return candidate


def initialize_app(
    service_account: str | Path,
    project_id: str | None = None,
    *,
    app_name: str | None = None,
) -> App:
    """Initialise a Firebase Admin app if one has not already been created."""

    name = app_name or firebase_admin._DEFAULT_APP_NAME  # type: ignore[attr-defined]
    try:
        return firebase_admin.get_app(name)
    except ValueError:
        key_path = _normalize_service_account(service_account)
        cred = credentials.Certificate(key_path)
        options: Mapping[str, Any] | None = None
        if project_id:
            options = {"projectId": project_id}
        return firebase_admin.initialize_app(cred, options, name=name)


def initialize_firestore(
    service_account: str | Path,
    project_id: str | None = None,
    *,
    app_name: str | None = None,
) -> firestore.Client:
    """Create (or reuse) a Firestore client bound to the configured app."""

    app = initialize_app(service_account, project_id, app_name=app_name)
    return firestore.client(app=app)


__all__ = ["initialize_app", "initialize_firestore"]

#!/usr/bin/env python3
"""List Diana sessions and their structured notes stored in Firestore.

Usage examples::

    python scripts/list_sessions.py service_account.json
    python scripts/list_sessions.py service_account.json --project-id my-project --json

The command connects using a Firebase service account, fetches the requested
sessions, and prints their settings, structured notes, and cached thought
document if present. Use ``--json`` for machine-readable output and
``--page``/``--page-size`` to page through large datasets.
"""

from __future__ import annotations

import argparse
import json
import sys
from dataclasses import asdict, is_dataclass
from typing import Any, Iterable

from google.api_core.exceptions import GoogleAPIError
from google.api_core.retry import Retry
from firebase_admin import exceptions as firebase_exceptions
from google.cloud import firestore

from notes_tools.firebase import initialize_firestore
from notes_tools.notes import (
    Appointment,
    FreeNote,
    NotesTagCatalog,
    Session,
    SessionSettings,
    TagMappingContext,
    Thought,
    TodoItem,
    parse_remote_note,
    parse_remote_session,
)


NOTE_COLLECTION = "notes"
THOUGHT_DOCUMENT_ID = "__thought_document__"
DEFAULT_PAGE_SIZE = 25
DEFAULT_LOCALE = "en"


def parse_args(argv: Iterable[str] | None = None) -> argparse.Namespace:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument(
        "service_account",
        help="Path to the Firebase service account JSON key",
    )
    parser.add_argument(
        "--project-id",
        dest="project_id",
        help="Override the Firebase project ID if the key omits it",
    )
    parser.add_argument(
        "--session-id",
        dest="session_id",
        help="Fetch a single session by ID",
    )
    parser.add_argument(
        "--json",
        action="store_true",
        help="Emit machine-readable JSON instead of formatted text",
    )
    parser.add_argument(
        "--page-size",
        type=int,
        default=DEFAULT_PAGE_SIZE,
        help="Number of sessions to fetch per page",
    )
    parser.add_argument(
        "--page",
        type=int,
        default=1,
        help="Page number (1-indexed) when browsing all sessions",
    )
    parser.add_argument(
        "--limit",
        type=int,
        default=None,
        help="Optional upper bound on the number of sessions to return",
    )
    parser.add_argument(
        "--notes-limit",
        type=int,
        default=None,
        help="Limit how many structured notes to load per session",
    )
    return parser.parse_args(argv)


def _firestore_client(args: argparse.Namespace) -> firestore.Client:
    return initialize_firestore(args.service_account, args.project_id)


def _session_query(client: firestore.Client, args: argparse.Namespace):
    base = client.collection("sessions").order_by("name")
    page_size = args.page_size if args.page_size and args.page_size > 0 else DEFAULT_PAGE_SIZE
    if args.limit and args.limit < page_size:
        page_size = args.limit
    offset = max(args.page - 1, 0) * page_size
    query = base.limit(page_size)
    if offset:
        query = query.offset(offset)
    if args.limit:
        query = query.limit(min(page_size, args.limit))
    return query


def _fetch_sessions(
    client: firestore.Client, args: argparse.Namespace
) -> list[tuple[Session, dict[str, Any]]]:
    if args.session_id:
        snapshot = client.collection("sessions").document(args.session_id).get()
        if not snapshot.exists:
            return []
        data = snapshot.to_dict() or {}
        session = parse_remote_session(snapshot)
        if session is None:
            return []
        return [(session, data)]

    query = _session_query(client, args)
    sessions: list[tuple[Session, dict[str, Any]]] = []
    for document in query.stream(retry=Retry(deadline=30.0)):
        session = parse_remote_session(document)
        if session is None:
            continue
        sessions.append((session, document.to_dict() or {}))
        if args.limit and len(sessions) >= args.limit:
            break
    return sessions


def _tag_context(session_data: dict[str, Any]) -> TagMappingContext:
    settings = session_data.get("settings")
    catalog_data = None
    locale = DEFAULT_LOCALE
    if isinstance(settings, dict):
        catalog_data = settings.get("tagCatalog")
        locale = settings.get("locale", DEFAULT_LOCALE)
    catalog = NotesTagCatalog.from_map(catalog_data if isinstance(catalog_data, dict) else None)
    return TagMappingContext(catalog=catalog, locale=locale or DEFAULT_LOCALE)


def _note_to_dict(note: Any) -> dict[str, Any]:
    if is_dataclass(note):
        payload = asdict(note)
    elif isinstance(note, dict):
        payload = dict(note)
    else:
        payload = {"value": repr(note)}
    if isinstance(note, TodoItem):
        payload["type"] = "todo"
    elif isinstance(note, Thought):
        payload["type"] = "memo"
    elif isinstance(note, Appointment):
        payload["type"] = "event"
    elif isinstance(note, FreeNote):
        payload["type"] = "free"
    return payload


def _fetch_notes_for_session(
    client: firestore.Client,
    session_id: str,
    session_data: dict[str, Any],
    notes_limit: int | None,
) -> tuple[list[dict[str, Any]], dict[str, Any] | None]:
    collection = (
        client.collection("sessions")
        .document(session_id)
        .collection(NOTE_COLLECTION)
    )
    tag_context = _tag_context(session_data)
    notes: list[dict[str, Any]] = []
    thought_document: dict[str, Any] | None = None
    loaded_notes = 0
    for document in collection.stream(retry=Retry(deadline=30.0)):
        if document.id == THOUGHT_DOCUMENT_ID:
            thought_document = document.to_dict() or {}
            continue
        parsed = parse_remote_note(document, tag_context)
        if parsed is None:
            continue
        notes.append(_note_to_dict(parsed))
        loaded_notes += 1
        if notes_limit and loaded_notes >= notes_limit:
            break
    notes.sort(key=lambda item: item.get("created_at", item.get("createdAt", 0)), reverse=True)
    return notes, thought_document


def _serialize_session(
    session: Session,
    session_data: dict[str, Any],
    notes: list[dict[str, Any]],
    thought_document: dict[str, Any] | None,
) -> dict[str, Any]:
    categorized: dict[str, list[dict[str, Any]]] = {
        "todo": [],
        "memo": [],
        "event": [],
        "free": [],
    }
    for note in notes:
        note_type = note.get("type")
        if note_type in categorized:
            categorized[note_type].append(note)
        else:
            categorized.setdefault("other", []).append(note)
    return {
        "id": session.id,
        "name": session.name,
        "settings": session.settings.to_map(),
        "raw_settings": session_data.get("settings", {}),
        "notes": categorized,
        "thought_document": thought_document,
    }


def _format_session_text(payload: dict[str, Any]) -> str:
    settings: SessionSettings | dict[str, Any] = payload.get("settings", {})
    settings_parts = []
    if isinstance(settings, SessionSettings):
        settings_map = settings.to_map()
    else:
        settings_map = dict(settings)
    for key, value in sorted(settings_map.items()):
        settings_parts.append(f"{key}={value}")
    lines = [f"Session: {payload['name']} ({payload['id']})"]
    if settings_parts:
        lines.append(f"  Settings: {', '.join(settings_parts)}")
    notes = payload.get("notes", {})
    for note_type in ("todo", "memo", "event", "free"):
        entries = notes.get(note_type, []) if isinstance(notes, dict) else []
        lines.append(f"  {note_type.title()} notes: {len(entries)}")
        for note in entries:
            text = note.get("text") or note.get("value") or ""
            status = note.get("status")
            created_at = note.get("created_at") or note.get("createdAt")
            details = []
            if status:
                details.append(f"status={status}")
            if created_at:
                details.append(f"created_at={created_at}")
            tag_ids = note.get("tag_ids") or note.get("tagIds")
            if tag_ids:
                details.append(f"tags={tag_ids}")
            detail_str = f" ({', '.join(details)})" if details else ""
            lines.append(f"    - {text}{detail_str}")
    thought_document = payload.get("thought_document")
    if thought_document:
        md_preview = (thought_document.get("markdown") or thought_document.get("markdown_body") or "")
        snippet = md_preview.strip().splitlines()[:1]
        lines.append("  Thought document: present")
        if snippet:
            lines.append(f"    Preview: {snippet[0][:80]}")
    else:
        lines.append("  Thought document: none")
    return "\n".join(lines)


def _print_text(sessions: list[dict[str, Any]]) -> None:
    if not sessions:
        print("No sessions found.")
        return
    for index, payload in enumerate(sessions, start=1):
        if index > 1:
            print("".rjust(0))
        print(_format_session_text(payload))


def main(argv: Iterable[str] | None = None) -> int:
    args = parse_args(argv)
    try:
        client = _firestore_client(args)
        sessions = _fetch_sessions(client, args)
        reports: list[dict[str, Any]] = []
        for session, session_data in sessions:
            notes, thought_document = _fetch_notes_for_session(
                client, session.id, session_data, args.notes_limit
            )
            reports.append(_serialize_session(session, session_data, notes, thought_document))
    except FileNotFoundError as exc:
        print(f"Error: {exc}", file=sys.stderr)
        return 1
    except (firebase_exceptions.FirebaseError, GoogleAPIError) as exc:
        print(f"Firebase error: {exc}", file=sys.stderr)
        return 2
    except Exception as exc:  # pragma: no cover - unexpected failures
        print(f"Unexpected error: {exc}", file=sys.stderr)
        return 3

    if args.json:
        json.dump(reports, sys.stdout, indent=2, sort_keys=True)
        sys.stdout.write("\n")
    else:
        _print_text(reports)
    return 0


if __name__ == "__main__":
    sys.exit(main())

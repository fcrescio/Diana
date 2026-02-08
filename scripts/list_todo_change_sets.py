#!/usr/bin/env python3
"""List todo change sets and their actions for a session in Firestore."""

from __future__ import annotations

import argparse
import json
from dataclasses import asdict, is_dataclass
from typing import Any, Iterable

from google.api_core.exceptions import GoogleAPIError
from google.api_core.retry import Retry
from google.cloud import firestore

from notes_tools.firebase import initialize_firestore
from notes_tools.notes import TodoChangeSet, parse_remote_todo_change_set


DEFAULT_PAGE_SIZE = 50


def parse_args(argv: Iterable[str] | None = None) -> argparse.Namespace:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument(
        "service_account",
        help="Path to the Firebase service account JSON key",
    )
    parser.add_argument(
        "session_id",
        help="Session ID to query for todo change sets",
    )
    parser.add_argument(
        "--project-id",
        dest="project_id",
        help="Override the Firebase project ID if the key omits it",
    )
    parser.add_argument(
        "--json",
        action="store_true",
        help="Emit machine-readable JSON instead of formatted text",
    )
    parser.add_argument(
        "--limit",
        type=int,
        default=None,
        help="Limit the number of change sets to return",
    )
    parser.add_argument(
        "--page-size",
        type=int,
        default=DEFAULT_PAGE_SIZE,
        help="Page size when streaming change sets",
    )
    return parser.parse_args(argv)


def _firestore_client(args: argparse.Namespace) -> firestore.Client:
    return initialize_firestore(args.service_account, args.project_id)


def _change_sets_collection(client: firestore.Client, session_id: str):
    return (
        client.collection("sessions")
        .document(session_id)
        .collection("todo_change_sets")
    )


def _fetch_change_sets(
    client: firestore.Client,
    session_id: str,
    limit: int | None,
) -> list[TodoChangeSet]:
    collection = _change_sets_collection(client, session_id)
    change_sets: list[TodoChangeSet] = []
    try:
        iterator = collection.stream(retry=Retry(deadline=30.0))
    except GoogleAPIError as exc:  # pragma: no cover - network failure
        raise RuntimeError(f"Failed to stream todo change sets: {exc}") from exc
    for document in iterator:
        parsed = parse_remote_todo_change_set(document)
        if parsed is None:
            continue
        change_sets.append(parsed)
        if limit and len(change_sets) >= limit:
            break
    change_sets.sort(key=lambda item: item.timestamp, reverse=True)
    return change_sets


def _serialize(value: Any) -> Any:
    if is_dataclass(value):
        return asdict(value)
    if isinstance(value, list):
        return [_serialize(item) for item in value]
    return value


def _render_change_set(change_set: TodoChangeSet) -> str:
    lines: list[str] = []
    header = (
        f"{change_set.change_set_id} | {change_set.timestamp} | "
        f"memo={change_set.memo_id} | type={change_set.change_type}"
    )
    lines.append(header)
    for action in change_set.actions:
        lines.append(f"  - {action.op}")
        before_text = action.before.text if action.before else ""
        before_status = action.before.status if action.before else ""
        before_tags = ", ".join(action.before.tag_ids) if action.before else ""
        after_text = action.after.text if action.after else ""
        after_status = action.after.status if action.after else ""
        after_tags = ", ".join(action.after.tag_ids) if action.after else ""
        lines.append(f"    before: {before_text} [{before_status}] ({before_tags})")
        lines.append(f"    after:  {after_text} [{after_status}] ({after_tags})")
    return "\n".join(lines)


def main(argv: Iterable[str] | None = None) -> int:
    args = parse_args(argv)
    client = _firestore_client(args)
    change_sets = _fetch_change_sets(client, args.session_id, args.limit)
    if args.json:
        payload = _serialize(change_sets)
        print(json.dumps(payload, indent=2, sort_keys=True))
        return 0
    if not change_sets:
        print("No todo change sets found.")
        return 0
    for change_set in change_sets:
        print(_render_change_set(change_set))
    return 0


if __name__ == "__main__":
    raise SystemExit(main())

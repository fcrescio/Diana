#!/usr/bin/env python3
"""Run the memo-processing pipeline for a session via the OpenRouter API."""

from __future__ import annotations

import argparse
import json
import os
import sys
import time
from dataclasses import asdict, is_dataclass
from pathlib import Path
from typing import Any, Iterable, Iterator, Mapping, MutableMapping

from google.api_core.exceptions import GoogleAPIError
from google.api_core.retry import Retry
from google.cloud import firestore

from notes_tools import (
    Appointment,
    MemoProcessor,
    MemoSummary,
    NotesTagCatalog,
    Session,
    TagMappingContext,
    Thought,
    ThoughtDocument,
    ThoughtOutline,
    ThoughtOutlineSection,
    TodoItem,
    initialize_firestore,
    load_resource,
    parse_remote_note,
    parse_remote_session,
    structured_note_to_map,
    summary_to_notes,
)
from notes_tools.memo_processing import LlmLogger


THOUGHT_DOCUMENT_ID = "__thought_document__"
THOUGHT_DOCUMENT_TYPE = "thought_document"
DEFAULT_LOCALE = "en"


class ScriptError(RuntimeError):
    """Raised when the memo processing script encounters a fatal issue."""


def _memo_text(args: argparse.Namespace) -> str:
    if args.memo_file:
        path = Path(args.memo_file).expanduser()
        if not path.is_file():
            raise ScriptError(f"Memo file not found: {path}")
        text = path.read_text(encoding="utf-8")
    elif args.memo is not None:
        text = args.memo
    else:
        data = sys.stdin.read()
        text = data
    memo = text.strip()
    if not memo:
        raise ScriptError("Memo text is empty")
    return memo


def _load_firestore(args: argparse.Namespace) -> firestore.Client:
    try:
        return initialize_firestore(args.service_account, args.project_id)
    except FileNotFoundError as exc:  # service account missing
        raise ScriptError(str(exc)) from exc


def _load_session(
    client: firestore.Client, session_id: str
) -> tuple[Session, MemoSummary, NotesTagCatalog | None, str]:
    document = client.collection("sessions").document(session_id).get()
    if not document.exists:
        raise ScriptError(f"Session '{session_id}' not found")
    session = parse_remote_session(document)
    if session is None:
        raise ScriptError(f"Session '{session_id}' is missing required fields")
    session_data = document.to_dict() or {}
    settings = session_data.get("settings") if isinstance(session_data.get("settings"), Mapping) else {}
    locale = str(settings.get("locale", DEFAULT_LOCALE)).strip() or DEFAULT_LOCALE
    catalog_data = settings.get("tagCatalog") if isinstance(settings.get("tagCatalog"), Mapping) else None
    tag_catalog = NotesTagCatalog.from_map(catalog_data)
    tag_context = TagMappingContext(catalog=tag_catalog, locale=locale)

    notes_ref = document.reference.collection("notes")
    notes: list[Any] = []
    thought_document: ThoughtDocument | None = None
    retry = Retry(deadline=30.0)
    try:
        iterator: Iterator[firestore.DocumentSnapshot] = notes_ref.stream(retry=retry)
    except GoogleAPIError as exc:  # pragma: no cover - network failure
        raise ScriptError(f"Failed to stream notes: {exc}") from exc

    for snapshot in iterator:
        if snapshot.id == THOUGHT_DOCUMENT_ID:
            thought_document = _parse_thought_document(snapshot.to_dict() or {})
            continue
        parsed = parse_remote_note(snapshot, tag_context)
        if parsed is not None:
            notes.append(parsed)

    todo_items = [note for note in notes if isinstance(note, TodoItem)]
    appointment_items = [note for note in notes if isinstance(note, Appointment)]
    thought_items = [note for note in notes if isinstance(note, Thought)]

    summary = MemoSummary(
        todo="\n".join(item.text for item in todo_items if item.text),
        appointments="\n".join(item.text for item in appointment_items if item.text),
        thoughts=(
            thought_document.markdown_body
            if thought_document is not None
            else "\n".join(item.text for item in thought_items if item.text)
        ),
        todo_items=list(todo_items),
        appointment_items=list(appointment_items),
        thought_items=list(thought_items),
        thought_document=thought_document,
    )
    return session, summary, tag_catalog, locale


def _parse_thought_document(data: Mapping[str, Any]) -> ThoughtDocument | None:
    doc_type = str(data.get("type", "")).strip()
    if doc_type != THOUGHT_DOCUMENT_TYPE:
        return None
    markdown = data.get("markdown")
    if not isinstance(markdown, str) or not markdown.strip():
        return None
    outline_sections = _parse_outline_sections(data.get("outline"))
    return ThoughtDocument(markdown_body=markdown, outline=ThoughtOutline(outline_sections))


def _parse_outline_sections(value: Any) -> list[ThoughtOutlineSection]:
    if not isinstance(value, Iterable):
        return []
    sections: list[ThoughtOutlineSection] = []
    for entry in value:
        if not isinstance(entry, Mapping):
            continue
        title = str(entry.get("title", "")).strip()
        if not title:
            continue
        level = int(entry.get("level", 1) or 1)
        anchor = str(entry.get("anchor", "")).strip() or _default_anchor(title)
        children = _parse_outline_sections(entry.get("children"))
        sections.append(ThoughtOutlineSection(title=title, level=level, anchor=anchor, children=children))
    return sections


def _default_anchor(title: str) -> str:
    slug = "".join(ch for ch in title.lower() if ch.isalnum() or ch.isspace()).strip().replace(" ", "-")
    return slug or f"section-{abs(hash(title)) & 0xFFFF:x}"


def _call_openrouter(
    *,
    url: str,
    payload: Mapping[str, Any],
    api_key: str,
    timeout: float = 60.0,
) -> Mapping[str, Any]:
    import urllib.error
    import urllib.request

    data = json.dumps(payload).encode("utf-8")
    request = urllib.request.Request(
        url,
        data=data,
        headers={
            "Content-Type": "application/json",
            "Authorization": f"Bearer {api_key}",
        },
        method="POST",
    )

    last_error: Exception | None = None
    for attempt in range(3):
        try:
            with urllib.request.urlopen(request, timeout=timeout) as response:
                body = response.read()
                text = body.decode("utf-8", errors="replace")
                return json.loads(text)
        except (urllib.error.HTTPError, urllib.error.URLError, TimeoutError, json.JSONDecodeError) as exc:
            last_error = exc
            if attempt == 2:
                break
            time.sleep(2 ** attempt)
    if last_error is None:
        raise ScriptError("OpenRouter request failed")
    raise ScriptError(f"OpenRouter request failed: {last_error}")


def _extract_structured_json(response: Mapping[str, Any]) -> Mapping[str, Any]:
    choices = response.get("choices")
    if not isinstance(choices, list) or not choices:
        raise ScriptError("LLM response missing choices")
    message = choices[0].get("message")
    content: Any
    if isinstance(message, Mapping):
        content = message.get("content")
    else:
        content = None
    if isinstance(content, list):
        text = "".join(
            part.get("text", "")
            for part in content
            if isinstance(part, Mapping) and isinstance(part.get("text"), str)
        )
    elif isinstance(content, str):
        text = content
    else:
        raise ScriptError("LLM response missing content")
    start = text.find("{")
    end = text.rfind("}")
    if start == -1 or end == -1 or end <= start:
        raise ScriptError("LLM content did not include JSON object")
    try:
        return json.loads(text[start : end + 1])
    except json.JSONDecodeError as exc:
        raise ScriptError(f"Invalid JSON from model: {exc}") from exc


def _thought_document_to_map(document: ThoughtDocument) -> Mapping[str, Any]:
    return {
        "type": THOUGHT_DOCUMENT_TYPE,
        "markdown": document.markdown_body,
        "outline": [_outline_section_to_map(section) for section in document.outline.sections],
    }


def _outline_section_to_map(section: ThoughtOutlineSection) -> MutableMapping[str, Any]:
    return {
        "title": section.title,
        "level": section.level,
        "anchor": section.anchor,
        "children": [_outline_section_to_map(child) for child in section.children],
    }


def _write_summary(
    client: firestore.Client,
    session_id: str,
    summary: MemoSummary,
    *,
    save_todos: bool,
    save_appointments: bool,
    save_thoughts: bool,
) -> MemoSummary:
    collection = client.collection("sessions").document(session_id).collection("notes")
    saved_todos: list[TodoItem] = []
    for note in summary_to_notes(summary, save_todos, save_appointments, save_thoughts):
        payload = structured_note_to_map(note)
        if isinstance(note, TodoItem):
            note_id = note.note_id.strip()
            if note_id:
                collection.document(note_id).set(payload)
                saved_todos.append(note)
            else:
                doc_ref, _ = collection.add(payload)
                saved_todos.append(
                    TodoItem(
                        text=note.text,
                        status=note.status,
                        tag_ids=list(note.tag_ids),
                        tag_labels=list(note.tag_labels),
                        due_date=note.due_date,
                        event_date=note.event_date,
                        note_id=doc_ref.id,
                        created_at=note.created_at,
                    )
                )
        else:
            collection.add(payload)

    if save_thoughts and summary.thought_document is not None:
        collection.document(THOUGHT_DOCUMENT_ID).set(
            _thought_document_to_map(summary.thought_document)
        )

    if save_todos:
        return MemoSummary(
            todo=summary.todo,
            appointments=summary.appointments,
            thoughts=summary.thoughts,
            todo_items=saved_todos,
            appointment_items=summary.appointment_items,
            thought_items=summary.thought_items,
            thought_document=summary.thought_document,
        )
    return summary


def _summary_to_serializable(summary: MemoSummary) -> Mapping[str, Any]:
    if is_dataclass(summary):
        return asdict(summary)
    return {
        "todo": summary.todo,
        "appointments": summary.appointments,
        "thoughts": summary.thoughts,
        "todo_items": summary.todo_items,
        "appointment_items": summary.appointment_items,
        "thought_items": summary.thought_items,
        "thought_document": summary.thought_document,
    }


def parse_args(argv: Iterable[str] | None = None) -> argparse.Namespace:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("service_account", help="Path to the Firebase service account JSON key")
    parser.add_argument("session_id", help="Target session identifier")
    parser.add_argument(
        "--project-id",
        dest="project_id",
        help="Override the Firebase project ID if the key omits it",
    )
    memo_group = parser.add_mutually_exclusive_group()
    memo_group.add_argument("--memo", help="Memo text to process")
    memo_group.add_argument("--memo-file", help="Path to a file containing memo text")
    parser.add_argument(
        "--todos",
        dest="process_todos",
        action=argparse.BooleanOptionalAction,
        default=None,
        help="Process todo items (default: session setting)",
    )
    parser.add_argument(
        "--appointments",
        dest="process_appointments",
        action=argparse.BooleanOptionalAction,
        default=None,
        help="Process appointments (default: session setting)",
    )
    parser.add_argument(
        "--thoughts",
        dest="process_thoughts",
        action=argparse.BooleanOptionalAction,
        default=None,
        help="Process thought document updates (default: session setting)",
    )
    parser.add_argument(
        "--model",
        dest="model",
        help="Override the model configured for the session",
    )
    parser.add_argument(
        "--api-key",
        dest="api_key",
        help="Explicit OpenRouter API key (defaults to OPENROUTER_API_KEY)",
    )
    update_group = parser.add_mutually_exclusive_group()
    update_group.add_argument(
        "--update",
        dest="update",
        action="store_true",
        help="Persist the updated summary back to Firestore",
    )
    update_group.add_argument(
        "--dry-run",
        dest="dry_run",
        action="store_true",
        help="Force dry-run mode without saving changes",
    )
    parser.add_argument(
        "--show-logs",
        action="store_true",
        help="Print the captured LLM request/response logs",
    )
    return parser.parse_args(argv)


def main(argv: Iterable[str] | None = None) -> int:
    args = parse_args(argv)
    try:
        memo_text = _memo_text(args)
        api_key = args.api_key or os.environ.get("OPENROUTER_API_KEY", "").strip()
        if not api_key:
            raise ScriptError("OpenRouter API key is required (set --api-key or OPENROUTER_API_KEY)")
        client = _load_firestore(args)
        session, summary, tag_catalog, locale = _load_session(client, args.session_id)

        process_todos = args.process_todos if args.process_todos is not None else session.settings.process_todos
        process_appointments = (
            args.process_appointments if args.process_appointments is not None else session.settings.process_appointments
        )
        process_thoughts = (
            args.process_thoughts if args.process_thoughts is not None else session.settings.process_thoughts
        )

        logger = LlmLogger()
        processor = MemoProcessor(
            api_key=api_key,
            locale=locale,
            tag_catalog=tag_catalog,
            logger=logger,
        )
        model_override = args.model or session.settings.model
        if model_override:
            processor.model = model_override
        processor.initialize(summary)

        base_url = load_resource("llm/base_url.txt").strip()

        requests = processor.prepare_requests(
            memo_text,
            process_todos=process_todos,
            process_appointments=process_appointments,
            process_thoughts=process_thoughts,
        )

        for aspect, payload in requests.items():
            response = _call_openrouter(url=base_url, payload=payload, api_key=api_key)
            structured = _extract_structured_json(response)
            processor.ingest_response(aspect, json.dumps(structured))

        updated_summary = processor.summary()

        should_update = args.update and not args.dry_run
        if should_update:
            updated_summary = _write_summary(
                client,
                args.session_id,
                updated_summary,
                save_todos=process_todos,
                save_appointments=process_appointments,
                save_thoughts=process_thoughts,
            )

        serializable = _summary_to_serializable(updated_summary)
        print(json.dumps(serializable, indent=2, ensure_ascii=False))

        if args.show_logs:
            entries = getattr(logger, "entries", None)
            if callable(entries):
                print("\n=== LLM Logs ===")
                for entry in entries():
                    print(entry)

        if should_update:
            print("\nSummary saved to Firestore.")
        else:
            print("\nDry run complete – no changes written.")
        return 0
    except ScriptError as exc:
        print(f"error: {exc}", file=sys.stderr)
        return 1


if __name__ == "__main__":
    raise SystemExit(main())

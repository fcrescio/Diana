"""Utilities for working with remote notes and memo summaries."""

try:
    from .firebase import initialize_firestore
except ModuleNotFoundError as exc:
    def initialize_firestore(*args, **kwargs):
        raise ModuleNotFoundError("firebase_admin is required for Firestore access") from exc

from .notes import (
    Appointment,
    LocalizedLabel,
    MemoSummary,
    NoteCollection,
    NotesTagCatalog,
    NotesTagDefinition,
    Session,
    SessionSettings,
    StructuredNote,
    Thought,
    ThoughtDocument,
    ThoughtOutline,
    ThoughtOutlineSection,
    TodoAction,
    TodoChangeSet,
    TodoItem,
    parse_remote_note,
    parse_remote_todo_change_set,
    parse_remote_session,
    resolve_tag_data,
    summary_to_notes,
)
from .memo_processing import MemoProcessor, Prompts, load_resource

__all__ = [
    "initialize_firestore",
    "Appointment",
    "LocalizedLabel",
    "MemoSummary",
    "NoteCollection",
    "NotesTagCatalog",
    "NotesTagDefinition",
    "Session",
    "SessionSettings",
    "StructuredNote",
    "Thought",
    "ThoughtDocument",
    "ThoughtOutline",
    "ThoughtOutlineSection",
    "TodoAction",
    "TodoChangeSet",
    "TodoItem",
    "parse_remote_note",
    "parse_remote_todo_change_set",
    "parse_remote_session",
    "resolve_tag_data",
    "summary_to_notes",
    "MemoProcessor",
    "Prompts",
    "load_resource",
]

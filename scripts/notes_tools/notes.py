"""Dataclasses and helpers for working with sessions and structured notes."""

from __future__ import annotations

from dataclasses import dataclass, field
from typing import Any, Iterable, Mapping, Sequence

DocumentData = Mapping[str, Any]


def _as_string_sequence(value: Any) -> list[str]:
    if isinstance(value, Sequence) and not isinstance(value, (str, bytes, bytearray)):
        return [str(item) for item in value]
    return []


def _coerce_int(value: Any, *, default: int = 0) -> int:
    """Best-effort conversion of Firestore timestamp fields to integers."""

    if isinstance(value, bool):
        return int(value)
    if isinstance(value, int):
        return value
    if isinstance(value, float):
        return int(value)
    if isinstance(value, str):
        trimmed = value.strip()
        if not trimmed:
            return default
        try:
            return int(trimmed)
        except ValueError:
            try:
                return int(float(trimmed))
            except ValueError:
                return default
    return default


@dataclass(slots=True)
class SessionSettings:
    process_todos: bool = True
    process_appointments: bool = True
    process_thoughts: bool = True
    model: str = ""

    @classmethod
    def from_remote(cls, data: Mapping[str, Any] | None) -> "SessionSettings":
        if data is None:
            return cls()

        def parse_bool(value: Any, default: bool) -> bool:
            if isinstance(value, bool):
                return value
            if isinstance(value, (int, float)):
                return bool(int(value))
            if isinstance(value, str):
                lowered = value.strip().lower()
                if lowered in {"true", "1", "yes"}:
                    return True
                if lowered in {"false", "0", "no"}:
                    return False
            return default

        return cls(
            process_todos=parse_bool(
                data.get("processTodos", data.get("saveTodos")), True
            ),
            process_appointments=parse_bool(
                data.get("processAppointments", data.get("saveAppointments")), True
            ),
            process_thoughts=parse_bool(
                data.get("processThoughts", data.get("saveThoughts")), True
            ),
            model=str(data.get("model", "")).strip(),
        )

    def to_map(self) -> dict[str, Any]:
        return {
            "processTodos": self.process_todos,
            "processAppointments": self.process_appointments,
            "processThoughts": self.process_thoughts,
            "model": self.model,
        }


@dataclass(slots=True)
class Session:
    id: str
    name: str
    settings: SessionSettings

    def to_map(self) -> dict[str, Any]:
        return {"name": self.name, "settings": self.settings.to_map()}


def _document_payload(document: Any) -> tuple[str, DocumentData]:
    if hasattr(document, "to_dict"):
        data = document.to_dict() or {}
    elif isinstance(document, Mapping):
        data = dict(document)
    else:
        raise TypeError("Unsupported document type")

    if hasattr(document, "id"):
        doc_id = getattr(document, "id")
    else:
        doc_id = data.get("id")
    if doc_id is None or str(doc_id).strip() == "":
        raise ValueError("Document is missing an identifier")
    return str(doc_id), data


def _as_mapping(value: Any) -> Mapping[str, Any] | None:
    if isinstance(value, Mapping):
        return value
    return None


def parse_remote_session(document: Any) -> Session | None:
    doc_id, data = _document_payload(document)
    name = str(data.get("name", "")).strip()
    if not name:
        return None
    settings_data = data.get("settings") if isinstance(data.get("settings"), Mapping) else None
    settings = SessionSettings.from_remote(settings_data)
    return Session(doc_id, name, settings)


@dataclass(slots=True)
class LocalizedLabel:
    locale_tag: str | None
    value: str

    @property
    def normalized_tag(self) -> str | None:
        if self.locale_tag is None:
            return None
        normalized = self.locale_tag.replace("_", "-").strip().lower()
        return normalized or None

    @classmethod
    def from_mapping(cls, locale_tag: str | None, value: str) -> "LocalizedLabel":
        return cls(locale_tag if locale_tag else None, value)


@dataclass(slots=True)
class NotesTagDefinition:
    id: str
    labels: list[LocalizedLabel]
    color: str | None = None

    def label_for_locale(self, locale: str) -> str | None:
        if not self.labels:
            return None
        normalized_locale = locale.replace("_", "-").lower()
        by_tag: dict[str, str] = {}
        default_label: str | None = None
        for label in self.labels:
            tag = label.normalized_tag
            if tag is None:
                default_label = default_label or label.value
            elif tag not in by_tag:
                by_tag[tag] = label.value
        if normalized_locale in by_tag:
            return by_tag[normalized_locale]
        language = normalized_locale.split("-")[0]
        return by_tag.get(language) or default_label or self.labels[0].value

    @classmethod
    def from_map(cls, data: Mapping[str, Any]) -> "NotesTagDefinition":
        raw_labels = data.get("labels")
        labels: list[LocalizedLabel] = []
        if isinstance(raw_labels, Mapping):
            for key, value in raw_labels.items():
                if not isinstance(value, str):
                    continue
                locale_tag = None if key == "default" else key
                labels.append(LocalizedLabel.from_mapping(locale_tag, value))
        return cls(str(data.get("id", "")).strip(), labels, data.get("color"))


@dataclass(slots=True)
class NotesTagCatalog:
    tags: list[NotesTagDefinition] = field(default_factory=list)

    @classmethod
    def from_map(cls, data: Mapping[str, Any] | None) -> "NotesTagCatalog":
        if not data:
            return cls([])
        raw_tags = data.get("tags")
        tags: list[NotesTagDefinition] = []
        if isinstance(raw_tags, Sequence):
            for entry in raw_tags:
                if isinstance(entry, Mapping):
                    tag_id = str(entry.get("id", "")).strip()
                    if not tag_id:
                        continue
                    labels = []
                    raw_labels = entry.get("labels")
                    if isinstance(raw_labels, Mapping):
                        for key, value in raw_labels.items():
                            if not isinstance(value, str):
                                continue
                            locale_tag = None if key == "default" else key
                            labels.append(LocalizedLabel.from_mapping(locale_tag, value))
                    tags.append(
                        NotesTagDefinition(
                            id=tag_id,
                            labels=labels or [LocalizedLabel(None, tag_id)],
                            color=(entry.get("color") if isinstance(entry.get("color"), str) else None),
                        )
                    )
        return cls(tags)


@dataclass(slots=True)
class ThoughtOutlineSection:
    title: str
    level: int
    anchor: str
    children: list["ThoughtOutlineSection"] = field(default_factory=list)


@dataclass(slots=True)
class ThoughtOutline:
    sections: list[ThoughtOutlineSection]

    @classmethod
    def empty(cls) -> "ThoughtOutline":
        return cls([])


@dataclass(slots=True)
class ThoughtDocument:
    markdown_body: str
    outline: ThoughtOutline = field(default_factory=ThoughtOutline.empty)


@dataclass(slots=True)
class StructuredNote:
    created_at: int = 0


@dataclass(slots=True)
class TodoItem(StructuredNote):
    text: str = ""
    status: str = ""
    tag_ids: list[str] = field(default_factory=list)
    tag_labels: list[str] = field(default_factory=list)
    due_date: str = ""
    event_date: str = ""
    note_id: str = ""


@dataclass(slots=True)
class TodoAction:
    op: str
    before: TodoItem | None
    after: TodoItem | None


@dataclass(slots=True)
class TodoChangeSet:
    change_set_id: str
    session_id: str
    memo_id: str
    timestamp: int
    model: str
    prompt_version: str
    actions: list[TodoAction]
    change_type: str = "apply"


@dataclass(slots=True)
class Thought(StructuredNote):
    text: str = ""
    tag_ids: list[str] = field(default_factory=list)
    tag_labels: list[str] = field(default_factory=list)
    section_anchor: str | None = None
    section_title: str | None = None


@dataclass(slots=True)
class Appointment(StructuredNote):
    text: str = ""
    datetime: str = ""
    location: str = ""


@dataclass(slots=True)
class FreeNote(StructuredNote):
    text: str = ""
    tag_ids: list[str] = field(default_factory=list)
    tag_labels: list[str] = field(default_factory=list)


StructuredNoteType = TodoItem | Thought | Appointment | FreeNote


@dataclass(slots=True)
class NoteCollection:
    notes: list[StructuredNoteType]


@dataclass(slots=True)
class MemoSummary:
    todo: str
    appointments: str
    thoughts: str
    todo_items: list[TodoItem]
    appointment_items: list[Appointment]
    thought_items: list[Thought]
    thought_document: ThoughtDocument | None = None


def _sanitize_strings(values: Iterable[str]) -> list[str]:
    seen: list[str] = []
    for value in values:
        if not isinstance(value, str):
            continue
        trimmed = value.strip()
        if trimmed and trimmed not in seen:
            seen.append(trimmed)
    return seen


@dataclass(slots=True)
class TagMigrationResult:
    tag_ids: list[str]
    unresolved_labels: list[str]


@dataclass(slots=True)
class TagMappingContext:
    catalog: NotesTagCatalog | None
    locale: str
    _canonical_ids: set[str] = field(init=False, default_factory=set)
    _id_lookup: dict[str, str] = field(init=False, default_factory=dict)
    _label_lookup: dict[str, str] = field(init=False, default_factory=dict)

    def __post_init__(self) -> None:
        ids: list[str] = []
        id_lookup: dict[str, str] = {}
        label_lookup: dict[str, str] = {}
        if self.catalog:
            for definition in self.catalog.tags:
                tag_id = definition.id.strip()
                if not tag_id:
                    continue
                if tag_id not in ids:
                    ids.append(tag_id)
                id_lookup.setdefault(tag_id.lower(), tag_id)
                preferred = definition.label_for_locale(self.locale)
                if preferred:
                    label_lookup.setdefault(preferred.strip().lower(), tag_id)
                for label in definition.labels:
                    normalized = (label.value or "").strip().lower()
                    if normalized:
                        label_lookup.setdefault(normalized, tag_id)
        self._canonical_ids = set(ids)
        self._id_lookup = id_lookup
        self._label_lookup = label_lookup

    def map_legacy(self, legacy: Sequence[str]) -> TagMigrationResult:
        resolved: list[str] = []
        unresolved: list[str] = []
        for raw in legacy:
            candidate = self._resolve(raw)
            if candidate:
                if candidate not in resolved:
                    resolved.append(candidate)
            else:
                trimmed = raw.strip()
                if trimmed:
                    unresolved.append(trimmed)
        return TagMigrationResult(resolved, unresolved)

    def _resolve(self, value: str) -> str | None:
        trimmed = value.strip()
        if not trimmed:
            return None
        if trimmed in self._canonical_ids:
            return trimmed
        lower = trimmed.lower()
        return self._id_lookup.get(lower) or self._label_lookup.get(lower)


def resolve_tag_data(
    explicit_ids: Sequence[str],
    explicit_labels: Sequence[str],
    legacy_tags: Sequence[str],
    tag_context: TagMappingContext,
) -> tuple[list[str], list[str]]:
    ids = _sanitize_strings(explicit_ids)
    labels = _sanitize_strings(explicit_labels)
    legacy = _sanitize_strings(legacy_tags)
    if not ids and legacy:
        migrated = tag_context.map_legacy(legacy)
        ids = migrated.tag_ids
        labels.extend(label for label in migrated.unresolved_labels if label not in labels)
    elif legacy:
        migrated = tag_context.map_legacy(legacy)
        for tag_id in migrated.tag_ids:
            if tag_id not in ids:
                ids.append(tag_id)
        labels.extend(label for label in migrated.unresolved_labels if label not in labels)
    return ids, labels


def parse_remote_note(document: Any, tag_context: TagMappingContext) -> StructuredNoteType | None:
    doc_id, data = _document_payload(document)
    note_type = str(data.get("type", "")).strip().lower()
    text = data.get("text")
    if not isinstance(text, str) or not text.strip():
        return None
    created_at = _coerce_int(data.get("createdAt", 0))
    tag_ids, tag_labels = resolve_tag_data(
        explicit_ids=_as_string_sequence(data.get("tagIds")),
        explicit_labels=_as_string_sequence(data.get("tagLabels")),
        legacy_tags=_as_string_sequence(data.get("tags")),
        tag_context=tag_context,
    )

    if note_type == "todo":
        status = str(data.get("status", "")).strip()
        due_date = str(data.get("dueDate", "")).strip()
        event_date = str(data.get("eventDate", "")).strip()
        return TodoItem(
            text=text.strip(),
            status=status,
            tag_ids=tag_ids,
            tag_labels=tag_labels,
            due_date=due_date,
            event_date=event_date,
            note_id=doc_id,
            created_at=created_at,
        )
    if note_type == "memo":
        section_anchor = str(data.get("sectionAnchor", "")).strip() or None
        section_title = str(data.get("sectionTitle", "")).strip() or None
        return Thought(
            text=text.strip(),
            tag_ids=tag_ids,
            tag_labels=tag_labels,
            section_anchor=section_anchor,
            section_title=section_title,
            created_at=created_at,
        )
    if note_type == "event":
        datetime = str(data.get("datetime", "")).strip()
        location = str(data.get("location", "")).strip()
        return Appointment(
            text=text.strip(),
            datetime=datetime,
            location=location,
            created_at=created_at,
        )
    if note_type == "free":
        return FreeNote(
            text=text.strip(),
            tag_ids=tag_ids,
            tag_labels=tag_labels,
            created_at=created_at,
        )
    return None


def _parse_todo_item(data: Mapping[str, Any] | None) -> TodoItem | None:
    if not data:
        return None
    text = str(data.get("text", "")).strip()
    if not text:
        return None
    status = str(data.get("status", "")).strip()
    due_date = str(data.get("dueDate", "")).strip()
    event_date = str(data.get("eventDate", "")).strip()
    tag_ids = _as_string_sequence(data.get("tagIds"))
    tag_labels = _as_string_sequence(data.get("tagLabels"))
    note_id = str(data.get("id", "")).strip()
    return TodoItem(
        text=text,
        status=status,
        tag_ids=tag_ids,
        tag_labels=tag_labels,
        due_date=due_date,
        event_date=event_date,
        note_id=note_id,
    )


def parse_remote_todo_change_set(document: Any) -> TodoChangeSet | None:
    change_set_id, data = _document_payload(document)
    session_id = str(data.get("sessionId", "")).strip()
    memo_id = str(data.get("memoId", "")).strip()
    timestamp = _coerce_int(data.get("timestamp", 0))
    model = str(data.get("model", "")).strip()
    prompt_version = str(data.get("promptVersion", "")).strip()
    change_type = str(data.get("type", "")).strip() or "apply"
    raw_actions = data.get("actions")
    actions: list[TodoAction] = []
    if isinstance(raw_actions, Sequence):
        for entry in raw_actions:
            action_data = _as_mapping(entry)
            if not action_data:
                continue
            op = str(action_data.get("op", "")).strip()
            if not op:
                continue
            before = _parse_todo_item(_as_mapping(action_data.get("before")))
            after = _parse_todo_item(_as_mapping(action_data.get("after")))
            actions.append(TodoAction(op=op, before=before, after=after))
    if not actions:
        return None
    return TodoChangeSet(
        change_set_id=change_set_id,
        session_id=session_id,
        memo_id=memo_id,
        timestamp=timestamp,
        model=model,
        prompt_version=prompt_version,
        actions=actions,
        change_type=change_type,
    )


def structured_note_to_map(note: StructuredNoteType) -> dict[str, Any]:
    if isinstance(note, TodoItem):
        payload: dict[str, Any] = {
            "type": "todo",
            "text": note.text,
            "status": note.status,
            "tagIds": note.tag_ids,
            "tagLabels": note.tag_labels,
            "dueDate": note.due_date,
            "eventDate": note.event_date,
            "createdAt": note.created_at,
        }
        if note.note_id:
            payload["id"] = note.note_id
        return payload
    if isinstance(note, Thought):
        payload = {
            "type": "memo",
            "text": note.text,
            "tagIds": note.tag_ids,
            "tagLabels": note.tag_labels,
            "sectionAnchor": note.section_anchor,
            "sectionTitle": note.section_title,
            "createdAt": note.created_at,
        }
        return payload
    if isinstance(note, Appointment):
        return {
            "type": "event",
            "text": note.text,
            "datetime": note.datetime,
            "location": note.location,
            "createdAt": note.created_at,
        }
    if isinstance(note, FreeNote):
        return {
            "type": "free",
            "text": note.text,
            "tagIds": note.tag_ids,
            "tagLabels": note.tag_labels,
            "createdAt": note.created_at,
        }
    raise TypeError(f"Unsupported note type: {type(note)!r}")


def summary_to_notes(
    summary: MemoSummary,
    save_todos: bool = True,
    save_appointments: bool = True,
    save_thoughts: bool = True,
) -> list[StructuredNoteType]:
    notes: list[StructuredNoteType] = []
    if save_todos:
        for item in summary.todo_items:
            notes.append(
                TodoItem(
                    text=item.text,
                    status=item.status,
                    tag_ids=list(item.tag_ids),
                    tag_labels=list(item.tag_labels),
                    due_date=item.due_date,
                    event_date=item.event_date,
                    note_id=item.note_id,
                    created_at=item.created_at,
                )
            )
    if save_appointments:
        for item in summary.appointment_items:
            notes.append(
                Appointment(
                    text=item.text,
                    datetime=item.datetime,
                    location=item.location,
                    created_at=item.created_at,
                )
            )
    if save_thoughts:
        for item in summary.thought_items:
            notes.append(
                Thought(
                    text=item.text,
                    tag_ids=list(item.tag_ids),
                    tag_labels=list(item.tag_labels),
                    section_anchor=item.section_anchor,
                    section_title=item.section_title,
                    created_at=item.created_at,
                )
            )
    return notes


__all__ = [
    "Appointment",
    "FreeNote",
    "LocalizedLabel",
    "MemoSummary",
    "NoteCollection",
    "NotesTagCatalog",
    "NotesTagDefinition",
    "Session",
    "SessionSettings",
    "StructuredNote",
    "TagMappingContext",
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
    "structured_note_to_map",
    "summary_to_notes",
]

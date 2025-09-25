"""Python port of the Android memo processing pipeline."""

from __future__ import annotations

import json
from dataclasses import dataclass, field
from datetime import date
from pathlib import Path, PurePosixPath
from typing import Any, Iterable, Mapping, Sequence

from .notes import (
    Appointment,
    MemoSummary,
    NotesTagCatalog,
    NotesTagDefinition,
    TagMappingContext,
    Thought,
    ThoughtDocument,
    ThoughtOutline,
    ThoughtOutlineSection,
    TodoItem,
)

RESOURCE_ROOT = Path(__file__).resolve().parents[2] / "app" / "src" / "main" / "resources" / "llm"


def load_resource(path: str, root: Path | None = None) -> str:
    """Load a text asset from the LLM resources directory."""

    trimmed = path.strip()
    if not trimmed:
        raise ValueError("Empty resource path")
    normalized = trimmed.lstrip("/")
    if normalized.startswith("llm/"):
        normalized = normalized[4:]
    candidate = PurePosixPath(normalized)
    if any(part == ".." for part in candidate.parts):
        raise ValueError(f"Invalid resource path: {path}")
    base = root or RESOURCE_ROOT
    target = base.joinpath(*candidate.parts)
    return target.read_text(encoding="utf-8")


@dataclass(slots=True)
class Prompts:
    todo: str
    appointments: str
    thoughts: str
    system_template: str
    user_template: str

    @classmethod
    def for_locale(cls, locale: str, root: Path | None = None) -> "Prompts":
        language = locale.split("-")[0].lower()
        if language not in {"en", "it", "fr"}:
            language = "en"

        def _load(name: str) -> str:
            return load_resource(f"llm/prompts/{language}/{name}.txt", root).strip()

        return cls(
            todo=_load("todo"),
            appointments=_load("appointments"),
            thoughts=_load("thoughts"),
            system_template=_load("system"),
            user_template=_load("user"),
        )


@dataclass(slots=True)
class LlmLogger:
    max_entries: int = 100
    _entries: list[str] = field(default_factory=list)

    def log(self, request: str, response: str) -> None:
        entry = f"REQUEST: {request}\nRESPONSE: {response}"
        self._entries.append(entry)
        if len(self._entries) > self.max_entries:
            del self._entries[0]

    def entries(self) -> list[str]:
        return list(self._entries)


@dataclass(slots=True)
class TagDescriptor:
    id: str
    label: str


@dataclass(slots=True)
class TagCatalogSnapshot:
    descriptors: list[TagDescriptor]
    approved_ids: set[str]
    prompt_text: str
    primary_tag_id: str | None

    @classmethod
    def from_catalog(cls, catalog: NotesTagCatalog | None, locale: str) -> "TagCatalogSnapshot":
        if catalog is None or not catalog.tags:
            return cls([], set(), "- (no tags available)", None)
        descriptors: list[TagDescriptor] = []
        approved: list[str] = []
        for definition in catalog.tags:
            tag_id = definition.id.strip()
            if not tag_id:
                continue
            label = definition.label_for_locale(locale) or (
                definition.labels[0].value if definition.labels else tag_id
            )
            descriptors.append(TagDescriptor(tag_id, label))
            approved.append(tag_id)
        unique: dict[str, TagDescriptor] = {}
        for descriptor in descriptors:
            unique.setdefault(descriptor.id, descriptor)
        descriptors = sorted(
            unique.values(),
            key=lambda item: (item.label.lower(), item.id.lower()),
        )
        prompt = "\n".join(f"- {descriptor.id}: {descriptor.label}" for descriptor in descriptors)
        return cls(descriptors, set(approved), prompt, descriptors[0].id if descriptors else None)


def available_model_ids(root: Path | None = None) -> list[str]:
    try:
        raw = load_resource("llm/models.json", root)
    except FileNotFoundError:
        return []
    try:
        parsed = json.loads(raw)
    except json.JSONDecodeError:
        return []
    ids: list[str] = []
    seen: set[str] = set()
    for entry in parsed:
        if not isinstance(entry, Mapping):
            continue
        identifier = str(entry.get("id", "")).strip()
        if identifier and identifier not in seen:
            ids.append(identifier)
            seen.add(identifier)
    return ids


class MemoProcessor:
    """Stateless interface for constructing memo update requests."""

    DEFAULT_MODEL = "mistralai/mistral-nemo"

    def __init__(
        self,
        api_key: str,
        *,
        locale: str = "en",
        logger: LlmLogger | None = None,
        root: Path | None = None,
        tag_catalog: NotesTagCatalog | None = None,
    ) -> None:
        self.api_key = api_key
        self.locale = locale
        self.root = root
        self.prompts = Prompts.for_locale(locale, root)
        self.logger = logger or LlmLogger()
        self.base_schema = json.loads(load_resource("llm/schema/base.json", root))
        self.todo_schema = json.loads(load_resource("llm/schema/todo.json", root))
        self.appointment_schema = json.loads(load_resource("llm/schema/appointment.json", root))
        self.thought_schema = json.loads(load_resource("llm/schema/thought.json", root))
        self.tag_catalog_snapshot = TagCatalogSnapshot.from_catalog(tag_catalog, locale)
        self.todo: str = ""
        self.todo_items: list[TodoItem] = []
        self.appointments: str = ""
        self.appointment_items: list[Appointment] = []
        self.thoughts: str = ""
        self.thought_items: list[Thought] = []
        self.thought_document: ThoughtDocument | None = None
        self._pending_requests: dict[str, str] = {}
        self._model = self._normalize_model(self.DEFAULT_MODEL)

    @property
    def model(self) -> str:
        return self._model

    @model.setter
    def model(self, value: str) -> None:
        self._model = self._normalize_model(value)

    def _normalize_model(self, candidate: str) -> str:
        available = available_model_ids(self.root)
        if not available:
            return candidate or self.DEFAULT_MODEL
        if candidate in available:
            return candidate
        if self.DEFAULT_MODEL in available:
            return self.DEFAULT_MODEL
        return available[0]

    def update_tag_catalog(self, catalog: NotesTagCatalog | None) -> None:
        self.tag_catalog_snapshot = TagCatalogSnapshot.from_catalog(catalog, self.locale)
        self.todo_items = self._sanitize_todo_items(self.todo_items)
        self.thought_items = self._sanitize_thought_items(self.thought_items)

    def initialize(self, summary: MemoSummary) -> None:
        self.todo = summary.todo
        self.todo_items = self._sanitize_todo_items(summary.todo_items)
        self.appointments = summary.appointments
        self.appointment_items = [
            Appointment(text=item.text, datetime=item.datetime, location=item.location, created_at=item.created_at)
            for item in summary.appointment_items
        ]
        self.thoughts = summary.thought_document.markdown_body if summary.thought_document else summary.thoughts
        self.thought_items = self._sanitize_thought_items(summary.thought_items)
        self.thought_document = summary.thought_document

    def prepare_requests(
        self,
        memo_text: str,
        *,
        process_todos: bool = True,
        process_appointments: bool = True,
        process_thoughts: bool = True,
    ) -> dict[str, dict[str, Any]]:
        if not self.api_key:
            raise ValueError("Missing API key")
        requests: dict[str, dict[str, Any]] = {}
        if process_todos:
            prior = self._todo_prior_json()
            payload = self._build_request(self.prompts.todo, prior, memo_text)
            requests[self.prompts.todo] = payload
            self._pending_requests[self.prompts.todo] = json.dumps(payload, ensure_ascii=False)
        if process_appointments:
            prior = self._appointment_prior_json()
            payload = self._build_request(self.prompts.appointments, prior, memo_text)
            requests[self.prompts.appointments] = payload
            self._pending_requests[self.prompts.appointments] = json.dumps(payload, ensure_ascii=False)
        if process_thoughts:
            prior = self._thought_prior_json()
            payload = self._build_request(self.prompts.thoughts, prior, memo_text)
            requests[self.prompts.thoughts] = payload
            self._pending_requests[self.prompts.thoughts] = json.dumps(payload, ensure_ascii=False)
        return requests

    def ingest_response(self, aspect: str, response_body: str) -> str:
        if aspect not in self._pending_requests:
            raise KeyError(f"No pending request for aspect '{aspect}'")
        request = self._pending_requests.pop(aspect)
        self.logger.log(request, response_body)
        return self._apply_response(aspect, response_body)

    def summary(self) -> MemoSummary:
        return MemoSummary(
            todo=self.todo,
            appointments=self.appointments,
            thoughts=self.thoughts,
            todo_items=self.todo_items,
            appointment_items=self.appointment_items,
            thought_items=self.thought_items,
            thought_document=self.thought_document,
        )

    def _sanitize_todo_items(self, items: Iterable[TodoItem]) -> list[TodoItem]:
        sanitized: list[TodoItem] = []
        for item in items:
            sanitized.append(
                TodoItem(
                    text=item.text.strip(),
                    status=item.status.strip(),
                    tag_ids=self._sanitize_tag_ids(item.tag_ids),
                    tag_labels=list(item.tag_labels),
                    due_date=item.due_date.strip(),
                    event_date=item.event_date.strip(),
                    note_id=item.note_id,
                    created_at=item.created_at,
                )
            )
        return sanitized

    def _sanitize_thought_items(self, items: Iterable[Thought]) -> list[Thought]:
        sanitized: list[Thought] = []
        for item in items:
            sanitized.append(
                Thought(
                    text=item.text.strip(),
                    tag_ids=self._sanitize_tag_ids(item.tag_ids),
                    tag_labels=list(item.tag_labels),
                    section_anchor=item.section_anchor,
                    section_title=item.section_title,
                    created_at=item.created_at,
                )
            )
        return sanitized

    def _sanitize_tag_ids(self, ids: Sequence[str]) -> list[str]:
        sanitized: list[str] = []
        for raw in ids:
            trimmed = raw.strip()
            if not trimmed:
                continue
            if self.tag_catalog_snapshot.approved_ids and trimmed not in self.tag_catalog_snapshot.approved_ids:
                continue
            if trimmed not in sanitized:
                sanitized.append(trimmed)
        if not sanitized and self.tag_catalog_snapshot.primary_tag_id:
            sanitized.append(self.tag_catalog_snapshot.primary_tag_id)
        return sanitized

    def _todo_prior_json(self) -> str:
        items = []
        self.todo_items = self._sanitize_todo_items(self.todo_items)
        for item in self.todo_items:
            items.append(
                {
                    "text": item.text,
                    "status": item.status,
                    "tags": item.tag_ids,
                    "due_date": item.due_date or None,
                    "event_date": item.event_date or None,
                    "id": item.note_id or None,
                }
            )
        payload = {"items": items}
        return json.dumps(payload, ensure_ascii=False)

    def _appointment_prior_json(self) -> str:
        entries = [
            {"text": item.text, "datetime": item.datetime, "location": item.location}
            for item in self.appointment_items
        ]
        payload = {"updated": self.appointments, "items": entries}
        return json.dumps(payload, ensure_ascii=False)

    def _thought_prior_json(self) -> str:
        self.thought_items = self._sanitize_thought_items(self.thought_items)
        sections = []
        if self.thought_document:
            sections = [self._outline_section_to_dict(section) for section in self.thought_document.outline.sections]
        payload = {
            "markdown_body": self.thought_document.markdown_body if self.thought_document else self.thoughts,
            "sections": sections,
            "items": [
                {"text": item.text, "tags": item.tag_ids}
                for item in self.thought_items
            ],
        }
        return json.dumps(payload, ensure_ascii=False)

    def _outline_section_to_dict(self, section: ThoughtOutlineSection) -> dict[str, Any]:
        return {
            "title": section.title,
            "level": section.level,
            "anchor": section.anchor,
            "children": [self._outline_section_to_dict(child) for child in section.children],
        }

    def _build_request(self, aspect: str, prior_json: str, memo_text: str) -> dict[str, Any]:
        if aspect == self.prompts.todo:
            schema = json.loads(json.dumps(self.todo_schema))
        elif aspect == self.prompts.appointments:
            schema = json.loads(json.dumps(self.appointment_schema))
        elif aspect == self.prompts.thoughts:
            schema = json.loads(json.dumps(self.thought_schema))
        else:
            schema = json.loads(json.dumps(self.base_schema))
        self._apply_tag_enumeration(aspect, schema)
        system = self.prompts.system_template.replace("{aspect}", aspect)
        user = (
            self.prompts.user_template
            .replace("{aspect}", aspect)
            .replace("{prior}", prior_json)
            .replace("{memo}", memo_text)
            .replace("{today}", date.today().isoformat())
            .replace("{tag_catalog}", self.tag_catalog_snapshot.prompt_text)
        )
        payload = {
            "model": self.model,
            "messages": [
                {"role": "system", "content": system},
                {"role": "user", "content": user},
            ],
            "response_format": {"type": "json_schema", "json_schema": schema},
        }
        return payload

    def _apply_response(self, aspect: str, response_body: str) -> str:
        data = json.loads(response_body)
        if aspect == self.prompts.todo:
            return self._apply_todo_response(data)
        if aspect == self.prompts.appointments:
            return self._apply_appointment_response(data)
        if aspect == self.prompts.thoughts:
            return self._apply_thought_response(data)
        return data.get("updated", "") if isinstance(data, Mapping) else ""

    def _apply_todo_response(self, data: Mapping[str, Any]) -> str:
        items = []
        updates = data.get("items")
        if isinstance(updates, Sequence):
            for entry in updates:
                if not isinstance(entry, Mapping):
                    continue
                text = str(entry.get("text", "")).strip()
                if not text:
                    continue
                status = str(entry.get("status", "")).strip()
                tags = entry.get("tags") if isinstance(entry.get("tags"), Sequence) else []
                due_date = str(entry.get("due_date", "")).strip()
                event_date = str(entry.get("event_date", "")).strip()
                note_id = str(entry.get("id", "")).strip()
                items.append(
                    TodoItem(
                        text=text,
                        status=status,
                        tag_ids=self._sanitize_tag_ids([str(tag) for tag in tags]),
                        due_date=due_date,
                        event_date=event_date,
                        note_id=note_id,
                    )
                )
        existing = {item.note_id or item.text: item for item in self.todo_items}
        for item in items:
            key = item.note_id or item.text
            existing[key] = item
        self.todo_items = self._sanitize_todo_items(existing.values())
        self.todo = "\n".join(item.text for item in self.todo_items if item.text)
        return self.todo

    def _apply_appointment_response(self, data: Mapping[str, Any]) -> str:
        updated = str(data.get("updated", self.appointments))
        items: list[Appointment] = []
        entries = data.get("items")
        if isinstance(entries, Sequence):
            for entry in entries:
                if not isinstance(entry, Mapping):
                    continue
                text = str(entry.get("text", "")).strip()
                if not text:
                    continue
                datetime_str = str(entry.get("datetime", "")).strip()
                location = str(entry.get("location", "")).strip()
                items.append(Appointment(text=text, datetime=datetime_str, location=location))
        self.appointment_items = items
        self.appointments = updated
        return self.appointments

    def _apply_thought_response(self, data: Mapping[str, Any]) -> str:
        items: list[Thought] = []
        entries = data.get("items")
        if isinstance(entries, Sequence):
            for entry in entries:
                if not isinstance(entry, Mapping):
                    continue
                text = str(entry.get("text", "")).strip()
                if not text:
                    continue
                tags = entry.get("tags") if isinstance(entry.get("tags"), Sequence) else []
                items.append(
                    Thought(
                        text=text,
                        tag_ids=self._sanitize_tag_ids([str(tag) for tag in tags]),
                        created_at=0,
                    )
                )
        updated = str(data.get("updated_markdown", self.thoughts))
        sections = self._parse_outline_sections(data.get("sections"))
        self.thought_document = ThoughtDocument(updated, ThoughtOutline(sections))
        self.thought_items = self._sanitize_thought_items(items)
        self.thoughts = updated
        return self.thoughts

    def _parse_outline_sections(self, value: Any) -> list[ThoughtOutlineSection]:
        if not isinstance(value, Sequence):
            return []
        sections: list[ThoughtOutlineSection] = []
        for entry in value:
            if not isinstance(entry, Mapping):
                continue
            title = str(entry.get("title", "")).strip()
            if not title:
                continue
            level = int(entry.get("level", 1))
            anchor = str(entry.get("anchor", "")).strip() or self._default_anchor(title)
            children = self._parse_outline_sections(entry.get("children"))
            sections.append(ThoughtOutlineSection(title, level, anchor, children))
        return sections

    def _default_anchor(self, title: str) -> str:
        slug = "".join(ch for ch in title.lower() if ch.isalnum() or ch.isspace()).strip().replace(" ", "-")
        return slug or f"section-{abs(hash(title)) & 0xFFFF:x}"

    def _apply_tag_enumeration(self, aspect: str, schema_object: dict[str, Any]) -> None:
        if aspect not in {self.prompts.todo, self.prompts.thoughts}:
            return
        target = schema_object.get("schema")
        if not isinstance(target, dict):
            target = schema_object
        cursor: dict[str, Any] | None = target
        path = ["properties", "items", "items", "properties", "tags", "items"]
        for segment in path[:-1]:
            next_obj = cursor.get(segment) if isinstance(cursor, dict) else None
            if not isinstance(next_obj, dict):
                return
            cursor = next_obj
        final = cursor.get(path[-1]) if isinstance(cursor, dict) else None
        if not isinstance(final, dict):
            return
        final.pop("pattern", None)
        if self.tag_catalog_snapshot.descriptors:
            final["enum"] = [descriptor.id for descriptor in self.tag_catalog_snapshot.descriptors]
        else:
            final.pop("enum", None)


__all__ = [
    "LlmLogger",
    "MemoProcessor",
    "Prompts",
    "available_model_ids",
    "load_resource",
]

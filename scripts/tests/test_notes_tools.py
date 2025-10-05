from __future__ import annotations

import unittest
from datetime import date
from dataclasses import dataclass

from scripts.notes_tools.memo_processing import MemoProcessor
from scripts.notes_tools.notes import (
    LocalizedLabel,
    NotesTagCatalog,
    NotesTagDefinition,
    Session,
    TagMappingContext,
    parse_remote_note,
    parse_remote_session,
)


@dataclass
class DummySnapshot:
    doc_id: str
    data: dict[str, object]

    @property
    def id(self) -> str:
        return self.doc_id

    def to_dict(self) -> dict[str, object]:
        return dict(self.data)


class NotesToolsTest(unittest.TestCase):
    def test_parse_remote_session(self) -> None:
        snapshot = DummySnapshot(
            "session-1",
            {
                "name": " Weekly Review ",
                "settings": {
                    "processTodos": False,
                    "saveAppointments": "true",
                    "processThoughts": 0,
                    "model": "custom-model",
                },
            },
        )
        session = parse_remote_session(snapshot)
        self.assertIsInstance(session, Session)
        self.assertEqual(session.id, "session-1")
        self.assertEqual(session.name, "Weekly Review")
        self.assertFalse(session.settings.process_todos)
        self.assertTrue(session.settings.process_appointments)
        self.assertFalse(session.settings.process_thoughts)
        self.assertEqual(session.settings.model, "custom-model")

    def test_parse_remote_note_with_tag_migration(self) -> None:
        catalog = NotesTagCatalog(
            tags=[
                NotesTagDefinition(
                    id="work",
                    labels=[LocalizedLabel(locale_tag=None, value="Work")],
                )
            ]
        )
        context = TagMappingContext(catalog=catalog, locale="en")
        snapshot = DummySnapshot(
            "note-1",
            {
                "type": "todo",
                "text": "Finish report",
                "status": "in_progress",
                "tags": ["Work", "Personal"],
                "createdAt": 123,
            },
        )
        note = parse_remote_note(snapshot, context)
        self.assertIsNotNone(note)
        self.assertEqual(getattr(note, "tag_ids"), ["work"])
        self.assertIn("Personal", getattr(note, "tag_labels"))
        self.assertEqual(getattr(note, "created_at"), 123)

    def test_parse_remote_note_handles_non_numeric_created_at(self) -> None:
        context = TagMappingContext(catalog=None, locale="en")
        snapshot = DummySnapshot(
            "note-iso",
            {
                "type": "todo",
                "text": "Call the bank",
                "createdAt": "2024-10-01T12:00:00Z",
            },
        )
        note = parse_remote_note(snapshot, context)
        self.assertIsNotNone(note)
        self.assertEqual(getattr(note, "created_at"), 0)

    def test_schema_injects_tag_catalog(self) -> None:
        catalog = NotesTagCatalog(
            tags=[
                NotesTagDefinition(
                    id="alpha",
                    labels=[LocalizedLabel(locale_tag=None, value="Alpha")],
                ),
                NotesTagDefinition(
                    id="beta",
                    labels=[LocalizedLabel(locale_tag="en", value="Beta")],
                ),
            ]
        )
        processor = MemoProcessor(api_key="secret", tag_catalog=catalog)
        requests = processor.prepare_requests("memo", process_appointments=False, process_thoughts=False)
        self.assertIn(processor.prompts.todo, requests)
        schema = requests[processor.prompts.todo]["response_format"]["json_schema"]
        tags_enum = (
            schema["schema"]["properties"]["items"]["items"]["properties"]["tags"]["items"]["enum"]
        )
        self.assertEqual(tags_enum, ["alpha", "beta"])

    def test_prepare_requests_replaces_date_placeholder(self) -> None:
        processor = MemoProcessor(api_key="secret")
        requests = processor.prepare_requests(
            "memo text",
            process_appointments=False,
            process_thoughts=False,
        )
        payload = requests[processor.prompts.todo]
        messages = payload.get("messages", [])
        user_message = next(
            (msg.get("content", "") for msg in messages if msg.get("role") == "user"),
            "",
        )
        today = date.today().isoformat()
        self.assertNotIn("{date}", user_message)
        self.assertNotIn("{today}", user_message)
        self.assertIn(today, user_message)


if __name__ == "__main__":
    unittest.main()

# Notes

A `StructuredNote` represents an item extracted from a transcript. Four types
are supported:

- **ToDo** for actionable tasks.
- **Memo** for free form text snippets.
- **Event** for calendar entries with optional date and time.
- **Free** for content that does not fit other categories.

Notes are grouped in a `NoteCollection` to represent a session or a day. Each
note keeps only essential information to remain lightweight and portable.

Notes are stored using a JSON schema with the fields `type`, `text`, and
`datetime` to provide stable persistence locally and in Firestore.

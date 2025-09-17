# Notes

A `StructuredNote` represents an item extracted from a transcript. Four types
are supported:

- **ToDo** for actionable tasks.
- **Memo** for free form text snippets.
- **Event** for calendar entries with optional date and time.
- **Free** for content that does not fit other categories.

Each subtype carries a small, well-defined set of fields:

- **ToDo** — `text`, `status`, `tags`, `dueDate`, `eventDate`, `createdAt`, and
  an `id` (blank until a Firestore document assigns one). 【F:app/src/main/java/li/crescio/penates/diana/notes/Models.kt†L14-L23】
- **Memo** — `text`, `tags`, and `createdAt`. 【F:app/src/main/java/li/crescio/penates/diana/notes/Models.kt†L25-L30】
- **Event** — `text`, `datetime`, `location`, and `createdAt`. 【F:app/src/main/java/li/crescio/penates/diana/notes/Models.kt†L32-L37】
- **Free** — `text`, `tags`, and `createdAt`. 【F:app/src/main/java/li/crescio/penates/diana/notes/Models.kt†L39-L44】

Notes are grouped in a `NoteCollection` to represent a session or a day. Each
note keeps only essential information to remain lightweight and portable.

## Persistence

`NoteRepository` persists notes both locally and in Firestore. Locally, each
note is serialized to a single-line JSON object written to the repository file.
The JSON contains the shared `type`, `text`, `createdAt`, `datetime`, and
`location` keys, plus subtype-specific fields (`status`, `tags`, `dueDate`,
`eventDate`, and `id` for todos). When the file is read back, every property is
restored so the original `createdAt` timestamps are preserved. 【F:app/src/main/java/li/crescio/penates/diana/persistence/NoteRepository.kt†L52-L107】【F:app/src/main/java/li/crescio/penates/diana/persistence/NoteRepository.kt†L180-L251】

For remote persistence, the repository writes maps with the same fields to the
per-session Firestore collection `sessions/{sessionId}/notes`. Todo items with
blank `id` values receive the server-assigned document ID, and subsequent saves
reuse that identifier while keeping the stored `createdAt`. Loading combines
local and remote records, again honoring the stored timestamps. 【F:app/src/main/java/li/crescio/penates/diana/persistence/NoteRepository.kt†L16-L108】【F:app/src/main/java/li/crescio/penates/diana/persistence/NoteRepository.kt†L118-L219】

Todo items can therefore round-trip through Firestore, pick up their document
IDs, and return to the client with the original creation time intact. 【F:app/src/main/java/li/crescio/penates/diana/persistence/NoteRepository.kt†L16-L77】

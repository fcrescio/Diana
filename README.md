# Diana

Diana turns spoken or written memos into structured todos, appointments, and
thought documents that stay in sync across local storage and Firestore. The app
ships with a Markdown-based thought reader so longer reflections remain easy to
scan and navigate. 【F:app/src/main/java/li/crescio/penates/diana/llm/MemoProcessor.kt†L54-L120】【F:app/src/main/java/li/crescio/penates/diana/ui/NotesListScreen.kt†L45-L215】

## Thought document viewer

1. **Capture or type a memo.** Recording or typing in the Text Memo screen feeds
   the memo to `MemoProcessor`, which asks the LLM to emit an updated Markdown
   body plus an outline describing each heading. 【F:app/src/main/java/li/crescio/penates/diana/llm/MemoProcessor.kt†L361-L399】
2. **Open the Notes tab.** The Thoughts card shows the outline as nested chips;
   tap any heading to load just that section in the Markdown pane and filter by
   tag to jump to relevant paragraphs. 【F:app/src/main/java/li/crescio/penates/diana/ui/NotesListScreen.kt†L160-L215】
3. **Resume later on any device.** The Markdown body lives in `thoughts.md` and
   its outline in `thought_outline.json`, both synced under the
   `__thought_document__` document for the session so the reader is portable.
   【F:app/src/main/java/li/crescio/penates/diana/persistence/NoteRepository.kt†L19-L119】【F:app/src/main/java/li/crescio/penates/diana/persistence/NoteRepository.kt†L236-L275】

### Locale support

The viewer reuses locale-specific prompts for English, Italian, and French. Set
Android’s language before processing a memo and the app will fetch the matching
prompt bundle automatically, producing Markdown that respects local phrasing and
headings. 【F:app/src/main/java/li/crescio/penates/diana/llm/MemoProcessor.kt†L382-L413】

### Troubleshooting

* If no structured outline exists yet, the Thoughts card falls back to showing
  tagged memo snippets until the first Markdown document is generated.
  【F:app/src/main/java/li/crescio/penates/diana/ui/NotesListScreen.kt†L62-L141】
* Clearing thoughts from Settings removes the cached Markdown/outline files; the
  next processed memo will rebuild them from scratch. 【F:app/src/main/java/li/crescio/penates/diana/persistence/NoteRepository.kt†L248-L275】

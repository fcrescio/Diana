# Architecture

Diana records voice notes and transforms them into structured items. The
application uses several modules:

- `recorder` captures audio from the device.
- `transcriber` turns audio into text.
- `llm` interprets transcripts into notes.
- `persistence` stores notes locally and in Firebase.
- `notes` defines core domain models.
- `ui` presents a Compose based interface.

The processing pipeline moves a recording through four ordered steps:

1. **TranscriptionStep** converts audio clips into text.
2. **InterpretationStep** calls the language model to build structured notes.
3. **PersistenceStep** saves notes to Firestore and to a local file.
4. **CallbackStep** reports progress back to the user interface.

Data flows from the microphone to persistent storage in the following order:

recording → transcription → LLM processing → structured notes → persistence
→ display.

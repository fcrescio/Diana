package li.crescio.penates.diana.pipeline

import li.crescio.penates.diana.llm.NoteInterpreter
import li.crescio.penates.diana.notes.StructuredNote
import li.crescio.penates.diana.notes.Transcript
import li.crescio.penates.diana.persistence.NoteRepository
import li.crescio.penates.diana.transcriber.Transcriber

class TranscriptionStep(private val transcriber: Transcriber) : PipelineStep {
    override suspend fun process(context: PipelineContext): PipelineContext {
        val transcript = transcriber.transcribe(context.recording)
        return context.copy(transcript = transcript)
    }
}

class InterpretationStep(private val interpreter: NoteInterpreter) : PipelineStep {
    override suspend fun process(context: PipelineContext): PipelineContext {
        val transcript = context.transcript ?: Transcript("")
        val notes: List<StructuredNote> = interpreter.interpret(transcript)
        return context.copy(notes = notes)
    }
}

class PersistenceStep(private val repository: NoteRepository) : PipelineStep {
    override suspend fun process(context: PipelineContext): PipelineContext {
        repository.saveNotes(context.notes)
        return context
    }
}

class CallbackStep(private val callback: (PipelineContext) -> Unit) : PipelineStep {
    override suspend fun process(context: PipelineContext): PipelineContext {
        callback(context)
        return context
    }
}

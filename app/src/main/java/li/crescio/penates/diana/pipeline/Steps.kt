package li.crescio.penates.diana.pipeline

import li.crescio.penates.diana.llm.NoteInterpreter
import li.crescio.penates.diana.notes.StructuredNote
import li.crescio.penates.diana.notes.Transcript
import li.crescio.penates.diana.transcriber.Transcriber

/**
 * Transcribes the raw recording into text.
 *
 * @param transcriber the engine performing the transcription
 * @param enabled whether this step should run. Future pipelines can disable
 *        steps without removing them from the chain.
 */
class TranscriptionStep(
    private val transcriber: Transcriber,
    private val enabled: Boolean = true,
) : PipelineStep {

    override suspend fun shouldProcess(context: PipelineContext): Boolean = enabled

    override suspend fun process(context: PipelineContext): PipelineContext {
        val transcript = transcriber.transcribe(context.recording)
        return context.copy(transcript = transcript)
    }
}

/**
 * Uses a large language model to interpret the transcript into structured notes.
 *
 * @param interpreter the LLM adapter
 * @param enabled whether this step should run
 */
class InterpretationStep(
    private val interpreter: NoteInterpreter,
    private val enabled: Boolean = true,
) : PipelineStep {

    override suspend fun shouldProcess(context: PipelineContext): Boolean = enabled

    override suspend fun process(context: PipelineContext): PipelineContext {
        val transcript = context.transcript ?: Transcript("")
        val notes: List<StructuredNote> = interpreter.interpret(transcript)
        return context.copy(notes = notes)
    }
}


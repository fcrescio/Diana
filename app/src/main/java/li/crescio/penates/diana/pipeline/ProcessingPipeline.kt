package li.crescio.penates.diana.pipeline

import li.crescio.penates.diana.notes.RawRecording
import li.crescio.penates.diana.notes.StructuredNote
import li.crescio.penates.diana.notes.Transcript

data class PipelineContext(
    val recording: RawRecording,
    val transcript: Transcript? = null,
    val notes: List<StructuredNote> = emptyList()
)

interface PipelineStep {
    suspend fun process(context: PipelineContext): PipelineContext
}

class ProcessingPipeline(private val steps: List<PipelineStep>) {
    suspend fun execute(recording: RawRecording) {
        var ctx = PipelineContext(recording)
        steps.forEach { step ->
            ctx = step.process(ctx)
        }
    }
}

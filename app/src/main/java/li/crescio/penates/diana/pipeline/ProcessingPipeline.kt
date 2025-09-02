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
    /**
     * Whether this step should run given the current [PipelineContext].
     * The default implementation always executes the step.
     */
    suspend fun shouldProcess(context: PipelineContext): Boolean = true

    /**
     * Perform the work of this step and return the updated [PipelineContext].
     */
    suspend fun process(context: PipelineContext): PipelineContext
}

class ProcessingPipeline(private val steps: List<PipelineStep>) {
    /**
     * Run the configured pipeline for a [recording], returning the final context.
     *
     * Each step may choose not to run by returning `false` from [PipelineStep.shouldProcess].
     */
    suspend fun execute(recording: RawRecording): PipelineContext {
        var ctx = PipelineContext(recording)
        steps.forEach { step ->
            if (step.shouldProcess(ctx)) {
                ctx = step.process(ctx)
            }
        }
        return ctx
    }
}

package com.wax.module.status

/**
 * What the studio still has to do to a selection before it can be posted.
 *
 * A renderer is honest about this. MediaMuxer can cut a selection out of an MP4 and write it
 * back, but it cannot apply a fade or turn the volume down, and a renderer that quietly ignored
 * those settings would be reporting a clip the user never asked for. Naming the limits turns that
 * from a silent difference into a refusal the caller can show.
 */
enum class StatusAudioEdit {
    /** Cut the selection out of the source. */
    TRIM,

    /** Ramp the volume up at the start. */
    FADE_IN,

    /** Ramp the volume down at the end. */
    FADE_OUT,

    /** Scale the whole clip. */
    VOLUME,

    /** Measure and correct the loudness of the whole clip. */
    NORMALIZE,

    /** Re-encode into a container the client posts natively. */
    TRANSCODE,
}

/** One part of the plan, as handed to a renderer. */
data class StatusAudioRenderRequest(
    val segment: StatusAudioSegment,
    val container: AudioContainer,
    val options: StatusAudioOptions,
    /** Whether this part is a cut out of a longer source rather than the whole selection. */
    val needsTrim: Boolean,
    /** The working file the renderer may read. Never shown to the user. */
    val sourcePath: String,
    /** The file the renderer must create. */
    val outputPath: String,
) {
    /** The edits this part needs, so a renderer can refuse what it cannot do. */
    val requiredEdits: Set<StatusAudioEdit> =
        buildSet {
            if (needsTrim) add(StatusAudioEdit.TRIM)
            if (options.fadeInMillis > 0L) add(StatusAudioEdit.FADE_IN)
            if (options.fadeOutMillis > 0L) add(StatusAudioEdit.FADE_OUT)
            if (options.volumePercent != 100) add(StatusAudioEdit.VOLUME)
            if (options.normalize) add(StatusAudioEdit.NORMALIZE)
            if (!container.nativeVoiceStatus) add(StatusAudioEdit.TRANSCODE)
        }
}

/** What a renderer did with one part. */
sealed interface StatusAudioRenderOutcome {
    /** The file exists and its length is [durationMillis]. */
    data class Written(
        val path: String,
        val durationMillis: Long,
        val sizeBytes: Long,
        val transcodeFrom: AudioContainer?,
    ) : StatusAudioRenderOutcome

    /** This renderer cannot write the container, and the reason is worth showing. */
    data class Refused(
        val code: String,
        val explanation: String,
    ) : StatusAudioRenderOutcome

    /** Something went wrong while working; the caller deletes whatever was written. */
    data class Failed(
        val reason: String,
    ) : StatusAudioRenderOutcome
}

/**
 * Turns one planned part into a file.
 *
 * The interface exists so the preparation rules — refusal, cleanup, cancellation, naming — can be
 * tested on a machine with no audio device, and so a renderer that cannot honour a request says
 * so instead of quietly doing less than was asked.
 */
interface StatusAudioRenderer {
    /** What this renderer can do, for one container. */
    fun supports(
        container: AudioContainer,
        edits: Set<StatusAudioEdit>,
    ): Boolean

    /** A one-line name for diagnostics. */
    val name: String

    /** Prepares one part. Called off the calling thread. */
    fun render(request: StatusAudioRenderRequest): StatusAudioRenderOutcome
}

/** Where prepared files may be written, and what may be deleted. */
interface StatusAudioWorkspace {
    /** A path inside the workspace for [part], or null when the name cannot be made safe. */
    fun allocate(
        part: StatusAudioSegment,
        container: AudioContainer,
    ): String?

    /** Removes a file the workspace owns. Missing files are not an error. */
    fun discard(path: String)
}

/** A part that exists on disk and may be handed to the composer. */
data class StatusAudioPreparedPart(
    val path: String,
    val label: String,
    val index: Int,
    val total: Int,
    val container: AudioContainer,
    val durationMillis: Long,
    val sizeBytes: Long,
    val transcodeFrom: AudioContainer?,
)

/** The result of preparing a whole plan. */
sealed interface StatusAudioPreparation {
    /** Every part of the plan is on disk. Nothing less counts as prepared. */
    data class Prepared(
        val parts: List<StatusAudioPreparedPart>,
        val warnings: List<String>,
        val strippedFields: Set<String>,
    ) : StatusAudioPreparation {
        /** Total length of everything that was prepared. */
        val totalMillis: Long get() = parts.sumOf { it.durationMillis }
    }

    /** Nothing was prepared, and the plan is not postable as it stands. */
    data class NotPrepared(
        val rejection: StatusAudioRejection,
        val warnings: List<String>,
    ) : StatusAudioPreparation
}

/** Raised from inside a preparation that the caller cancelled. */
object StatusAudioCancelled : RuntimeException("the preparation was cancelled")

/**
 * Prepares a plan into files, or refuses it.
 *
 * The rules are deliberately strict, because the failure they prevent is a user posting something
 * they did not choose:
 *
 * - **All or nothing.** A part that fails takes the whole preparation down, and every file written
 *   before it is deleted. A half-prepared series posted as a series would be worse than a refusal.
 * - **Refuse rather than approximate.** A renderer that cannot trim, fade, rescale or transcode
 *   says so, and the request is refused with the container and the missing edit named.
 * - **Names are made here.** The workspace allocates every path, so a source called
 *   `../../../shared_prefs/x.mp3` cannot choose where anything is written, and no source path is
 *   ever carried into a result.
 */
class StatusAudioPreparer(
    private val renderer: StatusAudioRenderer,
    private val workspace: StatusAudioWorkspace,
) {
    /**
     * Prepares every part of [plan].
     *
     * @param sourcePath the working file the renderer reads; never returned or logged.
     * @param cancelled polled between parts so a long series can be abandoned.
     */
    fun prepare(
        plan: StatusAudioPlan,
        container: AudioContainer?,
        options: StatusAudioOptions,
        sourcePath: String,
        cancelled: () -> Boolean = { false },
    ): StatusAudioPreparation {
        val warnings = ArrayList(plan.warnings)
        if (container == null) {
            return StatusAudioPreparation.NotPrepared(StatusAudioProblems.UNKNOWN_CONTAINER, warnings)
        }
        if (plan.kind == StatusAudioPlanKind.REJECT) {
            return StatusAudioPreparation.NotPrepared(
                plan.rejection ?: StatusAudioProblems.CANCELLED,
                warnings,
            )
        }
        if (plan.segments.isEmpty()) {
            return StatusAudioPreparation.NotPrepared(StatusAudioProblems.CANCELLED, warnings)
        }

        val written = ArrayList<StatusAudioPreparedPart>()
        for (segment in plan.segments) {
            if (cancelled()) {
                discardAll(written)
                throw StatusAudioCancelled
            }
            when (val part = preparePart(segment, container, options, sourcePath, plan)) {
                is PartOutcome.Written -> {
                    written.add(part.part)
                }

                is PartOutcome.Refused -> {
                    discardAll(written)
                    val withWarning = part.warning?.let { warnings + it } ?: warnings
                    return StatusAudioPreparation.NotPrepared(part.rejection, withWarning)
                }
            }
        }
        if (plan.strippedFields.isNotEmpty()) {
            warnings.add("Prepared without ${plan.strippedFields.sorted().joinToString(", ")}.")
        }
        return StatusAudioPreparation.Prepared(written, warnings, plan.strippedFields)
    }

    /** What became of one part, so the loop above reads as a sequence and not as a decision tree. */
    private sealed interface PartOutcome {
        data class Written(
            val part: StatusAudioPreparedPart,
        ) : PartOutcome

        data class Refused(
            val rejection: StatusAudioRejection,
            val warning: String?,
        ) : PartOutcome
    }

    private fun preparePart(
        segment: StatusAudioSegment,
        container: AudioContainer,
        options: StatusAudioOptions,
        sourcePath: String,
        plan: StatusAudioPlan,
    ): PartOutcome {
        val outputPath =
            workspace.allocate(segment, container)
                ?: return PartOutcome.Refused(
                    StatusAudioRejection("unsafe_name", "The selection could not be given a safe file name."),
                    null,
                )
        val request =
            StatusAudioRenderRequest(
                segment = segment,
                container = container,
                options = options,
                needsTrim = plan.kind != StatusAudioPlanKind.POST_AS_IS,
                sourcePath = sourcePath,
                outputPath = outputPath,
            )
        val refusal = refuseIfUnsupported(request)
        if (refusal != null) {
            return refusal
        }
        return when (val outcome = renderer.render(request)) {
            is StatusAudioRenderOutcome.Written -> {
                PartOutcome.Written(
                    StatusAudioPreparedPart(
                        path = outcome.path,
                        label = segment.label,
                        index = segment.index,
                        total = segment.total,
                        container = container,
                        durationMillis = outcome.durationMillis,
                        sizeBytes = outcome.sizeBytes,
                        transcodeFrom = outcome.transcodeFrom,
                    ),
                )
            }

            is StatusAudioRenderOutcome.Refused -> {
                refusePart(request, outcome)
            }

            is StatusAudioRenderOutcome.Failed -> {
                refusePart(request, outcome)
            }
        }
    }

    /**
     * Releases a part that did not come out, whatever the renderer reported.
     *
     * A renderer may have created the file before refusing or failing, so this part's own path is
     * discarded along with the ones already written. Anything left behind would be a file the
     * user never chose and that nothing else would clean up.
     */
    private fun refusePart(
        request: StatusAudioRenderRequest,
        outcome: StatusAudioRenderOutcome,
    ): PartOutcome.Refused {
        workspace.discard(request.outputPath)
        return when (outcome) {
            is StatusAudioRenderOutcome.Refused -> {
                PartOutcome.Refused(StatusAudioRejection(outcome.code, outcome.explanation), null)
            }

            is StatusAudioRenderOutcome.Failed -> {
                PartOutcome.Refused(
                    StatusAudioRejection("render_failed", outcome.reason),
                    "Part ${request.segment.index} could not be prepared: ${outcome.reason}",
                )
            }

            is StatusAudioRenderOutcome.Written -> {
                PartOutcome.Refused(StatusAudioProblems.CANCELLED, null)
            }
        }
    }

    /**
     * The refusal a renderer earns by being unable to do what was asked.
     *
     * A renderer that ignores a fade or a rescale would hand back a clip the user did not ask
     * for, so the missing edits are named in the sentence instead. The allocated name is
     * released here too: a directory holding an empty file for a part that was never prepared
     * is still a file the user did not choose.
     */
    private fun refuseIfUnsupported(request: StatusAudioRenderRequest): PartOutcome.Refused? {
        if (renderer.supports(request.container, request.requiredEdits)) return null
        val missing =
            request.requiredEdits
                .filterNot { renderer.supports(request.container, setOf(it)) }
                .map { it.name.lowercase().replace('_', ' ') }
        workspace.discard(request.outputPath)
        val explanation =
            if (missing.isEmpty()) {
                "Preparing ${request.container.label} is not available on this device."
            } else {
                "Preparing ${request.container.label} needs ${missing.joinToString(", ")}, " +
                    "which this device cannot do."
            }
        return PartOutcome.Refused(StatusAudioRejection("unsupported_edit", explanation), null)
    }

    private fun discardAll(parts: List<StatusAudioPreparedPart>) {
        parts.forEach { workspace.discard(it.path) }
    }
}

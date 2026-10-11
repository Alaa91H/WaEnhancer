package com.wax.module.status

import android.media.MediaCodec
import android.media.MediaExtractor
import android.media.MediaFormat
import android.media.MediaMuxer
import java.io.File
import java.nio.ByteBuffer
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Cuts a selection out of a file the platform can rewrite, without re-encoding it.
 *
 * `MediaMuxer` copies compressed samples, so the audio is bit-identical to what the user chose
 * and no quality is lost. What it cannot do is change the audio: a fade, a volume change and a
 * loudness correction all need decoded samples. This renderer therefore declares exactly the
 * edits it can honour and refuses the rest, so the studio tells the user their choice cannot be
 * prepared here rather than posting something different from what they picked.
 *
 * Only the MP4 family is writable, because that is what `MediaMuxer` accepts as an output
 * format. Everything else is a refusal with the container named.
 */
class MuxerTrimAudioRenderer(
    private val cancelled: AtomicBoolean = AtomicBoolean(false),
) : StatusAudioRenderer {
    override val name = "muxer-trim"

    /** Containers this device can write with a muxer, as opposed to the ones it can only read. */
    private fun writable(container: AudioContainer): Boolean =
        when (container) {
            AudioContainer.M4A -> true
            else -> false
        }

    override fun supports(
        container: AudioContainer,
        edits: Set<StatusAudioEdit>,
    ): Boolean {
        if (!writable(container)) return false
        // Re-encoding would be a different renderer with a different quality trade-off, so a
        // request that needs one is refused here rather than quietly served without it.
        if (StatusAudioEdit.TRANSCODE in edits) return false
        // Copying samples cannot alter them, so every audio edit is out of reach.
        return edits.none { it in AUDIO_EDITS }
    }

    override fun render(request: StatusAudioRenderRequest): StatusAudioRenderOutcome {
        val container = request.container
        // Only MP4-family audio can be written back without re-encoding, because that is what
        // `MediaMuxer` accepts. Every other container is refused by name.
        writable(container)
            ?: return StatusAudioRenderOutcome.Refused(
                "container_not_writable",
                "This device cannot write ${container.label} directly, so it has to be converted first.",
            )
        if (request.segment.durationMillis < 1L) {
            return StatusAudioRenderOutcome.Refused("empty_selection", "The selected range is empty.")
        }

        var extractor: MediaExtractor? = null
        var muxer: MediaMuxer? = null
        var muxerTrack = -1
        var wrote = 0
        var lastPresentationUs = 0L
        var completed = false

        return try {
            extractor = MediaExtractor().apply { setDataSource(request.sourcePath) }
            val audioTrack = selectAudioTrack(extractor)
            if (audioTrack < 0) {
                return StatusAudioRenderOutcome.Refused("no_audio_track", "The file holds no audio track.")
            }
            extractor.selectTrack(audioTrack)
            val inputFormat = extractor.getTrackFormat(audioTrack)
            muxer = MediaMuxer(request.outputPath, MediaMuxer.OutputFormat.MUXER_OUTPUT_MPEG_4)
            muxerTrack = muxer.addTrack(inputFormat)
            muxer.start()

            val buffer = ByteBuffer.allocate(MAX_SAMPLE_BYTES)
            val info = MediaCodec.BufferInfo()

            loop@ while (true) {
                if (cancelled.get() || Thread.currentThread().isInterrupted) {
                    return StatusAudioRenderOutcome.Failed("the preparation was cancelled")
                }
                buffer.clear()
                val size = extractor.readSampleData(buffer, 0)
                if (size < 0) break@loop
                val sampleTime = extractor.sampleTime
                extractor.advance()

                if (sampleTime < request.segment.startMillis) continue
                if (sampleTime >= request.segment.endMillis) break@loop

                if (size > buffer.capacity()) {
                    // Copying a truncated sample would write corrupt audio; refusing leaves the
                    // file empty and the reason visible.
                    return StatusAudioRenderOutcome.Refused(
                        "sample_too_large",
                        "This audio stores samples larger than ${MAX_SAMPLE_BYTES / 1024}kB, " +
                            "so it cannot be copied without re-encoding.",
                    )
                }

                info.offset = 0
                info.size = size
                info.presentationTimeUs = (sampleTime - request.segment.startMillis) * MICROS_PER_MILLI
                info.flags = muxerFlagsFor(extractor.sampleFlags)
                    ?: return StatusAudioRenderOutcome.Refused(
                        "encrypted_source",
                        "This audio is encrypted, so it cannot be copied into a new file.",
                    )
                muxer.writeSampleData(muxerTrack, buffer, info)
                wrote++
                lastPresentationUs = info.presentationTimeUs
            }

            if (wrote == 0) {
                StatusAudioRenderOutcome.Refused(
                    "nothing_in_selection",
                    "The selected range held no audio samples.",
                )
            } else {
                completed = true
                muxer.stop()
                StatusAudioRenderOutcome.Written(
                    path = request.outputPath,
                    durationMillis = lastPresentationUs / MICROS_PER_MILLI,
                    sizeBytes = File(request.outputPath).length(),
                    transcodeFrom = null,
                )
            }
        } catch (cancellation: InterruptedException) {
            // The reason travels in the outcome rather than to a log: this renderer is called
            // from paths a JVM unit test reaches, and Log is unmocked there.
            Thread.currentThread().interrupt()
            StatusAudioRenderOutcome.Failed(
                cancellation.message ?: "the preparation was interrupted",
            )
        } catch (failure: Exception) {
            StatusAudioRenderOutcome.Failed(
                failure.message ?: "${failure.javaClass.simpleName} while preparing ${container.label}",
            )
        } finally {
            // A muxer that was started and not stopped cannot finalise its index, so the
            // half-written file is released rather than left looking complete.
            if (muxer != null && !completed) runCatching { muxer.stop() }
            runCatching { muxer?.release() }
            runCatching { extractor?.release() }
        }
    }

    /**
     * The muxer's name for a sample flag, or null when the sample must not be copied at all.
     *
     * The two flag sets are different namespaces and happen to share a bit: a muxer reads
     * `MediaCodec.BUFFER_FLAG_KEY_FRAME`, an extractor reports `MediaExtractor.SAMPLE_FLAG_SYNC`,
     * and passing the extractor's value straight through relies on that coincidence. The
     * encrypted flag has no muxer equivalent at all — copying such a sample would write
     * ciphertext into a container that claims to be playable, so the source is refused instead.
     */
    internal fun muxerFlagsFor(sampleFlags: Int): Int? {
        // Both of these have no muxer equivalent, so a sample carrying either is refused
        // rather than written with a flag that does not mean what it said.
        if (sampleFlags and UNMAPPABLE_SAMPLE_FLAGS != 0) return null
        return if (sampleFlags and MediaExtractor.SAMPLE_FLAG_SYNC != 0) {
            MediaCodec.BUFFER_FLAG_KEY_FRAME
        } else {
            0
        }
    }

    /** The audio track of the file, or -1 when it holds none. */
    private fun selectAudioTrack(extractor: MediaExtractor): Int {
        for (index in 0 until extractor.trackCount) {
            val format = extractor.getTrackFormat(index)
            val mime = format.getString(MediaFormat.KEY_MIME).orEmpty()
            if (mime.startsWith("audio/")) return index
        }
        return -1
    }

    private companion object {
        const val MICROS_PER_MILLI = 1_000L

        /**
         * Extractor flags with no muxer equivalent.
         *
         * A partial frame and an encrypted sample both mean something the output container
         * cannot express, so they are refused rather than silently dropped.
         */
        const val UNMAPPABLE_SAMPLE_FLAGS =
            MediaExtractor.SAMPLE_FLAG_ENCRYPTED or MediaExtractor.SAMPLE_FLAG_PARTIAL_FRAME

        /**
         * A copy buffer large enough for one audio sample of any container the muxer writes.
         *
         * A sample larger than this would be skipped rather than truncated, because a truncated
         * sample is corrupt audio and a skipped one is a gap the user can hear and diagnose.
         */
        const val MAX_SAMPLE_BYTES = 512 * 1024

        val AUDIO_EDITS =
            setOf(
                StatusAudioEdit.FADE_IN,
                StatusAudioEdit.FADE_OUT,
                StatusAudioEdit.VOLUME,
                StatusAudioEdit.NORMALIZE,
            )
    }
}

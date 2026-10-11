package com.wax.module.status

import android.media.MediaCodec
import android.media.MediaExtractor
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * What the muxer renderer will and will not claim.
 *
 * `MediaMuxer` copies compressed samples, so this renderer can cut a selection and write it back
 * bit-for-bit. It cannot alter audio, and a renderer that said otherwise would hand the user a
 * clip they did not choose. These cases pin the boundary: each one is a request a naive renderer
 * would silently serve wrong.
 */
class MuxerTrimAudioRendererTest {
    private val renderer = MuxerTrimAudioRenderer()

    @Test
    fun aPlainCutOfAnM4aIsSomethingItCanDo() {
        assertTrue(
            renderer.supports(AudioContainer.M4A, setOf(StatusAudioEdit.TRIM)),
        )
    }

    @Test
    fun theWholeSelectionNeedsNoEditAtAll() {
        assertTrue(renderer.supports(AudioContainer.M4A, emptySet()))
    }

    @Test
    fun itRefusesEveryEditThatWouldChangeTheAudio() {
        setOf(
            StatusAudioEdit.FADE_IN,
            StatusAudioEdit.FADE_OUT,
            StatusAudioEdit.VOLUME,
            StatusAudioEdit.NORMALIZE,
        ).forEach { edit ->
            assertFalse(
                "copying samples cannot apply $edit, and pretending otherwise would post a different clip",
                renderer.supports(AudioContainer.M4A, setOf(edit)),
            )
        }
    }

    @Test
    fun itRefusesEveryContainerItCannotWriteBack() {
        listOf(
            AudioContainer.MP3,
            AudioContainer.AAC,
            AudioContainer.OGG,
            AudioContainer.OPUS,
            AudioContainer.WAV,
            AudioContainer.FLAC,
        ).forEach { container ->
            assertFalse(
                "a muxer cannot write ${container.label}",
                renderer.supports(container, emptySet()),
            )
        }
    }

    @Test
    fun itRefusesToTranscodeRatherThanPretendingACopyIsOne() {
        assertFalse(
            renderer.supports(AudioContainer.M4A, setOf(StatusAudioEdit.TRANSCODE)),
        )
    }

    @Test
    fun theRefusalForAnUnwritableContainerNamesThatContainer() {
        val outcome =
            renderer.render(
                StatusAudioRenderRequest(
                    segment = StatusAudioSegment(1, 1, 0L, 1_000L, "part 1"),
                    container = AudioContainer.MP3,
                    options = StatusAudioOptions(),
                    needsTrim = false,
                    sourcePath = "/does/not/exist.mp3",
                    outputPath = "/does/not/exist/out.m4a",
                ),
            )

        assertTrue(outcome is StatusAudioRenderOutcome.Refused)
        outcome as StatusAudioRenderOutcome.Refused
        assertTrue(
            "the message has to say which container is the problem",
            outcome.explanation.contains(AudioContainer.MP3.label),
        )
    }

    @Test
    fun anEmptySelectionIsRefusedBeforeAnyFileIsOpened() {
        val outcome =
            renderer.render(
                StatusAudioRenderRequest(
                    segment = StatusAudioSegment(1, 1, 5_000L, 5_000L, "part 1"),
                    container = AudioContainer.M4A,
                    options = StatusAudioOptions(),
                    needsTrim = true,
                    sourcePath = "/does/not/exist.m4a",
                    outputPath = "/does/not/exist/out.m4a",
                ),
            )

        assertTrue(outcome is StatusAudioRenderOutcome.Refused)
        assertEquals("empty_selection", (outcome as StatusAudioRenderOutcome.Refused).code)
    }

    @Test
    fun aMissingSourceFailsWithoutThrowing() {
        val outcome =
            renderer.render(
                StatusAudioRenderRequest(
                    segment = StatusAudioSegment(1, 1, 0L, 1_000L, "part 1"),
                    container = AudioContainer.M4A,
                    options = StatusAudioOptions(),
                    needsTrim = true,
                    sourcePath = "/does/not/exist.m4a",
                    outputPath = "/does/not/exist/out.m4a",
                ),
            )

        assertTrue(
            "a source that cannot be opened is a failure, not a crash",
            outcome is StatusAudioRenderOutcome.Failed,
        )
    }

    @Test
    fun theRenderedRequestCarriesEveryEditTheOptionsAskFor() {
        val options =
            StatusAudioOptions(
                fadeInMillis = 400L,
                fadeOutMillis = 400L,
                volumePercent = 80,
                normalize = true,
            )
        val request =
            StatusAudioRenderRequest(
                segment = StatusAudioSegment(2, 3, 30_000L, 90_000L, "Part 2 of 3"),
                container = AudioContainer.MP3,
                options = options,
                needsTrim = true,
                sourcePath = "/tmp/source.mp3",
                outputPath = "/tmp/out.m4a",
            )

        assertTrue(StatusAudioEdit.TRIM in request.requiredEdits)
        assertTrue(StatusAudioEdit.FADE_IN in request.requiredEdits)
        assertTrue(StatusAudioEdit.FADE_OUT in request.requiredEdits)
        assertTrue(StatusAudioEdit.VOLUME in request.requiredEdits)
        assertTrue(StatusAudioEdit.NORMALIZE in request.requiredEdits)
        // MP3 is not a container the client posts natively, so the plan said it would be
        // converted and the renderer has to be told.
        assertTrue(StatusAudioEdit.TRANSCODE in request.requiredEdits)
    }

    @Test
    fun anUntouchedSelectionOfANativeContainerAsksForNoEdits() {
        val request =
            StatusAudioRenderRequest(
                segment = StatusAudioSegment(1, 1, 0L, 45_000L, "Status clip"),
                container = AudioContainer.M4A,
                options = StatusAudioOptions(),
                needsTrim = false,
                sourcePath = "/tmp/source.m4a",
                outputPath = "/tmp/out.m4a",
            )

        assertTrue(request.requiredEdits.isEmpty())
        assertTrue("which is exactly what this renderer can do", renderer.supports(AudioContainer.M4A, request.requiredEdits))
    }

    @Test
    fun anEncryptedSourceIsRefusedRatherThanCopiedAsCiphertext() {
        // The muxer has no encrypted flag. Writing such a sample would produce a container that
        // claims to be playable and holds ciphertext, so the renderer says no instead.
        assertNull(
            muxerFlagsFor(MediaExtractor.SAMPLE_FLAG_ENCRYPTED),
        )
    }

    @Test
    fun aSyncSampleBecomesAKeyFrameAndAnythingElseCarriesNoFlag() {
        assertEquals(
            MediaCodec.BUFFER_FLAG_KEY_FRAME,
            muxerFlagsFor(MediaExtractor.SAMPLE_FLAG_SYNC),
        )
        assertEquals(0, muxerFlagsFor(0))
    }

    @Test
    fun aFlagWithNoMuxerEquivalentRefusesTheSample() {
        assertNull(
            muxerFlagsFor(MediaExtractor.SAMPLE_FLAG_PARTIAL_FRAME),
        )
    }
}

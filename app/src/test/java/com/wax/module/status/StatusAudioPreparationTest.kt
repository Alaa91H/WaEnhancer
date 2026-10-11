package com.wax.module.status

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.nio.file.Files

/**
 * The preparation rules, tested without an audio device.
 *
 * The renderer is a stand-in here on purpose. What matters in this file is the behaviour around
 * it: a refusal that names what is missing, a failure that takes the whole preparation down, files
 * that do not survive either, a cancellation that leaves nothing, and names that a source file
 * cannot influence.
 */
class StatusAudioPreparationTest {
    private val container = AudioContainer.M4A

    private fun source(
        durationMillis: Long = 120_000L,
        container: AudioContainer? = AudioContainer.M4A,
    ) = StatusAudioSource(
        fileName = "clip.mp3",
        container = container,
        durationMillis = durationMillis,
    )

    private fun capability(limitMillis: Long = 60_000L) =
        StatusAudioCapability.resolved(limitMillis, setOf(AudioContainer.M4A, AudioContainer.MP3))

    /** A renderer that can do everything, so the rules around it are what is under test. */
    private class RecordingRenderer(
        private val canDo: Set<StatusAudioEdit> = StatusAudioEdit.entries.toSet(),
        private val outcome: (StatusAudioRenderRequest) -> StatusAudioRenderOutcome =
            { StatusAudioRenderOutcome.Written(it.outputPath, 1_000L, 512L, null) },
    ) : StatusAudioRenderer {
        val requests = mutableListOf<StatusAudioRenderRequest>()

        override val name = "recording"

        override fun supports(
            container: AudioContainer,
            edits: Set<StatusAudioEdit>,
        ): Boolean = edits.all { it in canDo }

        override fun render(request: StatusAudioRenderRequest): StatusAudioRenderOutcome {
            requests.add(request)
            File(request.outputPath).writeBytes(ByteArray(16))
            return outcome(request)
        }
    }

    /** A workspace backed by a real temporary directory, so cleanup is observable. */
    private class TempWorkspace : StatusAudioWorkspace {
        val root: File = Files.createTempDirectory("wae-audio-test").toFile()
        val allocated = mutableListOf<String>()
        val discarded = mutableListOf<String>()

        override fun allocate(
            part: StatusAudioSegment,
            container: AudioContainer,
        ): String {
            val file = File(root, "part-${part.index}.${container.label.lowercase()}")
            allocated.add(file.absolutePath)
            return file.absolutePath
        }

        override fun discard(path: String) {
            discarded.add(path)
            File(path).delete()
        }
    }

    @Test
    fun everySegmentOfASplitIsPreparedAndCounted() {
        val plan =
            StatusAudioStudio.plan(
                source(durationMillis = 180_000L, container = AudioContainer.M4A),
                StatusAudioOptions(overflowAction = StatusAudioOverflowAction.AUTO_SPLIT),
                capability(),
            )
        val workspace = TempWorkspace()
        val renderer = RecordingRenderer()

        val prepared = StatusAudioPreparer(renderer, workspace).prepare(plan, AudioContainer.M4A, StatusAudioOptions(), "/tmp/source.m4a")

        assertTrue(prepared is StatusAudioPreparation.Prepared)
        prepared as StatusAudioPreparation.Prepared
        assertEquals(3, prepared.parts.size)
        assertEquals(listOf(1, 2, 3), prepared.parts.map { it.index })
        assertEquals(3_000L, prepared.totalMillis)
        assertEquals(listOf(0L, 60_000L, 120_000L), renderer.requests.map { it.segment.startMillis })
        assertEquals(listOf(60_000L, 120_000L, 180_000L), renderer.requests.map { it.segment.endMillis })
        assertEquals(3, renderer.requests.size)
        assertTrue("a split is a cut out of a longer source", renderer.requests.all { it.needsTrim })
    }

    @Test
    fun aRendererThatCannotFadeIsRefusedWithTheMissingEditNamed() {
        val options = StatusAudioOptions(fadeInMillis = 500L)
        val plan = StatusAudioStudio.plan(source(30_000L), options, capability())
        val workspace = TempWorkspace()
        val renderer = RecordingRenderer(canDo = StatusAudioEdit.entries.toSet() - StatusAudioEdit.FADE_IN)

        val prepared = StatusAudioPreparer(renderer, workspace).prepare(plan, AudioContainer.M4A, options, "/tmp/source.m4a")

        assertTrue(prepared is StatusAudioPreparation.NotPrepared)
        prepared as StatusAudioPreparation.NotPrepared
        assertEquals("unsupported_edit", prepared.rejection.code)
        assertTrue(prepared.rejection.explanation.contains("fade in"))
        assertTrue("nothing may be rendered", renderer.requests.isEmpty())
        assertEquals("the allocated name is released", 1, workspace.discarded.size)
    }

    @Test
    fun aRendererThatCannotTranscodeRefusesTheContainerThatNeedsIt() {
        // MP3 is not a container the client posts natively, so the plan says it will be
        // converted. A renderer that cannot convert must say so rather than post the original.
        val options = StatusAudioOptions()
        val plan = StatusAudioStudio.plan(source(30_000L, AudioContainer.MP3), options, capability())
        val workspace = TempWorkspace()
        val renderer = RecordingRenderer(canDo = StatusAudioEdit.entries.toSet() - StatusAudioEdit.TRANSCODE)

        val prepared = StatusAudioPreparer(renderer, workspace).prepare(plan, AudioContainer.MP3, options, "/tmp/source.mp3")

        assertTrue(prepared is StatusAudioPreparation.NotPrepared)
        prepared as StatusAudioPreparation.NotPrepared
        assertEquals("unsupported_edit", prepared.rejection.code)
        assertTrue(prepared.rejection.explanation.contains("transcode"))
    }

    @Test
    fun aFailedPartTakesTheWholePreparationDownAndLeavesNoFiles() {
        val plan =
            StatusAudioStudio.plan(
                source(durationMillis = 180_000L, container = AudioContainer.M4A),
                StatusAudioOptions(overflowAction = StatusAudioOverflowAction.AUTO_SPLIT),
                capability(),
            )
        val workspace = TempWorkspace()
        val renderer =
            RecordingRenderer { request ->
                if (request.segment.index == 2) {
                    StatusAudioRenderOutcome.Failed("the storage was full")
                } else {
                    StatusAudioRenderOutcome.Written(request.outputPath, 60_000L, 1_024L, null)
                }
            }

        val prepared = StatusAudioPreparer(renderer, workspace).prepare(plan, AudioContainer.M4A, StatusAudioOptions(), "/tmp/source.m4a")

        assertTrue(prepared is StatusAudioPreparation.NotPrepared)
        assertEquals("render_failed", (prepared as StatusAudioPreparation.NotPrepared).rejection.code)
        assertEquals(2, workspace.discarded.size)
        assertTrue(
            "no part of a failed preparation is left behind",
            workspace.root
                .listFiles()
                .orEmpty()
                .size == 0,
        )
    }

    @Test
    fun aRendererRefusalReleasesThePartItHadAlreadyCreated() {
        val plan = StatusAudioStudio.plan(source(30_000L), StatusAudioOptions(), capability())
        val workspace = TempWorkspace()
        val renderer =
            RecordingRenderer {
                StatusAudioRenderOutcome.Refused("no_encoder", "This device has no encoder for M4A.")
            }

        val prepared = StatusAudioPreparer(renderer, workspace).prepare(plan, AudioContainer.M4A, StatusAudioOptions(), "/tmp/source.m4a")

        assertTrue(prepared is StatusAudioPreparation.NotPrepared)
        assertEquals(1, workspace.discarded.size)
        assertTrue(
            workspace.root
                .listFiles()
                .orEmpty()
                .isEmpty(),
        )
    }

    fun aRendererRefusalIsReportedWithItsOwnExplanation() {
        val plan = StatusAudioStudio.plan(source(30_000L), StatusAudioOptions(), capability())
        val workspace = TempWorkspace()
        val renderer =
            RecordingRenderer {
                StatusAudioRenderOutcome.Refused("no_encoder", "This device has no encoder for M4A.")
            }

        val prepared = StatusAudioPreparer(renderer, workspace).prepare(plan, AudioContainer.M4A, StatusAudioOptions(), "/tmp/source.m4a")

        assertEquals("no_encoder", (prepared as StatusAudioPreparation.NotPrepared).rejection.code)
        assertTrue(prepared.rejection.explanation.contains("no encoder"))
    }

    @Test
    fun cancellingBetweenPartsLeavesNothingBehind() {
        val plan =
            StatusAudioStudio.plan(
                source(durationMillis = 180_000L, container = AudioContainer.M4A),
                StatusAudioOptions(overflowAction = StatusAudioOverflowAction.AUTO_SPLIT),
                capability(),
            )
        val workspace = TempWorkspace()
        val renderer = RecordingRenderer()
        // Cancel once the first part has been written: the preparer polls between parts, so
        // this is the point at which the half-finished work has to be undone.
        var polls = 0

        val thrown =
            runCatching {
                StatusAudioPreparer(renderer, workspace).prepare(
                    plan,
                    AudioContainer.M4A,
                    StatusAudioOptions(),
                    "/tmp/source.m4a",
                ) {
                    polls++
                    polls > 1
                }
            }.exceptionOrNull()

        assertTrue(thrown is StatusAudioCancelled)
        assertEquals("the first part was written before the cancellation", 1, renderer.requests.size)
        assertEquals("its file was released", 1, workspace.discarded.size)
        assertTrue(
            "a cancelled preparation leaves no file behind",
            workspace.root
                .listFiles()
                .orEmpty()
                .isEmpty(),
        )
    }

    @Test
    fun aRejectedPlanIsNeverPrepared() {
        val plan =
            StatusAudioStudio.plan(
                source(container = AudioContainer.M4A),
                StatusAudioOptions(overflowAction = StatusAudioOverflowAction.CANCEL),
                capability(),
            )
        val workspace = TempWorkspace()
        val renderer = RecordingRenderer()

        val prepared = StatusAudioPreparer(renderer, workspace).prepare(plan, AudioContainer.M4A, StatusAudioOptions(), "/tmp/source.m4a")

        assertEquals("cancelled", (prepared as StatusAudioPreparation.NotPrepared).rejection.code)
        assertTrue(renderer.requests.isEmpty())
    }

    @Test
    fun anUnknownContainerIsRefusedBeforeAnythingIsAllocated() {
        val plan = StatusAudioStudio.plan(source(container = null), StatusAudioOptions(), capability())
        val workspace = TempWorkspace()
        val renderer = RecordingRenderer()

        val prepared = StatusAudioPreparer(renderer, workspace).prepare(plan, null, StatusAudioOptions(), "/tmp/source.bin")

        assertEquals("unknown_container", (prepared as StatusAudioPreparation.NotPrepared).rejection.code)
        assertTrue(workspace.allocated.isEmpty())
    }

    @Test
    fun aNameTheWorkspaceCannotMakeSafeStopsThePreparation() {
        val plan = StatusAudioStudio.plan(source(30_000L), StatusAudioOptions(), capability())
        val renderer = RecordingRenderer()
        val workspace =
            object : StatusAudioWorkspace {
                var refused = 0

                override fun allocate(
                    part: StatusAudioSegment,
                    container: AudioContainer,
                ): String? {
                    refused++
                    return null
                }

                override fun discard(path: String) = Unit
            }

        val prepared = StatusAudioPreparer(renderer, workspace).prepare(plan, AudioContainer.M4A, StatusAudioOptions(), "/tmp/source.m4a")

        assertEquals("unsafe_name", (prepared as StatusAudioPreparation.NotPrepared).rejection.code)
        assertEquals(1, workspace.refused)
        assertTrue(renderer.requests.isEmpty())
    }

    @Test
    fun aPreparedPartCarriesNoPathFromTheSource() {
        val plan = StatusAudioStudio.plan(source(30_000L), StatusAudioOptions(), capability())
        val workspace = TempWorkspace()
        val renderer = RecordingRenderer()

        val prepared =
            StatusAudioPreparer(renderer, workspace).prepare(
                plan,
                AudioContainer.M4A,
                StatusAudioOptions(),
                "/data/user/0/com.whatsapp/shared_prefs/WaGlobal.xml",
            ) as StatusAudioPreparation.Prepared

        assertTrue(prepared.parts.none { it.path.contains("WaGlobal") })
        assertFalse(prepared.parts.any { it.path.contains("..") })
    }

    @Test
    fun theDirectoryWorkspaceNeverLetsASourceNameChooseWhereAFileGoes() {
        val root = Files.createTempDirectory("wae-workspace-test").toFile()
        val target = File(root, "nested")
        val workspace = DirectoryStatusAudioWorkspace(target) { "m4a" }
        val segment = StatusAudioSegment(1, 3, 0L, 1_000L, "../../etc/passwd")

        val path = workspace.allocate(segment, AudioContainer.M4A)

        assertTrue(path!!.startsWith(target.absolutePath))
        assertEquals("wae-status-part-1-of-3.m4a", File(path).name)

        workspace.discard(path)
        assertTrue("the directory goes when the last part is removed", !target.exists())
        workspace.releaseAll()
    }

    @Test
    fun theDirectoryWorkspaceRefusesASuffixThatIsNotASuffix() {
        val root = Files.createTempDirectory("wae-workspace-test").toFile()
        val workspace = DirectoryStatusAudioWorkspace(root) { "../m4a" }

        assertNull(workspace.allocate(StatusAudioSegment(1, 1, 0L, 1_000L, "part"), AudioContainer.M4A))
    }
}

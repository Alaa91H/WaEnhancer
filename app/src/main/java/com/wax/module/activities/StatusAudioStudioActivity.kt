package com.wax.module.activities

import android.net.Uri
import android.os.Bundle
import android.provider.OpenableColumns
import android.view.View
import android.widget.RadioGroup
import android.widget.SeekBar
import android.widget.TextView
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.widget.Toolbar
import com.google.android.material.button.MaterialButton
import com.google.android.material.materialswitch.MaterialSwitch
import com.wax.module.R
import com.wax.module.activities.base.BaseActivity
import com.wax.module.platform.TargetApp
import com.wax.module.status.AndroidStatusAudioSourceReader
import com.wax.module.status.DirectoryStatusAudioWorkspace
import com.wax.module.status.MuxerTrimAudioRenderer
import com.wax.module.status.StatusAudioCapabilityReader
import com.wax.module.status.StatusAudioOptions
import com.wax.module.status.StatusAudioOverflowAction
import com.wax.module.status.StatusAudioPlan
import com.wax.module.status.StatusAudioPreparation
import com.wax.module.status.StatusAudioPreparer
import com.wax.module.status.StatusAudioSource
import com.wax.module.status.StatusAudioSourceReaderSupport
import com.wax.module.status.StatusAudioStudio
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.util.Locale
import java.util.concurrent.atomic.AtomicBoolean

/**
 * The editor for a voice Status made from a file on this device.
 *
 * It shows the plan before anything is written, and it never claims more than it did: the header
 * says whether this client's limit was read or assumed, and a selection this device cannot
 * prepare ends in the reason rather than in a file.
 *
 * Everything slow happens off the UI thread — copying in, reading, planning and preparing — and
 * a preparation in flight is cancelled with the screen, so leaving it does not leave a thread
 * holding files.
 */
class StatusAudioStudioActivity : BaseActivity() {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)

    /** The prepared parts, kept for as long as the editor is open. */
    private var parts: DirectoryStatusAudioWorkspace? = null

    /** Set when the screen goes away, so a long split stops instead of finishing unwatched. */
    private val cancelled = AtomicBoolean(false)
    private var preparation: Job? = null

    private val reader by lazy { AndroidStatusAudioSourceReader(this) }

    /** The file the app copied in, and the description read from it. */
    private var workingPath: String? = null
    private var source: StatusAudioSource? = null
    private var plan: StatusAudioPlan? = null

    /** Set when the trim handle moves in whole seconds, so the screen can say so. */
    private var coarseTrim: String? = null

    /** Which target the screen is preparing for; the limit is read for that one only. */
    private val target: TargetApp by lazy {
        TargetApp.entries.firstOrNull { it.packageName == intent.getStringExtra(EXTRA_TARGET) } ?: TargetApp.WHATSAPP
    }

    private val pickAudio =
        registerForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
            uri?.let { onPicked(it) }
        }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_status_audio_studio)

        val toolbar = findViewById<Toolbar>(R.id.toolbar)
        setSupportActionBar(toolbar)
        supportActionBar?.setDisplayHomeAsUpEnabled(true)
        toolbar.setNavigationOnClickListener { finish() }

        findViewById<MaterialButton>(R.id.pickButton).setOnClickListener { pickAudio.launch(AUDIO_MIME_TYPES) }
        findViewById<MaterialButton>(R.id.prepareButton).setOnClickListener { prepare() }

        val replan = View.OnClickListener { onSelectionChanged() }
        findViewById<SeekBar>(R.id.trimStart).onChange(replan)
        findViewById<SeekBar>(R.id.trimEnd).onChange(replan)
        findViewById<SeekBar>(R.id.fadeIn).onChange(replan)
        findViewById<SeekBar>(R.id.fadeOut).onChange(replan)
        findViewById<SeekBar>(R.id.volume).onChange(replan)
        findViewById<MaterialSwitch>(R.id.normalize).setOnCheckedChangeListener { _, _ -> onSelectionChanged() }
        findViewById<MaterialSwitch>(R.id.stripMetadata).setOnCheckedChangeListener { _, _ -> onSelectionChanged() }
        findViewById<MaterialSwitch>(R.id.numberParts).setOnCheckedChangeListener { _, _ -> onSelectionChanged() }
        findViewById<RadioGroup>(R.id.overflow).setOnCheckedChangeListener { _, _ -> onSelectionChanged() }

        // The seek bar and the default must agree, or the screen opens showing one value and
        // meaning another.
        findViewById<SeekBar>(R.id.volume).progress = 100
    }

    override fun onDestroy() {
        cancelled.set(true)
        preparation?.cancel()
        releaseWorking()
        releasePrepared()
        scope.cancel()
        super.onDestroy()
    }

    // --- choosing ------------------------------------------------------------------

    private fun onPicked(uri: Uri) {
        val displayName = displayNameOf(uri)
        showResult(getString(R.string.status_audio_reading))
        setBusy(true)

        scope.launch {
            val read = withContext(Dispatchers.IO) { readSource(uri.toString(), displayName) }
            setBusy(false)
            if (read == null) {
                // The copy never happened, so there is nothing to clean up. The reason is the
                // file's own when the media framework refused it, because "this file cannot be
                // read" without saying why is not something the user can act on.
                val why = reader.unreadableReason
                Toast
                    .makeText(
                        this@StatusAudioStudioActivity,
                        if (why == null) {
                            getString(R.string.status_audio_unreadable)
                        } else {
                            getString(R.string.status_audio_unreadable_reason, why)
                        },
                        Toast.LENGTH_LONG,
                    ).show()
                return@launch
            }
            adopt(read.first, read.second)
        }
    }

    private fun readSource(
        uri: String,
        displayName: String,
    ): Pair<String, StatusAudioSource>? {
        releaseWorking()
        val copied = reader.copyIn(uri, displayName) ?: return null
        return copied to reader.describe(copied)
    }

    private fun adopt(
        path: String,
        described: StatusAudioSource,
    ) {
        releasePrepared()
        workingPath = path
        source = described
        findViewById<View>(R.id.emptyState).visibility = View.GONE
        findViewById<View>(R.id.sourcePanel).visibility = View.VISIBLE
        findViewById<TextView>(R.id.sourceName).text = described.fileName
        findViewById<TextView>(R.id.sourceFacts).text =
            getString(
                R.string.status_audio_source_format,
                described.container?.label ?: getString(R.string.status_audio_unreadable),
                clock(described.durationMillis),
            )
        describeLimit()

        val scale = described.durationMillis.coerceIn(1L, SEEK_MAX.toLong()).toInt()
        findViewById<SeekBar>(R.id.trimStart).max = scale
        findViewById<SeekBar>(R.id.trimEnd).max = scale
        findViewById<SeekBar>(R.id.trimEnd).progress = scale
        // The handle has a fixed number of steps, so a long file cannot be cut to the frame.
        // Saying so is better than letting the user discover it by aiming somewhere precise
        // and landing a second away from where they pointed.
        findViewById<TextView>(R.id.selectionFacts).visibility = View.VISIBLE
        coarseTrim =
            if (hasFineTrim(described.durationMillis)) {
                getString(R.string.status_audio_trim_coarse)
            } else {
                null
            }
        onSelectionChanged()
    }

    /** Says where the limit came from, because a fallback is not the same fact as a reading. */
    private fun describeLimit() {
        val capability = StatusAudioCapabilityReader.read(this, target)
        findViewById<TextView>(R.id.limitFacts).text =
            if (capability.limitResolved) {
                getString(R.string.status_audio_limit_known, clock(capability.maxVoiceStatusMillis))
            } else {
                getString(
                    R.string.status_audio_limit_fallback,
                    clock(StatusAudioCapabilityReader.FALLBACK_MILLIS),
                )
            }
    }

    // --- planning ------------------------------------------------------------------

    private fun currentOptions(): StatusAudioOptions {
        val start = findViewById<SeekBar>(R.id.trimStart).progress.toLong()
        val end = findViewById<SeekBar>(R.id.trimEnd).progress.toLong()
        val selection = (end - start).coerceAtLeast(0L)
        return StatusAudioOptions(
            trimStartMillis = start,
            trimEndMillis = end,
            fadeInMillis = fadeFor(findViewById<SeekBar>(R.id.fadeIn).progress, selection),
            fadeOutMillis = fadeFor(findViewById<SeekBar>(R.id.fadeOut).progress, selection),
            volumePercent = findViewById<SeekBar>(R.id.volume).progress,
            normalize = findViewById<MaterialSwitch>(R.id.normalize).isChecked,
            addNumbering = findViewById<MaterialSwitch>(R.id.numberParts).isChecked,
            stripMetadata = findViewById<MaterialSwitch>(R.id.stripMetadata).isChecked,
            overflowAction = selectedOverflow(),
        )
    }

    /**
     * A fade covers at most a quarter of the selection.
     *
     * A fade longer than the clip ramps up and down across the same samples and cancels itself
     * out. The planner warns when that happens, but a control that can produce it is a control
     * that can mislead, so it is not reachable from here.
     */
    private fun fadeFor(
        percent: Int,
        selectionMillis: Long,
    ): Long = selectionMillis.coerceAtLeast(0L) * percent.coerceIn(0, MAX_FADE_PERCENT) / 100L

    private fun selectedOverflow(): StatusAudioOverflowAction =
        when (findViewById<RadioGroup>(R.id.overflow).checkedRadioButtonId) {
            R.id.overflowTrim -> StatusAudioOverflowAction.TRIM
            R.id.overflowSplit -> StatusAudioOverflowAction.AUTO_SPLIT
            R.id.overflowCancel -> StatusAudioOverflowAction.CANCEL
            else -> StatusAudioOverflowAction.ASK
        }

    private fun onSelectionChanged() {
        val described = source ?: return
        val options = currentOptions()
        val next = StatusAudioStudio.plan(described, options, StatusAudioCapabilityReader.read(this, target))
        plan = next

        findViewById<TextView>(R.id.volumeLabel).text =
            getString(R.string.status_audio_volume_format, options.volumePercent)
        findViewById<TextView>(R.id.selectionFacts).text =
            getString(
                R.string.status_audio_selection_format,
                clock((options.trimEndMillis ?: 0L) - options.trimStartMillis),
                clock(described.durationMillis),
            ) + (coarseTrim?.let { "\n$it" } ?: "")
        findViewById<TextView>(R.id.planText).text =
            buildString {
                append(next.toDisplayLine())
                next.warnings.forEach { append('\n').append(it) }
            }

        // The button is the honest statement of what can happen next, and it stays disabled
        // while a plan asks the user a question or refuses outright.
        findViewById<MaterialButton>(R.id.prepareButton).isEnabled =
            next.accepted && next.segments.isNotEmpty() && !preparationActive()
    }

    // --- preparing -----------------------------------------------------------------

    private fun prepare() {
        val described = source ?: return
        val current = plan ?: return
        val container = described.container ?: return
        val path = workingPath ?: return
        val options = currentOptions()

        // A second preparation starts from a clean directory: the names are positional, so
        // parts left by a previous run with a different length would otherwise linger.
        releasePrepared()
        val workspace =
            DirectoryStatusAudioWorkspace(File(cacheDir, PARTS_DIRECTORY)) {
                StatusAudioSourceReaderSupport.suffixFor(it)
            }
        parts = workspace

        showResult(getString(R.string.status_audio_preparing))
        setBusy(true)
        preparation =
            scope.launch {
                val outcome =
                    withContext(Dispatchers.IO) {
                        StatusAudioPreparer(MuxerTrimAudioRenderer(cancelled), workspace)
                            .prepare(current, container, options, path) { cancelled.get() }
                    }
                preparation = null
                setBusy(false)
                when (outcome) {
                    is StatusAudioPreparation.Prepared -> {
                        showResult(
                            getString(
                                R.string.status_audio_prepared,
                                outcome.parts.size,
                                clock(outcome.totalMillis),
                            ),
                        )
                    }

                    is StatusAudioPreparation.NotPrepared -> {
                        showResult(
                            getString(R.string.status_audio_cannot_prepare, outcome.rejection.explanation),
                        )
                    }
                }
                onSelectionChanged()
            }
    }

    private fun preparationActive(): Boolean = preparation?.isActive == true

    private fun setBusy(busy: Boolean) {
        findViewById<View>(R.id.progress).visibility = if (busy) View.VISIBLE else View.GONE
        findViewById<MaterialButton>(R.id.pickButton).isEnabled = !busy
        findViewById<MaterialButton>(R.id.prepareButton).isEnabled =
            !busy && plan?.accepted == true && plan?.segments?.isNotEmpty() == true
    }

    private fun showResult(message: String) {
        val result = findViewById<TextView>(R.id.resultText)
        result.text = message
        result.visibility = View.VISIBLE
    }

    // --- housekeeping ---------------------------------------------------------------

    /** The copy is app-owned; it goes when a different file replaces it. */
    private fun releaseWorking() {
        workingPath?.let { reader.discard(it) }
        workingPath = null
    }

    private fun displayNameOf(uri: Uri): String =
        runCatching {
            contentResolver
                .query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)
                ?.use { cursor ->
                    val index = cursor.getColumnIndex(OpenableColumns.DISPLAY_NAME)
                    if (index >= 0 && cursor.moveToFirst()) cursor.getString(index) else null
                }
        }.getOrNull() ?: uri.lastPathSegment.orEmpty()

    /** A duration in the one format this screen uses. */
    private fun clock(millis: Long): String {
        val seconds = (millis.coerceAtLeast(0L) + 500L) / 1_000L
        return String.format(Locale.getDefault(), "%d:%02d", seconds / 60L, seconds % 60L)
    }

    /** Keeps the two trim handles from crossing, so a selection is never negative. */
    private fun onSeekChanged(
        seekBar: SeekBar,
        progress: Int,
        fromUser: Boolean,
    ) {
        if (!fromUser) return
        val start = findViewById<SeekBar>(R.id.trimStart)
        val end = findViewById<SeekBar>(R.id.trimEnd)
        when (seekBar.id) {
            R.id.trimStart -> if (progress > end.progress) seekBar.progress = end.progress
            R.id.trimEnd -> if (progress < start.progress) seekBar.progress = start.progress
        }
        onSelectionChanged()
    }

    private fun SeekBar.onChange(change: View.OnClickListener) {
        setOnSeekBarChangeListener(
            object : SeekBar.OnSeekBarChangeListener {
                override fun onProgressChanged(
                    bar: SeekBar,
                    value: Int,
                    fromUser: Boolean,
                ) {
                    onSeekChanged(bar, value, fromUser)
                }

                override fun onStartTrackingTouch(bar: SeekBar) = Unit

                override fun onStopTrackingTouch(bar: SeekBar) {
                    change.onClick(bar)
                }
            },
        )
    }

    companion object {
        /** The package the screen prepares for; absent means WhatsApp. */
        const val EXTRA_TARGET = "wax.status.target"

        private const val PARTS_DIRECTORY = "wae-status-parts"
        private const val SEEK_MAX = 1_000
        private const val MAX_FADE_PERCENT = 25

        /**
         * The seek bar has a thousand steps, so a long file cannot be trimmed to the frame.
         *
         * The resolution is stated here rather than implied: below this the handle moves a whole
         * second at a time, and the editor says so rather than pretending to finer control.
         */
        const val MIN_SEEK_MILLIS = 1_000L

        private val AUDIO_MIME_TYPES =
            arrayOf(
                "audio/*",
                "application/ogg",
                "application/octet-stream",
            )

        /** Whether a file of [durationMillis] can be trimmed as precisely as the handle suggests. */
        @JvmStatic
        fun hasFineTrim(durationMillis: Long): Boolean = durationMillis > MIN_SEEK_MILLIS * SEEK_MAX
    }
}

package com.somethingobvious.decibel

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.media.AudioDeviceInfo
import android.media.AudioFormat
import android.media.AudioManager
import android.media.AudioRecord
import android.media.AudioRecordingConfiguration
import android.media.MediaRecorder
import android.os.CombinedVibration
import android.os.Process
import android.os.VibrationAttributes
import android.os.VibrationEffect
import android.os.VibratorManager
import androidx.annotation.StringRes
import com.somethingobvious.decibel.meter.LevelMeter
import com.somethingobvious.decibel.meter.Session
import com.somethingobvious.decibel.meter.Stats
import com.somethingobvious.decibel.meter.Tick
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlin.math.pow

// Android CDD 5.11 asks for 94 dB SPL at 1 kHz to read -36 dBFS on the unprocessed path.
private const val UNPROCESSED_FULL_SCALE_DB = 130.0

// CDD 5.4.2 asks for 90 dB SPL at 1 kHz to read an RMS of 2500 out of 32768 (-22.35 dBFS).
private const val VOICE_FULL_SCALE_DB = 112.35

// Phone MEMS mics start to distort around here, below digital full scale on the unprocessed path.
private const val MIC_OVERLOAD_DB = 120.0

private const val ALERT_BUZZ_MILLIS = 200L
private const val SAMPLE_RATE = 48_000

/** Which mic path is in use, and the level that digital full scale stands for on it. */
enum class MicSource(
    val audioSource: Int,
    val fullScaleDb: Double,
    @param:StringRes val label: Int,
    @param:StringRes val defaultCal: Int,
) {
    UNPROCESSED(
        MediaRecorder.AudioSource.UNPROCESSED,
        UNPROCESSED_FULL_SCALE_DB,
        R.string.source_unprocessed,
        R.string.default_cal_unprocessed,
    ),
    VOICE_RECOGNITION(
        MediaRecorder.AudioSource.VOICE_RECOGNITION,
        VOICE_FULL_SCALE_DB,
        R.string.source_voice,
        R.string.default_cal_voice,
    ),
    ;

    /** The sample size where the mic or the converter stops telling the truth. */
    val clipLevel: Double get() = minOf(LevelMeter.CLIP_LEVEL, 10.0.pow((MIC_OVERLOAD_DB - fullScaleDb) / 20.0))
}

enum class Problem { NONE, NO_PERMISSION, MIC_FAILED, OTHER_MIC, LOG_FULL }

data class MeterState(
    val isRunning: Boolean = false,
    val session: Session = Session(),
    val stats: Stats = Stats.EMPTY,
    val latest: Tick? = null,
    val source: MicSource? = null,
    /** Another app holds the mic and Android is feeding us silence. */
    val isSilenced: Boolean = false,
    val problem: Problem = Problem.NONE,
    /** ALERT stays lit after the level drops, until it is acknowledged. */
    val isAlertLatched: Boolean = false,
    /** OVER stays lit after the input stops clipping, until it is acknowledged. */
    val isOverLatched: Boolean = false,
    /** GAP stays lit once silenced input has been left out of the log, until it is acknowledged. */
    val isGapLatched: Boolean = false,
)

/**
 * The meter itself: one capture thread feeding a [Session], published as [MeterState] each tick.
 *
 * It lives at process scope so the screen and the background service share one session.
 */
object Meter {
    private const val MILLIS_PER_TICK = 1_000L / LevelMeter.TICKS_PER_SECOND
    private const val REOPEN_TRIES = 3
    private const val REOPEN_DELAY_MILLIS = 500L

    private val flow = MutableStateFlow(MeterState())
    val state: StateFlow<MeterState> = flow.asStateFlow()

    // Ticks, resets and run starts all change the session and its stats together, so they take turns.
    private val lock = Any()

    @Volatile
    private var worker: Thread? = null
    private var closing: Thread? = null
    private val alerts = AlertWatch()

    /** True from the moment measuring is asked for, before the mic has actually opened. */
    val isOn: Boolean get() = worker != null

    fun start(context: Context) {
        synchronized(lock) {
            if (worker != null) return
            val app = context.applicationContext
            val previous = closing
            // A new run starts clean, so the last run's failure can't stop the service that is starting with it.
            flow.update { now -> now.copy(problem = Problem.NONE) }
            // The new thread waits for the old one to let go of the mic, so the main thread never does.
            // It's set as the worker before it starts, or its first check could find it isn't one yet.
            val thread =
                Thread({
                    previous?.join()
                    capture(app)
                }, "meter")
            worker = thread
            thread.start()
        }
    }

    fun stop() {
        synchronized(lock) {
            worker?.let { closing = it }
            worker = null
            flow.update { now -> now.copy(isRunning = false, isSilenced = false) }
        }
    }

    /** Clears the session and hands back what it was, so that exact session can be restored. */
    fun reset(): MeterState =
        synchronized(lock) {
            val old = flow.value
            val fresh = Session()
            if (old.isRunning) fresh.begin(System.currentTimeMillis())
            // A fresh session gets its own first alert, even if the room is still loud.
            alerts.rearm()
            flow.update { now ->
                now.copy(
                    session = fresh,
                    stats = fresh.stats,
                    isOverLatched = false,
                    isAlertLatched = false,
                    isGapLatched = false,
                    problem = if (now.problem == Problem.LOG_FULL) Problem.NONE else now.problem,
                )
            }
            old
        }

    /** Puts back a session that [reset] cleared, warnings and all. */
    fun restore(old: MeterState) {
        synchronized(lock) {
            flow.update { now ->
                if (now.isRunning) old.session.begin(System.currentTimeMillis())
                now.copy(
                    session = old.session,
                    stats = old.session.stats,
                    isOverLatched = old.isOverLatched,
                    isAlertLatched = old.isAlertLatched,
                    isGapLatched = old.isGapLatched,
                    problem = if (old.problem == Problem.LOG_FULL) Problem.LOG_FULL else now.problem,
                )
            }
        }
    }

    /** Clears the warnings [shown] had lit, and leaves any that lit since, which nobody has seen yet. */
    fun acknowledge(shown: MeterState) {
        flow.update { now ->
            now.copy(
                isAlertLatched = now.isAlertLatched && !shown.isAlertLatched,
                isOverLatched = now.isOverLatched && !shown.isOverLatched,
                isGapLatched = now.isGapLatched && !shown.isGapLatched,
            )
        }
    }

    fun noPermission() {
        flow.update { it.copy(problem = Problem.NO_PERMISSION, isRunning = false) }
    }

    /** Ends the second being logged, so a save right after a pause keeps its last fraction of a second. */
    fun closeSecond() {
        synchronized(lock) { flow.value.session.closeSecond() }
    }

    private fun capture(context: Context) {
        Process.setThreadPriority(Process.THREAD_PRIORITY_URGENT_AUDIO)
        val audio = context.getSystemService(AudioManager::class.java)
        val hasUnprocessed = audio.getProperty(AudioManager.PROPERTY_SUPPORT_AUDIO_SOURCE_UNPROCESSED) == "true"
        val source = if (hasUnprocessed) MicSource.UNPROCESSED else MicSource.VOICE_RECOGNITION
        var problem: Problem?
        var reopens = 0
        do {
            problem = if (worker === Thread.currentThread()) runOnce(context, audio, source) else null
            // A mic that dies mid-run, from an audio server restart or a new route, is opened again
            // rather than ending a log nobody is watching.
            val isLost = problem == Problem.MIC_FAILED && flow.value.isRunning && reopens++ < REOPEN_TRIES
            if (isLost) Thread.sleep(REOPEN_DELAY_MILLIS)
        } while (isLost)
        synchronized(lock) {
            if (worker === Thread.currentThread()) worker = null
            flow.update { now -> now.copy(isRunning = false, isSilenced = false, problem = problem ?: now.problem) }
        }
    }

    private fun runOnce(context: Context, audio: AudioManager, source: MicSource): Problem? {
        val record = openRecord(audio, source) ?: return refusal(context)
        val watch =
            object : AudioManager.AudioRecordingCallback() {
                override fun onRecordingConfigChanged(configs: List<AudioRecordingConfiguration>) {
                    val mine = configs.firstOrNull { it.clientAudioSessionId == record.audioSessionId }
                    flow.update { it.copy(isSilenced = mine?.isClientSilenced == true) }
                }
            }
        audio.registerAudioRecordingCallback(watch, null)
        return try {
            measure(context, record, source)
        } finally {
            audio.unregisterAudioRecordingCallback(watch)
            if (record.recordingState == AudioRecord.RECORDSTATE_RECORDING) record.stop()
            record.release()
        }
    }

    private fun measure(context: Context, record: AudioRecord, source: MicSource): Problem? {
        record.startRecording()
        val routed = record.routedDevice?.type
        // The calibration and full scale belong to the phone's own mic, so a headset mic would read wrong.
        val refused =
            when {
                record.recordingState != AudioRecord.RECORDSTATE_RECORDING -> Problem.MIC_FAILED
                routed != null && routed != AudioDeviceInfo.TYPE_BUILTIN_MIC -> Problem.OTHER_MIC
                else -> null
            }
        if (refused != null) return refused
        synchronized(lock) {
            if (worker === Thread.currentThread()) {
                flow.value.session.begin(System.currentTimeMillis() + CAPTURE_LEAD_TICKS * MILLIS_PER_TICK)
                // The last run's reading is old, so the readout stays blank until this run's first tick.
                flow.update { now ->
                    now.copy(isRunning = true, source = source, problem = Problem.NONE, latest = null)
                }
            }
        }
        val capture = Capture(record, source)
        var problem: Problem? = null
        while (worker === Thread.currentThread() && problem == null) {
            val isRead =
                capture.step { tick, isSilent ->
                    if (!addTick(context, tick, isSilent)) problem = Problem.LOG_FULL
                }
            if (!isRead) problem = Problem.MIC_FAILED
        }
        return problem
    }

    // False once the session can hold no more.
    private fun addTick(context: Context, tick: Tick, isSilent: Boolean): Boolean {
        val settings = SettingsStore.settings.value
        val fullScale = flow.value.source?.fullScaleDb ?: 0.0
        val levels = if (settings.response == Response.FAST) tick.fast else tick.slow
        val level = levels[settings.weighting.ordinal] + fullScale + settings.calibrationDb
        val hasCrossed = alerts.crosses(level = level, alert = settings.alertDb)
        if (hasCrossed) buzz(context)
        synchronized(lock) {
            val current = flow.value
            val isGap = isSilent || current.isSilenced
            // A tap can clip the converter, but it is left out of the figures, so it can't spoil them.
            val isOver = tick.isClipped && !tick.isMuted
            val isKept = current.session.add(tick = tick, isCounted = !tick.isMuted && !isGap, isGap = isGap)
            if (isKept) {
                flow.update { now ->
                    now.copy(
                        stats = now.session.stats,
                        // Nothing was heard during a gap, so there is no level to show for it.
                        latest = tick.takeUnless { isGap },
                        isAlertLatched = now.isAlertLatched || hasCrossed,
                        isOverLatched = now.isOverLatched || isOver,
                        isGapLatched = now.isGapLatched || isGap,
                    )
                }
            }
            return isKept
        }
    }
}

// Why the mic wouldn't open: no permission, or something else holding it.
private fun refusal(context: Context): Problem {
    val permission = context.checkSelfPermission(Manifest.permission.RECORD_AUDIO)
    return if (permission == PackageManager.PERMISSION_GRANTED) Problem.MIC_FAILED else Problem.NO_PERMISSION
}

// Short, and counted like any other sound, since leaving it out would hide the moment that set it off.
// It goes as an alarm, which still buzzes with touch feedback off or in Battery Saver.
private fun buzz(context: Context) {
    val effect = VibrationEffect.createOneShot(ALERT_BUZZ_MILLIS, VibrationEffect.DEFAULT_AMPLITUDE)
    val alarm = VibrationAttributes.Builder().setUsage(VibrationAttributes.USAGE_ALARM).build()
    context.getSystemService(VibratorManager::class.java).vibrate(CombinedVibration.createParallel(effect), alarm)
}

private fun openRecord(audio: AudioManager, source: MicSource): AudioRecord? {
    val format =
        AudioFormat
            .Builder()
            .setEncoding(AudioFormat.ENCODING_PCM_FLOAT)
            .setSampleRate(SAMPLE_RATE)
            .setChannelMask(AudioFormat.CHANNEL_IN_MONO)
            .build()
    val minimum =
        AudioRecord.getMinBufferSize(
            SAMPLE_RATE,
            AudioFormat.CHANNEL_IN_MONO,
            AudioFormat.ENCODING_PCM_FLOAT,
        )
    // Half a second of slack, so a busy main thread never costs samples.
    val bytes = maxOf(minimum, SAMPLE_RATE / 2 * Float.SIZE_BYTES)
    val record =
        try {
            AudioRecord
                .Builder()
                .setAudioSource(source.audioSource)
                .setAudioFormat(format)
                .setBufferSizeInBytes(bytes)
                .build()
        } catch (_: SecurityException) {
            null
        } catch (_: UnsupportedOperationException) {
            null
        }
    if (record != null && record.state != AudioRecord.STATE_INITIALIZED) {
        record.release()
        return null
    }
    // Asked for by name, since a plugged-in headset would otherwise take over these sources.
    val inputs = audio.getDevices(AudioManager.GET_DEVICES_INPUTS)
    val builtIn = inputs.firstOrNull { it.type == AudioDeviceInfo.TYPE_BUILTIN_MIC }
    if (builtIn != null) record?.setPreferredDevice(builtIn)
    return record
}

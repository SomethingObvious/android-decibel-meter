package com.somethingobvious.decibel

import android.content.Context
import android.content.SharedPreferences
import androidx.annotation.StringRes
import androidx.core.content.edit
import com.somethingobvious.decibel.meter.Weighting
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlin.time.Duration
import kotlin.time.Duration.Companion.hours
import kotlin.time.Duration.Companion.minutes

enum class Response(
    val letter: String,
    @param:StringRes val word: Int,
    @param:StringRes val detail: Int,
    @param:StringRes val spokenSetting: Int,
    @param:StringRes val spokenMax: Int,
    @param:StringRes val spokenMin: Int,
) {
    FAST(
        letter = "F",
        word = R.string.response_fast,
        detail = R.string.detail_fast,
        spokenSetting = R.string.spoken_setting_fast,
        spokenMax = R.string.spoken_max_fast,
        spokenMin = R.string.spoken_min_fast,
    ),
    SLOW(
        letter = "S",
        word = R.string.response_slow,
        detail = R.string.detail_slow,
        spokenSetting = R.string.spoken_setting_slow,
        spokenMax = R.string.spoken_max_slow,
        spokenMin = R.string.spoken_min_slow,
    ),
}

enum class Window(
    length: Duration,
    @param:StringRes val label: Int,
) {
    MINUTE(1.minutes, R.string.window_minute),
    TEN_MINUTES(10.minutes, R.string.window_ten_minutes),
    HOUR(1.hours, R.string.window_hour),
    SESSION(Duration.ZERO, R.string.window_session),
    ;

    val seconds: Int = length.inWholeSeconds.toInt()
}

data class Settings(
    val weighting: Weighting = Weighting.A,
    val response: Response = Response.FAST,
    val window: Window = Window.MINUTE,
    /** Shifts every reading, on top of the source's own full-scale level. */
    val calibrationDb: Double = 0.0,
    /** Level that buzzes and lights ALERT, or null for no alert. */
    val alertDb: Double? = null,
    val isBackgroundOn: Boolean = false,
)

/** Settings kept in shared preferences, which is all a handful of values needs. */
object SettingsStore {
    private const val NAME = "settings"
    private const val KEY_WEIGHTING = "weighting"
    private const val KEY_RESPONSE = "response"
    private const val KEY_WINDOW = "window"
    private const val KEY_CALIBRATION = "calibration"
    private const val KEY_ALERT = "alert"
    private const val KEY_BACKGROUND = "background"
    private var prefs: SharedPreferences? = null
    private val flow = MutableStateFlow(Settings())
    val settings: StateFlow<Settings> = flow.asStateFlow()

    fun load(context: Context) {
        if (prefs != null) return
        val stored = context.applicationContext.getSharedPreferences(NAME, Context.MODE_PRIVATE)
        prefs = stored
        val defaults = Settings()
        flow.value =
            Settings(
                weighting = enumOr(stored.getString(KEY_WEIGHTING, null), defaults.weighting),
                response = enumOr(stored.getString(KEY_RESPONSE, null), defaults.response),
                window = enumOr(stored.getString(KEY_WINDOW, null), defaults.window),
                calibrationDb = stored.getFloat(KEY_CALIBRATION, 0f).toDouble(),
                alertDb = if (stored.contains(KEY_ALERT)) stored.getFloat(KEY_ALERT, 0f).toDouble() else null,
                isBackgroundOn = stored.getBoolean(KEY_BACKGROUND, false),
            )
    }

    fun update(change: (Settings) -> Settings) {
        val next = change(flow.value)
        flow.value = next
        checkNotNull(prefs) { "SettingsStore.load has not run" }.edit {
            putString(KEY_WEIGHTING, next.weighting.name)
            putString(KEY_RESPONSE, next.response.name)
            putString(KEY_WINDOW, next.window.name)
            putFloat(KEY_CALIBRATION, next.calibrationDb.toFloat())
            putBoolean(KEY_BACKGROUND, next.isBackgroundOn)
            if (next.alertDb == null) remove(KEY_ALERT) else putFloat(KEY_ALERT, next.alertDb.toFloat())
        }
    }

    private inline fun <reified T : Enum<T>> enumOr(name: String?, fallback: T): T =
        enumValues<T>().firstOrNull { it.name == name } ?: fallback
}

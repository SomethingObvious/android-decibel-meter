package com.somethingobvious.decibel

import android.Manifest
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Color
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.Settings.ACTION_APPLICATION_DETAILS_SETTINGS
import android.util.Log
import androidx.activity.ComponentActivity
import androidx.activity.SystemBarStyle
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.viewModels
import androidx.compose.material3.SnackbarDuration
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.SnackbarResult
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.somethingobvious.decibel.ui.DecibelTheme
import com.somethingobvious.decibel.ui.MeterControls
import com.somethingobvious.decibel.ui.MeterScreen
import kotlinx.coroutines.launch

private const val LOG_TAG = "Decibel"

/**
 * What the screen keeps when rotation, a theme switch or a window resize rebuilds the activity.
 * Without it a paused meter would start again and a held reading would let go.
 */
class ScreenModel : ViewModel() {
    var isFirstStart = true
    var isPausedByLeaving = false
    var held by mutableStateOf<MeterState?>(null)

    // Snackbars run here, so turning the phone mid-message doesn't drop an Undo.
    val snackbar = SnackbarHostState()
}

class MainActivity :
    ComponentActivity(),
    MeterControls {
    private val model: ScreenModel by viewModels()

    private val askMic =
        registerForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
            if (granted) {
                startMeasuring()
            } else {
                Meter.noPermission()
                val app = applicationContext
                tell(message = getString(R.string.mic_needed), action = getString(R.string.settings)) {
                    openAppSettings(app)
                }
            }
        }

    // The service runs either way. Without this the level just never shows in the notification shade.
    private val askNotifications = registerForActivityResult(ActivityResultContracts.RequestPermission()) { }

    override fun onCreate(savedInstanceState: Bundle?) {
        enableEdgeToEdge(SystemBarStyle.dark(Color.TRANSPARENT), SystemBarStyle.dark(Color.TRANSPARENT))
        super.onCreate(savedInstanceState)
        SettingsStore.load(this)
        setContent { DecibelTheme { MeterScreen(controls = this, snackbar = model.snackbar, model = model) } }
    }

    override fun onStart() {
        super.onStart()
        // Opening the app measures straight away, and coming back picks up where leaving paused it.
        if (model.isFirstStart || model.isPausedByLeaving) {
            model.isFirstStart = false
            model.isPausedByLeaving = false
            begin()
        }
    }

    override fun onStop() {
        super.onStop()
        if (!isChangingConfigurations && Meter.isOn && !SettingsStore.settings.value.isBackgroundOn) {
            Meter.stop()
            model.isPausedByLeaving = true
        }
    }

    private fun begin() {
        if (checkSelfPermission(Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) {
            askMic.launch(Manifest.permission.RECORD_AUDIO)
            return
        }
        startMeasuring()
    }

    private fun startMeasuring() {
        Meter.start(this)
        if (SettingsStore.settings.value.isBackgroundOn) MeterService.start(this)
    }

    override fun run() {
        // A second tap while the mic is still opening would stop it before it ever read anything.
        if (Meter.isOn && !Meter.state.value.isRunning) return
        if (Meter.isOn) {
            Meter.stop()
            MeterService.stop(this)
        } else {
            begin()
        }
    }

    override fun reset() {
        val undo = getString(R.string.undo)
        // A reset while the last one's Undo is still up keeps that Undo, so it brings back the session
        // from before the first tap rather than the moment between the two.
        val showing = model.snackbar.currentSnackbarData
        val isUndoUp = showing != null && showing.visuals.actionLabel == undo
        val cleared = Meter.reset()
        if (isUndoUp) return
        tell(message = getString(R.string.session_cleared), action = undo) { Meter.restore(cleared) }
    }

    override fun save() {
        // A paused log still holds the fraction of a second before the pause, so it is written out first.
        if (!Meter.state.value.isRunning) Meter.closeSecond()
        val state = Meter.state.value
        val offset = Reading.offset(state, SettingsStore.settings.value)
        val app = applicationContext
        model.viewModelScope.launch {
            try {
                val saved = Export.save(context = app, session = state.session, offset = offset)
                if (saved == null) {
                    tell(app.getString(R.string.nothing_to_save))
                } else {
                    tell(message = app.getString(R.string.saved), action = app.getString(R.string.share)) {
                        Export.share(app, saved)
                    }
                }
            } catch (e: Export.Failed) {
                Log.w(LOG_TAG, "Saving the log failed", e)
                tell(app.getString(R.string.save_failed))
            }
        }
    }

    override fun setBackground(on: Boolean) {
        SettingsStore.update { it.copy(isBackgroundOn = on) }
        if (!on) {
            MeterService.stop(this)
            return
        }
        // Before Android 13 notifications need no runtime permission.
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
            checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED
        ) {
            askNotifications.launch(Manifest.permission.POST_NOTIFICATIONS)
        }
        if (Meter.state.value.isRunning) MeterService.start(this)
    }

    // A new message replaces the one showing, so an Undo never waits behind an old Share. An Undo is
    // never replaced, since that would throw away the session it holds, so a message after it waits.
    private fun tell(message: String, action: String? = null, onAction: () -> Unit = {}) {
        val snackbar = model.snackbar
        val current = snackbar.currentSnackbarData
        if (current != null && current.visuals.actionLabel != getString(R.string.undo)) current.dismiss()
        model.viewModelScope.launch {
            val result =
                snackbar.showSnackbar(
                    message = message,
                    actionLabel = action,
                    duration = SnackbarDuration.Long,
                )
            if (result == SnackbarResult.ActionPerformed) onAction()
        }
    }

    private fun openAppSettings(app: Context) {
        val settings =
            Intent(ACTION_APPLICATION_DETAILS_SETTINGS, Uri.fromParts("package", app.packageName, null))
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        app.startActivity(settings)
    }
}

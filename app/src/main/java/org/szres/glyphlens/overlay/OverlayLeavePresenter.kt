package org.szres.glyphlens.overlay

import android.content.Context
import android.content.Intent
import org.szres.glyphlens.capture.CaptureService
import org.szres.glyphlens.settings.AppPreferences
import org.szres.glyphlens.settings.RecognitionMode

object OverlayLeavePresenter {
    @JvmStatic
    fun onUserLeave(context: Context): Boolean {
        val visible = CaptureService.isOverlayVisible
        val preferences = AppPreferences(context)
        val enabled = preferences.showsFloatingWindow
        val state = CaptureService.currentState
        if (state != CaptureService.SessionState.STANDBY ||
            !enabled || visible || preferences.recognitionMode == RecognitionMode.REALTIME
        ) return false
        context.startService(Intent(context, CaptureService::class.java).setAction(CaptureService.ACTION_SHOW_OVERLAY))
        return true
    }
}

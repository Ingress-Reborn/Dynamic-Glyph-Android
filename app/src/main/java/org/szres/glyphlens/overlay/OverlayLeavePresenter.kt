package org.szres.glyphlens.overlay

import android.content.Context
import android.content.Intent
import org.szres.glyphlens.capture.CaptureService
import org.szres.glyphlens.settings.AppPreferences

/** Re-arms every ordinary app-leave path; an explicit user Hide applies only until the next leave. */
object OverlayLeavePresenter {
    @JvmStatic
    fun onUserLeave(context: Context): Boolean {
        val visible = CaptureService.isOverlayVisible
        val enabled = AppPreferences(context).showsFloatingWindow
        val state = CaptureService.currentState
        if (state == CaptureService.SessionState.STOPPED || state == CaptureService.SessionState.STOPPING ||
            !enabled || visible
        ) return false
        context.startService(Intent(context, CaptureService::class.java).setAction(CaptureService.ACTION_SHOW_OVERLAY))
        return true
    }
}

package org.teslatempo.drive

import android.content.Context
import android.graphics.PixelFormat
import android.provider.Settings
import android.view.Gravity
import android.view.WindowManager

/** Two bounded, non-touchable windows, owned and removed together on the main thread. */
class CockpitOverlay(private val context: Context) : AutoCloseable {
    private val manager = context.getSystemService(WindowManager::class.java)
    internal val windows = mutableListOf<CockpitBlock>()
    val visible: Boolean get() = windows.isNotEmpty()
    fun show(preferences: CockpitPreferences) {
        if (visible) return
        if (!Settings.canDrawOverlays(context)) throw DriveFailure("Superposition non autorisée.")
        try {
            listOf(true,false).forEach { speed ->
                val view = CockpitBlock(context, speed, preferences.size)
                val params = WindowManager.LayoutParams(WindowManager.LayoutParams.WRAP_CONTENT, WindowManager.LayoutParams.WRAP_CONTENT,
                    WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
                    WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE,
                    PixelFormat.TRANSLUCENT)
                params.gravity = Gravity.TOP or if (speed) Gravity.START else Gravity.END
                params.x = (12 * context.resources.displayMetrics.density).toInt()
                params.y = (preferences.offset * context.resources.displayMetrics.density).toInt()
                params.alpha = preferences.opacity.coerceIn(0.3f,0.75f)
                manager.addView(view, params); windows += view
            }
        } catch (e: Exception) { close(); throw e }
    }
    fun render(text: CockpitText) { windows.forEach { it.render(text) } }
    override fun close() {
        windows.forEach { runCatching { manager.removeViewImmediate(it) } }
        windows.clear()
    }
}

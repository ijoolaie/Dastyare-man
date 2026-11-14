package ir.dastyar.bridge

import android.accessibilityservice.AccessibilityService
import android.os.Build
import android.view.accessibility.AccessibilityEvent
import android.util.Log

/** User-enabled accessibility bridge for the Android lock-screen global action. */
class ScreenControlAccessibilityService : AccessibilityService() {
    companion object {
        @Volatile private var active: ScreenControlAccessibilityService? = null
        fun isActive(): Boolean = active != null

        fun lockScreen(): Boolean {
            if (Build.VERSION.SDK_INT < Build.VERSION_CODES.P) return false
            return try { active?.performGlobalAction(AccessibilityService.GLOBAL_ACTION_LOCK_SCREEN) == true }
            catch (e: Exception) { Log.e("RayaScreen", "Lock screen failed", e); false }
        }
    }

    override fun onServiceConnected() {
        super.onServiceConnected()
        active = this
        Log.i("RayaScreen", "Screen control accessibility service connected")
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) = Unit
    override fun onInterrupt() = Unit

    override fun onDestroy() {
        if (active === this) active = null
        super.onDestroy()
    }
}

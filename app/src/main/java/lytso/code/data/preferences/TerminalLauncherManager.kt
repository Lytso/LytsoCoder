package lytso.code.data.preferences

import android.content.ComponentName
import android.content.Context
import android.content.pm.PackageManager
import android.util.Log

object TerminalLauncherManager {
    const val STYLE_LYTSOTERM = "LYTSOTERM"
    const val STYLE_TERMUX = "TERMUX"

    private const val TAG = "TerminalLauncherMgr"

    fun getLytsoTermComponent(context: Context): ComponentName =
        ComponentName(context.packageName, "lytso.code.LytsoTermAlias")

    fun getClassicTermComponent(context: Context): ComponentName =
        ComponentName(context.packageName, "lytso.code.ClassicTermAlias")

    fun getDirectActivityComponent(context: Context): ComponentName =
        ComponentName(context.packageName, "lytso.code.TerminalActivity")

    /**
     * Inspects the Android PackageManager directly to determine if any standalone terminal launcher alias/activity is enabled.
     */
    fun isLauncherEnabled(context: Context): Boolean {
        return try {
            val pm = context.packageManager
            val lytsoTermState = pm.getComponentEnabledSetting(getLytsoTermComponent(context))
            val classicState = pm.getComponentEnabledSetting(getClassicTermComponent(context))

            // both aliases are disabled in the manifest, so only an explicit "enabled" from Settings shows an icon
            // (TerminalActivity itself has no launcher entry, so it never adds one)
            val isLytsoTermActive = lytsoTermState == PackageManager.COMPONENT_ENABLED_STATE_ENABLED
            val isClassicActive = classicState == PackageManager.COMPONENT_ENABLED_STATE_ENABLED

            isLytsoTermActive || isClassicActive
        } catch (_: Exception) {
            false
        }
    }

    /**
     * Inspects the Android PackageManager directly to determine which style alias is active.
     */
    fun getLauncherStyle(context: Context): String {
        return try {
            val pm = context.packageManager
            val classicState = pm.getComponentEnabledSetting(getClassicTermComponent(context))
            if (classicState == PackageManager.COMPONENT_ENABLED_STATE_ENABLED) {
                STYLE_TERMUX
            } else {
                STYLE_LYTSOTERM
            }
        } catch (_: Exception) {
            STYLE_LYTSOTERM
        }
    }

    /**
     * Applies the launcher alias state based on user preference.
     *
     * @param context Application context
     * @param enabled Whether the standalone terminal icon should appear in launcher / app drawer
     * @param style Either [STYLE_LYTSOTERM] or [STYLE_TERMUX]
     */
    fun applyLauncherSetting(context: Context, enabled: Boolean, style: String) {
        val pm = context.packageManager
        val lytsoTerm = getLytsoTermComponent(context)
        val classic = getClassicTermComponent(context)
        val direct = getDirectActivityComponent(context)

        try {
            // Ensure direct activity is not acting as a duplicate launcher if aliases exist
            try {
                val directState = pm.getComponentEnabledSetting(direct)
                if (directState != PackageManager.COMPONENT_ENABLED_STATE_DISABLED) {
                    pm.setComponentEnabledSetting(
                        direct,
                        PackageManager.COMPONENT_ENABLED_STATE_DISABLED,
                        PackageManager.DONT_KILL_APP
                    )
                }
            } catch (_: Exception) {}

            if (!enabled) {
                // Disable both aliases
                setComponentSafe(pm, lytsoTerm, PackageManager.COMPONENT_ENABLED_STATE_DISABLED)
                setComponentSafe(pm, classic, PackageManager.COMPONENT_ENABLED_STATE_DISABLED)
                Log.d(TAG, "Standalone Terminal Launcher icon disabled")
            } else {
                if (style == STYLE_TERMUX) {
                    setComponentSafe(pm, lytsoTerm, PackageManager.COMPONENT_ENABLED_STATE_DISABLED)
                    setComponentSafe(pm, classic, PackageManager.COMPONENT_ENABLED_STATE_ENABLED)
                    Log.d(TAG, "Enabled Termux Classic launcher alias (Termux)")
                } else {
                    setComponentSafe(pm, classic, PackageManager.COMPONENT_ENABLED_STATE_DISABLED)
                    setComponentSafe(pm, lytsoTerm, PackageManager.COMPONENT_ENABLED_STATE_ENABLED)
                    Log.d(TAG, "Enabled LytsoTerm launcher alias (LytsoTerm)")
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error updating launcher component settings: ${e.message}", e)
        }
    }

    private fun setComponentSafe(pm: PackageManager, component: ComponentName, state: Int) {
        try {
            pm.setComponentEnabledSetting(component, state, PackageManager.DONT_KILL_APP)
        } catch (e: Exception) {
            Log.w(TAG, "Component ${component.shortClassName} update warning: ${e.message}")
        }
    }
}

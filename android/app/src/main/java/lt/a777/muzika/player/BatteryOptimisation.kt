package lt.a777.muzika.player

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.PowerManager
import android.provider.Settings

/**
 * Whether Android will let playback carry on once the app is in the background.
 *
 * A media foreground service is supposed to be enough, and on stock Android it
 * is. Several OEM builds - Nothing OS, and most Chinese skins - run their own
 * killer on top, and stop a backgrounded app regardless. The symptom is exactly
 * the one a user reports as "it only plays again after I restart the app".
 *
 * There is no API to opt out of that; the only supported route is the user
 * exempting the app by hand. So rather than quietly playing worse on those
 * devices, the app reports the state and opens the right screen.
 */
object BatteryOptimisation {

    /** True when Android has been told to leave this app alone. */
    fun isExempt(context: Context): Boolean {
        val power = context.getSystemService(Context.POWER_SERVICE) as? PowerManager
            ?: return true  // nothing to ask: assume the best rather than nag
        return runCatching { power.isIgnoringBatteryOptimizations(context.packageName) }
            .getOrDefault(true)
    }

    /**
     * Ask for the exemption.
     *
     * The direct dialog is the good outcome. Some builds refuse to resolve it,
     * so the whole-list settings screen is the fallback - one more tap, but it
     * exists everywhere.
     */
    fun request(context: Context): Boolean {
        val candidates = listOf(
            Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS,
                   Uri.parse("package:${context.packageName}")),
            Intent(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS),
        )
        for (intent in candidates) {
            val opened = runCatching {
                context.startActivity(intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
                true
            }.getOrDefault(false)
            if (opened) return true
        }
        return false
    }
}

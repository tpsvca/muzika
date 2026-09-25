package lt.a777.muzika

import android.content.Context
import android.os.PowerManager
import androidx.test.core.app.ApplicationProvider
import lt.a777.muzika.player.BatteryOptimisation
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf

/**
 * The battery exemption is the one lever the app has on OEM builds that stop
 * a backgrounded media service anyway. Getting the reported state backwards
 * would either nag a user who is already fine or stay silent on a phone that
 * is killing their music, so the reading is pinned down here.
 */
@RunWith(RobolectricTestRunner::class)
class BatteryOptimisationTest {

    private val context: Context = ApplicationProvider.getApplicationContext()

    private fun setExempt(value: Boolean) {
        val power = context.getSystemService(Context.POWER_SERVICE) as PowerManager
        shadowOf(power).setIgnoringBatteryOptimizations(context.packageName, value)
    }

    @Test
    fun `reports the exemption when Android has granted it`() {
        setExempt(true)
        assertTrue(BatteryOptimisation.isExempt(context))
    }

    @Test
    fun `reports the restriction when it has not`() {
        setExempt(false)
        assertEquals(false, BatteryOptimisation.isExempt(context))
    }

    @Test
    fun `asking for the exemption opens a settings screen`() {
        setExempt(false)
        assertTrue(BatteryOptimisation.request(context))

        val started = shadowOf(context as android.app.Application).nextStartedActivity
        assertEquals(
            android.provider.Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS,
            started.action,
        )
        assertEquals("package:${context.packageName}", started.data.toString())
    }
}

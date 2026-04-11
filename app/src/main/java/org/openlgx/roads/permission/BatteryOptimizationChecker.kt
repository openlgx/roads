package org.openlgx.roads.permission

import android.content.Context
import android.os.Build
import android.os.PowerManager
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class BatteryOptimizationChecker
@Inject
constructor(
    @ApplicationContext private val context: Context,
) {
    /**
     * True when the app is exempt from battery optimization (recommended for reliable passive
     * detection on OEMs like Samsung).
     */
    fun isExempt(): Boolean {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.M) return true
        val pm = context.getSystemService(Context.POWER_SERVICE) as PowerManager
        return pm.isIgnoringBatteryOptimizations(context.packageName)
    }
}

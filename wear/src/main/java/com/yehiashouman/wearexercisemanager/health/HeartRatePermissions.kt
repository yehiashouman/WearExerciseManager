package com.yehiashouman.wearexercisemanager.health

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import androidx.core.content.ContextCompat

/**
 * Heart-rate access changed platform side: `BODY_SENSORS` was replaced by the granular health
 * permission `android.permission.health.READ_HEART_RATE`. Apps targeting Android 16 (API 36) can no
 * longer be granted `BODY_SENSORS`, so both permissions are requested and either one is accepted.
 */
object HeartRatePermissions {
    /** Declared in the manifest as well; kept as a literal because the constant needs API 36. */
    const val READ_HEART_RATE = "android.permission.health.READ_HEART_RATE"

    /** Permissions that should be requested on the current platform, most specific one first. */
    fun requestable(): List<String> =
        if (Build.VERSION.SDK_INT >= 36) listOf(READ_HEART_RATE)
        else listOf(Manifest.permission.BODY_SENSORS, READ_HEART_RATE)

    /** True when the watch may read heart-rate data through either permission model. */
    fun granted(context: Context): Boolean =
        isGranted(context, Manifest.permission.BODY_SENSORS) || isGranted(context, READ_HEART_RATE)

    private fun isGranted(context: Context, permission: String) =
        ContextCompat.checkSelfPermission(context, permission) == PackageManager.PERMISSION_GRANTED
}

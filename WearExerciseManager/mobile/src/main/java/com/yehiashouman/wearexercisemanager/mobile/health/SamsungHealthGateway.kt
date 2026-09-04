package com.yehiashouman.wearexercisemanager.mobile.health

import com.yehiashouman.wearexercisemanager.shared.ExerciseInterval
import com.yehiashouman.wearexercisemanager.shared.WorkoutSession

/**
 * Production Samsung Health integration boundary.
 *
 * Samsung Health Data SDK is distributed by Samsung as an AAR and exercise writes require
 * a Samsung Health Data SDK partnership/access code. Keep this interface compile-safe in the
 * public repository; after approval, place samsung-health-data-api.aar in mobile/libs and
 * implement this gateway with DataTypes.EXERCISE.
 */
interface SamsungHealthGateway {
    suspend fun sync(session: WorkoutSession): Result<Unit>
}

class PendingSamsungHealthGateway : SamsungHealthGateway {
    override suspend fun sync(session: WorkoutSession): Result<Unit> =
        Result.failure(IllegalStateException("Samsung Health partner SDK/access code not configured"))
}

/** Maps exact performed movement intervals, independent of Sequential/Circuit preset mode. */
fun WorkoutSession.intervalsForSamsungHealth(): List<ExerciseInterval> = intervals.filter { it.activeDurationSeconds > 0 }

package com.yehiashouman.wearexercisemanager

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.core.content.ContextCompat
import androidx.lifecycle.viewmodel.compose.viewModel
import com.yehiashouman.wearexercisemanager.ui.ExerciseManagerApp
import com.yehiashouman.wearexercisemanager.workout.WorkoutService

class MainActivity : ComponentActivity() {
    private val permissionLauncher = registerForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        requestWorkoutPermissions()
        setContent {
            val vm: AppViewModel = viewModel()
            ExerciseManagerApp(
                viewModel = vm,
                onStartWorkout = { presetId ->
                    val intent = Intent(this, WorkoutService::class.java).apply {
                        action = WorkoutService.ACTION_START
                        putExtra(WorkoutService.EXTRA_PRESET_ID, presetId)
                    }
                    ContextCompat.startForegroundService(this, intent)
                },
                onWorkoutAction = { action ->
                    startService(Intent(this, WorkoutService::class.java).apply { this.action = action })
                }
            )
        }
    }

    private fun requestWorkoutPermissions() {
        val permissions = buildList {
            add(Manifest.permission.BODY_SENSORS)
            add(Manifest.permission.RECORD_AUDIO)
            if (android.os.Build.VERSION.SDK_INT >= 33) add(Manifest.permission.POST_NOTIFICATIONS)
            if (android.os.Build.VERSION.SDK_INT >= 33) add(Manifest.permission.BODY_SENSORS_BACKGROUND)
        }.filter { ContextCompat.checkSelfPermission(this, it) != PackageManager.PERMISSION_GRANTED }
        if (permissions.isNotEmpty()) permissionLauncher.launch(permissions.toTypedArray())
    }
}

package com.yehiashouman.wearexercisemanager

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import com.yehiashouman.wearexercisemanager.data.AppRepository
import com.yehiashouman.wearexercisemanager.shared.*

class AppViewModel(application: Application) : AndroidViewModel(application) {
    val repository = AppRepository.getInstance(application)
    val store = repository.store

    fun saveExercise(exercise: ExerciseDefinition) = repository.saveExercise(exercise)
    fun deleteExercise(id: String) = repository.deleteExercise(id)
    fun savePreset(preset: WorkoutPreset) = repository.savePreset(preset)
    fun deletePreset(id: String) = repository.deletePreset(id)
    fun selectPreset(id: String) = repository.selectPreset(id)
    fun updateSettings(settings: AppSettings) = repository.updateSettings(settings)
    fun clearHistory() = repository.clearHistory()
}

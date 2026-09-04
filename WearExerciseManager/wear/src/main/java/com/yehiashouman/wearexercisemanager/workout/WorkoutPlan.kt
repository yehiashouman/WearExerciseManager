package com.yehiashouman.wearexercisemanager.workout

import com.yehiashouman.wearexercisemanager.shared.*

data class PlannedSet(
    val exercise: ExerciseDefinition,
    val set: ExerciseSet,
    val cycle: Int
)

object WorkoutPlanBuilder {
    fun build(preset: WorkoutPreset, exercises: List<ExerciseDefinition>): List<PlannedSet> {
        val ordered = preset.exerciseIds.mapNotNull { id -> exercises.firstOrNull { it.id == id } }
        if (ordered.isEmpty()) return emptyList()
        val cycles = preset.cycles.coerceAtLeast(1)

        return when (preset.style) {
            WorkoutStyle.SEQUENTIAL -> buildList {
                repeat(cycles) { cycleIndex ->
                    ordered.forEach { exercise ->
                        exercise.sets.forEach { set -> add(PlannedSet(exercise, set, cycleIndex + 1)) }
                    }
                }
            }
            WorkoutStyle.CIRCUIT -> buildList {
                repeat(cycles) { cycleIndex ->
                    ordered.forEach { exercise ->
                        if (exercise.sets.isNotEmpty()) {
                            val set = exercise.sets[cycleIndex % exercise.sets.size]
                            add(PlannedSet(exercise, set, cycleIndex + 1))
                        }
                    }
                }
            }
        }
    }
}

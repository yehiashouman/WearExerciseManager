# Settings audit

Every user-facing setting of the Wear OS app, where it is stored, where it is read and what it is
expected to change. All settings live in the single `AppSettings` object
(`shared/.../shared/Models.kt`), which is serialized as the `settings` field of the repository store
file `filesDir/exercise_manager.json` (`wear/.../data/AppRepository.kt`). Every write goes through
`AppRepository.updateSettings()`, which persists the whole store immediately, so every setting
survives an app or watch restart.

| Setting (UI label) | Stored key (`AppSettings`) | Applied in | Expected behaviour |
| --- | --- | --- | --- |
| Voice announcements | `voiceAnnouncements` | `VoiceCoach.speakInternal` | Off: no spoken guidance at all; the workout keeps running silently. |
| Spoken countdown | `spokenCountdown` | `WorkoutService.runTimer` | Off: the remaining seconds are never spoken. |
| Countdown starts at | `countdownStartSeconds` | `WorkoutService.runTimer` | The spoken countdown begins at this many remaining seconds (5–30). |
| Voice commands | `voiceCommands` | `WorkoutService.activateCommands` / `pauseWorkout` | Off: the speech recogniser is never started, the workout screen shows "Voice off". |
| Listen during exercise | `alwaysListening` | `WorkoutService.activateCommands` | Off: commands are only recognised during rest, transition and while paused, so the microphone is idle while training. |
| Training voice | `selectedVoiceName` | `VoiceCoach.applySettings` | Selects the text-to-speech voice used for all announcements. |
| Speech rate | `speechRate` | `VoiceCoach.applySettings` | Speed of all spoken guidance (0.5–2.0). |
| Default set duration | `defaultSetDurationSeconds` | `ExerciseEditor` | Duration pre-filled for a newly added set. |
| Duration step | `durationStepSeconds` | `ExerciseEditor` | Increment of the set-duration stepper. |
| Default rest | `defaultRestSeconds` | `ExerciseEditor` | Rest pre-filled for a newly added set. |
| Transition countdown | `transitionSeconds` | `WorkoutService.startTransition` | Countdown between two sets; `0` moves to the next set immediately. |
| Show heart rate | `showHeartRate` | `ActiveWorkoutScreen` (+ sensor start in `WorkoutService.startWorkout`) | On: the workout screen shows `♥ 112 bpm`, or `♥ -- bpm` while no reading is available. Off: the readout is hidden and the space is reclaimed. |
| Record heart rate | `recordHeartRate` | `WorkoutService` heart-rate callback | On: samples are stored in the session and the summary/history show the average. |
| Vibration | `vibration` | `WorkoutService.vibrate` (`health/Haptics.kt`) | On: a haptic pulse on set start, a double pulse on rest, a longer pattern on pause/resume and workout end. |
| Samsung Health sync | `samsungHealthSync` | Sent as `WearDataPaths.KEY_SAMSUNG_HEALTH_SYNC`, read in `WearSessionListenerService.syncToSamsungHealth` | On: the phone forwards a received session to the Samsung Health gateway. Off: the session is still stored and acknowledged, only the Samsung Health write is skipped. |
| Accent theme | `accentTheme` | `ExerciseManagerApp` → `accentColor` | Accent colour of every screen. |

## Heart-rate data path

1. **Permission** – `BODY_SENSORS` (up to Android 15) or `android.permission.health.READ_HEART_RATE`
   (Android 16+, where `BODY_SENSORS` can no longer be granted). Declared in the wear manifest and
   requested by `MainActivity.requestWorkoutPermissions()` via `HeartRatePermissions.requestable()`.
2. **Foreground service** – `WorkoutService.allowedForegroundServiceTypes()` only claims
   `FOREGROUND_SERVICE_TYPE_HEALTH` when one of those permissions is granted.
3. **Reading** – `HeartRateMonitor` registers a `SENSOR_DELAY_NORMAL` listener on
   `Sensor.TYPE_HEART_RATE`. It is started at the beginning of the workout whenever *either*
   "Show heart rate" or "Record heart rate" is enabled.
4. **Updating** – every sensor event updates `WorkoutUiState.heartRate`, so the workout screen shows
   live values; samples are appended to the session only when "Record heart rate" is enabled.
5. **Unavailable data** – a missing sensor, a missing permission, a zero reading or a
   `SENSOR_STATUS_NO_CONTACT` / `SENSOR_STATUS_UNRELIABLE` accuracy change reports `null`, which the
   UI renders as `♥ -- bpm` without changing the layout.
6. **Stopping** – `WorkoutService.finishWorkout()` and `onDestroy()` call `HeartRateMonitor.stop()`,
   which unregisters the listener exactly once.

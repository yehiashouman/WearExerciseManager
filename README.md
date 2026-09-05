# WearExerciseManager

Wear OS exercise manager with timed sets, circuits, voice coaching, heart-rate history, and a phone-side Samsung Health synchronization boundary.

## Repository

Recommended GitHub repository name: `WearExerciseManager`

## Modules

- `wear` — Wear OS app: exercise definitions, workouts, playback engine, TTS, voice commands, HR capture, history, settings.
- `mobile` — lightweight Android companion that receives completed workout intervals and owns Samsung Health integration.
- `shared` — serializable data models shared by watch and phone.

## Implemented workout behavior

- Exercise definitions with name, type/icon, sets, reps, free-text set labels, timed duration, and rest after each set.
- Defaults: 30 sec exercise, 30 sec rest, 15 sec duration step; arbitrary numeric entry remains possible.
- Workouts support Sequential and Circuit modes and configurable cycles.
- Sequential cycles repeat the complete ordered workout.
- Circuit cycles run one set per exercise per cycle; when an exercise has fewer sets than cycles its sets wrap from the beginning.
- Spoken exercise/set/reps announcement, final countdown, rest countdown, and 5-second transition.
- Voice commands: Next, Skip, Pause, Resume/Continue, Repeat/Again, Stop/Finish.
- No automatic rep detection and no required Done/Next touch control.
- Foreground workout service continues with screen off / UI closed.
- Interrupted service/app restart intentionally discards the active workout.
- Heart rate sampling continues while paused; paused samples are tagged and excluded from active averages.
- Completed workout announcement: `Workout complete.`
- History stores exact performed intervals and HR samples.
- Dark UI with selectable accent color.
- About screen links to https://www.yehiashouman.com.

## Watch to phone transfer

Both applications share one transfer state model (`SyncStatus`): `PENDING`, `SENDING`, `DELIVERED`, `FAILED`.

1. The watch stores the finished `WorkoutSession` with its permanent UUID and status `PENDING`.
2. The watch writes the payload to `/workout-session/<workoutId>` and stays `PENDING` until the phone answers.
3. The phone persists the session (insert or update by workout id, so a retry cannot duplicate it) and shows `Watch transfer: Received`.
4. The phone acknowledges the workout id on `/workout-session-ack/<workoutId>`.
5. Only that acknowledgement moves the watch to `Phone transfer: Delivered`.

Sessions the phone has not acknowledged are retried when the workout service starts, when the watch app is resumed and when History is opened.

## Samsung Health

Samsung Health Data SDK v1.1.0 targets Android smartphones. Writing exercise data requires Samsung Health Data SDK partnership approval and an access code. The proprietary SDK AAR is therefore intentionally not committed.

The watch transfers precise `WorkoutSession` interval data to the `mobile` module through the Wearable Data Layer. `SamsungHealthGateway` is the production integration boundary.

After Samsung approval:

1. Download the Samsung Health Data SDK from Samsung Developer.
2. Put `samsung-health-data-api.aar` in `mobile/libs/`.
3. Add `implementation(fileTree(mapOf("dir" to "libs", "include" to listOf("*.aar"))))` to `mobile/build.gradle.kts`.
4. Implement `SamsungHealthGateway` using `DataTypes.EXERCISE`.
5. Write actual performed exercise intervals, independent of whether the workout preset was Sequential or Circuit.
6. Register the mobile package/signature with Samsung and configure the issued access code.

Do not commit Samsung SDK binaries or access codes.

## Build

Open the root folder in the current Android Studio and sync Gradle, or run:

```bash
gradle :wear:assembleDebug :mobile:assembleDebug
```

GitHub Actions performs the same build and uploads both debug APKs as artifacts.

## Packages

- Watch: `com.yehiashouman.wearexercisemanager`
- Phone: `com.yehiashouman.wearexercisemanager` (same application ID as Wear, required for the paired app/Data Layer relationship)

These package names should be finalized before requesting Samsung Health partnership because Samsung registers package name and signing certificate.

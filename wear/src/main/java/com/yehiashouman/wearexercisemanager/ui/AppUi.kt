package com.yehiashouman.wearexercisemanager.ui

import android.content.Intent
import android.net.Uri
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicText
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.TextUnit
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.yehiashouman.wearexercisemanager.AppViewModel
import com.yehiashouman.wearexercisemanager.shared.*
import com.yehiashouman.wearexercisemanager.shared.R as SharedR
import com.yehiashouman.wearexercisemanager.sync.PhoneTransferCoordinator
import com.yehiashouman.wearexercisemanager.voice.VoiceCoach
import com.yehiashouman.wearexercisemanager.workout.WorkoutService
import com.yehiashouman.wearexercisemanager.workout.WorkoutStage
import com.yehiashouman.wearexercisemanager.workout.WorkoutUiState
import kotlinx.coroutines.flow.collectLatest
import java.text.SimpleDateFormat
import java.util.*

private enum class Screen { HOME, EXERCISES, EXERCISE_EDIT, WORKOUTS, WORKOUT_EDIT, HISTORY, SETTINGS, ABOUT, ACTIVE, COMPLETE }

/** Fraction of the available width used by the main buttons. */
private const val ButtonWidthFraction = 0.70f

/**
 * Fraction of the width that stays readable next to the top and bottom of a round display, where
 * the circular glass cuts the rectangular layout box.
 */
private const val SafeWidthFraction = 0.78f

/**
 * Width the three workout controls may occupy together. The row is rendered low on the display, so
 * it has to stay inside the chord of the circle at that height instead of the full layout width.
 */
private const val ControlRowWidthFraction = 0.70f

/**
 * Share of the display height kept free below the workout controls. At roughly 85% of the height
 * the chord of the circle is still wide enough for the full control row, so reserving the remaining
 * ~15% keeps the buttons clear of the lower arc.
 */
private const val WorkoutBottomReserve = 0.145f

/** Same idea for the summary screen, whose widest bottom element is the narrower "Done" button. */
private const val SummaryBottomReserve = 0.11f

/** Neutral background shared by all workout controls so they are visually identical. */
private val ControlBackground = Color(0xFF2E2E2E)

/**
 * Screen dependent metrics so the layout adapts to the actual round display instead of relying on
 * dimensions that only fit a single device.
 */
private data class WatchMetrics(
    val width: Dp,
    val height: Dp,
    val horizontalInset: Dp,
    val topInset: Dp,
    val bottomInset: Dp,
    val gap: Dp,
    val tightGap: Dp,
    val timer: TextUnit,
    val metricValue: TextUnit,
    val heading: TextUnit,
    val title: TextUnit,
    val body: TextUnit,
    val label: TextUnit,
    val button: TextUnit,
    val controlSize: Dp,
    val controlGap: Dp,
    val controlIcon: Dp
)

private fun watchMetrics(width: Dp, height: Dp): WatchMetrics {
    val shortest = if (width < height) width else height
    // 192dp is the classic round baseline; larger watches scale up moderately.
    val scale = (shortest.value / 192f).coerceIn(0.85f, 1.25f)
    fun sp(base: Float, min: Float, max: Float) = (base * scale).coerceIn(min, max).sp
    val controlGap = (7f * scale).coerceIn(6f, 10f)
    // The control row sits low on the display, where the circular glass already narrows the usable
    // width to roughly 70% of the layout box, so three equal controls plus their gaps must fit that.
    val control = ((width.value * ControlRowWidthFraction - 2f * controlGap) / 3f).coerceIn(38f, 52f)
    return WatchMetrics(
        width = width,
        height = height,
        // The curved edge eats roughly a tenth of the rectangular width on each side.
        horizontalInset = (width.value * 0.09f).coerceIn(12f, 26f).dp,
        topInset = (height.value * 0.07f).coerceIn(10f, 22f).dp,
        bottomInset = (height.value * 0.08f).coerceIn(12f, 26f).dp,
        gap = (5f * scale).coerceIn(3f, 8f).dp,
        // Screens that must not scroll use the tighter rhythm.
        tightGap = (2.5f * scale).coerceIn(2f, 4f).dp,
        timer = sp(26f, 23f, 31f),
        metricValue = sp(23f, 20f, 27f),
        heading = sp(18f, 16f, 22f),
        title = sp(14f, 13f, 17f),
        body = sp(13f, 12f, 16f),
        label = sp(10.5f, 10f, 13f),
        button = sp(13f, 12f, 15f),
        controlSize = control.dp,
        controlGap = controlGap.dp,
        controlIcon = (control * 0.40f).dp
    )
}

private val LocalWatchMetrics = staticCompositionLocalOf { watchMetrics(192.dp, 192.dp) }

/** Metrics of the display the app is currently drawn on. Only valid inside a composition. */
private val currentWatchMetrics: WatchMetrics
    @Composable get() = LocalWatchMetrics.current

@Composable
fun ExerciseManagerApp(
    viewModel: AppViewModel,
    onStartWorkout: (String) -> Unit,
    onWorkoutAction: (String) -> Unit
) {
    val store by viewModel.store.collectAsState()
    val workout by WorkoutService.state.collectAsState()
    var screen by remember { mutableStateOf(if (workout.running) Screen.ACTIVE else Screen.HOME) }
    var editingExercise by remember { mutableStateOf<ExerciseDefinition?>(null) }
    var editingWorkout by remember { mutableStateOf<WorkoutPreset?>(null) }
    val accent = accentColor(store.settings.accentTheme)
    val context = LocalContext.current

    LaunchedEffect(screen) {
        // Opening History is a natural moment to re-attempt the sessions the phone never confirmed.
        if (screen == Screen.HISTORY) PhoneTransferCoordinator.getInstance(context).retryPendingTransfers()
    }

    LaunchedEffect(workout.running, workout.completed, workout.stopped) {
        when {
            workout.running -> screen = Screen.ACTIVE
            workout.completed || workout.stopped -> screen = Screen.COMPLETE
            screen == Screen.ACTIVE -> screen = Screen.HOME
        }
    }

    AppFrame(accent) {
        when (screen) {
            Screen.HOME -> HomeScreen(
                selected = store.selectedPreset,
                presets = store.presets,
                accent = accent,
                onSelect = viewModel::selectPreset,
                onStart = { store.selectedPreset?.let { onStartWorkout(it.id) } },
                onExercises = { screen = Screen.EXERCISES },
                onWorkouts = { screen = Screen.WORKOUTS },
                onHistory = { screen = Screen.HISTORY },
                onSettings = { screen = Screen.SETTINGS }
            )
            Screen.EXERCISES -> ExerciseListScreen(
                store.exercises,
                accent,
                onBack = { screen = Screen.HOME },
                onAdd = { editingExercise = null; screen = Screen.EXERCISE_EDIT },
                onEdit = { editingExercise = it; screen = Screen.EXERCISE_EDIT },
                onDelete = viewModel::deleteExercise
            )
            Screen.EXERCISE_EDIT -> ExerciseEditor(
                original = editingExercise,
                accent = accent,
                defaults = store.settings,
                onSave = { viewModel.saveExercise(it); screen = Screen.EXERCISES },
                onCancel = { screen = Screen.EXERCISES }
            )
            Screen.WORKOUTS -> WorkoutListScreen(
                store.presets,
                accent,
                onBack = { screen = Screen.HOME },
                onAdd = { editingWorkout = null; screen = Screen.WORKOUT_EDIT },
                onEdit = { editingWorkout = it; screen = Screen.WORKOUT_EDIT },
                onDelete = viewModel::deletePreset
            )
            Screen.WORKOUT_EDIT -> WorkoutEditor(
                original = editingWorkout,
                exercises = store.exercises,
                accent = accent,
                onSave = { viewModel.savePreset(it); screen = Screen.WORKOUTS },
                onCancel = { screen = Screen.WORKOUTS }
            )
            Screen.HISTORY -> HistoryScreen(store.history, accent, { screen = Screen.HOME }, viewModel::clearHistory)
            Screen.SETTINGS -> SettingsScreen(store.settings, accent, viewModel::updateSettings, { screen = Screen.HOME }, { screen = Screen.ABOUT })
            Screen.ABOUT -> AboutScreen(accent) { screen = Screen.SETTINGS }
            Screen.ACTIVE -> ActiveWorkoutScreen(workout, store.settings.showHeartRate, accent, onWorkoutAction)
            Screen.COMPLETE -> WorkoutSummaryScreen(workout, accent) {
                WorkoutService.acknowledgeSummary()
                screen = Screen.HOME
            }
        }
    }
}

@Composable
private fun AppFrame(accent: Color, content: @Composable () -> Unit) {
    BoxWithConstraints(Modifier.fillMaxSize().background(Color.Black)) {
        val metrics = remember(maxWidth, maxHeight) { watchMetrics(maxWidth, maxHeight) }
        CompositionLocalProvider(LocalWatchMetrics provides metrics) {
            // Insets are applied per screen: a scrolling list needs more of them than a centred
            // layout that already keeps its content inside the safe width.
            Box(Modifier.fillMaxSize()) { content() }
        }
    }
}

@Composable
private fun HomeScreen(selected: WorkoutPreset?, presets: List<WorkoutPreset>, accent: Color, onSelect: (String)->Unit, onStart:()->Unit, onExercises:()->Unit, onWorkouts:()->Unit, onHistory:()->Unit, onSettings:()->Unit) {
    // Both headings are centred so the home screen stays symmetric on the circular display.
    AppColumn(horizontal = Alignment.CenterHorizontally) {
        Header("Exercise Manager")
        CenteredLabel("Select Workout")
        if (presets.isEmpty()) Muted("No workouts yet") else presets.forEach { p ->
            SmallButton((if (p.id == selected?.id) "● " else "○ ") + p.name, accent) { onSelect(p.id) }
        }
        PrimaryButton("▶  Start Workout", accent, enabled = selected != null, onClick = onStart)
        SmallButton("Exercises", accent, onExercises)
        SmallButton("Workouts", accent, onWorkouts)
        SmallButton("History", accent, onHistory)
        SmallButton("Settings", accent, onSettings)
    }
}

@Composable
private fun ExerciseListScreen(items: List<ExerciseDefinition>, accent: Color, onBack:()->Unit, onAdd:()->Unit, onEdit:(ExerciseDefinition)->Unit, onDelete:(String)->Unit) {
    AppColumn {
        Header("Exercises")
        items.forEach { ex ->
            Card(accent) {
                Body("${iconGlyph(ex.iconKey)}  ${ex.name}", bold = true)
                Muted("${ex.sets.size} sets")
                Row { MiniButton("Edit", accent) { onEdit(ex) }; Spacer(Modifier.width(5.dp)); MiniButton("Delete", accent) { onDelete(ex.id) } }
            }
        }
        PrimaryButton("+ Add Exercise", accent, onClick = onAdd)
        SmallButton("Back", accent, onBack)
    }
}

@Composable
private fun ExerciseEditor(original: ExerciseDefinition?, accent: Color, defaults: AppSettings, onSave:(ExerciseDefinition)->Unit, onCancel:()->Unit) {
    var name by remember(original?.id) { mutableStateOf(original?.name ?: "") }
    var typeKey by remember(original?.id) { mutableStateOf(original?.typeKey ?: "custom") }
    var iconKey by remember(original?.id) { mutableStateOf(original?.iconKey ?: "custom") }
    var sets by remember(original?.id) { mutableStateOf(original?.sets ?: listOf(ExerciseSet(label = "Set 1", durationSeconds = defaults.defaultSetDurationSeconds, restSeconds = defaults.defaultRestSeconds))) }
    val catalog = catalogItems()
    AppColumn {
        Header(if (original == null) "Add Exercise" else "Edit Exercise")
        Label("Exercise Name"); Input(name) { name = it }
        Label("Exercise Type")
        catalog.forEach { (key, title, icon) ->
            if (key == typeKey || key == "custom" || catalog.indexOfFirst { it.first == typeKey } < 0) {
                SmallButton((if (key == typeKey) "● " else "○ ") + title, accent) {
                    typeKey = key; iconKey = icon; if (name.isBlank() && key != "custom") name = title
                }
            }
        }
        Label("Icon")
        listOf("strength","legs","core","cardio","run","walk","cycle","custom").forEach { icon ->
            MiniButton((if (icon == iconKey) "● " else "") + "${iconGlyph(icon)} $icon", accent) { iconKey = icon }
        }
        Label("Sets")
        sets.forEachIndexed { index, set ->
            Card(accent) {
                Body("Set ${index + 1}", true)
                Label("Set Label"); Input(set.label) { v -> sets = sets.replace(index, set.copy(label = v)) }
                Label("Reps"); NumberInput(set.reps) { v -> sets = sets.replace(index, set.copy(reps = v.coerceAtLeast(0))) }
                Label("Duration")
                Stepper(set.durationSeconds, defaults.durationStepSeconds, accent) { v -> sets = sets.replace(index, set.copy(durationSeconds = v.coerceAtLeast(1))) }
                Label("Rest After Set")
                Stepper(set.restSeconds, 15, accent) { v -> sets = sets.replace(index, set.copy(restSeconds = v.coerceAtLeast(0))) }
                if (sets.size > 1) MiniButton("Remove Set", accent) { sets = sets.filterIndexed { i, _ -> i != index } }
            }
        }
        PrimaryButton("+ Add Set", accent) { sets = sets + ExerciseSet(label = "Set ${sets.size + 1}", durationSeconds = defaults.defaultSetDurationSeconds, restSeconds = defaults.defaultRestSeconds) }
        PrimaryButton("Save", accent, enabled = name.isNotBlank()) { onSave(ExerciseDefinition(id = original?.id ?: UUID.randomUUID().toString(), name = name.trim(), typeKey = typeKey, iconKey = iconKey, sets = sets)) }
        SmallButton("Cancel", accent, onCancel)
    }
}

@Composable
private fun WorkoutListScreen(items: List<WorkoutPreset>, accent: Color, onBack:()->Unit, onAdd:()->Unit, onEdit:(WorkoutPreset)->Unit, onDelete:(String)->Unit) {
    AppColumn {
        Header("Workouts")
        items.forEach { w -> Card(accent) {
            Body(w.name, true); Muted("${w.style.name.lowercase().replaceFirstChar { it.uppercase() }} • ${w.cycles} cycles")
            Row { MiniButton("Edit", accent) { onEdit(w) }; Spacer(Modifier.width(5.dp)); MiniButton("Delete", accent) { onDelete(w.id) } }
        } }
        PrimaryButton("+ Add Workout", accent, onClick = onAdd)
        SmallButton("Back", accent, onBack)
    }
}

@Composable
private fun WorkoutEditor(original: WorkoutPreset?, exercises: List<ExerciseDefinition>, accent: Color, onSave:(WorkoutPreset)->Unit, onCancel:()->Unit) {
    var name by remember(original?.id) { mutableStateOf(original?.name ?: "") }
    var ids by remember(original?.id) { mutableStateOf(original?.exerciseIds ?: emptyList()) }
    var style by remember(original?.id) { mutableStateOf(original?.style ?: WorkoutStyle.SEQUENTIAL) }
    var cycles by remember(original?.id) { mutableIntStateOf(original?.cycles ?: 1) }
    AppColumn {
        Header(if (original == null) "Add Workout" else "Edit Workout")
        Label("Workout Name"); Input(name) { name = it }
        Label("Exercises")
        exercises.forEach { ex -> SmallButton((if (ex.id in ids) "✓ " else "+ ") + ex.name, accent) { ids = if (ex.id in ids) ids - ex.id else ids + ex.id } }
        Label("Workout Style")
        SmallButton((if (style == WorkoutStyle.SEQUENTIAL) "● " else "○ ") + "Sequential", accent) { style = WorkoutStyle.SEQUENTIAL }
        SmallButton((if (style == WorkoutStyle.CIRCUIT) "● " else "○ ") + "Circuit", accent) { style = WorkoutStyle.CIRCUIT }
        Label("Number of Cycles"); Stepper(cycles, 1, accent) { cycles = it.coerceAtLeast(1) }
        PrimaryButton("Save", accent, enabled = name.isNotBlank() && ids.isNotEmpty()) { onSave(WorkoutPreset(id = original?.id ?: UUID.randomUUID().toString(), name = name.trim(), exerciseIds = ids, style = style, cycles = cycles)) }
        SmallButton("Cancel", accent, onCancel)
    }
}

@Composable
private fun ActiveWorkoutScreen(state: WorkoutUiState, showHeartRate: Boolean, accent: Color, action:(String)->Unit) {
    val m = currentWatchMetrics
    // Everything the athlete needs is visible at once: this screen never scrolls.
    FixedScreen(bottomFraction = WorkoutBottomReserve) {
        val stageToken = when (state.stage) {
            WorkoutStage.REST -> "REST"
            WorkoutStage.TRANSITION -> "GET READY"
            WorkoutStage.PAUSED -> "PAUSED"
            else -> "WORK"
        }
        val detail = if (state.stage == WorkoutStage.EXERCISE) buildString {
            if (state.setLabel.isNotBlank()) append(state.setLabel)
            if (state.reps > 0) { if (isNotEmpty()) append(" • "); append("${state.reps} reps") }
        } else ""
        val stageLine = if (detail.isBlank()) stageToken else "$stageToken • $detail"

        SafeText(
            state.exerciseName.ifBlank { state.presetName },
            TextStyle(Color.White, m.title, FontWeight.Bold, textAlign = TextAlign.Center)
        )
        SafeText(
            stageLine,
            TextStyle(
                if (state.paused) accent else if (state.stage == WorkoutStage.REST) Color(0xFF8AD0FF) else Color.LightGray,
                m.label,
                FontWeight.Medium,
                textAlign = TextAlign.Center
            )
        )
        BasicText(
            formatTime(state.remainingSeconds),
            style = TextStyle(accent, m.timer, FontWeight.Bold, textAlign = TextAlign.Center),
            maxLines = 1,
            modifier = Modifier.fillMaxWidth()
        )
        SafeText(
            "Cycle ${state.cycle}/${state.totalCycles} • Step ${state.currentStep}/${state.totalSteps}",
            TextStyle(Color.Gray, m.label, textAlign = TextAlign.Center)
        )
        // The status line is always rendered, so hiding the heart rate reclaims the horizontal space
        // without changing the height of the screen.
        val status = buildString {
            if (showHeartRate) append(heartRateText(state.heartRate))
            val voice = if (state.listening) "Listening" else "Voice off"
            if (isNotEmpty()) append("  •  ")
            append(voice)
        }
        SafeText(status, TextStyle(Color.Gray, m.label, textAlign = TextAlign.Center))
        // Identical controls: same size, same neutral background, same white icons, equal spacing.
        // The row wraps its content and is centred by the column; the three buttons already fit the
        // safe width because WatchMetrics derives controlSize from ControlRowWidthFraction.
        Row(
            horizontalArrangement = Arrangement.spacedBy(m.controlGap),
            verticalAlignment = Alignment.CenterVertically
        ) {
            if (state.paused) {
                ControlButton(ControlIcon.PLAY, "Resume") { action(WorkoutService.ACTION_RESUME) }
            } else {
                ControlButton(ControlIcon.PAUSE, "Pause") { action(WorkoutService.ACTION_PAUSE) }
            }
            ControlButton(ControlIcon.SKIP, "Skip to next exercise") { action(WorkoutService.ACTION_SKIP) }
            ControlButton(ControlIcon.STOP, "Stop workout") { action(WorkoutService.ACTION_STOP) }
        }
    }
}

/** Compact heart-rate readout; an unavailable measurement never changes the line length much. */
private fun heartRateText(bpm: Double?) = "♥ ${bpm?.toInt()?.toString() ?: "--"} bpm"

@Composable
private fun WorkoutSummaryScreen(state: WorkoutUiState, accent: Color, onDone:()->Unit) {
    val m = currentWatchMetrics
    // The whole summary has to be readable without scrolling, so the type scale stays modest.
    FixedScreen(bottomFraction = SummaryBottomReserve) {
        SafeText(
            if (state.completed) "Workout Complete" else "Workout Stopped",
            TextStyle(Color.White, m.heading, FontWeight.Bold, textAlign = TextAlign.Center)
        )
        SafeText(state.presetName, TextStyle(Color.White, m.body, FontWeight.Bold, textAlign = TextAlign.Center))
        BasicText(
            formatTime(state.summaryDurationSeconds),
            style = TextStyle(accent, m.metricValue, FontWeight.Bold, textAlign = TextAlign.Center),
            maxLines = 1,
            modifier = Modifier.fillMaxWidth()
        )
        SafeText("${state.summaryIntervals} intervals completed", TextStyle(Color.Gray, m.label, textAlign = TextAlign.Center))
        state.summaryAverageHeartRate?.let {
            SafeText("♥ Avg ${it.toInt()} bpm", TextStyle(Color.Gray, m.label, textAlign = TextAlign.Center))
        }
        PrimaryButtonSurface("Done", accent, true, Modifier.fillMaxWidth(0.55f), onDone)
    }
}

@Composable
private fun HistoryScreen(history: List<WorkoutSession>, accent: Color, onBack:()->Unit, onClear:()->Unit) {
    val fmt = remember { SimpleDateFormat("MMM d, HH:mm", Locale.getDefault()) }
    // History legitimately grows, so this screen keeps its scrolling behaviour.
    AppColumn {
        Header("History")
        if (history.isEmpty()) Muted("No workouts recorded")
        history.forEach { s -> Card(accent) {
            Body(s.presetName, true)
            Muted(fmt.format(Date(s.startedAtEpochMs)))
            val seconds = s.intervals.sumOf { it.activeDurationSeconds }
            Muted("${formatActiveDuration(seconds)} • ${s.intervals.size} intervals")
            val avg = s.heartRates.filterNot { it.paused }.map { it.bpm }.average()
            if (!avg.isNaN()) Muted("Avg HR ${avg.toInt()} bpm")
            Muted("Phone transfer: ${s.syncStatus.watchLabel()}")
        } }
        if (history.isNotEmpty()) SmallButton("Clear History", accent, onClear)
        SmallButton("Back", accent, onBack)
    }
}

@Composable
private fun SettingsScreen(settings: AppSettings, accent: Color, save:(AppSettings)->Unit, onBack:()->Unit, onAbout:()->Unit) {
    val context = LocalContext.current
    val coach = remember { VoiceCoach(context) }
    DisposableEffect(Unit) { onDispose { coach.shutdown() } }
    var current by remember(settings) { mutableStateOf(settings) }
    fun update(next: AppSettings) { current = next; save(next); coach.applySettings(next) }
    AppColumn {
        Header("Settings")
        Section("Voice")
        Toggle("Voice announcements", current.voiceAnnouncements, accent) { update(current.copy(voiceAnnouncements = it)) }
        Toggle("Spoken countdown", current.spokenCountdown, accent) { update(current.copy(spokenCountdown = it)) }
        Label("Countdown starts at"); Stepper(current.countdownStartSeconds, 5, accent) { update(current.copy(countdownStartSeconds = it.coerceIn(5, 30))) }
        Toggle("Voice commands", current.voiceCommands, accent) { update(current.copy(voiceCommands = it)) }
        Toggle("Listen during exercise", current.alwaysListening, accent) { update(current.copy(alwaysListening = it)) }
        Muted("Listening during exercise is on by default. With it off, commands are only recognised during rest, transitions and while paused, so the microphone is idle while you train.")
        Label("Training voice")
        val voices = coach.availableVoices().take(8)
        if (voices.isEmpty()) Muted("System English voice") else voices.forEach { v -> MiniButton((if (current.selectedVoiceName == v.name) "● " else "○ ") + v.name.take(26), accent) { update(current.copy(selectedVoiceName = v.name)) } }
        MiniButton("Preview Voice", accent) { coach.applySettings(current); coach.preview() }
        Label("Speech rate"); Stepper((current.speechRate * 10).toInt(), 1, accent, suffix = "") { update(current.copy(speechRate = (it.coerceIn(5, 20) / 10f))) }

        Section("Workout Defaults")
        Label("Default set duration"); Stepper(current.defaultSetDurationSeconds, current.durationStepSeconds, accent) { update(current.copy(defaultSetDurationSeconds = it.coerceAtLeast(1))) }
        Label("Duration step"); Stepper(current.durationStepSeconds, 5, accent) { update(current.copy(durationStepSeconds = it.coerceAtLeast(5))) }
        Label("Default rest"); Stepper(current.defaultRestSeconds, 15, accent) { update(current.copy(defaultRestSeconds = it.coerceAtLeast(0))) }
        Label("Transition countdown"); Stepper(current.transitionSeconds, 1, accent) { update(current.copy(transitionSeconds = it.coerceIn(0, 15))) }

        Section("Display & Health")
        Toggle("Show heart rate", current.showHeartRate, accent) { update(current.copy(showHeartRate = it)) }
        Toggle("Record heart rate", current.recordHeartRate, accent) { update(current.copy(recordHeartRate = it)) }
        Toggle("Samsung Health sync", current.samsungHealthSync, accent) { update(current.copy(samsungHealthSync = it)) }
        Toggle("Vibration", current.vibration, accent) { update(current.copy(vibration = it)) }
        Muted("Heart rate needs the sensor permission; the workout screen shows \"-- bpm\" while no reading is available.")

        Section("Appearance")
        listOf("system","blue","green","orange","red","purple","monochrome").forEach { theme -> MiniButton((if (current.accentTheme == theme) "● " else "○ ") + theme.replaceFirstChar { it.uppercase() }, accentColor(theme)) { update(current.copy(accentTheme = theme)) } }

        SmallButton("About", accent, onAbout)
        SmallButton("Back", accent, onBack)
    }
}

@Composable
private fun AboutScreen(accent: Color, onBack:()->Unit) {
    val context = LocalContext.current
    AppColumn(horizontal = Alignment.CenterHorizontally) {
        Header("About")
        AppLogo()
        Body("Exercise Manager", true)
        Muted("A Wear OS workout manager for timed exercises, sets, circuits, voice-guided workouts, heart-rate tracking, workout history, and Samsung Health synchronization.")
        PrimaryButton("About Author", accent) { context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse("https://www.yehiashouman.com"))) }
        Muted("Version 1.0.0")
        SmallButton("Back", accent, onBack)
    }
}

@Composable private fun AppColumn(horizontal: Alignment.Horizontal = Alignment.Start, content:@Composable ColumnScope.()->Unit) {
    val m = currentWatchMetrics
    Column(
        Modifier.fillMaxSize().padding(horizontal = m.horizontalInset).verticalScroll(rememberScrollState()),
        horizontalAlignment = horizontal,
        verticalArrangement = Arrangement.spacedBy(m.gap + 1.dp)
    ) {
        // Round displays clip the corners, so the first and last items need extra breathing room.
        Spacer(Modifier.height(m.topInset + m.gap))
        content()
        Spacer(Modifier.height(m.bottomInset + m.gap))
    }
}

/**
 * Container for the screens that must be fully visible at once. Content is centred vertically and
 * horizontally so nothing ends up under the curved edge, and it never scrolls.
 */
@Composable private fun FixedScreen(bottomFraction: Float = 0f, content:@Composable ColumnScope.()->Unit) {
    val m = currentWatchMetrics
    val density = LocalDensity.current
    // A larger bottom reserve pulls the content up so wide rows never reach the lower arc.
    val bottom = if (bottomFraction > 0f) m.height * bottomFraction else m.bottomInset * 0.8f
    CompositionLocalProvider(
        // The "no scrolling" guarantee has to hold with an enlarged system font size as well, so
        // the text on these screens never grows beyond its designed size.
        LocalDensity provides Density(density.density, density.fontScale.coerceAtMost(1f))
    ) {
        Column(
            Modifier.fillMaxSize()
                .padding(horizontal = m.horizontalInset / 2)
                .padding(top = m.topInset * 0.8f, bottom = bottom),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(m.tightGap, Alignment.CenterVertically),
            content = content
        )
    }
}

/** Single line of text kept inside the conservative safe width of a round display. */
@Composable private fun SafeText(text:String, style: TextStyle) = BasicText(
    text,
    style = style,
    maxLines = 1,
    overflow = TextOverflow.Ellipsis,
    modifier = Modifier.fillMaxWidth(SafeWidthFraction)
)

private enum class ControlIcon { PAUSE, PLAY, SKIP, STOP }

/**
 * Compact circular workout control. Pause, Skip and Stop deliberately share one visual style: same
 * diameter, same neutral background and white icons, so the row reads as a single control cluster.
 */
@Composable private fun ControlButton(icon: ControlIcon, description: String, onClick:()->Unit) {
    val m = currentWatchMetrics
    Box(
        Modifier
            .size(m.controlSize)
            .background(ControlBackground, CircleShape)
            .clickable(onClick = onClick)
            .semantics { contentDescription = description },
        contentAlignment = Alignment.Center
    ) {
        Canvas(Modifier.size(m.controlIcon)) {
            val color = Color.White
            val w = size.width
            val h = size.height
            when (icon) {
                ControlIcon.PAUSE -> {
                    val barWidth = w * 0.28f
                    drawRect(color, Offset(w * 0.08f, 0f), Size(barWidth, h))
                    drawRect(color, Offset(w - barWidth - w * 0.08f, 0f), Size(barWidth, h))
                }
                ControlIcon.PLAY -> drawPath(
                    Path().apply {
                        moveTo(w * 0.12f, 0f)
                        lineTo(w * 0.12f, h)
                        lineTo(w * 0.95f, h / 2f)
                        close()
                    },
                    color
                )
                ControlIcon.SKIP -> {
                    drawPath(
                        Path().apply {
                            moveTo(0f, 0f)
                            lineTo(0f, h)
                            lineTo(w * 0.68f, h / 2f)
                            close()
                        },
                        color
                    )
                    drawRect(color, Offset(w * 0.78f, 0f), Size(w * 0.22f, h))
                }
                ControlIcon.STOP -> drawRect(color, Offset(w * 0.08f, h * 0.08f), Size(w * 0.84f, h * 0.84f))
            }
        }
    }
}

/**
 * Application artwork above the app name. The width is measured from the word "About" so the logo
 * stays as small as the heading, and the square source vector keeps its aspect ratio.
 */
@Composable private fun AppLogo() {
    val m = currentWatchMetrics
    val measurer = rememberTextMeasurer()
    val headingStyle = TextStyle(fontSize = m.heading, fontWeight = FontWeight.Bold)
    val width = with(LocalDensity.current) {
        measurer.measure(AnnotatedString("About"), headingStyle).size.width.toDp()
    }
    Image(
        // The artwork lives in the shared module; with a non-transitive R class it has to be
        // referenced through that module's own R.
        painter = painterResource(SharedR.drawable.ic_launcher_foreground),
        contentDescription = "Exercise Manager logo",
        modifier = Modifier.padding(vertical = 4.dp).width(width).aspectRatio(1f)
    )
}

/** Centred variant of [Label] for headings that must be balanced on a round display. */
@Composable private fun CenteredLabel(text:String) = BasicText(
    text,
    style = TextStyle(Color.LightGray, currentWatchMetrics.label, FontWeight.Medium, textAlign = TextAlign.Center),
    modifier = Modifier.fillMaxWidth()
)

@Composable private fun Header(text:String) = BasicText(text, style = TextStyle(Color.White, currentWatchMetrics.heading, FontWeight.Bold, textAlign = TextAlign.Center), modifier = Modifier.fillMaxWidth().padding(vertical = 3.dp))
@Composable private fun Section(text:String) = BasicText(text, style = TextStyle(Color.White, currentWatchMetrics.body, FontWeight.Bold), modifier = Modifier.padding(top = 6.dp))
@Composable private fun Label(text:String) = BasicText(text, style = TextStyle(Color.LightGray, currentWatchMetrics.label))
@Composable private fun Body(text:String, bold:Boolean=false) = BasicText(text, style = TextStyle(Color.White, currentWatchMetrics.body, if (bold) FontWeight.Bold else FontWeight.Normal))
@Composable private fun Muted(text:String) = BasicText(text, style = TextStyle(Color.Gray, currentWatchMetrics.label))

@Composable private fun Input(value:String, onChange:(String)->Unit) { BasicTextField(value, onChange, textStyle = TextStyle(Color.White, currentWatchMetrics.body), singleLine = true, modifier = Modifier.fillMaxWidth().background(Color(0xFF222222), RoundedCornerShape(12.dp)).padding(9.dp)) }
@Composable private fun NumberInput(value:Int, onChange:(Int)->Unit) = Input(value.toString()) { onChange(it.filter(Char::isDigit).toIntOrNull() ?: 0) }

@Composable private fun Card(accent:Color, content:@Composable ColumnScope.()->Unit) = Column(Modifier.fillMaxWidth().background(Color(0xFF151515), RoundedCornerShape(14.dp)).padding(horizontal = 9.dp, vertical = 8.dp), verticalArrangement = Arrangement.spacedBy(3.dp), content = content)

/** Centers a button that only uses [ButtonWidthFraction] of the available width. */
@Composable private fun CenteredButtonRow(content:@Composable ()->Unit) = Box(Modifier.fillMaxWidth(), contentAlignment = Alignment.Center) { content() }

@Composable private fun PrimaryButtonSurface(text:String, accent:Color, enabled:Boolean, modifier: Modifier, onClick:()->Unit) = Box(
    modifier.background(if(enabled) accent else Color.DarkGray, RoundedCornerShape(22.dp)).clickable(enabled = enabled, onClick = onClick).padding(9.dp),
    contentAlignment = Alignment.Center
) { BasicText(text, style=TextStyle(Color.Black, currentWatchMetrics.button, FontWeight.Bold, textAlign = TextAlign.Center), maxLines = 1) }

@Composable private fun PrimaryButton(text:String, accent:Color, enabled:Boolean=true, onClick:()->Unit) = CenteredButtonRow {
    PrimaryButtonSurface(text, accent, enabled, Modifier.fillMaxWidth(ButtonWidthFraction), onClick)
}

@Composable private fun SmallButtonSurface(text:String, accent:Color, modifier: Modifier, onClick:()->Unit) = Box(
    modifier.background(Color(0xFF202020), RoundedCornerShape(18.dp)).clickable(onClick=onClick).padding(9.dp),
    contentAlignment = Alignment.Center
) { BasicText(text, style=TextStyle(if (accent == Color.White) Color.White else accent, currentWatchMetrics.button, FontWeight.Medium, textAlign = TextAlign.Center), maxLines = 2, overflow = TextOverflow.Ellipsis) }

@Composable private fun SmallButton(text:String, accent:Color, onClick:()->Unit) = CenteredButtonRow {
    SmallButtonSurface(text, accent, Modifier.fillMaxWidth(ButtonWidthFraction), onClick)
}

@Composable private fun MiniButton(text:String, accent:Color, onClick:()->Unit) = Box(
    Modifier.background(Color(0xFF242424), RoundedCornerShape(14.dp)).clickable(onClick=onClick).padding(horizontal=6.dp, vertical=6.dp),
    contentAlignment = Alignment.Center
) { BasicText(text, style=TextStyle(accent, currentWatchMetrics.label, FontWeight.Medium, textAlign = TextAlign.Center), maxLines = 2, overflow = TextOverflow.Ellipsis) }
@Composable private fun Toggle(label:String, value:Boolean, accent:Color, onChange:(Boolean)->Unit) = SmallButton((if(value) "● " else "○ ") + label, accent) { onChange(!value) }

@Composable private fun Stepper(value:Int, step:Int, accent:Color, suffix:String=" sec", onChange:(Int)->Unit) {
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
        MiniButton("−", accent) { onChange(value-step) }
        Input(value.toString()) { onChange(it.filter(Char::isDigit).toIntOrNull() ?: value) }
        BasicText("$value$suffix", style=TextStyle(Color.White, currentWatchMetrics.label, FontWeight.Bold), maxLines = 1)
        MiniButton("+", accent) { onChange(value+step) }
    }
}

private fun <T> List<T>.replace(index:Int, item:T) = toMutableList().also { it[index]=item }.toList()
private fun formatTime(seconds:Int) = "%02d:%02d".format(seconds / 60, seconds % 60)

/** Sub-minute workouts show seconds so a short session is never reported as "0 min active". */
private fun formatActiveDuration(seconds:Int) =
    if (seconds < 60) "$seconds sec active" else "${seconds / 60} min active"
private fun accentColor(name:String) = when(name) { "green"->Color(0xFF55DD88); "orange"->Color(0xFFFFA64D); "red"->Color(0xFFFF6666); "purple"->Color(0xFFBB86FC); "monochrome"->Color.White; else->Color(0xFF63B3FF) }
private fun iconGlyph(key:String) = when(key) { "strength"->"◆"; "legs"->"▲"; "core"->"●"; "cardio"->"♥"; "run"->"➤"; "walk"->"•"; "cycle"->"○"; else->"◇" }
private fun catalogItems() = listOf(
    Triple("bicep_curl","Bicep Curl","strength"), Triple("hammer_curl","Hammer Curl","strength"), Triple("shoulder_press","Shoulder Press","strength"), Triple("bench_press","Bench Press","strength"), Triple("push_up","Push-up","strength"), Triple("squat","Squat","legs"), Triple("lunge","Lunge","legs"), Triple("deadlift","Deadlift","strength"), Triple("plank","Plank","core"), Triple("sit_up","Sit-up","core"), Triple("jumping_jacks","Jumping Jacks","cardio"), Triple("high_knees","High Knees","cardio"), Triple("burpee","Burpee","cardio"), Triple("running","Running","run"), Triple("walking","Walking","walk"), Triple("cycling","Cycling","cycle"), Triple("rowing","Rowing","cardio"), Triple("jump_rope","Jump Rope","cardio"), Triple("mountain_climber","Mountain Climber","cardio"), Triple("custom","Custom","custom")
)

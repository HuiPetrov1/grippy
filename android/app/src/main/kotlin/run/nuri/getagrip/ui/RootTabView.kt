// SPDX-License-Identifier: MPL-2.0
// Original contributions Copyright 2026 Nuri Bruner.

package run.nuri.getagrip.ui

import run.nuri.getagrip.engine.MaxSave

import run.nuri.getagrip.R
import androidx.compose.ui.res.vectorResource
import androidx.annotation.DrawableRes
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.calculateStartPadding
import androidx.compose.foundation.layout.calculateEndPadding
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.platform.LocalLayoutDirection
import run.nuri.getagrip.ui.components.LocalFloatingTabBarInset
import androidx.compose.ui.unit.dp
import androidx.compose.foundation.layout.Column
import androidx.compose.ui.platform.LocalContext
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.lifecycle.viewModelScope
import run.nuri.getagrip.runner.WorkoutViewModel
import run.nuri.getagrip.runner.RunnerSession
import run.nuri.getagrip.runner.CuePlayer
import run.nuri.getagrip.BuildConfig
import androidx.activity.compose.LocalActivity
import run.nuri.getagrip.ui.criticalforce.CriticalForceSession
import run.nuri.getagrip.engine.CriticalForceHands
import run.nuri.getagrip.runner.CriticalForceServiceController
import run.nuri.getagrip.ui.criticalforce.CriticalForceTestRequest
import run.nuri.getagrip.ui.criticalforce.CriticalForceTestScreen
import run.nuri.getagrip.runner.AndroidActivityPublisher
import run.nuri.getagrip.runner.AndroidSessionServiceController
import run.nuri.getagrip.store.LogIdentity
import run.nuri.getagrip.store.LocalDeviceStore
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.material3.MaterialTheme
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.tween
import androidx.compose.material3.Icon
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.NavigationBarItemDefaults
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.scale
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.Color
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.layout.statusBars
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.sp
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.saveable.rememberSaveableStateHolder
import run.nuri.getagrip.ui.theme.InstrumentSurface
import run.nuri.getagrip.ui.theme.screenArrival
import androidx.compose.ui.graphics.vector.ImageVector
import run.nuri.getagrip.engine.GripSpec
import run.nuri.getagrip.engine.L10n
import run.nuri.getagrip.engine.MaxSource
import run.nuri.getagrip.engine.Side
import run.nuri.getagrip.store.LocalHistoryFeed
import run.nuri.getagrip.store.LocalTemplateStore
import run.nuri.getagrip.ui.builder.BuilderMode
import run.nuri.getagrip.ui.builder.RoutineBuilderHost
import run.nuri.getagrip.ui.history.HistoryScreen
import run.nuri.getagrip.ui.history.SessionLogSheet
import run.nuri.getagrip.ui.l10n.tr
import run.nuri.getagrip.ui.maxes.NewMaxDraft
import run.nuri.getagrip.ui.maxes.NewMaxSheet
import run.nuri.getagrip.ui.maxes.MaxEditSheet
import run.nuri.getagrip.ui.maxes.SharedMaxSheet
import run.nuri.getagrip.store.TemplateStore
import run.nuri.getagrip.ui.maxes.MaxMeasureScreen
import run.nuri.getagrip.ui.maxes.newCriticalForceTest
import run.nuri.getagrip.ui.maxes.MaxesTabScreen
import run.nuri.getagrip.ui.runner.RunnerHost
import run.nuri.getagrip.ui.settings.SettingsScreen
import run.nuri.getagrip.ui.theme.LocalGripPalette
import run.nuri.getagrip.ui.theme.rememberReduceMotion
import run.nuri.getagrip.ui.gauge.LiveGaugeHost
import run.nuri.getagrip.ui.today.TodayScreen
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CalendarMonth
import androidx.compose.material.icons.filled.FitnessCenter
import androidx.compose.material.icons.filled.Home
import androidx.compose.material.icons.filled.Speed
import androidx.compose.material.icons.outlined.CalendarMonth
import androidx.compose.material.icons.outlined.FitnessCenter
import androidx.compose.material.icons.outlined.Home
import androidx.compose.material.icons.outlined.Speed
import androidx.compose.foundation.background
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.interaction.MutableInteractionSource
import app.grippy.measure.HomeScreen
import app.grippy.measure.MeasureNavigator
import app.grippy.measure.MeasureRouteHost
import app.grippy.measure.MeasureSheets
import app.grippy.measure.MeasureTabScreen
import app.grippy.measure.measureColors
import app.grippy.measure.textStyle

/// Three tabs on iOS became four when Maxes earned its own; the anti-Frez tab count is still
/// "the fewest that can carry the ritual".
///
/// **The icons are the iOS custom symbols**, generated as vector drawables by
/// `scripts/make_symbols.swift --android` from the same drawings (docs/CUSTOM_SYMBOLS.md):
/// fingertips on the edge, a calendar of session circles, a max pull under its record
/// line, a gear. Material's convention holds: OUTLINED at rest, FILLED when selected.
/// Today has one form — a hollow finger is a loop, and a loop reads as a session.
///
/// **The label is a `get()`, not a constructor argument.** Enum entries are built once per
/// process, so a translated string baked in would keep the old language after the phone's
/// changes. Every enum carrying a display name resolves it on read for this reason.
enum class Tab(private val key: String, val icon: ImageVector, val selectedIcon: ImageVector) {
    // Grippy: Frez's four tabs. Settings and Benchmarks open from Home.
    Home("Home", Icons.Outlined.Home, Icons.Filled.Home),
    Workout("Workout", Icons.Outlined.FitnessCenter, Icons.Filled.FitnessCenter),
    Measure("Measure", Icons.Outlined.Speed, Icons.Filled.Speed),
    History("History", Icons.Outlined.CalendarMonth, Icons.Filled.CalendarMonth);

    val label: String get() = L10n.tr(key)
}

/// Grippy: the pages Home pushes over the tabs.
enum class HomePage { Settings, Benchmarks }

/// The SOFT NUDGE: once the newest measured max is four weeks stale the Maxes icon pulses
/// (Nuri, 2026-08-10). No badge, no notification; someone who never measured is never nudged.
///
/// **Off under Reduce Motion**, as iOS gates `symbolEffect(.pulse)`; the tab's staleness
/// subtitle says it in words.
///
/// TRANSLATION NOTE: Compose has no symbol effect, so the beat is a scale out and back. Not
/// routed through `Motion`: those are TRANSITION curves, and a heartbeat is not a transition.
///
/// **A few beats each time the bar appears, then still.** Breathing forever kept the frame
/// clock at sixty frames a second on Today for weeks.
@Composable
internal fun Modifier.benchmarkPulse(active: Boolean): Modifier {
    val scale = remember { Animatable(1f) }
    LaunchedEffect(active) {
        if (!active) {
            scale.snapTo(1f)
            return@LaunchedEffect
        }
        repeat(BENCHMARK_PULSE_BEATS) {
            scale.animateTo(1.14f, tween(900))
            scale.animateTo(1f, tween(900))
        }
    }
    return this.graphicsLayer { scaleX = scale.value; scaleY = scale.value }
}

/// Enough to catch an eye moving across the bar, few enough to stop before it is wallpaper.
internal const val BENCHMARK_PULSE_BEATS = 3

@Composable
fun RootTabView() {
    var current by rememberSaveable { mutableStateOf(Tab.Home) }
    val tabState = rememberSaveableStateHolder()
    val palette = LocalGripPalette.current
    val templates = LocalTemplateStore.current
    val feed = LocalHistoryFeed.current
    val reduceMotion = rememberReduceMotion()

    // Retain the route AND runner across Activity recreation — not process death, where the
    // gauge timeline no longer exists.
    val workouts: WorkoutViewModel = viewModel()
    val device = LocalDeviceStore.current
    val appContext = LocalContext.current.applicationContext

    // **A shared routine lands on Today, so the tab moves FIRST**; answering while Settings is
    // up leaves no trace of what happened.
    //
    // ABOVE the early returns on purpose: composables after one never run. It PRESENTS nothing
    // (iOS: `onOpenURL` never presents) — a link tapped mid-session sets the tab, the session
    // continues, and the inbox waits for the runner to close.
    LaunchedEffect(templates.pendingImport, templates.pendingImportError) {
        if (templates.pendingImport != null || templates.pendingImportError != null) {
            current = Tab.Workout
        }
    }

    val request = workouts.active
    // ABOVE the early return: the rating ask compares the save counter across the runner.
    val review = rememberReviewRequest(runnerOpen = request != null)
    if (request != null) {
        RunnerHost(
            workout = request,
            submissionScope = workouts.viewModelScope,
            onFinished = { outcome, decision ->
                // Discard writes NOTHING; a session nobody pulled in is not worth logging either.
                if (decision.save && outcome.didAnyWork) {
                    val saved = templates.recordSession(
                        plan = outcome.plan,
                        // The finished-session draft's id: a Save and a launch recovery are one row.
                        identity = LogIdentity.of(request.template, outcome.plan, outcome.id),
                        reps = outcome.results,
                        startedAt = outcome.startedAt,
                        finishedAt = outcome.finishedAt,
                        rpe = decision.rpe,
                        newMaxes = decision.newMaxes.map {
                            run.nuri.getagrip.data.MaxRecordEntity.from(
                                grip = it.grip, kg = it.kg, source = MaxSource.measured, side = it.side)
                        },
                    )
                    if (saved != null) feed.refresh()
                    saved != null
                } else true
            },
            onExit = { workouts.finish(request) },
        )
        return
    }

    // THE BUILDER IS A FULL-SCREEN COVER, never a sheet or push: nothing touches the store until
    // Save, Cancel IS undo, and a back chevron would promise save-as-you-go.
    // The live gauge replaces the root like the runner: the graph wants the whole screen.
    // DEBUG `--ez previewGauge true`: the gauge opened directly, for headless screenshots.
    val gaugeRequested = BuildConfig.DEBUG &&
        LocalActivity.current?.intent?.getBooleanExtra("previewGauge", false) == true
    var liveGauge by rememberSaveable { mutableStateOf(gaugeRequested) }
    if (liveGauge) {
        LiveGaugeHost(onClose = { liveGauge = false })
        return
    }

    // Which screen the root is presenting survives a rotation — see `RootPresentation`.
    val presentation: RootPresentation = viewModel { RootPresentation() }

    // THE CRITICAL FORCE TEST replaces the root like the max test: the phone is on a bench
    // and both hands are on the edge. Its session lives in `RootPresentation`, so a
    // recreation mid-test keeps the test.
    fun openCriticalForce(grip: GripSpec, hands: CriticalForceHands) {
        if (presentation.criticalForce != null) return
        // One cue player for the visit; each hand gets a fresh test that plays through it.
        val cues = CuePlayer(appContext, diagnostic = device::recordAudio)
        val scope = presentation.viewModelScope
        presentation.criticalForce = CriticalForceTestRequest(
            grip = grip,
            hands = hands,
            scope = scope,
            newSession = { CriticalForceSession(scope = scope, cues = cues) },
            service = CriticalForceServiceController(appContext),
        )
    }
    val launchIntent = LocalActivity.current?.intent
    LaunchedEffect(Unit) {
        // DEBUG `--ez previewCriticalForce true`: open the test for a headless screenshot, from
        // the Benchmarks tab and by its rule — the one door the test has.
        if (BuildConfig.DEBUG && !presentation.previewedCriticalForce &&
            launchIntent?.getBooleanExtra("previewCriticalForce", false) == true) {
            presentation.previewedCriticalForce = true
            current = Tab.Home
            val (grip, hands) = newCriticalForceTest(templates.criticalForceRecords, templates.recentGrips)
            openCriticalForce(grip, hands)
        }
        // DEBUG `--ez previewMaxMeasure true` (`--ez previewMaxBoth true` for both hands): the
        // max visit, opened bare. It connects and reads on its own — the thing being shown.
        if (BuildConfig.DEBUG && !presentation.previewedMaxMeasure &&
            launchIntent?.getBooleanExtra("previewMaxMeasure", false) == true) {
            presentation.previewedMaxMeasure = true
            current = Tab.Home
            presentation.measuring = MeasureRequest(
                templates.recentGrips.firstOrNull() ?: GripSpec(),
                if (launchIntent.getBooleanExtra("previewMaxBoth", false)) Side.both else Side.left,
            )
        }
    }
    val criticalForce = presentation.criticalForce
    if (criticalForce != null) {
        CriticalForceTestScreen(criticalForce, onClose = {
            presentation.criticalForce = null
            feed.refresh()
        })
        return
    }
    var building by presentation::building
    val builderMode = building
    if (builderMode != null) {
        RoutineBuilderHost(mode = builderMode, onDone = { building = null })
        return
    }

    // Keep the grip composer above each child destination: Cancel returns to the same
    // grip, while a successful save closes the whole creation flow after its receipt.
    var newMax by presentation::newMax
    var editingMax by presentation::editingMax
    var editingSharedMax by presentation::editingSharedMax
    var measuring by presentation::measuring
    var measurementSaved by presentation::measurementSaved
    val measure = measuring
    if (measure != null) {
        MaxMeasureScreen(
            grip = measure.grip,
            initialSide = measure.side,
            session = presentation.liveMaxSession(measure),
            onSave = { values ->
                val receipt = templates.recordMaxesWithReceipt(values.map {
                    MaxSave(measure.grip, it.side, it.kg, it.source, it.seconds)
                })
                if (receipt != null) {
                    measurementSaved = true
                    feed.refresh()
                }
                receipt
            },
            onClose = {
                if (measurementSaved && measure.fromNew) newMax = null
                measuring = null
            },
        )
        return
    }
    editingSharedMax?.let { request ->
        SharedMaxSheet(request.grip,
            onSaved = { if (request.fromNew) newMax = null },
            onClose = { editingSharedMax = null })
        return
    }
    editingMax?.let { request ->
        MaxEditSheet(request.grip,
            onSaved = { if (request.fromNew) newMax = null },
            onClose = { editingMax = null })
        return
    }
    newMax?.let { draft ->
        NewMaxSheet(draft,
            onMeasure = { grip, side, seconds -> measuring = MeasureRequest(grip, side, fromNew = true, seconds = seconds) },
            onEnter = { grip -> editingMax = MaxEditRequest(grip, fromNew = true) },
            onShared = { grip -> editingSharedMax = MaxEditRequest(grip, fromNew = true) },
            onClose = { newMax = null })
        return
    }

    // One log sheet with two doors (History, Today's consistency card): one kind of row, one
    // set of rules about what settles a day.
    var loggingSession by presentation::loggingSession

    // Grippy: the Measure section's routes, and Home's Settings / Benchmarks, cover the tabs.
    val measureNav: MeasureNavigator = viewModel()
    var homePage by rememberSaveable { mutableStateOf<HomePage?>(null) }
    if (measureNav.top != null) {
        Box(Modifier.fillMaxSize().background(measureColors().bg)) { MeasureRouteHost(measureNav) }
        MeasureSheets(measureNav, onLiveData = { liveGauge = true })
        return
    }
    homePage?.let { page ->
        androidx.activity.compose.BackHandler { homePage = null }
        Box(Modifier.fillMaxSize().background(measureColors().bg).windowInsetsPadding(WindowInsets.statusBars)) {
            when (page) {
                HomePage.Settings -> SettingsScreen(onBack = { homePage = null })
                HomePage.Benchmarks -> MaxesTabScreen(
                    onAddMax = { seed -> newMax = NewMaxDraft(seed ?: templates.recentGrips.firstOrNull() ?: GripSpec()) },
                    onEdit = { grip -> editingMax = MaxEditRequest(grip) },
                    onMeasure = { grip, side, seconds -> measuring = MeasureRequest(grip, side, seconds = seconds) },
                    onCriticalForce = ::openCriticalForce,
                    onBack = { homePage = null },
                )
            }
        }
        if (loggingSession) SessionLogSheet(onClose = { loggingSession = false })
        return
    }

    Scaffold(
        containerColor = androidx.compose.ui.graphics.Color.Transparent,
        bottomBar = { GrippyTabBar(current) { current = it } },
    ) { padding ->
        // Only top/sides constrain the viewport: a bottom inset clipped scrolling cards along a
        // rectangle above the floating bar.
        val direction = LocalLayoutDirection.current
        Box(Modifier.fillMaxSize().padding(
            start = padding.calculateStartPadding(direction),
            top = padding.calculateTopPadding(),
            end = padding.calculateEndPadding(direction),
        ).screenArrival(current)) {
            CompositionLocalProvider(LocalFloatingTabBarInset provides padding.calculateBottomPadding()) {
            tabState.SaveableStateProvider(current) {
            when (current) {
                Tab.Home -> HomeScreen(
                    nav = measureNav,
                    onSettings = { homePage = HomePage.Settings },
                    onBenchmarks = { homePage = HomePage.Benchmarks },
                    onLiveData = { liveGauge = true },
                )
                Tab.Workout -> TodayScreen(
                    onStart = { template, timerOnly ->
                        // Written on START: at 19:00 the question is which one you are in the middle of.
                        val started = workouts.start(template) { sessionScope ->
                            RunnerSession(
                                plan = template.plan,
                                routineName = template.name,
                                device = device,
                                maxes = templates.maxTable,
                                criticalForces = templates.currentCriticalForces,
                                timerOnly = timerOnly,
                                scope = sessionScope,
                                cues = CuePlayer(appContext, diagnostic = device::recordAudio),
                                activity = AndroidActivityPublisher(appContext),
                                service = AndroidSessionServiceController(appContext),
                            )
                        }
                        if (started) templates.noteSessionStarted(template)
                    },
                    onBuild = {
                        building = if (templates.routines.isEmpty()) BuilderMode.FirstRun else BuilderMode.AddAnother
                    },
                    onEdit = { template -> building = BuilderMode.Edit(template.id) },
                    onEditImport = { draft -> building = BuilderMode.Importing(draft) },
                    onShowHistory = { current = Tab.History },
                    onLogSession = { loggingSession = true },
                    onOpenGauge = { liveGauge = true },
                    // The two presentations Today cannot see (both hosted here, Today composed beneath), so the
                    // guard has to be told.
                    canPresentImport = !loggingSession,
                )
                Tab.Measure -> MeasureTabScreen(measureNav, onLiveData = { liveGauge = true })
                Tab.History -> HistoryScreen(onLogSession = { loggingSession = true })
            }
        }
        }
        }
    }
    MeasureSheets(measureNav, onLiveData = { liveGauge = true })

    if (loggingSession) {
        SessionLogSheet(onClose = { loggingSession = false })
    }

    // Below the runner's early return, so it never appears over a workout still going.
    UnsavedSessionPrompt()
    ReviewRequestDialog(review)

}

/// Material 3's `NavigationBarItemHorizontalPadding`: the gap NavigationBar leaves between items.
private val TAB_ITEM_SPACING = 8.dp

/// Air a tab label keeps inside its item, so a label that "fits" is not drawn edge to edge.
private val TAB_LABEL_MARGIN = 4.dp

/// Grippy: Frez's flat tab bar — a hairline on top, outlined icons at rest, filled when chosen.
@Composable
private fun GrippyTabBar(current: Tab, onSelect: (Tab) -> Unit) {
    val c = measureColors()
    Column(Modifier.fillMaxWidth().background(if (c.dark) Color(0xFF1E1E23) else Color(0xFFFAFAFC))) {
        Box(Modifier.fillMaxWidth().height(1.dp).background(c.border))
        Row(Modifier.fillMaxWidth().navigationBarsPadding().height(76.dp)) {
            Tab.entries.forEach { tab ->
                val on = tab == current
                Column(
                    Modifier.weight(1f).fillMaxSize().selectable(
                        selected = on, role = androidx.compose.ui.semantics.Role.Tab,
                        interactionSource = remember { MutableInteractionSource() }, indication = null,
                    ) { onSelect(tab) },
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = androidx.compose.foundation.layout.Arrangement.Center,
                ) {
                    Icon(if (on) tab.selectedIcon else tab.icon, contentDescription = null,
                        tint = if (on) c.text else c.text3, modifier = Modifier.size(30.dp))
                    Text(tab.label, maxLines = 1,
                        style = textStyle(15.sp, if (on) FontWeight.SemiBold else FontWeight.Medium, if (on) c.text else c.text3),
                        modifier = Modifier.padding(top = 4.dp))
                }
            }
        }
    }
}


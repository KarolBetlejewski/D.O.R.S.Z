@file:OptIn(ExperimentalLayoutApi::class)

package com.example.djicontroller.ui

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Checkbox
import androidx.compose.material3.CheckboxDefaults
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
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
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.example.djicontroller.core.diagnostics.AxisCheckState
import com.example.djicontroller.core.diagnostics.AxisVerdict
import com.example.djicontroller.core.diagnostics.HoverTestState
import com.example.djicontroller.core.diagnostics.MarkerHoverTest
import com.example.djicontroller.core.diagnostics.StickAxisCheck
import com.example.djicontroller.core.model.ConnectionState
import com.example.djicontroller.core.model.Setpoint
import com.example.djicontroller.core.model.StickAxes
import com.example.djicontroller.core.model.isAirborneStage
import com.example.djicontroller.core.model.label
import com.example.djicontroller.vision.LandingPadLayout
import java.util.Locale

/**
 * Ekran Diagnostyka (F2): symulator MSDK, gimbal, wizja ArUco z nakładką, Virtual Stick (test osi),
 * lot testowy (start, lądowanie z ochroną — R5) i test R3 „Zawis nad markerem”. STOP zawsze widoczny.
 */
@Composable
fun DiagnosticsScreen(onBack: () -> Unit, vm: DiagnosticsViewModel = viewModel()) {
    val state by vm.state.collectAsStateWithLifecycle()
    val snackbar = remember { SnackbarHostState() }
    LaunchedEffect(vm) { vm.messages.collect { snackbar.showSnackbar(it) } }

    val pickImage = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri -> uri?.let(vm::useImage) }
    val pickVideo = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri -> uri?.let(vm::useVideo) }

    Box(Modifier.fillMaxSize().background(PatrolColors.Background)) {
        Column(Modifier.fillMaxSize().padding(8.dp)) {
            Header(state, onBack)
            Spacer(Modifier.height(6.dp))
            Row(Modifier.fillMaxWidth().weight(1f), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Box(
                    Modifier
                        .weight(1.15f)
                        .fillMaxHeight()
                        .clip(RoundedCornerShape(8.dp))
                        .background(Color.Black)
                        .border(1.dp, PatrolColors.PanelBorder, RoundedCornerShape(8.dp)),
                ) {
                    CameraPreview(vm.videoPreview, Modifier.fillMaxSize())
                    VisionOverlay(if (state.visionOn || state.runningTest != null) state.vision else null, Modifier.fillMaxSize())
                    Text(
                        text = videoFooter(state),
                        color = Color.White,
                        fontSize = 11.sp,
                        fontFamily = FontFamily.Monospace,
                        modifier = Modifier
                            .align(Alignment.BottomStart)
                            .padding(6.dp)
                            .clip(RoundedCornerShape(4.dp))
                            .background(Color.Black.copy(alpha = 0.6f))
                            .padding(horizontal = 8.dp, vertical = 4.dp),
                    )
                }
                Column(
                    Modifier.weight(1f).fillMaxHeight().verticalScroll(rememberScrollState()),
                    verticalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    HoverCard(state, vm)
                    VisionCard(state, vm, onPickImage = { pickImage.launch(arrayOf("image/*")) }, onPickVideo = { pickVideo.launch(arrayOf("video/*")) })
                    VirtualStickCard(state, vm)
                    FlightCard(state, vm)
                    GimbalCard(state, vm)
                    SimulatorCard(state, vm)
                }
            }
        }
        Box(Modifier.align(Alignment.BottomEnd).padding(14.dp)) {
            StopButton(onClick = vm::stop, active = state.cycle.isAirborneStage || state.runningTest != null || state.drone.isFlying == true)
        }
        SnackbarHost(snackbar, Modifier.align(Alignment.BottomCenter).padding(bottom = 16.dp))
    }
}

@Composable
private fun Header(state: DiagnosticsUiState, onBack: () -> Unit) {
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
        OutlinedButton(onClick = onBack, contentPadding = PaddingValues(horizontal = 12.dp, vertical = 4.dp)) {
            Text("← Ekran główny", fontSize = 12.sp)
        }
        Text("Diagnostyka F2", color = Color.White, fontWeight = FontWeight.Bold, fontSize = 16.sp)
        val conn = when (val c = state.connection) {
            is ConnectionState.Connected -> if (c.supported) "Mini 4 Pro" else c.productType
            ConnectionState.Disconnected -> "brak drona"
            is ConnectionState.RegistrationFailed -> "rejestracja: błąd"
            else -> "MSDK: start"
        }
        Muted("$conn · ${state.cycle.label()}${if (state.isSimulation) " · SYMULACJA" else ""}")
        state.runningTest?.let {
            Text("TEST: ${testName(it)}", color = Color.Black, fontSize = 12.sp, fontWeight = FontWeight.Bold,
                modifier = Modifier.clip(RoundedCornerShape(50)).background(PatrolColors.Warn).padding(horizontal = 10.dp, vertical = 3.dp))
        }
        if (!state.cycleIdle) Text("Cykl w toku — testy zablokowane", color = PatrolColors.Warn, fontSize = 12.sp)
    }
}

// ---------------------------------------------------------------- R3

@Composable
private fun HoverCard(state: DiagnosticsUiState, vm: DiagnosticsViewModel) {
    Card("Zawis nad markerem (R3)") {
        var heightText by rememberSaveable { mutableStateOf(fmt1(state.hoverHeightM)) }
        val running = state.runningTest == MarkerHoverTest.TEST_NAME
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            OutlinedTextField(
                value = heightText,
                onValueChange = { text ->
                    heightText = text
                    text.replace(',', '.').toDoubleOrNull()?.let(vm::setHoverHeight)
                },
                label = { Text("Wysokość [m]", fontSize = 11.sp) },
                singleLine = true,
                enabled = !running,
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
                modifier = Modifier.width(130.dp),
            )
            if (running) {
                ActionButton("Zakończ", PatrolColors.Warn, vm::stopHover)
            } else {
                ActionButton("Start testu", PatrolColors.Ok, vm::startHover, enabled = state.cycleIdle && state.runningTest == null)
            }
        }
        Muted("Dron w powietrzu, marker pod kamerą. Gimbal −90°, Virtual Stick, regulator PD; bez opadania poniżej celu.")
        when (val h = state.hover) {
            HoverTestState.Idle -> Unit
            is HoverTestState.Running -> {
                Mono("faza: ${h.phase} · ${h.elapsedMs / 1000} s")
                Mono("eH ${h.horizontalErrorM?.let { fmt2(it) } ?: "—"} m · eZ ${h.heightErrorM?.let { fmt2(it) } ?: "—"} m · opadanie ${if (h.descentAllowed) "tak" else "nie"}")
                Mono("cmd ${setpointText(h.setpoint)}")
                h.summary?.let { s ->
                    Mono("RMS ${fmt2(s.rmsHorizontalErrorM)} m · max ${fmt2(s.maxHorizontalErrorM)} m · widoczny ${s.markerVisiblePercent.toInt()}%")
                }
            }
            is HoverTestState.Finished -> {
                Text(
                    "Koniec: ${h.reason}",
                    color = if (h.success) PatrolColors.Ok else PatrolColors.Warn,
                    fontSize = 12.sp,
                )
                h.summary?.let { s ->
                    Mono("czas ${s.durationMs / 1000} s · próbek ${s.samples} · widoczny ${s.markerVisiblePercent.toInt()}%")
                    Mono("RMS ${fmt2(s.rmsHorizontalErrorM)} m · max ${fmt2(s.maxHorizontalErrorM)} m")
                    Mono("|eZ| śr. ${fmt2(s.meanAbsHeightErrorM)} m · min H ${fmt2(s.minHeightM)} m")
                }
            }
        }
    }
}

// ---------------------------------------------------------------- wizja i R4

@Composable
private fun VisionCard(state: DiagnosticsUiState, vm: DiagnosticsViewModel, onPickImage: () -> Unit, onPickVideo: () -> Unit) {
    Card("Wizja ArUco (R4)") {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Switch(
                checked = state.visionOn,
                onCheckedChange = vm::setVisionOn,
                colors = SwitchDefaults.colors(checkedTrackColor = PatrolColors.Accent),
            )
            Text("Detekcja i nakładka", color = Color.White, fontSize = 13.sp)
        }
        state.visionProblem?.let { Text(it, color = PatrolColors.Stop, fontSize = 12.sp) }
        Muted("Źródło: ${state.visionSource}")
        FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            SmallButton("Kamera drona", vm::useDroneCamera)
            SmallButton("Plik: obraz", onPickImage)
            SmallButton("Plik: wideo", onPickVideo)
            SmallButton("Zapisz klatkę", vm::saveFrame)
            SmallButton("Reset statystyk", vm::resetVisionStats)
        }
        Muted("Lądowisko:")
        FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            LandingPadLayout.PRESETS.forEach { layout ->
                Choice(layout.name, selected = layout.name == state.layout.name) { vm.setLayout(layout) }
            }
        }
        state.calibration?.let { c ->
            Muted("Kalibracja: ${c.source} · ${c.width}×${c.height} · HFOV ${fmt1(c.horizontalFovDeg)}°")
        }
        val s = state.visionStats
        Mono("fps ${fmt1(s.fps)} · klatek ${s.frames} · z markerem ${s.framesWithMarker}")
        Mono("detekcja śr. ${fmt1(s.detectionMsMean)} ms · p95 ${s.detectionMsP95} ms")
        Mono("wiek klatki śr. ${fmt1(s.frameAgeMsMean)} ms · p95 ${s.frameAgeMsP95} ms")
        Muted("Wiek klatki = od odbioru z MSDK do wyniku detekcji. Opóźnienie transmisji drona → telefon mierzy się osobno (R4).")
    }
}

// ---------------------------------------------------------------- Virtual Stick

@Composable
private fun VirtualStickCard(state: DiagnosticsUiState, vm: DiagnosticsViewModel) {
    Card("Virtual Stick") {
        val vs = state.virtualStick
        Mono("włączony ${yesNo(vs.enabled)} · zaawansowany ${yesNo(vs.advancedMode)} · pętla ${yesNo(vs.loopActive)}")
        Mono("właściciel ${vs.authority ?: "—"} · ostatni powód ${vs.lastChangeReason ?: "—"}")
        Mono("wysłano ${vs.lastSent?.let(::setpointText) ?: "—"}${if (vs.watchdogHover) " (watchdog → zawis)" else ""}")
        Muted("Mapowanie osi (BODY, VELOCITY):")
        FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            Choice("roll = przód (DJI)", selected = vs.axes == StickAxes.ROLL_FORWARD) { vm.setStickAxes(StickAxes.ROLL_FORWARD) }
            Choice("pitch = przód", selected = vs.axes == StickAxes.PITCH_FORWARD) { vm.setStickAxes(StickAxes.PITCH_FORWARD) }
        }
        val running = state.runningTest == StickAxisCheck.TEST_NAME
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            if (running) {
                ActionButton("Przerwij test osi", PatrolColors.Warn, vm::stopAxisCheck)
            } else {
                ActionButton("Test osi", PatrolColors.Blue, vm::startAxisCheck, enabled = state.cycleIdle && state.runningTest == null)
            }
            Muted("Najpierw w symulatorze MSDK! 0,5 m/s do przodu, potem w prawo.")
        }
        when (val a = state.axisCheck) {
            AxisCheckState.Idle -> Unit
            is AxisCheckState.Running -> Mono("test: ${a.step}")
            is AxisCheckState.Finished -> {
                val r = a.result
                if (r == null) {
                    Text("Test przerwany: ${a.reason}", color = PatrolColors.Warn, fontSize = 12.sp)
                } else {
                    val (text, color) = when (r.verdict) {
                        AxisVerdict.OK -> "Osie poprawne" to PatrolColors.Ok
                        AxisVerdict.SWAPPED -> "Osie zamienione — przełącz mapowanie" to PatrolColors.Warn
                        AxisVerdict.INVERTED -> "Kierunek odwrócony — NIE LATAĆ, zgłoś błąd" to PatrolColors.Stop
                        AxisVerdict.NO_MOTION -> "Dron nie ruszył — wynik nierozstrzygnięty" to PatrolColors.Warn
                    }
                    Text(text, color = color, fontSize = 13.sp, fontWeight = FontWeight.SemiBold)
                    Mono("„przód” → przód ${fmt2(r.forwardCommand.forwardMps)} prawo ${fmt2(r.forwardCommand.rightMps)} m/s")
                    Mono("„prawo” → przód ${fmt2(r.rightCommand.forwardMps)} prawo ${fmt2(r.rightCommand.rightMps)} m/s")
                    if (r.verdict == AxisVerdict.SWAPPED) SmallButton("Przełącz mapowanie", vm::applyAxisRecommendation)
                }
            }
        }
    }
}

// ---------------------------------------------------------------- lot testowy i R5

@Composable
private fun FlightCard(state: DiagnosticsUiState, vm: DiagnosticsViewModel) {
    Card("Lot testowy (R5)") {
        val d = state.drone
        Mono("lata ${yesNo(d.isFlying)} · silniki ${yesNo(d.areMotorsOn)} · tryb ${d.flightMode ?: "—"}")
        Mono("H ${d.altitudeM?.let(::fmt1) ?: "—"} m · ToF ${d.ultrasonicHeightM?.let(::fmt1) ?: "—"} m · sat ${d.gpsSatelliteCount ?: "—"}")
        val canCommand = state.cycleIdle && state.runningTest == null
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
            ActionButton("Start", PatrolColors.Ok, vm::takeOff, enabled = canCommand && d.isFlying != true)
            ActionButton("Ląduj", PatrolColors.Blue, vm::autoLanding, enabled = canCommand && d.isFlying == true)
        }
        Row(verticalAlignment = Alignment.CenterVertically) {
            Checkbox(
                checked = state.autoConfirmLanding,
                onCheckedChange = vm::setAutoConfirm,
                colors = CheckboxDefaults.colors(checkedColor = PatrolColors.Accent),
            )
            Text("Automatycznie potwierdzaj ochronę lądowania", color = Color.White, fontSize = 12.sp)
        }
        if (d.isLandingConfirmationNeeded == true) {
            Text("Dron czeka na potwierdzenie lądowania", color = PatrolColors.Warn, fontSize = 13.sp, fontWeight = FontWeight.SemiBold)
            ActionButton("Potwierdź lądowanie", PatrolColors.Warn, vm::confirmLanding)
        }
    }
}

@Composable
private fun GimbalCard(state: DiagnosticsUiState, vm: DiagnosticsViewModel) {
    Card("Kamera i gimbal") {
        Mono("pochylenie ${state.gimbalPitchDeg?.let(::fmt1) ?: "—"}° (−90 = w dół)")
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            SmallButton("Kamera w dół (−90°)", vm::gimbalDown)
            SmallButton("Reset gimbala", vm::gimbalReset)
        }
    }
}

@Composable
private fun SimulatorCard(state: DiagnosticsUiState, vm: DiagnosticsViewModel) {
    Card("Symulator MSDK") {
        val sim = state.simulator
        if (sim == null) {
            Muted("Wariant demo — cały dron jest symulowany w aplikacji. Symulator MSDK działa w wariancie prod z podłączonym RC-N2.")
            return@Card
        }
        val suggested = remember { vm.suggestedSimulatorLocation() }
        var lat by rememberSaveable { mutableStateOf(suggested?.latitude?.let { String.format(Locale.US, "%.6f", it) } ?: "") }
        var lon by rememberSaveable { mutableStateOf(suggested?.longitude?.let { String.format(Locale.US, "%.6f", it) } ?: "") }
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
            Switch(
                checked = sim.enabled,
                onCheckedChange = { on -> vm.setSimulator(on, lat.toDoubleOrNull(), lon.toDoubleOrNull()) },
                colors = SwitchDefaults.colors(checkedTrackColor = PatrolColors.Accent),
            )
            Text(if (sim.enabled) "Symulator włączony" else "Symulator wyłączony", color = Color.White, fontSize = 13.sp)
        }
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            OutlinedTextField(lat, { lat = it }, label = { Text("Szer.", fontSize = 11.sp) }, singleLine = true,
                enabled = !sim.enabled, modifier = Modifier.weight(1f),
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal))
            OutlinedTextField(lon, { lon = it }, label = { Text("Dł.", fontSize = 11.sp) }, singleLine = true,
                enabled = !sim.enabled, modifier = Modifier.weight(1f),
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal))
        }
        if (sim.enabled) {
            Mono("silniki ${yesNo(sim.areMotorsOn)} · lata ${yesNo(sim.isFlying)}")
            sim.positionM?.let { (x, y, z) -> Mono("pozycja X ${fmt1(x)} Y ${fmt1(y)} Z ${fmt1(z)} m") }
        }
        Muted("Włączać przy wyłączonych silnikach, ze ZDJĘTYMI śmigłami. Obraz z kamery pozostaje prawdziwy.")
    }
}

// ---------------------------------------------------------------- elementy

@Composable
private fun Card(title: String, content: @Composable ColumnScope.() -> Unit) {
    Column(
        Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(8.dp))
            .background(PatrolColors.Panel)
            .border(1.dp, PatrolColors.PanelBorder, RoundedCornerShape(8.dp))
            .padding(10.dp),
        verticalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        Text(title, color = PatrolColors.Accent, fontWeight = FontWeight.Bold, fontSize = 14.sp)
        content()
    }
}

@Composable
private fun Muted(text: String) = Text(text, color = PatrolColors.TextMuted, fontSize = 11.sp)

@Composable
private fun Mono(text: String) = Text(text, color = Color.White, fontSize = 12.sp, fontFamily = FontFamily.Monospace)

@Composable
private fun SmallButton(text: String, onClick: () -> Unit) {
    OutlinedButton(onClick = onClick, contentPadding = PaddingValues(horizontal = 10.dp, vertical = 2.dp), modifier = Modifier.height(32.dp)) {
        Text(text, fontSize = 11.sp, maxLines = 1)
    }
}

@Composable
private fun Choice(text: String, selected: Boolean, onClick: () -> Unit) {
    Text(
        text,
        fontSize = 12.sp,
        color = if (selected) Color.Black else Color.White,
        fontWeight = if (selected) FontWeight.Bold else FontWeight.Normal,
        modifier = Modifier
            .clip(RoundedCornerShape(50))
            .background(if (selected) PatrolColors.Accent else PatrolColors.Background)
            .border(1.dp, PatrolColors.Accent, RoundedCornerShape(50))
            .clickable(onClick = onClick)
            .padding(horizontal = 10.dp, vertical = 4.dp),
    )
}

@Composable
private fun ActionButton(text: String, color: Color, onClick: () -> Unit, enabled: Boolean = true) {
    Button(
        onClick = onClick,
        enabled = enabled,
        colors = ButtonDefaults.buttonColors(containerColor = color, contentColor = Color.Black),
        contentPadding = PaddingValues(horizontal = 14.dp, vertical = 6.dp),
    ) {
        Text(text, fontWeight = FontWeight.SemiBold, fontSize = 13.sp, maxLines = 1)
    }
}

private fun videoFooter(state: DiagnosticsUiState): String {
    val d = state.drone
    return listOfNotNull(
        d.altitudeM?.let { "H ${fmt1(it)} m" },
        d.ultrasonicHeightM?.let { "ToF ${fmt1(it)} m" },
        d.headingDeg?.let { "kurs ${it.toInt()}°" },
        state.gimbalPitchDeg?.let { "gimbal ${it.toInt()}°" },
        state.virtualStick.lastSent?.takeIf { state.virtualStick.loopActive }?.let { "VS ${setpointText(it)}" },
    ).joinToString("  ·  ").ifEmpty { "brak telemetrii" }
}

private fun testName(name: String) = when (name) {
    MarkerHoverTest.TEST_NAME -> "zawis nad markerem"
    StickAxisCheck.TEST_NAME -> "osie Virtual Stick"
    else -> name
}

private fun setpointText(s: Setpoint) =
    "fwd ${fmt2(s.forwardMps)} right ${fmt2(s.rightMps)} up ${fmt2(s.upMps)} yaw ${fmt1(s.yawRateDegS)}"

private fun yesNo(v: Boolean?) = when (v) {
    true -> "tak"
    false -> "nie"
    null -> "—"
}

private fun fmt1(v: Double) = String.format(Locale.US, "%.1f", v)
private fun fmt2(v: Double) = String.format(Locale.US, "%+.2f", v)

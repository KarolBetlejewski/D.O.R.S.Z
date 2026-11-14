package com.example.djicontroller.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.example.djicontroller.core.model.ConnectionState
import com.example.djicontroller.core.model.CycleStage
import com.example.djicontroller.core.model.CycleState
import com.example.djicontroller.core.model.StationLink
import com.example.djicontroller.core.model.isAirborneStage
import com.example.djicontroller.core.model.label
import com.example.djicontroller.core.model.stage
import com.example.djicontroller.core.model.status
import com.example.djicontroller.di.DebugAction
import java.util.Locale

/** Ekran główny (sekcja 6, projekt D1), orientacja pozioma — podpięty pod ViewModel i kamerę drona. */
@Composable
fun PatrolScreen(vm: MainViewModel = viewModel(), onOpenDiagnostics: () -> Unit = {}) {
    val state by vm.state.collectAsStateWithLifecycle()
    val snackbar = remember { SnackbarHostState() }

    LaunchedEffect(vm) {
        vm.messages.collect { snackbar.showSnackbar(it) }
    }

    PatrolContent(
        state = state,
        snackbar = snackbar,
        video = { CameraPreview(vm.videoPreview, Modifier.fillMaxSize()) },
        debugActions = vm.debugActions,
        onStart = vm::startCycle,
        onReturn = vm::returnToStation,
        onResume = vm::resume,
        onReset = vm::reset,
        onStop = vm::stop,
        onOpenDiagnostics = onOpenDiagnostics,
    )
}

/**
 * Bezstanowy układ ekranu: dostaje gotowy [MainUiState] i akcje.
 * Dzięki temu da się go narysować w podglądzie Android Studio bez drona i bez SDK DJI.
 */
@Composable
fun PatrolContent(
    state: MainUiState,
    snackbar: SnackbarHostState,
    video: @Composable () -> Unit,
    onStart: () -> Unit,
    onReturn: () -> Unit,
    onResume: () -> Unit,
    onReset: () -> Unit,
    onStop: () -> Unit,
    debugActions: List<DebugAction> = emptyList(),
    onOpenDiagnostics: () -> Unit = {},
) {
    Box(
        Modifier
            .fillMaxSize()
            .background(PatrolColors.Background),
    ) {
        Column(Modifier.fillMaxSize().padding(8.dp)) {
            TopStatusBar(state)
            if (state.alarms.isNotEmpty()) {
                Spacer(Modifier.height(6.dp))
                AlarmBanner(state.alarms.joinToString("  •  ") { it.message })
            }
            Spacer(Modifier.height(6.dp))
            Row(Modifier.fillMaxWidth().weight(1f), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                VideoPanel(video, state, Modifier.weight(1f).fillMaxHeight())
                Panel(Modifier.weight(1f).fillMaxHeight()) {
                    MapPanel(state.drone, state.route, Modifier.fillMaxSize())
                    PanelLabel("Mapa", Modifier.align(Alignment.TopStart))
                }
            }
            if (debugActions.isNotEmpty()) {
                Spacer(Modifier.height(6.dp))
                DebugBar(debugActions)
            }
            Spacer(Modifier.height(6.dp))
            BottomBar(
                state = state,
                onStart = onStart,
                onReturn = onReturn,
                onResume = onResume,
                onReset = onReset,
                onStop = onStop,
                onOpenDiagnostics = onOpenDiagnostics,
            )
        }
        SnackbarHost(snackbar, Modifier.align(Alignment.BottomCenter).padding(bottom = 96.dp))
    }
}

// ---------------------------------------------------------------- górny pasek

@Composable
private fun TopStatusBar(state: MainUiState) {
    val d = state.drone
    Row(
        Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(6.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        val battery = d.batteryPercent
        val threshold = state.returnThresholdPercent
        val batteryColor = when {
            battery == null -> PatrolColors.TextMuted
            threshold != null && battery <= threshold -> PatrolColors.Stop
            threshold != null && battery <= threshold + 10 -> PatrolColors.Warn
            else -> PatrolColors.Ok
        }
        StatusChip(
            text = "Bateria ${battery?.let { "$it%" } ?: "—"}  |  próg ${threshold?.let { "$it%" } ?: "—"}",
            color = batteryColor,
        )
        val gps = d.gpsSignalLevel
        StatusChip(
            text = "GPS ${gps ?: "—"}/5 · ${d.gpsSatelliteCount ?: "—"} sat",
            color = when {
                gps == null -> PatrolColors.TextMuted
                gps >= 4 -> PatrolColors.Ok
                gps >= 2 -> PatrolColors.Warn
                else -> PatrolColors.Stop
            },
        )
        ConnectionChip(state.connection)
        StatusChip(
            text = state.cycle.label() + (state.landingPhase?.let { " · ${it.step.label}" } ?: ""),
            color = when (state.cycle) {
                is CycleState.Failure -> PatrolColors.Stop
                is CycleState.Holding -> PatrolColors.Warn
                CycleState.Ready -> PatrolColors.Ok
                else -> PatrolColors.Accent
            },
            emphasized = true,
            modifier = Modifier.weight(1f, fill = false),
        )
        StatusChip(
            text = "Punkt przerwania: ${state.breakpoint?.let { it + 1 } ?: "—"}",
            color = PatrolColors.TextMuted,
        )
        StationChip(state.station)
    }
}

@Composable
private fun ConnectionChip(connection: ConnectionState) {
    val (text, color) = when (connection) {
        ConnectionState.Initializing -> "MSDK: start" to PatrolColors.TextMuted
        ConnectionState.Registering -> "MSDK: rejestracja" to PatrolColors.Warn
        is ConnectionState.RegistrationFailed -> "Rejestracja DJI: błąd" to PatrolColors.Stop
        ConnectionState.Disconnected -> "Brak drona" to PatrolColors.Warn
        is ConnectionState.Connected ->
            if (connection.supported) "Mini 4 Pro" to PatrolColors.Ok
            else "Nieobsługiwany: ${connection.productType}" to PatrolColors.Stop
    }
    StatusChip(text, color)
}

@Composable
private fun StationChip(link: StationLink) {
    val status = link.status
    val (text, color) = when {
        link !is StationLink.Connected -> "Stacja: brak łącza" to PatrolColors.TextMuted
        status == null -> "Stacja: czekam na status" to PatrolColors.Warn
        status.charging -> "Stacja: ładowanie" to PatrolColors.Blue
        !status.padClear -> "Stacja: płyta zajęta" to PatrolColors.Stop
        status.ready -> "Stacja gotowa" to PatrolColors.Ok
        else -> "Stacja niegotowa" to PatrolColors.Warn
    }
    StatusChip(text, color)
}

@Composable
private fun StatusChip(
    text: String,
    color: Color,
    modifier: Modifier = Modifier,
    emphasized: Boolean = false,
) {
    Text(
        text = text,
        color = if (emphasized) Color.Black else color,
        fontSize = 13.sp,
        fontWeight = if (emphasized) FontWeight.Bold else FontWeight.Medium,
        maxLines = 1,
        overflow = TextOverflow.Ellipsis,
        modifier = modifier
            .clip(RoundedCornerShape(50))
            .background(if (emphasized) color else PatrolColors.Panel)
            .border(1.dp, if (emphasized) color else color.copy(alpha = 0.6f), RoundedCornerShape(50))
            .padding(horizontal = 10.dp, vertical = 5.dp),
    )
}

@Composable
private fun AlarmBanner(text: String) {
    Text(
        text = "⚠ $text",
        color = Color.White,
        fontWeight = FontWeight.SemiBold,
        fontSize = 14.sp,
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(6.dp))
            .background(PatrolColors.Stop)
            .padding(horizontal = 12.dp, vertical = 6.dp),
    )
}

// ---------------------------------------------------------------- środek

@Composable
private fun VideoPanel(video: @Composable () -> Unit, state: MainUiState, modifier: Modifier) {
    Panel(modifier) {
        video()
        if (state.vision != null) VisionOverlay(state.vision, Modifier.fillMaxSize())
        PanelLabel("Obraz z kamery", Modifier.align(Alignment.TopStart))
        if (state.connection !is ConnectionState.Connected) {
            Text(
                text = when (state.connection) {
                    is ConnectionState.RegistrationFailed ->
                        "Rejestracja DJI nie powiodła się:\n${state.connection.reason}\nSprawdź klucz API i internet."
                    ConnectionState.Initializing, ConnectionState.Registering ->
                        if (state.isSimulation) "Uruchamianie symulatora…" else "Uruchamianie MSDK…"
                    else ->
                        if (state.isSimulation) "SYMULACJA — dron wyłączony na stacji.\nNaciśnij „Start cyklu”."
                        else "Podłącz RC-N2 i włącz drona"
                },
                color = PatrolColors.TextMuted,
                fontSize = 15.sp,
                modifier = Modifier.align(Alignment.Center).padding(16.dp),
            )
        }
        FlightReadout(state, Modifier.align(Alignment.BottomStart))
    }
}

@Composable
private fun FlightReadout(state: MainUiState, modifier: Modifier) {
    val d = state.drone
    val parts = listOfNotNull(
        d.altitudeM?.let { "H ${fmt(it)} m" },
        d.ultrasonicHeightM?.let { "ToF ${fmt(it)} m" },
        d.velocity?.let { "${fmt(it.horizontalMps)} m/s" },
        d.headingDeg?.let { "${it.toInt()}°" },
        d.distanceToHomeM()?.let { "do domu ${it.toInt()} m" },
        d.flightMode,
    )
    if (parts.isEmpty()) return
    Text(
        text = parts.joinToString("  ·  "),
        color = Color.White,
        fontSize = 12.sp,
        modifier = modifier
            .padding(6.dp)
            .clip(RoundedCornerShape(4.dp))
            .background(Color.Black.copy(alpha = 0.55f))
            .padding(horizontal = 8.dp, vertical = 4.dp),
    )
}

@Composable
private fun Panel(modifier: Modifier, content: @Composable androidx.compose.foundation.layout.BoxScope.() -> Unit) {
    Box(
        modifier
            .clip(RoundedCornerShape(8.dp))
            .background(Color.Black)
            .border(1.dp, PatrolColors.PanelBorder, RoundedCornerShape(8.dp)),
        content = content,
    )
}

@Composable
private fun PanelLabel(text: String, modifier: Modifier) {
    Text(
        text = text,
        color = Color.White,
        fontSize = 11.sp,
        modifier = modifier
            .padding(6.dp)
            .clip(RoundedCornerShape(4.dp))
            .background(Color.Black.copy(alpha = 0.55f))
            .padding(horizontal = 6.dp, vertical = 2.dp),
    )
}

// ---------------------------------------------------------------- akcje testowe (demo)

@Composable
private fun DebugBar(actions: List<DebugAction>) {
    Row(
        Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(6.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text("SYMULACJA:", color = PatrolColors.Warn, fontSize = 12.sp, fontWeight = FontWeight.Bold)
        actions.forEach { action ->
            OutlinedButton(
                onClick = action.run,
                contentPadding = PaddingValues(horizontal = 10.dp, vertical = 2.dp),
                modifier = Modifier.height(30.dp),
            ) {
                Text(action.label, fontSize = 11.sp, maxLines = 1)
            }
        }
    }
}

// ---------------------------------------------------------------- dolny pasek

@Composable
private fun BottomBar(
    state: MainUiState,
    onStart: () -> Unit,
    onReturn: () -> Unit,
    onResume: () -> Unit,
    onReset: () -> Unit,
    onStop: () -> Unit,
    onOpenDiagnostics: () -> Unit,
) {
    val cycle = state.cycle
    Row(
        Modifier.fillMaxWidth().height(76.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        OutlinedButton(
            onClick = onOpenDiagnostics,
            contentPadding = PaddingValues(horizontal = 10.dp, vertical = 6.dp),
        ) {
            Text("Diagnostyka", fontSize = 12.sp, maxLines = 1)
        }
        StageChips(cycle.stage, Modifier.weight(1f))

        when (cycle) {
            is CycleState.Holding -> ActionButton("Wznów", PatrolColors.Ok, onResume)
            is CycleState.Failure -> OutlinedButton(onClick = onReset) { Text("Reset") }
            else -> Unit
        }
        ActionButton("Start cyklu", PatrolColors.Ok, onStart, enabled = cycle == CycleState.Ready)
        ActionButton(
            "Powrót do stacji",
            PatrolColors.Blue,
            onReturn,
            enabled = cycle == CycleState.TakingOff || cycle is CycleState.Patrolling ||
                cycle is CycleState.Holding || cycle is CycleState.Failure,
        )
        StopButton(onStop, active = cycle.isAirborneStage)
    }
}

@Composable
private fun StageChips(current: CycleStage, modifier: Modifier) {
    val stages = listOf(
        CycleStage.START to "Start",
        CycleStage.PATROL to "Patrol",
        CycleStage.RETURN to "Powrót",
        CycleStage.LANDING to "Lądowanie",
        CycleStage.CHARGING to "Ładowanie",
    )
    Row(modifier, horizontalArrangement = Arrangement.spacedBy(4.dp)) {
        stages.forEach { (stage, label) ->
            val active = stage == current
            Text(
                text = label,
                fontSize = 12.sp,
                fontWeight = if (active) FontWeight.Bold else FontWeight.Normal,
                color = if (active) Color.Black else PatrolColors.TextMuted,
                maxLines = 1,
                modifier = Modifier
                    .clip(RoundedCornerShape(6.dp))
                    .background(if (active) PatrolColors.Accent else PatrolColors.Panel)
                    .padding(horizontal = 8.dp, vertical = 6.dp),
            )
        }
    }
}

@Composable
private fun RowScope.ActionButton(text: String, color: Color, onClick: () -> Unit, enabled: Boolean = true) {
    Button(
        onClick = onClick,
        enabled = enabled,
        colors = ButtonDefaults.buttonColors(containerColor = color, contentColor = Color.Black),
        contentPadding = PaddingValues(horizontal = 14.dp, vertical = 10.dp),
    ) {
        Text(text, fontWeight = FontWeight.SemiBold, maxLines = 1)
    }
}

/** Duży czerwony STOP — zawsze aktywny, bez potwierdzenia (sekcja 5.8). */
@Composable
internal fun StopButton(onClick: () -> Unit, active: Boolean) {
    Button(
        onClick = onClick,
        shape = CircleShape,
        colors = ButtonDefaults.buttonColors(containerColor = PatrolColors.Stop, contentColor = Color.White),
        contentPadding = PaddingValues(0.dp),
        modifier = Modifier
            .size(72.dp)
            .border(if (active) 3.dp else 0.dp, Color.White, CircleShape),
    ) {
        Text("STOP", fontWeight = FontWeight.Black, fontSize = 16.sp)
    }
    Spacer(Modifier.width(4.dp))
}

private fun fmt(value: Double): String = String.format(Locale.US, "%.1f", value)

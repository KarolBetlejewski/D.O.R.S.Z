package com.example.djicontroller.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.tooling.preview.Preview
import com.example.djicontroller.core.model.ConnectionState
import com.example.djicontroller.core.model.CycleState
import com.example.djicontroller.core.model.DroneState
import com.example.djicontroller.core.model.FailsafeAction
import com.example.djicontroller.core.model.GeoPoint
import com.example.djicontroller.core.model.PatrolRoute
import com.example.djicontroller.core.model.PatrolWaypoint
import com.example.djicontroller.core.model.StationLink
import com.example.djicontroller.core.model.StationStatus
import com.example.djicontroller.core.model.Velocity3D
import com.example.djicontroller.core.orchestrator.SafetyAlarm

/*
 * Podglądy ekranu głównego w Android Studio (zakładka Split/Design) — bez telefonu, drona i SDK DJI.
 * Dane są przykładowe. Rozdzielczość odpowiada typowemu telefonowi w poziomie.
 */

private val station = GeoPoint(52.2297, 21.0122)

private val sampleRoute = PatrolRoute(
    id = "demo",
    name = "Patrol demo",
    station = station,
    waypoints = listOf(
        PatrolWaypoint(station.offsetBy(120.0, -60.0), 30.0),
        PatrolWaypoint(station.offsetBy(180.0, 60.0), 30.0),
        PatrolWaypoint(station.offsetBy(120.0, 170.0), 30.0),
        PatrolWaypoint(station.offsetBy(-20.0, 200.0), 30.0),
        PatrolWaypoint(station.offsetBy(-90.0, 110.0), 30.0),
        PatrolWaypoint(station.offsetBy(-60.0, -20.0), 30.0),
    ),
)

private val flyingDrone = DroneState(
    connected = true,
    batteryPercent = 64,
    location = station.offsetBy(150.0, 110.0),
    altitudeM = 30.2,
    headingDeg = 72.0,
    velocity = Velocity3D(1.6, 4.9, 0.0),
    gpsSatelliteCount = 19,
    gpsSignalLevel = 5,
    isFlying = true,
    areMotorsOn = true,
    flightMode = "WAYPOINT",
    homeLocation = station,
    failsafeAction = FailsafeAction.GO_HOME,
)

private val stationReady = StationLink.Connected(
    StationStatus(charging = false, dronePresent = false, padClear = true, ready = true, receivedAtMs = 0),
)

@Composable
private fun PreviewScreen(state: MainUiState) {
    PatrolTheme {
        PatrolContent(
            state = state,
            snackbar = remember { SnackbarHostState() },
            video = { FakeVideo() },
            onStart = {}, onReturn = {}, onResume = {}, onReset = {}, onStop = {},
        )
    }
}

/** Zamiast obrazu z kamery — w podglądzie nie ma strumienia wideo. */
@Composable
private fun FakeVideo() {
    Box(Modifier.fillMaxSize().background(Color(0xFF2A3138))) {
        Text("[ obraz z kamery drona ]", color = Color(0xFF8A939C), modifier = Modifier.align(Alignment.Center))
    }
}

@Preview(name = "Patrol w toku", widthDp = 891, heightDp = 411, showBackground = true)
@Composable
fun PreviewPatrolling() = PreviewScreen(
    MainUiState(
        connection = ConnectionState.Connected(1, "DJI_MINI_4_PRO", supported = true),
        drone = flyingDrone,
        cycle = CycleState.Patrolling(waypoint = 2, total = 6),
        breakpoint = 3,
        station = stationReady,
        route = sampleRoute,
        returnThresholdPercent = 35,
    ),
)

@Preview(name = "STOP — zawis z alarmem", widthDp = 891, heightDp = 411, showBackground = true)
@Composable
fun PreviewHolding() = PreviewScreen(
    MainUiState(
        connection = ConnectionState.Connected(1, "DJI_MINI_4_PRO", supported = true),
        drone = flyingDrone.copy(batteryPercent = 38, location = station.offsetBy(2.0, 1.0), altitudeM = 6.5),
        cycle = CycleState.Holding(CycleState.Landing(attempt = 2)),
        station = StationLink.Disconnected,
        alarms = listOf(SafetyAlarm("station", "Lądowanie wstrzymane: utrata łącza ze stacją", 0)),
        route = sampleRoute,
        returnThresholdPercent = 25,
    ),
)

@Preview(name = "Start aplikacji — brak drona", widthDp = 891, heightDp = 411, showBackground = true)
@Composable
fun PreviewNoDrone() = PreviewScreen(
    MainUiState(connection = ConnectionState.Disconnected),
)

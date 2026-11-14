package com.example.djicontroller.di

import android.app.Application
import com.example.djicontroller.core.model.PatrolRoute
import com.example.djicontroller.core.port.CameraPort
import com.example.djicontroller.core.port.ConnectionPort
import com.example.djicontroller.core.port.FlightControlPort
import com.example.djicontroller.core.port.MissionPort
import com.example.djicontroller.core.port.SimulatorPort
import com.example.djicontroller.core.port.StationPort
import com.example.djicontroller.core.port.TelemetryPort
import com.example.djicontroller.ui.VideoPreview

/**
 * Zestaw implementacji portów z :core dla danego wariantu budowania:
 * - `prod` — DJI MSDK (:msdk-adapter), tylko telefony ARM,
 * - `demo` — symulator drona i stacji, bez SDK DJI, działa na emulatorze x86.
 *
 * Wariant wybiera fabryka `createDroneBackend` z katalogu `src/prod` lub `src/demo`.
 */
interface DroneBackend {
    /** Krótka nazwa do UI i logów, np. „DJI MSDK” albo „SYMULACJA”. */
    val name: String
    val isSimulation: Boolean

    val connection: ConnectionPort
    val telemetry: TelemetryPort
    val camera: CameraPort
    val videoPreview: VideoPreview
    val flight: FlightControlPort
    val mission: MissionPort
    val station: StationPort

    /** Symulator MSDK (test R3 bez śmigieł); `null` w `demo`, gdzie cały dron jest symulowany. */
    val simulator: SimulatorPort?

    /** Trasa dostępna bez ekranu planowania (F4). W `prod` brak, w `demo` przykładowa. */
    val defaultRoute: PatrolRoute?

    /** Akcje testowe pokazywane na ekranie (np. „Bateria −20%”). W `prod` lista jest pusta. */
    val debugActions: List<DebugAction>

    /** Uruchamia backend; wołane raz z `AppContainer.start()`. */
    fun start()
}

data class DebugAction(val label: String, val run: () -> Unit)

/** Hook wariantu wołany w `Application.attachBaseContext` (w `prod`: wymaganie MSDK). */
fun interface PlatformBootstrap {
    fun install(application: Application)
}

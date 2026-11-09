package com.example.djicontroller.msdk.flight

import com.example.djicontroller.core.logging.command
import com.example.djicontroller.core.logging.system
import com.example.djicontroller.core.model.CommandResult
import com.example.djicontroller.core.model.GeoPoint
import com.example.djicontroller.core.model.SimulatorStatus
import com.example.djicontroller.core.port.FlightLogger
import com.example.djicontroller.core.port.LogLevel
import com.example.djicontroller.core.port.SimulatorPort
import com.example.djicontroller.core.port.TelemetryPort
import com.example.djicontroller.msdk.common.awaitCompletion
import com.example.djicontroller.msdk.connection.SdkInitializer
import com.example.djicontroller.msdk.telemetry.toGeoPoint
import dji.sdk.keyvalue.value.common.LocationCoordinate2D
import dji.v5.manager.aircraft.simulator.InitializationSettings
import dji.v5.manager.aircraft.simulator.SimulatorManager
import dji.v5.manager.aircraft.simulator.SimulatorStatusListener
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.filter
import kotlinx.coroutines.flow.launchIn
import kotlinx.coroutines.flow.onEach
import kotlinx.coroutines.flow.update

/**
 * Symulator MSDK (wzorzec `SimulatorVM.kt`): dron podłączony przez RC-N2 „lata” w symulatorze
 * kontrolera lotu — silniki się nie kręcą, telemetria (pozycja, wysokość, prędkość) pochodzi z modelu.
 * Pozwala przejść test osi Virtual Stick i R3 bez śmigieł.
 *
 * Uwaga: obraz z kamery w symulatorze jest prawdziwy (kamera stoi), więc marker trzeba
 * pokazać kamerze ręcznie albo użyć testowego źródła klatek.
 */
class SimulatorAdapter(
    private val sdk: SdkInitializer,
    scope: CoroutineScope,
    private val telemetry: TelemetryPort,
    private val logger: FlightLogger,
) : SimulatorPort {

    private val manager
        get() = SimulatorManager.getInstance()

    private val _status = MutableStateFlow(SimulatorStatus())
    override val status: StateFlow<SimulatorStatus> = _status.asStateFlow()

    private val listener = SimulatorStatusListener { state ->
        _status.value = SimulatorStatus(
            enabled = manager.isSimulatorEnabled,
            location = state.location?.toGeoPoint(),
            areMotorsOn = state.areMotorsOn(),
            isFlying = state.isFlying,
            positionM = Triple(state.positionX.toDouble(), state.positionY.toDouble(), state.positionZ.toDouble()),
        )
    }

    init {
        sdk.initialized
            .filter { it }
            .onEach {
                manager.addSimulatorStateListener(listener)
                _status.update { it.copy(enabled = manager.isSimulatorEnabled) }
                logger.system("simulator_listener", fields = mapOf("enabled" to manager.isSimulatorEnabled))
            }
            .launchIn(scope)
    }

    override suspend fun enable(location: GeoPoint, satellites: Int): CommandResult {
        if (!sdk.initialized.value) return CommandResult.Failure("MSDK nie jest jeszcze zainicjalizowane")
        if (!location.isValid) return CommandResult.Failure("Nieprawidłowe współrzędne startowe")
        val d = telemetry.state.value
        if (!d.connected) return CommandResult.Failure("Brak połączenia z dronem")
        if (d.areMotorsOn == true || d.isFlying == true) {
            return CommandResult.Failure("Symulator można włączyć tylko przy wyłączonych silnikach")
        }
        val settings = InitializationSettings.createInstance(
            LocationCoordinate2D(location.latitude, location.longitude),
            satellites.coerceIn(0, 20),
        )
        val result = awaitCompletion { cb -> manager.enableSimulator(settings, cb) }
        logger.command(
            "simulator_enable",
            result,
            mapOf("lat" to location.latitude, "lon" to location.longitude, "sats" to satellites),
        )
        if (result.isSuccess) _status.update { it.copy(enabled = true, location = location) }
        return result
    }

    override suspend fun disable(): CommandResult {
        if (!sdk.initialized.value) return CommandResult.Failure("MSDK nie jest jeszcze zainicjalizowane")
        if (_status.value.isFlying == true || telemetry.state.value.isFlying == true) {
            logger.system("simulator_disable_refused", LogLevel.WARN)
            return CommandResult.Failure("Najpierw wyląduj w symulatorze")
        }
        val result = awaitCompletion { cb -> manager.disableSimulator(cb) }
        logger.command("simulator_disable", result)
        if (result.isSuccess) _status.value = SimulatorStatus(enabled = false)
        return result
    }
}

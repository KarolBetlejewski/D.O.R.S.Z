package com.example.djicontroller.msdk.telemetry

import com.example.djicontroller.core.logging.system
import com.example.djicontroller.core.model.DroneState
import com.example.djicontroller.core.model.FailsafeAction
import com.example.djicontroller.core.model.GeoPoint
import com.example.djicontroller.core.model.Velocity3D
import com.example.djicontroller.core.port.Clock
import com.example.djicontroller.core.port.FlightLogger
import com.example.djicontroller.core.port.LogLevel
import com.example.djicontroller.core.port.TelemetryPort
import com.example.djicontroller.msdk.common.asFlow
import com.example.djicontroller.msdk.connection.SdkInitializer
import com.example.djicontroller.msdk.connection.whenInitialized
import dji.sdk.keyvalue.key.BatteryKey
import dji.sdk.keyvalue.key.DJIKey
import dji.sdk.keyvalue.key.FlightControllerKey
import dji.sdk.keyvalue.key.KeyTools
import dji.sdk.keyvalue.value.common.LocationCoordinate2D
import dji.sdk.keyvalue.value.flightcontroller.GPSSignalLevel
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.flow.launchIn
import kotlinx.coroutines.flow.onEach
import kotlinx.coroutines.flow.update
import java.util.concurrent.atomic.AtomicBoolean
import dji.sdk.keyvalue.value.flightcontroller.FailsafeAction as DjiFailsafeAction

/**
 * Telemetria (sekcja 5.2, zasada Z1): wszystkie klucze z tabeli zamienione na `Flow`
 * przez [asFlow] i złożone w jeden `StateFlow<DroneState>`.
 *
 * Uwaga do PDF: MSDK V5 nie ma kluczy `KeyAircraftLocation` ani `KeyAltitude` —
 * pozycję i wysokość względem punktu startu daje jeden klucz `KeyAircraftLocation3D`.
 *
 * Semantyka `null`: wartość `null` z SDK (np. chwilowe rozłączenie komponentu) nie kasuje
 * ostatniej znanej wartości — dzięki temu po utracie łącza w powietrzu stan nadal mówi
 * „lata” (ostrożniej dla resetu). O świeżości danych informuje `connected` i `updatedAtMs`.
 */
class KeyRepository(
    private val sdk: SdkInitializer,
    private val scope: CoroutineScope,
    private val logger: FlightLogger,
    private val clock: Clock,
) : TelemetryPort {

    private val _state = MutableStateFlow(DroneState.UNKNOWN)
    override val state: StateFlow<DroneState> = _state.asStateFlow()

    private val started = AtomicBoolean(false)

    /** Rozpoczyna nasłuch wszystkich kluczy. Nasłuch ruszy faktycznie po `INITIALIZE_COMPLETE`. */
    fun start() {
        if (!started.compareAndSet(false, true)) return

        // KeyConnection: false przy utracie łącza z kontrolerem lotu — jedyne pole, dla którego null = rozłączony.
        sdk.whenInitialized { KeyTools.createKey(FlightControllerKey.KeyConnection).asFlow() }
            .onEach { v -> mutate("KeyConnection") { it.copy(connected = v == true) } }
            .catch { logKeyError("KeyConnection", it) }
            .launchIn(scope)

        bind({ KeyTools.createKey(BatteryKey.KeyChargeRemainingInPercent) }, "KeyChargeRemainingInPercent") { s, v ->
            s.copy(batteryPercent = v.coerceIn(0, 100))
        }
        bind({ KeyTools.createKey(FlightControllerKey.KeyAircraftLocation3D) }, "KeyAircraftLocation3D") { s, v ->
            s.copy(location = GeoPoint(v.latitude ?: Double.NaN, v.longitude ?: Double.NaN), altitudeM = v.altitude)
        }
        bind({ KeyTools.createKey(FlightControllerKey.KeyUltrasonicHeight) }, "KeyUltrasonicHeight") { s, v ->
            s.copy(ultrasonicHeightM = v / 10.0) // SDK podaje decymetry
        }
        bind({ KeyTools.createKey(FlightControllerKey.KeyCompassHeading) }, "KeyCompassHeading") { s, v ->
            s.copy(headingDeg = v) // −180..180, 0 = północ
        }
        bind({ KeyTools.createKey(FlightControllerKey.KeyAircraftVelocity) }, "KeyAircraftVelocity") { s, v ->
            s.copy(velocity = Velocity3D(northMps = v.x ?: 0.0, eastMps = v.y ?: 0.0, downMps = v.z ?: 0.0))
        }
        bind({ KeyTools.createKey(FlightControllerKey.KeyGPSSatelliteCount) }, "KeyGPSSatelliteCount") { s, v ->
            s.copy(gpsSatelliteCount = v)
        }
        bind({ KeyTools.createKey(FlightControllerKey.KeyGPSSignalLevel) }, "KeyGPSSignalLevel") { s, v ->
            s.copy(gpsSignalLevel = v.toLevel())
        }
        bind({ KeyTools.createKey(FlightControllerKey.KeyIsFlying) }, "KeyIsFlying") { s, v ->
            s.copy(isFlying = v)
        }
        bind({ KeyTools.createKey(FlightControllerKey.KeyAreMotorsOn) }, "KeyAreMotorsOn") { s, v ->
            s.copy(areMotorsOn = v)
        }
        bind({ KeyTools.createKey(FlightControllerKey.KeyFlightMode) }, "KeyFlightMode") { s, v ->
            s.copy(flightMode = v.name)
        }
        bind({ KeyTools.createKey(FlightControllerKey.KeyHomeLocation) }, "KeyHomeLocation") { s, v ->
            s.copy(homeLocation = v.toGeoPoint())
        }
        bind({ KeyTools.createKey(FlightControllerKey.KeyIsLowBatteryWarning) }, "KeyIsLowBatteryWarning") { s, v ->
            s.copy(isLowBatteryWarning = v)
        }
        bind({ KeyTools.createKey(FlightControllerKey.KeyIsSeriousLowBatteryWarning) }, "KeyIsSeriousLowBatteryWarning") { s, v ->
            s.copy(isSeriousLowBatteryWarning = v)
        }
        bind({ KeyTools.createKey(FlightControllerKey.KeyIsFailSafe) }, "KeyIsFailSafe") { s, v ->
            s.copy(isFailSafe = v)
        }
        bind({ KeyTools.createKey(FlightControllerKey.KeyFailsafeAction) }, "KeyFailsafeAction") { s, v ->
            s.copy(failsafeAction = v.toCore())
        }
        bind({ KeyTools.createKey(FlightControllerKey.KeyIsLandingConfirmationNeeded) }, "KeyIsLandingConfirmationNeeded") { s, v ->
            s.copy(isLandingConfirmationNeeded = v)
        }
        logger.system("telemetry_bound", fields = mapOf("keys" to 17))
    }

    /** Nasłuch jednego klucza; wartości `null` są pomijane (zachowujemy ostatnią znaną). */
    private fun <T : Any> bind(
        keyFactory: () -> DJIKey<T>,
        name: String,
        reducer: (DroneState, T) -> DroneState,
    ) {
        sdk.whenInitialized { keyFactory().asFlow() }
            .filterNotNull()
            .onEach { value -> mutate(name) { reducer(it, value) } }
            .catch { logKeyError(name, it) }
            .launchIn(scope)
    }

    private inline fun mutate(name: String, crossinline transform: (DroneState) -> DroneState) {
        try {
            _state.update { transform(it).copy(updatedAtMs = clock.monotonicMs()) }
        } catch (e: RuntimeException) {
            // Nietypowa wartość z SDK nie może zatrzymać całej telemetrii.
            logger.system("telemetry_value_error", LogLevel.WARN, mapOf("key" to name, "error" to e.toString()))
        }
    }

    private fun logKeyError(name: String, error: Throwable) {
        logger.system("telemetry_key_failed", LogLevel.ERROR, mapOf("key" to name, "error" to error.toString()))
    }
}

/** LEVEL_0..LEVEL_5 → 0..5; LEVEL_10 (RTK) → 5; LEVEL_NONE → 0; inne → null. */
internal fun GPSSignalLevel.toLevel(): Int? = when (name) {
    "LEVEL_NONE" -> 0
    "LEVEL_10" -> 5
    else -> name.removePrefix("LEVEL_").toIntOrNull()?.coerceIn(0, 5)
}

internal fun DjiFailsafeAction.toCore(): FailsafeAction = when (name) {
    "HOVER" -> FailsafeAction.HOVER
    "LANDING" -> FailsafeAction.LANDING
    "GOHOME" -> FailsafeAction.GO_HOME
    else -> FailsafeAction.UNKNOWN
}

internal fun LocationCoordinate2D.toGeoPoint(): GeoPoint =
    GeoPoint(latitude ?: Double.NaN, longitude ?: Double.NaN)

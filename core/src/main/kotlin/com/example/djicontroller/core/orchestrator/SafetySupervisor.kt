package com.example.djicontroller.core.orchestrator

import com.example.djicontroller.core.logging.command
import com.example.djicontroller.core.logging.safety
import com.example.djicontroller.core.model.CommandResult
import com.example.djicontroller.core.model.ConnectionState
import com.example.djicontroller.core.model.ControlLoss
import com.example.djicontroller.core.model.ControlOwner
import com.example.djicontroller.core.model.CycleState
import com.example.djicontroller.core.model.DroneState
import com.example.djicontroller.core.model.GeoPoint
import com.example.djicontroller.core.model.MissionStatus
import com.example.djicontroller.core.model.StationLink
import com.example.djicontroller.core.model.isAirborneStage
import com.example.djicontroller.core.model.status
import com.example.djicontroller.core.port.Clock
import com.example.djicontroller.core.port.ConnectionPort
import com.example.djicontroller.core.port.FlightControlPort
import com.example.djicontroller.core.port.FlightLogger
import com.example.djicontroller.core.port.LogLevel
import com.example.djicontroller.core.port.MissionPort
import com.example.djicontroller.core.port.StationPort
import com.example.djicontroller.core.port.TelemetryPort
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Job
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.launch

/** Polecenie nadzoru dla orkiestratora; ma pierwszeństwo przed bieżącym etapem cyklu. */
sealed interface SafetyDirective {
    val reason: String

    /** Przejście do ZAWISU (`CycleState.Holding`); na ziemi — przerwanie etapu. */
    data class Hold(override val reason: String) : SafetyDirective

    /** Przejście do AWARII (`CycleState.Failure`) — dalsza decyzja należy do operatora. */
    data class Fail(override val reason: String) : SafetyDirective
}

/** Aktywny alarm do wyświetlenia w UI. */
data class SafetyAlarm(val key: String, val message: String, val sinceMs: Long)

/**
 * Nadzór i STOP (sekcja 5.8) — najwyższy priorytet w aplikacji.
 *
 * Reaguje w dwóch krokach: najpierw wysyła [SafetyDirective] (orkiestrator anuluje bieżący etap,
 * żeby nie wydał już żadnego polecenia), a zaraz potem sam zatrzymuje ruch drona, nie czekając
 * na orkiestrator. Nigdy nie wyłącza silników w powietrzu (Z4).
 */
class SafetySupervisor(
    private val scope: CoroutineScope,
    private val telemetry: TelemetryPort,
    private val connection: ConnectionPort,
    private val flight: FlightControlPort,
    private val mission: MissionPort,
    private val station: StationPort,
    private val batteryPolicy: BatteryPolicy,
    private val logger: FlightLogger,
    private val clock: Clock,
) {
    private val directiveChannel = Channel<SafetyDirective>(Channel.UNLIMITED)

    /** Strumień dyrektyw — konsumowany przez dokładnie jednego odbiorcę (orkiestrator). */
    val directives: Flow<SafetyDirective> = directiveChannel.receiveAsFlow()

    private val _alarms = MutableStateFlow<List<SafetyAlarm>>(emptyList())
    val alarms: StateFlow<List<SafetyAlarm>> = _alarms.asStateFlow()

    private var activeFindings: Map<String, SafetyAlarm> = emptyMap()

    /**
     * Przycisk STOP: natychmiastowy zawis, bez potwierdzenia. Nieblokujące — można wołać z wątku UI.
     * Część synchroniczna (log + dyrektywa) wykonuje się jeszcze przed powrotem z tej funkcji.
     */
    fun stop(source: String = "operator"): Job =
        scope.launch(start = CoroutineStart.UNDISPATCHED) { stopNow(source) }

    suspend fun stopNow(source: String = "operator") {
        logger.safety("stop", LogLevel.WARN, mapOf("source" to source, "owner" to flight.controlOwner.value))
        directiveChannel.send(SafetyDirective.Hold("STOP ($source)"))
        haltMotion()
    }

    /**
     * Zatrzymuje ruch drona zależnie od właściciela sterowania. Idempotentne:
     * misja już wstrzymana nie jest pauzowana ponownie, a zawis Virtual Stick można powtarzać.
     */
    suspend fun haltMotion(): CommandResult {
        val owner = flight.controlOwner.value
        val result = when (owner) {
            ControlOwner.MISSION ->
                if (mission.progress.value.status == MissionStatus.PAUSED) {
                    CommandResult.Success
                } else {
                    val paused = mission.pause()
                    if (paused is CommandResult.Failure) {
                        logger.command("mission_pause", paused)
                        mission.stop()
                    } else {
                        paused
                    }
                }
            ControlOwner.VIRTUAL_STICK,
            ControlOwner.AUTO_LANDING,
            ControlOwner.NONE -> flight.holdPosition()
        }
        logger.command("halt_motion", result, mapOf("owner" to owner))
        return result
    }

    /**
     * Ciągła ocena warunków bezpieczeństwa. Działa do anulowania; uruchamiana przez orkiestrator.
     * @param stationLocation pozycja stacji z aktywnej trasy (do oceny baterii)
     */
    suspend fun monitor(cycle: StateFlow<CycleState>, stationLocation: Flow<GeoPoint?>): Unit = coroutineScope {
        launch {
            combine(cycle, telemetry.state, connection.state, station.link, stationLocation) { c, d, conn, link, st ->
                assess(c, d, conn, link, st)
            }.collect { findings -> onFindings(findings) }
        }
        launch {
            flight.controlLost.collect { loss -> onControlLost(loss, cycle.value) }
        }
    }

    /** Czysta ocena stanu — wyodrębniona do testów. */
    internal fun assess(
        cycle: CycleState,
        drone: DroneState,
        conn: ConnectionState,
        link: StationLink,
        stationLocation: GeoPoint?,
    ): List<Finding> {
        if (!cycle.isAirborneStage) return emptyList()
        val findings = mutableListOf<Finding>()

        if (conn !is ConnectionState.Connected) {
            findings += Finding.DroneLinkLost("brak połączenia z dronem")
        } else if (drone.isFailSafe == true) {
            findings += Finding.DroneLinkLost("dron w trybie failsafe")
        }

        // W stanie Landing procedura i tak sprowadza drona na stację — tam nie lądujemy „w miejscu”.
        if (cycle !is CycleState.Landing) {
            val critical = if (stationLocation != null) {
                batteryPolicy.evaluate(drone, stationLocation) as? BatteryDecision.LandNow
            } else if (drone.isSeriousLowBatteryWarning == true) {
                BatteryDecision.LandNow(drone.batteryPercent, "Dron zgłosił krytycznie niski poziom baterii")
            } else {
                null
            }
            if (critical != null) findings += Finding.CriticalBattery(critical.reason)
        }

        if (cycle is CycleState.Landing) {
            val status = link.status
            when {
                link !is StationLink.Connected -> findings += Finding.StationUnavailable("utrata łącza ze stacją")
                status == null -> findings += Finding.StationUnavailable("brak statusu stacji")
                !status.padClear -> findings += Finding.StationUnavailable("płyta lądowiska zajęta")
            }
        }
        return findings
    }

    private suspend fun onFindings(findings: List<Finding>) {
        val now = clock.monotonicMs()
        val previous = activeFindings
        val current = findings.associate { f -> f.key to (previous[f.key] ?: SafetyAlarm(f.key, f.message, now)) }
        activeFindings = current
        _alarms.value = current.values.toList()

        for (finding in findings) {
            if (finding.key in previous) continue   // reagujemy tylko na pojawienie się warunku
            when (finding) {
                is Finding.DroneLinkLost -> {
                    logger.safety("drone_link_lost", LogLevel.ERROR, mapOf("detail" to finding.detail))
                    directiveChannel.send(SafetyDirective.Fail(finding.message))
                }
                is Finding.CriticalBattery -> emergencyLand(finding.message)
                is Finding.StationUnavailable -> {
                    logger.safety("station_unavailable", LogLevel.ERROR, mapOf("detail" to finding.detail))
                    directiveChannel.send(SafetyDirective.Hold(finding.message))
                    haltMotion()
                }
            }
        }
        for (key in previous.keys - current.keys) {
            logger.safety("alarm_cleared", LogLevel.INFO, mapOf("key" to key))
        }
    }

    private suspend fun onControlLost(loss: ControlLoss, cycle: CycleState) {
        logger.safety(
            "control_lost",
            LogLevel.ERROR,
            mapOf("reason" to loss.reason, "rc" to loss.takenOverByRc, "state" to cycle.toString()),
        )
        if (!cycle.isAirborneStage) return
        if (loss.takenOverByRc) {
            directiveChannel.send(SafetyDirective.Fail("Sterowanie przejęte z aparatury: ${loss.reason}"))
        } else {
            directiveChannel.send(SafetyDirective.Hold("Utrata kontroli Virtual Stick: ${loss.reason}"))
            haltMotion()
        }
    }

    /** Krytyczna bateria: lądowanie w bieżącym miejscu (sekcja 5.8). */
    private suspend fun emergencyLand(reason: String) {
        logger.safety("emergency_landing", LogLevel.ERROR, mapOf("reason" to reason))
        directiveChannel.send(SafetyDirective.Fail("Lądowanie awaryjne: $reason"))
        when (flight.controlOwner.value) {
            ControlOwner.MISSION -> logger.command("mission_stop", mission.stop())
            ControlOwner.VIRTUAL_STICK -> logger.command("vs_stop", flight.stopVirtualStick())
            ControlOwner.AUTO_LANDING, ControlOwner.NONE -> Unit
        }
        if (flight.controlOwner.value != ControlOwner.AUTO_LANDING) {
            logger.command("auto_landing_emergency", flight.startAutoLanding())
        }
    }

    internal sealed interface Finding {
        val key: String
        val message: String

        data class DroneLinkLost(val detail: String) : Finding {
            override val key = "drone_link"
            override val message = "Utrata łącza z dronem ($detail) – dron wykonuje własną procedurę failsafe"
        }

        data class CriticalBattery(override val message: String) : Finding {
            override val key = "battery_critical"
        }

        data class StationUnavailable(val detail: String) : Finding {
            override val key = "station"
            override val message = "Lądowanie wstrzymane: $detail"
        }
    }
}

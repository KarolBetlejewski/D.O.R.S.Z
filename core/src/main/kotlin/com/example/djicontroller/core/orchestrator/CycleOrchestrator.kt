package com.example.djicontroller.core.orchestrator

import com.example.djicontroller.core.landing.LandingOutcome
import com.example.djicontroller.core.landing.LandingProcedure
import com.example.djicontroller.core.logging.command
import com.example.djicontroller.core.logging.safety
import com.example.djicontroller.core.logging.system
import com.example.djicontroller.core.logging.transition
import com.example.djicontroller.core.model.CommandResult
import com.example.djicontroller.core.model.ConnectionState
import com.example.djicontroller.core.model.ControlOwner
import com.example.djicontroller.core.model.CycleState
import com.example.djicontroller.core.model.DroneState
import com.example.djicontroller.core.model.FailsafeAction
import com.example.djicontroller.core.model.MissionPlan
import com.example.djicontroller.core.model.MissionStatus
import com.example.djicontroller.core.model.PatrolRoute
import com.example.djicontroller.core.model.PowerAction
import com.example.djicontroller.core.model.ReturnReason
import com.example.djicontroller.core.model.RouteLimits
import com.example.djicontroller.core.model.StationCommand
import com.example.djicontroller.core.model.StationLink
import com.example.djicontroller.core.model.isAirborneStage
import com.example.djicontroller.core.model.label
import com.example.djicontroller.core.model.onFailure
import com.example.djicontroller.core.model.status
import com.example.djicontroller.core.port.BreakpointStore
import com.example.djicontroller.core.port.Clock
import com.example.djicontroller.core.port.ConnectionPort
import com.example.djicontroller.core.port.FlightControlPort
import com.example.djicontroller.core.port.FlightLogger
import com.example.djicontroller.core.port.LogCategory
import com.example.djicontroller.core.port.LogLevel
import com.example.djicontroller.core.port.MissionPort
import com.example.djicontroller.core.port.StationPort
import com.example.djicontroller.core.port.TelemetryPort
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.filter
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.mapNotNull
import kotlinx.coroutines.flow.merge
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withTimeoutOrNull
import kotlin.math.abs
import kotlin.math.max

/** Parametry cyklu; czasy w milisekundach. Wartości do kalibracji w testach R2–R6. */
data class CycleConfig(
    val powerOnTimeoutMs: Long = 90_000,
    val preflightTimeoutMs: Long = 120_000,
    val minGpsSignalLevel: Int = 4,
    val minSatellites: Int = 10,
    val minTakeoffBatteryPercent: Int = 80,
    /** Maksymalna odległość punktu startu (home) od stacji zapisanej w trasie, m. */
    val maxHomeOffsetFromStationM: Double = 10.0,
    /** Wymaga `KeyFailsafeAction == GO_HOME` przed startem (sekcja 5.8: utrata łącza → powrót na stację). */
    val requireGoHomeFailsafe: Boolean = true,
    val takeoffTimeoutMs: Long = 20_000,
    val takeoffMinAltitudeM: Double = 1.0,
    val returnAltitudeM: Double = 30.0,
    val returnSpeedMps: Double = 6.0,
    /** Promień, w którym uznajemy, że dron jest już nad stacją, m. */
    val arrivalRadiusM: Double = 3.0,
    val returnTimeoutMarginMs: Long = 60_000,
    val maxLandingAttempts: Int = 3,
    val motorsOffTimeoutMs: Long = 30_000,
    val powerOffTimeoutMs: Long = 45_000,
    val chargeTimeoutMs: Long = 3L * 60 * 60 * 1_000,
    val routeLimits: RouteLimits = RouteLimits(),
) {
    init {
        require(maxLandingAttempts >= 1) { "maxLandingAttempts musi być ≥ 1" }
        require(arrivalRadiusM > 0) { "arrivalRadiusM musi być dodatni" }
        require(returnSpeedMps > 0) { "returnSpeedMps musi być dodatni" }
    }
}

/**
 * Maszyna stanów cyklu pracy (sekcja 4).
 *
 * Każdy stan ma własny etap — funkcję `suspend` uruchamianą w osobnym [Job], która zwraca następny
 * stan. Polecenia operatora i dyrektywy [SafetySupervisor] anulują bieżący etap i wchodzą w nowy stan.
 * Wszystkie przejścia są serializowane przez [Mutex] i logowane (Z5); zdezaktualizowany etap
 * rozpoznajemy po numerze epoki, więc nie może nadpisać nowszego stanu.
 */
class CycleOrchestrator(
    private val scope: CoroutineScope,
    private val telemetry: TelemetryPort,
    private val connection: ConnectionPort,
    private val flight: FlightControlPort,
    private val mission: MissionPort,
    private val station: StationPort,
    private val landing: LandingProcedure,
    private val safety: SafetySupervisor,
    private val batteryPolicy: BatteryPolicy,
    private val breakpoints: BreakpointStore,
    private val logger: FlightLogger,
    private val clock: Clock,
    private val config: CycleConfig = CycleConfig(),
) {
    private val _state = MutableStateFlow<CycleState>(CycleState.Ready)
    val state: StateFlow<CycleState> = _state.asStateFlow()

    private val _activeRoute = MutableStateFlow<PatrolRoute?>(null)
    val activeRoute: StateFlow<PatrolRoute?> = _activeRoute.asStateFlow()

    /** Punkt przerwania aktywnej trasy (ekran: „Punkt przerwania”). */
    private val _breakpoint = MutableStateFlow<Int?>(null)
    val breakpoint: StateFlow<Int?> = _breakpoint.asStateFlow()

    private val mutex = Mutex()
    private var stageJob: Job? = null
    private var epoch = 0L
    private var started: Job? = null

    @Volatile private var plan: MissionPlan? = null
    @Volatile private var lastPatrolIndex: Int? = null
    @Volatile private var chargeRequestedAtMs: Long? = null
    private var planCounter = 0

    /** Uruchamia odbiór dyrektyw nadzoru i monitoring bezpieczeństwa. Wołać raz, po utworzeniu. */
    fun start(): Job {
        check(started == null) { "CycleOrchestrator już uruchomiony" }
        val job = scope.launch {
            launch { safety.directives.collect { applyDirective(it) } }
            launch { safety.monitor(state, activeRoute.map { it?.station }) }
        }
        started = job
        logger.system("orchestrator_started")
        return job
    }

    // ------------------------------------------------------------------ polecenia operatora

    /** „Start cyklu”. Dozwolone tylko w stanie GOTOWY, przy gotowej stacji i poprawnej trasie. */
    suspend fun startCycle(route: PatrolRoute): CommandResult = mutex.withLock {
        val current = _state.value
        if (current != CycleState.Ready) {
            return reject("start_cycle", "Cykl można rozpocząć tylko w stanie GOTOWY (teraz: ${current.label()})")
        }
        val issues = route.validate(config.routeLimits)
        if (issues.isNotEmpty()) {
            val text = issues.joinToString("; ") { i -> i.waypointIndex?.let { "punkt ${it + 1}: ${i.message}" } ?: i.message }
            return reject("start_cycle", "Trasa niepoprawna: $text")
        }
        val status = station.link.value.status
        val stationProblem = when {
            station.link.value !is StationLink.Connected -> "brak łącza ze stacją"
            status == null -> "brak statusu stacji"
            !status.ready -> "stacja niegotowa"
            !status.dronePresent -> "brak drona na stacji"
            !status.padClear -> "płyta lądowiska zajęta"
            else -> null
        }
        if (stationProblem != null) return reject("start_cycle", "Nie można wystartować: $stationProblem")

        val saved = breakpoints.load(route.id)?.takeIf { it in route.waypoints.indices }
        _activeRoute.value = route
        _breakpoint.value = saved
        plan = null
        lastPatrolIndex = saved ?: 0
        chargeRequestedAtMs = null
        logger.command("start_cycle", CommandResult.Success, mapOf("route" to route.id, "from" to (saved ?: 0)))
        enterLocked(CycleState.Booting, "Start cyklu: trasa ${route.id}, od punktu ${(saved ?: 0) + 1}")
        CommandResult.Success
    }

    /** „Powrót do stacji” — z każdego stanu w locie poza już trwającym powrotem i lądowaniem. */
    suspend fun returnToStation(): CommandResult = mutex.withLock {
        if (_activeRoute.value == null) return reject("return_to_station", "Brak aktywnej trasy")
        val breakpoint: Int? = when (val current = _state.value) {
            CycleState.TakingOff -> null
            is CycleState.Patrolling -> current.waypoint
            is CycleState.Holding -> (current.resumeTo as? CycleState.Patrolling)?.waypoint
            is CycleState.Failure -> {
                if (connection.state.value !is ConnectionState.Connected) {
                    return reject("return_to_station", "Brak połączenia z dronem")
                }
                if (!telemetry.state.value.possiblyAirborne) {
                    return reject("return_to_station", "Dron jest na ziemi — użyj resetu")
                }
                lastPatrolIndex
            }
            is CycleState.Returning, is CycleState.Landing ->
                return reject("return_to_station", "Dron już wraca lub ląduje")
            else -> return reject("return_to_station", "Powrót możliwy tylko w locie (teraz: ${current.label()})")
        }
        cancelStageLocked()
        enterLocked(CycleState.Returning(ReturnReason.OPERATOR, breakpoint), "Polecenie operatora")
        CommandResult.Success
    }

    /** Wznowienie z ZAWISU do stanu sprzed STOP, o ile nie ma aktywnych alarmów. */
    suspend fun resume(): CommandResult = mutex.withLock {
        val current = _state.value as? CycleState.Holding
            ?: return reject("resume", "Wznowienie możliwe tylko ze stanu ZAWIS")
        if (connection.state.value !is ConnectionState.Connected) {
            return reject("resume", "Brak połączenia z dronem")
        }
        val alarms = safety.alarms.value
        if (alarms.isNotEmpty()) {
            return reject("resume", "Aktywne alarmy: ${alarms.joinToString("; ") { it.message }}")
        }
        cancelStageLocked()
        enterLocked(current.resumeTo, "Wznowienie przez operatora")
        CommandResult.Success
    }

    /** Wyjście z AWARII do GOTOWY — tylko gdy dron na pewno stoi na ziemi. */
    suspend fun reset(): CommandResult = mutex.withLock {
        if (_state.value !is CycleState.Failure) return reject("reset", "Reset możliwy tylko ze stanu AWARIA")
        val drone = telemetry.state.value
        val connected = connection.state.value is ConnectionState.Connected
        val onGround = if (connected) drone.confirmedOnGroundMotorsOff else drone.isFlying != true
        if (!onGround) return reject("reset", "Dron może być w powietrzu — najpierw wyląduj")
        cancelStageLocked()
        plan = null
        chargeRequestedAtMs = null
        enterLocked(CycleState.Ready, "Reset przez operatora")
        CommandResult.Success
    }

    // ------------------------------------------------------------------ dyrektywy nadzoru

    private suspend fun applyDirective(directive: SafetyDirective) = mutex.withLock {
        val current = _state.value
        when (directive) {
            is SafetyDirective.Hold -> when {
                current is CycleState.Holding ->
                    logger.safety("hold_ignored", LogLevel.INFO, mapOf("why" to "już w zawisie", "reason" to directive.reason))
                current.isAirborneStage -> {
                    cancelStageLocked()
                    enterLocked(CycleState.Holding(current), directive.reason)
                }
                current == CycleState.Booting || current == CycleState.ShuttingDown -> {
                    cancelStageLocked()
                    enterLocked(CycleState.Failure("Przerwano: ${directive.reason}"), directive.reason)
                }
                else ->
                    logger.safety("hold_ignored", LogLevel.INFO, mapOf("why" to current.toString(), "reason" to directive.reason))
            }
            is SafetyDirective.Fail -> when {
                current is CycleState.Failure ->
                    logger.safety("fail_ignored", LogLevel.INFO, mapOf("reason" to directive.reason))
                current.isAirborneStage || current == CycleState.Booting || current == CycleState.ShuttingDown -> {
                    cancelStageLocked()
                    enterLocked(CycleState.Failure(directive.reason), directive.reason)
                }
                else ->
                    logger.safety("fail_ignored", LogLevel.INFO, mapOf("why" to current.toString(), "reason" to directive.reason))
            }
        }
    }

    // ------------------------------------------------------------------ silnik przejść

    private data class Step(val next: CycleState, val cause: String)

    /** Wymaga trzymania [mutex]. */
    private fun enterLocked(next: CycleState, cause: String) {
        val previous = _state.value
        val myEpoch = ++epoch
        _state.value = next
        logger.transition(previous, next, cause)
        stageJob = scope.launch {
            val step = try {
                runStage(next, myEpoch)
            } catch (e: CancellationException) {
                ensureActive() // prawdziwe anulowanie etapu → przekaż dalej
                Step(CycleState.Failure("Przekroczono czas w etapie ${next.label()}"), e.toString())
            } catch (e: Throwable) {
                logger.system("stage_exception", LogLevel.ERROR, mapOf("state" to next.toString(), "error" to e.toString()))
                Step(CycleState.Failure("Błąd wewnętrzny w etapie ${next.label()}: ${e.message ?: e::class.simpleName}"), e.toString())
            }
            mutex.withLock {
                if (epoch == myEpoch) enterLocked(step.next, step.cause)
            }
        }
    }

    /** Wymaga trzymania [mutex]. */
    private suspend fun cancelStageLocked() {
        stageJob?.cancelAndJoin()
        stageJob = null
    }

    /** Aktualizacja stanu bez zmiany etapu (np. kolejny punkt patrolu). */
    private suspend fun updateInPlace(myEpoch: Long, next: CycleState) = mutex.withLock {
        if (epoch == myEpoch && _state.value != next) _state.value = next
    }

    private suspend fun runStage(state: CycleState, myEpoch: Long): Step = when (state) {
        CycleState.Ready -> awaitCancellation()
        CycleState.Booting -> boot()
        CycleState.TakingOff -> takeOff()
        is CycleState.Patrolling -> patrol(myEpoch)
        is CycleState.Returning -> flyHome(state)
        is CycleState.Landing -> land(state)
        CycleState.ShuttingDown -> shutDown()
        CycleState.Charging -> charge()
        is CycleState.Holding -> hold()
        is CycleState.Failure -> failure()
    }

    // ------------------------------------------------------------------ etapy

    private suspend fun boot(): Step {
        val route = _activeRoute.value ?: return fail("Brak aktywnej trasy")

        val chargeOff = station.send(StationCommand.Charge(on = false))
        logger.command("station_charge_off", chargeOff)
        chargeOff.onFailure { return fail("Stacja nie wyłączyła ładowania: ${it.reason}") }

        if (connection.state.value !is ConnectionState.Connected) {
            val powerOn = station.send(StationCommand.PowerButton(PowerAction.POWER_ON))
            logger.command("station_power_on", powerOn)
            powerOn.onFailure { return fail("Stacja nie włączyła drona: ${it.reason}") }
            withTimeoutOrNull(config.powerOnTimeoutMs) {
                connection.state.first { it is ConnectionState.Connected }
            } ?: return fail("Dron nie połączył się w ciągu ${config.powerOnTimeoutMs / 1000} s od włączenia")
        }
        val conn = connection.state.value as? ConnectionState.Connected
            ?: return fail("Utracono połączenie z dronem podczas uruchamiania")
        if (!conn.supported) return fail("Nieobsługiwany model drona: ${conn.productType}")

        withTimeoutOrNull(config.preflightTimeoutMs) {
            telemetry.state.first { preflightProblems(it, route).isEmpty() }
        } ?: return fail(
            "Dron niegotowy do startu: " + preflightProblems(telemetry.state.value, route).joinToString("; "),
        )

        val fromIndex = _breakpoint.value ?: 0
        val newPlan = route.toMissionPlan(nextPlanId(route), fromIndex)
        val uploaded = mission.upload(newPlan)
        logger.command(
            "mission_upload",
            uploaded,
            mapOf("plan" to newPlan.id, "from" to fromIndex, "points" to newPlan.waypoints.size),
        )
        uploaded.onFailure { return fail("Nie udało się wysłać misji: ${it.reason}") }
        plan = newPlan
        lastPatrolIndex = fromIndex
        return Step(CycleState.TakingOff, "Kontrola przedstartowa OK")
    }

    internal fun preflightProblems(d: DroneState, route: PatrolRoute): List<String> {
        val problems = mutableListOf<String>()
        if (!d.connected) problems += "brak łącza z kontrolerem lotu"
        if (d.isFlying == true) problems += "dron zgłasza lot"
        val gps = d.gpsSignalLevel
        if (gps == null || gps < config.minGpsSignalLevel) problems += "słaby sygnał GPS (${gps ?: "?"} < ${config.minGpsSignalLevel})"
        val sats = d.gpsSatelliteCount
        if (sats == null || sats < config.minSatellites) problems += "za mało satelitów (${sats ?: "?"} < ${config.minSatellites})"
        val home = d.homeLocation
        if (home == null || !home.isValid) {
            problems += "brak punktu startu (home)"
        } else {
            val offset = home.distanceTo(route.station)
            if (offset > config.maxHomeOffsetFromStationM) problems += "punkt startu ${offset.toInt()} m od stacji"
        }
        val battery = d.batteryPercent
        if (battery == null || battery < config.minTakeoffBatteryPercent) {
            problems += "bateria ${battery ?: "?"}% < ${config.minTakeoffBatteryPercent}%"
        }
        if (config.requireGoHomeFailsafe && d.failsafeAction != FailsafeAction.GO_HOME) {
            problems += "akcja failsafe to ${d.failsafeAction ?: "?"}, wymagany powrót do domu"
        }
        return problems
    }

    private suspend fun takeOff(): Step {
        val route = _activeRoute.value ?: return fail("Brak aktywnej trasy")
        val currentPlan = plan ?: return fail("Brak wysłanej misji")

        if (telemetry.state.value.isFlying != true) {
            val r = flight.takeOff()
            logger.command("takeoff", r)
            r.onFailure { return fail("Start odrzucony przez drona: ${it.reason}") }
        }
        withTimeoutOrNull(config.takeoffTimeoutMs) {
            telemetry.state.first { it.isFlying == true && (it.altitudeM ?: 0.0) >= config.takeoffMinAltitudeM }
        } ?: return if (telemetry.state.value.isFlying == true) {
            Step(CycleState.Returning(ReturnReason.SAFETY, null), "Start niepełny — nie osiągnięto wysokości")
        } else {
            fail("Dron nie wystartował w ciągu ${config.takeoffTimeoutMs / 1000} s")
        }

        val progress = mission.progress.value
        if (!(progress.missionId == currentPlan.id && progress.isActive)) {
            val r = mission.start(currentPlan.id)
            logger.command("mission_start", r, mapOf("plan" to currentPlan.id))
            r.onFailure {
                return Step(CycleState.Returning(ReturnReason.SAFETY, null), "Nie udało się uruchomić misji: ${it.reason}")
            }
        }
        return Step(CycleState.Patrolling(currentPlan.fromIndex, route.size), "W powietrzu, misja uruchomiona")
    }

    private sealed interface PatrolEnd {
        data object Done : PatrolEnd
        data class LowBattery(val reason: String) : PatrolEnd
        data class MissionFailed(val reason: String) : PatrolEnd
    }

    private suspend fun patrol(myEpoch: Long): Step {
        val route = _activeRoute.value ?: return fail("Brak aktywnej trasy")
        val currentPlan = plan ?: return fail("Brak wysłanej misji")

        val progress = mission.progress.value
        if (progress.missionId == currentPlan.id && progress.status == MissionStatus.PAUSED) {
            val r = mission.resume()
            logger.command("mission_resume", r, mapOf("plan" to currentPlan.id))
            r.onFailure { return fail("Nie udało się wznowić misji: ${it.reason}") }
        }

        return coroutineScope {
            val planProgress = mission.progress.filter { it.missionId == currentPlan.id }
            val tracker = launch {
                planProgress.collect { p ->
                    val index = p.missionWaypointIndex?.let(currentPlan::patrolIndexOf) ?: return@collect
                    if (index != lastPatrolIndex) {
                        lastPatrolIndex = index
                        logger.log(LogCategory.STATE, LogLevel.INFO, "waypoint", mapOf("index" to index, "total" to route.size))
                    }
                    updateInPlace(myEpoch, CycleState.Patrolling(index, route.size))
                }
            }
            val end = merge(
                planProgress.mapNotNull { p ->
                    when (p.status) {
                        MissionStatus.FINISHED -> PatrolEnd.Done
                        MissionStatus.FAILED -> PatrolEnd.MissionFailed(p.error ?: "nieznany błąd")
                        else -> null
                    }
                },
                telemetry.state.mapNotNull { s ->
                    (batteryPolicy.evaluate(s, route.station) as? BatteryDecision.ReturnNow)?.let { PatrolEnd.LowBattery(it.reason) }
                },
            ).first()
            tracker.cancel()
            when (end) {
                PatrolEnd.Done -> Step(CycleState.Returning(ReturnReason.ROUTE_DONE, null), "Trasa ukończona")
                is PatrolEnd.LowBattery ->
                    Step(CycleState.Returning(ReturnReason.LOW_BATTERY, lastPatrolIndex ?: currentPlan.fromIndex), end.reason)
                is PatrolEnd.MissionFailed ->
                    Step(CycleState.Failure("Misja przerwana przez drona: ${end.reason}"), end.reason)
            }
        }
    }

    private suspend fun flyHome(s: CycleState.Returning): Step {
        val route = _activeRoute.value ?: return fail("Brak aktywnej trasy")
        persistBreakpoint(route, s)

        val drone = telemetry.state.value
        val distance = drone.distanceToM(route.station)
        if (distance == null || distance > config.arrivalRadiusM) {
            val from = drone.location?.takeIf { it.isValid }
                ?: return fail("Brak pozycji GPS — nie można zaplanować powrotu")
            if (mission.progress.value.isActive) logger.command("mission_stop", mission.stop())

            val currentAltitude = drone.altitudeM ?: 0.0
            val returnAltitude = max(config.returnAltitudeM, currentAltitude).coerceAtMost(config.routeLimits.maxAltitudeM)
            val returnPlan = PatrolRoute.returnRoute(
                id = "${route.id}-return",
                from = from,
                station = route.station,
                returnAltitudeM = returnAltitude,
                approachAltitudeM = route.approachAltitudeM,
                speedMps = config.returnSpeedMps,
            ).toMissionPlan(nextPlanId(route))

            val uploaded = mission.upload(returnPlan)
            logger.command("return_upload", uploaded, mapOf("plan" to returnPlan.id, "distance" to distance))
            uploaded.onFailure { return fail("Nie udało się wysłać trasy powrotu: ${it.reason}") }
            val started = mission.start(returnPlan.id)
            logger.command("return_start", started, mapOf("plan" to returnPlan.id))
            started.onFailure { return fail("Nie udało się uruchomić powrotu: ${it.reason}") }
            plan = returnPlan

            val climbAndDescentM = abs(returnAltitude - currentAltitude) + abs(returnAltitude - route.approachAltitudeM)
            val timeoutMs = ((from.distanceTo(route.station) / config.returnSpeedMps + climbAndDescentM / VERTICAL_SPEED_MPS) *
                2_000.0).toLong() + config.returnTimeoutMarginMs
            val end = withTimeoutOrNull(timeoutMs) {
                mission.progress.first {
                    it.missionId == returnPlan.id && (it.status == MissionStatus.FINISHED || it.status == MissionStatus.FAILED)
                }
            } ?: return fail("Powrót trwa dłużej niż ${timeoutMs / 1000} s")
            if (end.status == MissionStatus.FAILED) return fail("Powrót przerwany przez drona: ${end.error ?: "nieznany błąd"}")
        }

        if (station.link.value.status?.padClear != true) {
            logger.safety("landing_blocked", LogLevel.WARN, mapOf("link" to station.link.value.toString()))
            station.link.first { it.status?.padClear == true }
            logger.safety("landing_unblocked", LogLevel.INFO)
        }
        return Step(CycleState.Landing(1), "Nad stacją")
    }

    private suspend fun persistBreakpoint(route: PatrolRoute, s: CycleState.Returning) {
        when {
            s.reason == ReturnReason.ROUTE_DONE -> {
                breakpoints.clear(route.id)
                _breakpoint.value = null
                logger.log(LogCategory.STATE, LogLevel.INFO, "breakpoint_cleared", mapOf("route" to route.id))
            }
            s.breakpoint != null -> {
                breakpoints.save(route.id, s.breakpoint)
                _breakpoint.value = s.breakpoint
                logger.log(LogCategory.STATE, LogLevel.INFO, "breakpoint_saved", mapOf("route" to route.id, "index" to s.breakpoint))
            }
        }
    }

    private suspend fun land(s: CycleState.Landing): Step {
        val outcome = landing.execute(s.attempt)
        logger.log(
            LogCategory.STATE,
            if (outcome == LandingOutcome.Landed) LogLevel.INFO else LogLevel.WARN,
            "landing_outcome",
            mapOf("attempt" to s.attempt, "outcome" to outcome.toString()),
        )
        return when (outcome) {
            LandingOutcome.Landed -> Step(CycleState.ShuttingDown, "Wylądowano na stacji")
            is LandingOutcome.MarkerLost ->
                if (s.attempt < config.maxLandingAttempts) {
                    Step(CycleState.Landing(s.attempt + 1), "Próba ${s.attempt} nieudana: ${outcome.reason}")
                } else {
                    fail("Marker utracony ${config.maxLandingAttempts} razy (${outcome.reason})")
                }
            is LandingOutcome.Aborted -> fail("Lądowanie przerwane: ${outcome.reason}")
        }
    }

    private suspend fun shutDown(): Step {
        withTimeoutOrNull(config.motorsOffTimeoutMs) {
            telemetry.state.first { it.confirmedOnGroundMotorsOff }
        } ?: return fail("Silniki nie wyłączyły się po lądowaniu")

        if (connection.state.value is ConnectionState.Connected) {
            val powerOff = station.send(StationCommand.PowerButton(PowerAction.POWER_OFF))
            logger.command("station_power_off", powerOff)
            powerOff.onFailure { return fail("Stacja nie wyłączyła drona: ${it.reason}") }
            withTimeoutOrNull(config.powerOffTimeoutMs) {
                connection.state.first { it !is ConnectionState.Connected }
            } ?: return fail("Dron nie wyłączył się w ciągu ${config.powerOffTimeoutMs / 1000} s")
        }

        val requestedAt = clock.monotonicMs()
        val chargeOn = station.send(StationCommand.Charge(on = true))
        logger.command("station_charge_on", chargeOn)
        chargeOn.onFailure { return fail("Stacja nie włączyła ładowania: ${it.reason}") }
        chargeRequestedAtMs = requestedAt
        return Step(CycleState.Charging, "Dron wyłączony, ładowanie")
    }

    /**
     * Czeka na status stacji odebrany po poleceniu ładowania, w którym ładowanie jest zakończone.
     * Wymaga od firmware stacji wysłania `status` zaraz po wykonaniu polecenia `charge`.
     */
    private suspend fun charge(): Step {
        val since = chargeRequestedAtMs ?: clock.monotonicMs()
        withTimeoutOrNull(config.chargeTimeoutMs) {
            station.link.first { link ->
                val st = link.status
                st != null && st.receivedAtMs > since && !st.charging && st.ready && st.dronePresent
            }
        } ?: return fail("Ładowanie nie zakończyło się w ciągu ${config.chargeTimeoutMs / 60_000} min")
        chargeRequestedAtMs = null
        return Step(CycleState.Ready, "Naładowano — cykl zakończony")
    }

    /** ZAWIS: upewnij się, że dron stoi w miejscu, i czekaj na decyzję operatora. */
    private suspend fun hold(): Step {
        val r = safety.haltMotion()
        r.onFailure { logger.safety("hold_failed", LogLevel.ERROR, mapOf("reason" to it.reason)) }
        awaitCancellation()
    }

    /** AWARIA: oddaj drążki aparaturze (wyłącz Virtual Stick) i czekaj na operatora. */
    private suspend fun failure(): Step {
        if (flight.controlOwner.value == ControlOwner.VIRTUAL_STICK) {
            logger.command("vs_stop_on_failure", flight.stopVirtualStick())
        }
        awaitCancellation()
    }

    // ------------------------------------------------------------------ pomocnicze

    private fun fail(cause: String) = Step(CycleState.Failure(cause), cause)

    private fun reject(command: String, reason: String): CommandResult {
        val result = CommandResult.Failure(reason)
        logger.command(command, result, mapOf("state" to _state.value.toString()))
        return result
    }

    private fun nextPlanId(route: PatrolRoute): String = "${route.id}#${++planCounter}"

    private companion object {
        /** Przybliżona prędkość wznoszenia/zniżania w misji, do szacowania limitu czasu powrotu. */
        const val VERTICAL_SPEED_MPS = 2.0
    }
}

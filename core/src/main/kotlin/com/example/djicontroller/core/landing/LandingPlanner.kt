package com.example.djicontroller.core.landing

import com.example.djicontroller.core.logging.command
import com.example.djicontroller.core.logging.marker
import com.example.djicontroller.core.logging.safety
import com.example.djicontroller.core.model.CommandResult
import com.example.djicontroller.core.model.ControlOwner
import com.example.djicontroller.core.model.GeoPoint
import com.example.djicontroller.core.model.MarkerObservation
import com.example.djicontroller.core.model.Setpoint
import com.example.djicontroller.core.model.toStatus
import com.example.djicontroller.core.port.CameraPort
import com.example.djicontroller.core.port.Clock
import com.example.djicontroller.core.port.CoroutineTicker
import com.example.djicontroller.core.port.FlightControlPort
import com.example.djicontroller.core.port.FlightLogger
import com.example.djicontroller.core.port.LogCategory
import com.example.djicontroller.core.port.LogLevel
import com.example.djicontroller.core.port.MissionPort
import com.example.djicontroller.core.port.StationPort
import com.example.djicontroller.core.port.TelemetryPort
import com.example.djicontroller.core.port.Ticker
import com.example.djicontroller.core.port.VisionPort
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlin.math.abs
import kotlin.math.ceil
import kotlin.math.cos
import kotlin.math.hypot
import kotlin.math.min
import kotlin.math.sin

/** Pod-stany lądowania z sekcji 5.5. */
enum class LandingStep(val label: String) {
    L1_APPROACH("L1 nad stacją"),
    L2_SEARCH("L2 szukanie markera"),
    L3_DESCENT("L3 centrowanie i opadanie"),
    L4_TOUCHDOWN("L4 przyziemienie"),
    L5_VERIFY("L5 weryfikacja na płycie"),
}

/** Bieżący pod-stan do UI i testów. */
data class LandingPhase(val attempt: Int, val step: LandingStep)

/** Parametry procedury L1–L5; czasy w ms, odległości w m. Wartości do kalibracji w testach R3–R5. */
data class LandingPlannerConfig(
    /** Okres pętli sterowania (20 Hz). */
    val tickMs: Long = 50,
    /** Liczba prób lądowania — musi być równa `CycleConfig.maxLandingAttempts` orkiestratora. */
    val maxAttempts: Int = 3,

    // --- L1
    /** Dalej od stacji nie zaczynamy — orkiestrator powinien był najpierw przylecieć nad stację. */
    val maxStartDistanceM: Double = 10.0,
    /** Co ile powtarzać alarm blokady stacji w logu. */
    val stationAlarmRepeatMs: Long = 5_000,
    /** Ile najwyżej czekać na wolną płytę; `null` = do skutku (przerywa STOP / nadzór). */
    val stationWaitTimeoutMs: Long? = null,

    // --- L2
    val gimbalTimeoutMs: Long = 3_000,
    /** Gimbal uznajemy za skierowany w dół przy pochyleniu ≤ −90° + tyle stopni. */
    val gimbalToleranceDeg: Double = 5.0,
    /**
     * Zakres wysokości poszukiwań; poza nim najpierw zejście/wznoszenie do [searchAltitudeTargetM]
     * z centrowaniem GPS. Dobrany do zasięgu PEWNEJ detekcji, nie do „widać płytę”:
     * duży marker 30 cm (z małym markerem w środku) w obrazie 960×540 (demo) ma na 6 m ok. 32 px boku
     * i jego wykrycie zależy od ułożenia pikseli — w nieruchomym zawisie potrafi nie wykryć się wcale
     * (pomiar na tym samym rendererze: 6 m, kurs 2° → 2/8; 8 m → poza odrzucana w 118/122 klatkach
     * w logu z emulatora). Na 4–5 m detekcja jest pewna (100 % przy każdym kursie i przesunięciu).
     * Prawdziwy Mini 4 Pro (1280 px do detekcji) ma ten sam zapas na ok. 6 m.
     */
    val searchAltitudeMinM: Double = 3.5,
    val searchAltitudeMaxM: Double = 5.0,
    val searchAltitudeTargetM: Double = 4.5,
    val searchTimeoutMs: Long = 5_000,
    /** O ile wznieść się, gdy marker nie pojawił się w [searchTimeoutMs] (większe pole widzenia). */
    val searchClimbM: Double = 2.0,
    /** Wznoszenie w L2 najwyżej do tej wysokości — wyżej marker jest za mały, żeby go pewnie wykryć. */
    val searchCeilingM: Double = 6.5,
    /** Ile razy wznosić się w L2, zanim próba zostanie uznana za nieudaną. */
    val searchClimbs: Int = 1,
    val verticalMps: Double = 0.6,
    /** Centrowanie nad stacją według GPS, dopóki marker nie jest widoczny (L2 i powrót po utracie). */
    val gpsCenteringKp: Double = 0.5,
    val gpsCenteringMaxMps: Double = 1.0,
    val gpsCenteringDeadbandM: Double = 0.5,
    /** Twardy limit wysokości przy wznoszeniu (wyszukiwanie, powrót po utracie markera), m nad stacją. */
    val maxAltitudeM: Double = 15.0,

    // --- L3
    /** Obserwacja starsza niż tyle nie steruje dronem (zawis). */
    val maxObservationAgeMs: Long = 600,
    /** Brak markera dłuższy niż tyle (przy działającej detekcji) = utrata markera. */
    val markerLostTimeoutMs: Long = 1_000,
    /** Brak NOWYCH klatek z detekcji dłuższy niż tyle też jest utratą (zawieszona kamera / wizja). */
    val visionStallTimeoutMs: Long = 3_000,
    /** Wzniesienie po utracie markera przed kolejną próbą. */
    val recoveryClimbM: Double = 2.0,
    /** Wysokość przejścia do L4 (przy błędzie w stożku), m. */
    val touchdownHeightM: Double = 0.5,
    val descentTimeoutMs: Long = 120_000,

    // --- L4
    val confirmRetryMs: Long = 2_000,
    val touchdownTimeoutMs: Long = 30_000,

    // --- L5
    val stationConfirmTimeoutMs: Long = 15_000,
) {
    init {
        require(tickMs in 10..200) { "tickMs w 10..200" }
        require(maxAttempts >= 1) { "maxAttempts ≥ 1" }
        require(searchAltitudeMinM <= searchAltitudeTargetM && searchAltitudeTargetM <= searchAltitudeMaxM) { "min ≤ cel ≤ max" }
        require(searchAltitudeMaxM <= searchCeilingM && searchCeilingM <= maxAltitudeM) { "searchAltitudeMaxM ≤ searchCeilingM ≤ maxAltitudeM" }
        require(verticalMps > 0) { "verticalMps > 0" }
        require(markerLostTimeoutMs > 0 && maxObservationAgeMs > 0) { "Czasy utraty markera > 0" }
    }
}

/**
 * Precyzyjne lądowanie L1–L5 (F3, sekcje 5.5, 5.7, 5.8). Wołany przez [com.example.djicontroller.core.orchestrator.CycleOrchestrator]
 * w stanie `Landing(attempt)`; numerację prób i przejście do AWARII po ostatniej prowadzi orkiestrator.
 *
 * - **L1** — nad stacją: zatrzymanie misji WPML (Z2), kontrola odległości, blokada do czasu wolnej płyty
 *   i łącza ze stacją (zawis + alarm).
 * - **L2** — gimbal −90° z potwierdzeniem `KeyGimbalAttitude`, Virtual Stick + HOVER, zejście na wysokość
 *   poszukiwań (zasięg detekcji) z centrowaniem GPS nad stacją, szukanie markera; po
 *   [LandingPlannerConfig.searchTimeoutMs] wzniesienie o [LandingPlannerConfig.searchClimbM] (najwyżej do
 *   [LandingPlannerConfig.searchCeilingM]) i drugie szukanie.
 * - **L3** — pętla 20 Hz: [LandingController] → `setVelocityTarget`. Zanik markera = natychmiast HOVER;
 *   zanik dłuższy niż [LandingPlannerConfig.markerLostTimeoutMs] = wzniesienie i [LandingOutcome.MarkerLost]
 *   (przy ostatniej próbie bez wznoszenia).
 * - **L4** — wyłączenie Virtual Stick (Z2), `KeyStartAutoLanding`, `KeyConfirmLanding` przy
 *   `KeyIsLandingConfirmationNeeded`, czekanie na `areMotorsOn == false`.
 * - **L5** — silniki wyłączone i stacja potwierdza drona na płycie → [LandingOutcome.Landed].
 *
 * Anulowanie (STOP, dyrektywa nadzoru) w dowolnym miejscu zostawia drona w zawisie (Z4):
 * w L2/L3 HOVER i oddanie Virtual Stick (dron wisi na GPS), w L4 przerwanie auto-lądowania.
 *
 * Czysty Kotlin: czas z [Clock], odstępy pętli przez [Ticker] — w testach JVM czas wirtualny.
 */
class LandingPlanner(
    private val flight: FlightControlPort,
    private val mission: MissionPort,
    private val camera: CameraPort,
    private val vision: VisionPort,
    private val telemetry: TelemetryPort,
    private val station: StationPort,
    /** Położenie stacji (z aktywnej trasy albo punkt startu); `null` = nieznane, bez kontroli odległości. */
    private val stationLocation: () -> GeoPoint?,
    private val logger: FlightLogger,
    private val clock: Clock,
    private val ticker: Ticker = CoroutineTicker,
    val config: LandingPlannerConfig = LandingPlannerConfig(),
    controllerConfig: LandingControllerConfig = LandingControllerConfig(),
) : LandingProcedure {

    val controller = LandingController(controllerConfig)

    private val _phase = MutableStateFlow<LandingPhase?>(null)
    /** Bieżący pod-stan L1–L5; `null` poza lądowaniem. */
    val phase: StateFlow<LandingPhase?> = _phase.asStateFlow()

    private val _alarm = MutableStateFlow<String?>(null)
    /** Powód blokady w L1 (np. „płyta lądowiska zajęta”); `null` = brak. */
    val alarm: StateFlow<String?> = _alarm.asStateFlow()

    /** Przerwanie procedury z wnętrza pętli (np. utrata sterowania) — rozwija stos do [execute]. */
    private class Interrupted(val outcome: LandingOutcome) : RuntimeException(outcome.toString())

    @Volatile private var controlLossReason: String? = null

    override suspend fun execute(attempt: Int): LandingOutcome {
        controller.reset()
        controlLossReason = null
        val run = AttemptState(attempt)
        logger.log(LogCategory.STATE, LogLevel.INFO, "landing_start", mapOf("attempt" to attempt, "maxAttempts" to config.maxAttempts))
        var outcome: LandingOutcome? = null
        try {
            val result = coroutineScope {
                // Utrata sterowania Virtual Stick / przerwanie auto-lądowania z aparatury (onChangeReasonUpdate).
                val watcher = launch(start = CoroutineStart.UNDISPATCHED) {
                    flight.controlLost.collect { loss ->
                        controlLossReason = loss.reason
                        logger.safety("landing_control_lost", LogLevel.ERROR, mapOf("attempt" to attempt, "reason" to loss.reason, "rc" to loss.takenOverByRc))
                    }
                }
                try {
                    runSteps(run)
                } catch (e: Interrupted) {
                    e.outcome
                } finally {
                    watcher.cancel()
                }
            }
            outcome = result
            return result
        } finally {
            withContext(NonCancellable) { cleanUp(run, outcome) }
        }
    }

    /** Stan jednej próby (do sprzątania w `finally`). */
    private class AttemptState(val attempt: Int) {
        var step: LandingStep? = null
        var virtualStickStarted = false
        var visionAcquired = false
        var autoLandingStarted = false
        var touchdownStartedAt = 0L
    }

    private suspend fun runSteps(run: AttemptState): LandingOutcome {
        approach(run)?.let { return it }
        val found = search(run) ?: return recoverAfterLoss(run, "Nie znaleziono markera nad stacją", climb = false)
        descend(run, found)?.let { return it }
        touchdown(run)?.let { return it }
        return verify(run)
    }

    // ------------------------------------------------------------------ L1

    private suspend fun approach(run: AttemptState): LandingOutcome? {
        enter(run, LandingStep.L1_APPROACH, "Start próby ${run.attempt}")

        // Z2: misja WPML nie może sterować równocześnie z Virtual Stick.
        if (mission.progress.value.isActive || flight.controlOwner.value == ControlOwner.MISSION) {
            val stopped = mission.stop()
            logger.command("landing_mission_stop", stopped, mapOf("attempt" to run.attempt))
            if (stopped is CommandResult.Failure) return LandingOutcome.Aborted("Nie udało się zatrzymać misji WPML: ${stopped.reason}")
        }
        if (flight.controlOwner.value == ControlOwner.AUTO_LANDING) {
            logger.command("landing_stale_autoland_cancel", flight.cancelAutoLanding())
        }

        val drone = telemetry.state.value
        if (drone.isFlying == false) return LandingOutcome.Aborted("Dron nie jest w powietrzu")
        val target = stationLocation()
        val distance = target?.let { drone.distanceToM(it) }
        logger.log(
            LogCategory.STATE,
            LogLevel.INFO,
            "landing_l1_position",
            mapOf("attempt" to run.attempt, "distanceM" to distance, "alt" to drone.altitudeM, "ultra" to drone.ultrasonicHeightM),
        )
        if (distance != null && distance > config.maxStartDistanceM) {
            return LandingOutcome.Aborted("Dron ${"%.1f".format(distance)} m od stacji (limit ${config.maxStartDistanceM} m)")
        }

        // Weryfikacja stacji (5.7/5.8): bez łącza albo przy zajętej płycie — zawis i alarm, aż się zwolni.
        val waitStart = clock.monotonicMs()
        var lastAlarm: String? = null
        var lastAlarmAt = 0L
        while (true) {
            val st = station.link.value.toStatus()
            val problem = when {
                !st.isConnected -> "brak łącza ze stacją"
                !st.padClear -> "płyta lądowiska zajęta"
                else -> null
            }
            val now = clock.monotonicMs()
            if (problem == null) {
                if (lastAlarm != null) {
                    logger.safety("landing_station_ok", LogLevel.INFO, mapOf("attempt" to run.attempt, "waitedMs" to now - waitStart))
                }
                _alarm.value = null
                break
            }
            if (problem != lastAlarm || now - lastAlarmAt >= config.stationAlarmRepeatMs) {
                logger.safety("landing_station_blocked", LogLevel.WARN, mapOf("attempt" to run.attempt, "reason" to problem, "waitedMs" to now - waitStart))
                _alarm.value = "Lądowanie wstrzymane: $problem"
                lastAlarm = problem
                lastAlarmAt = now
            }
            val limit = config.stationWaitTimeoutMs
            if (limit != null && now - waitStart > limit) {
                return LandingOutcome.Aborted("Stacja niegotowa przez ${limit / 1000} s: $problem")
            }
            tick() // dron bez właściciela sterowania wisi na GPS
        }
        return null
    }

    // ------------------------------------------------------------------ L2

    /** Zwraca pierwszą świeżą obserwację albo `null`, gdy markera nie znaleziono. */
    private suspend fun search(run: AttemptState): MarkerObservation? {
        enter(run, LandingStep.L2_SEARCH, "Stacja gotowa")

        val gimbal = camera.pointGimbalDown()
        logger.command("landing_gimbal_down", gimbal, mapOf("attempt" to run.attempt))
        if (gimbal is CommandResult.Failure) throw Interrupted(LandingOutcome.Aborted("Gimbal: ${gimbal.reason}"))
        val gimbalOk = waitUntil(config.gimbalTimeoutMs) {
            camera.gimbalPitchDeg.value?.let { it <= -90.0 + config.gimbalToleranceDeg } == true
        }
        logger.log(LogCategory.COMMAND, if (gimbalOk) LogLevel.INFO else LogLevel.ERROR, "landing_gimbal_attitude", mapOf("pitch" to camera.gimbalPitchDeg.value, "ok" to gimbalOk))
        if (!gimbalOk) throw Interrupted(LandingOutcome.Aborted("Gimbal nie potwierdził −90° w ${config.gimbalTimeoutMs / 1000} s"))

        vision.acquire(VISION_USER)
        run.visionAcquired = true

        val vs = flight.startVirtualStick()
        logger.command("landing_vs_start", vs, mapOf("attempt" to run.attempt))
        if (vs is CommandResult.Failure) throw Interrupted(LandingOutcome.Aborted("Virtual Stick: ${vs.reason}"))
        run.virtualStickStarted = true
        flight.setVelocityTarget(Setpoint.HOVER)

        // Wysokość poszukiwań dobrana do zasięgu detekcji (domyślnie 4–7 m, cel 6 m) + centrowanie GPS nad stacją.
        val alt = telemetry.state.value.altitudeM
        logger.log(LogCategory.STATE, LogLevel.INFO, "landing_search_start", mapOf("attempt" to run.attempt, "alt" to alt, "gpsDistM" to gpsDistanceM()))
        if (alt != null && (alt < config.searchAltitudeMinM || alt > config.searchAltitudeMaxM)) {
            logger.log(LogCategory.STATE, LogLevel.INFO, "landing_search_altitude", mapOf("from" to alt, "to" to config.searchAltitudeTargetM))
            // Bez przerywania na pierwszym widoku markera: powyżej zakresu detekcja jest niepewna,
            // a L3 rozpoczęte tam szybko „gubiłoby” marker.
            moveToAltitude(config.searchAltitudeTargetM, stopOnMarker = false)
        }

        var climbs = 0
        while (true) {
            val found = waitForMarker(config.searchTimeoutMs)
            if (found != null) {
                logger.log(LogCategory.MARKER, LogLevel.INFO, "landing_marker_found", mapOf("attempt" to run.attempt, "id" to found.markerId, "dz" to found.dzM, "err" to found.horizontalErrorM, "climbs" to climbs))
                return found
            }
            val current = telemetry.state.value.altitudeM
            if (climbs >= config.searchClimbs || (current != null && current >= config.searchCeilingM - 0.1)) {
                logger.safety("landing_marker_not_found", LogLevel.WARN, mapOf("attempt" to run.attempt, "climbs" to climbs, "alt" to current, "gpsDistM" to gpsDistanceM()))
                return null
            }
            climbs++
            val target = current?.let { min(it + config.searchClimbM, config.searchCeilingM) }
            logger.log(LogCategory.STATE, LogLevel.INFO, "landing_search_climb", mapOf("attempt" to run.attempt, "from" to current, "to" to target, "climb" to climbs))
            if (target != null) moveToAltitude(target, stopOnMarker = true) else climbBlind(config.searchClimbM)
        }
    }

    /** Czeka na świeży marker, centrując się nad stacją według GPS (bez zmiany wysokości). */
    private suspend fun waitForMarker(timeoutMs: Long): MarkerObservation? {
        val start = clock.monotonicMs()
        while (clock.monotonicMs() - start <= timeoutMs) {
            freshObservation(clock.monotonicMs())?.let { return it }
            flight.setVelocityTarget(gpsCentering(0.0))
            tick()
        }
        return null
    }

    // ------------------------------------------------------------------ L3

    private suspend fun descend(run: AttemptState, first: MarkerObservation): LandingOutcome? {
        enter(run, LandingStep.L3_DESCENT, "Marker ${first.markerId} widoczny")
        val start = clock.monotonicMs()
        var lastTick = start
        var lastFrame: Long? = null
        var lastMarkerId: Int? = null
        var lastCommand: LandingCommand? = null
        var missingMs = 0L
        var stalledMs = 0L
        var dropoutSince: Long? = null

        while (true) {
            val now = clock.monotonicMs()
            val dt = now - lastTick
            lastTick = now
            if (now - start > config.descentTimeoutMs) {
                flight.setVelocityTarget(Setpoint.HOVER)
                return recoverAfterLoss(run, "Opadanie trwa ponad ${config.descentTimeoutMs / 1000} s", climb = true)
            }

            val obs = freshObservation(now)
            if (obs != null) {
                if (dropoutSince != null) {
                    logger.log(LogCategory.MARKER, LogLevel.INFO, "landing_marker_back", mapOf("attempt" to run.attempt, "goneMs" to now - dropoutSince, "missingMs" to missingMs, "stalledMs" to stalledMs))
                    dropoutSince = null
                }
                missingMs = 0
                stalledMs = 0
                val newFrame = obs.timestampMs != lastFrame || obs.markerId != lastMarkerId
                if (newFrame) {
                    lastFrame = obs.timestampMs
                    lastMarkerId = obs.markerId
                    logger.marker(obs, obs.timestampMs)
                    val cmd = controller.update(obs, telemetry.state.value, now)
                    lastCommand = cmd
                    logger.log(LogCategory.COMMAND, LogLevel.INFO, "landing_pd", cmd.toLogFields() + ("attempt" to run.attempt))
                    if (cmd.withinCone && cmd.yawAligned && cmd.heightM <= config.touchdownHeightM) {
                        flight.setVelocityTarget(Setpoint.HOVER)
                        logger.log(LogCategory.STATE, LogLevel.INFO, "landing_touchdown_ready", mapOf("attempt" to run.attempt, "h" to cmd.heightM, "hSrc" to cmd.heightSource, "err" to cmd.horizontalErrorM, "tol" to cmd.toleranceM, "yawErr" to cmd.yawErrorDeg))
                        return null
                    }
                    flight.setVelocityTarget(cmd.setpoint)
                } else {
                    // Ta sama klatka co przed chwilą — odświeżamy cel, żeby watchdog pętli 20 Hz nie wymusił zawisu.
                    flight.setVelocityTarget(lastCommand?.setpoint ?: Setpoint.HOVER)
                }
            } else {
                // Zanik markera: natychmiast zawis — zero opadania (Z4).
                flight.setVelocityTarget(Setpoint.HOVER)
                if (dropoutSince == null) {
                    dropoutSince = now
                    logger.safety("landing_marker_dropout", LogLevel.WARN, mapOf("attempt" to run.attempt, "h" to lastCommand?.heightM))
                } else {
                    // Czas bez markera liczymy osobno dla „detekcja działa, markera nie ma” i „brak nowych klatek”.
                    if (visionProducing(now)) missingMs += dt else stalledMs += dt
                }
                if (missingMs >= config.markerLostTimeoutMs || stalledMs >= config.visionStallTimeoutMs) {
                    val reason = if (missingMs >= config.markerLostTimeoutMs) {
                        "Marker niewidoczny ${missingMs} ms (limit ${config.markerLostTimeoutMs} ms)"
                    } else {
                        "Brak nowych klatek z kamery ${stalledMs} ms (limit ${config.visionStallTimeoutMs} ms)"
                    }
                    logger.safety(
                        "landing_marker_lost",
                        LogLevel.WARN,
                        mapOf("attempt" to run.attempt, "missingMs" to missingMs, "stalledMs" to stalledMs, "h" to lastCommand?.heightM, "alt" to telemetry.state.value.altitudeM),
                    )
                    return recoverAfterLoss(run, reason, climb = true)
                }
            }
            tick()
        }
    }

    /**
     * Nieudana próba: zawis, a jeśli to nie ostatnia próba — wzniesienie o bezpieczny dystans
     * (większe pole widzenia przy kolejnym L2). Ostatnia próba: tylko zawis, orkiestrator przechodzi w AWARIĘ.
     */
    private suspend fun recoverAfterLoss(run: AttemptState, reason: String, climb: Boolean): LandingOutcome {
        flight.setVelocityTarget(Setpoint.HOVER)
        if (run.attempt >= config.maxAttempts) {
            logger.safety("landing_attempts_exhausted", LogLevel.ERROR, mapOf("attempt" to run.attempt, "reason" to reason))
            return LandingOutcome.MarkerLost(reason)
        }
        if (climb && run.virtualStickStarted) {
            val alt = telemetry.state.value.altitudeM
            if (alt == null) {
                logger.log(LogCategory.STATE, LogLevel.INFO, "landing_recovery_climb", mapOf("attempt" to run.attempt, "from" to null, "by" to config.recoveryClimbM))
                climbBlind(config.recoveryClimbM)
            } else {
                // Wznoszenie o bezpieczny dystans, ale nie ponad zakres poszukiwań — wyżej detekcja słabnie
                // i następna próba musiałaby i tak zejść (to dawało „wznosi się i opada w kółko”).
                val target = min(alt + config.recoveryClimbM, config.searchAltitudeMaxM)
                logger.log(LogCategory.STATE, LogLevel.INFO, "landing_recovery_climb", mapOf("attempt" to run.attempt, "from" to alt, "to" to target))
                if (target > alt + ALTITUDE_TOLERANCE_M) moveToAltitude(target, stopOnMarker = false) else flight.setVelocityTarget(Setpoint.HOVER)
            }
        }
        return LandingOutcome.MarkerLost(reason)
    }

    // ------------------------------------------------------------------ L4

    private suspend fun touchdown(run: AttemptState): LandingOutcome? {
        enter(run, LandingStep.L4_TOUCHDOWN, "Nad środkiem płyty")

        // Z2: auto-lądowanie DJI przejmuje sterowanie dopiero po czystym wyłączeniu Virtual Stick —
        // inaczej pętla 20 Hz wysyłałaby HOVER w trakcie przyziemiania.
        flight.setVelocityTarget(Setpoint.HOVER)
        val stopped = flight.stopVirtualStick()
        logger.command("landing_vs_stop", stopped, mapOf("attempt" to run.attempt))
        if (stopped is CommandResult.Failure || flight.controlOwner.value == ControlOwner.VIRTUAL_STICK) {
            return LandingOutcome.Aborted("Nie udało się wyłączyć Virtual Stick przed przyziemieniem: ${(stopped as? CommandResult.Failure)?.reason ?: "nadal właścicielem"}")
        }
        run.virtualStickStarted = false
        if (run.visionAcquired) {
            vision.release(VISION_USER)
            run.visionAcquired = false
        }

        run.touchdownStartedAt = clock.monotonicMs()
        val landing = flight.startAutoLanding(confirmAutomatically = false)
        logger.command("landing_auto_start", landing, mapOf("attempt" to run.attempt))
        if (landing is CommandResult.Failure) return LandingOutcome.Aborted("Automatyczne lądowanie odrzucone: ${landing.reason}")
        run.autoLandingStarted = true

        var lastConfirmAt: Long? = null
        var confirmations = 0
        var protectionReported = false
        while (true) {
            val now = clock.monotonicMs()
            val d = telemetry.state.value
            if (d.areMotorsOn == false) {
                logger.log(LogCategory.STATE, LogLevel.INFO, "landing_motors_off", mapOf("attempt" to run.attempt, "ms" to now - run.touchdownStartedAt, "confirmations" to confirmations))
                run.autoLandingStarted = false
                return null
            }
            if (d.isLandingConfirmationNeeded == true) {
                if (!protectionReported) {
                    logger.safety("landing_confirmation_needed", LogLevel.WARN, mapOf("attempt" to run.attempt, "alt" to d.altitudeM, "ultra" to d.ultrasonicHeightM))
                    protectionReported = true
                }
                if (lastConfirmAt == null || now - lastConfirmAt >= config.confirmRetryMs) {
                    lastConfirmAt = now
                    confirmations++
                    logger.command("landing_confirm", flight.confirmLanding(), mapOf("attempt" to run.attempt, "n" to confirmations))
                }
            }
            if (d.isFlying == true && flight.controlOwner.value != ControlOwner.AUTO_LANDING && now - run.touchdownStartedAt > AUTO_LANDING_GRACE_MS) {
                return LandingOutcome.Aborted("Automatyczne lądowanie przerwane (właściciel sterowania: ${flight.controlOwner.value})")
            }
            if (now - run.touchdownStartedAt > config.touchdownTimeoutMs) {
                return LandingOutcome.Aborted("Przyziemienie trwa ponad ${config.touchdownTimeoutMs / 1000} s")
            }
            tick()
        }
    }

    // ------------------------------------------------------------------ L5

    private suspend fun verify(run: AttemptState): LandingOutcome {
        enter(run, LandingStep.L5_VERIFY, "Silniki wyłączone")
        val start = clock.monotonicMs()
        while (true) {
            val now = clock.monotonicMs()
            val motorsOff = telemetry.state.value.areMotorsOn == false
            val st = station.link.value.toStatus()
            // Status musi przyjść po rozpoczęciu przyziemienia — stary „dronePresent” sprzed startu nic nie znaczy.
            val present = st.isConnected && st.dronePresent && st.receivedAtMs >= run.touchdownStartedAt
            if (motorsOff && present) {
                logger.log(LogCategory.STATE, LogLevel.INFO, "landing_verified", mapOf("attempt" to run.attempt, "ms" to now - start))
                return LandingOutcome.Landed
            }
            if (now - start > config.stationConfirmTimeoutMs) {
                logger.safety("landing_verify_failed", LogLevel.ERROR, mapOf("attempt" to run.attempt, "motorsOff" to motorsOff, "dronePresent" to st.dronePresent, "connected" to st.isConnected))
                return LandingOutcome.Aborted(
                    "Brak potwierdzenia lądowania w ${config.stationConfirmTimeoutMs / 1000} s (silniki wyłączone: $motorsOff, stacja widzi drona: ${st.dronePresent && st.isConnected})",
                )
            }
            tick()
        }
    }

    // ------------------------------------------------------------------ sprzątanie (Z4)

    private suspend fun cleanUp(run: AttemptState, outcome: LandingOutcome?) {
        val motorsOn = telemetry.state.value.areMotorsOn != false
        if (run.autoLandingStarted && motorsOn && flight.controlOwner.value == ControlOwner.AUTO_LANDING) {
            // Przerwanie w L4 (STOP, timeout): KeyStopAutoLanding — dron zawisa.
            logger.command("landing_auto_cancel", flight.cancelAutoLanding(), mapOf("attempt" to run.attempt, "step" to run.step))
        }
        if (run.virtualStickStarted) {
            flight.setVelocityTarget(Setpoint.HOVER)
            logger.command("landing_vs_stop", flight.stopVirtualStick(), mapOf("attempt" to run.attempt, "step" to run.step, "cleanup" to true))
        }
        if (run.visionAcquired) vision.release(VISION_USER)
        _alarm.value = null
        _phase.value = null
        logger.log(
            LogCategory.STATE,
            if (outcome == LandingOutcome.Landed) LogLevel.INFO else LogLevel.WARN,
            "landing_end",
            mapOf("attempt" to run.attempt, "step" to run.step, "outcome" to (outcome?.toString() ?: "przerwane (anulowanie)")),
        )
    }

    // ------------------------------------------------------------------ pomocnicze

    private fun enter(run: AttemptState, step: LandingStep, cause: String) {
        val previous = run.step
        run.step = step
        _phase.value = LandingPhase(run.attempt, step)
        logger.log(
            LogCategory.STATE,
            LogLevel.INFO,
            "landing_phase",
            mapOf("attempt" to run.attempt, "from" to previous?.name, "to" to step.name, "label" to step.label, "cause" to cause),
        )
    }

    /** Jeden okres pętli; sprawdza utratę sterowania zgłoszoną przez platformę. */
    private suspend fun tick() {
        ticker.sleep(config.tickMs)
        controlLossReason?.let { throw Interrupted(LandingOutcome.Aborted("Utrata sterowania: $it")) }
    }

    private suspend fun waitUntil(timeoutMs: Long, condition: () -> Boolean): Boolean {
        val start = clock.monotonicMs()
        while (!condition()) {
            if (clock.monotonicMs() - start > timeoutMs) return false
            tick()
        }
        return true
    }

    private fun freshObservation(now: Long): MarkerObservation? =
        vision.observation.value?.takeIf { it.ageMs(now) in -config.tickMs..config.maxObservationAgeMs }

    /** Detekcja przetwarza nowe klatki (ostatni wynik nie starszy niż [LandingPlannerConfig.maxObservationAgeMs]). */
    private fun visionProducing(now: Long): Boolean =
        vision.results.value?.let { now - it.frameTimestampMs <= config.maxObservationAgeMs } == true

    /**
     * Ruch pionowy do [targetM] (wysokość względem stacji) z Virtual Stick, poziomo centrowanie GPS nad stacją.
     * @param stopOnMarker zakończ wcześniej, gdy pojawi się świeży marker (w L2 — od razu do L3)
     */
    private suspend fun moveToAltitude(targetM: Double, stopOnMarker: Boolean) {
        val startAlt = telemetry.state.value.altitudeM
        if (startAlt == null) {
            logger.log(LogCategory.STATE, LogLevel.WARN, "landing_altitude_unknown", mapOf("target" to targetM))
            return
        }
        val limitMs = (abs(targetM - startAlt) / config.verticalMps * 2_000).toLong() + 3_000
        val start = clock.monotonicMs()
        while (true) {
            val alt = telemetry.state.value.altitudeM ?: break
            val error = targetM - alt
            if (abs(error) <= ALTITUDE_TOLERANCE_M || clock.monotonicMs() - start > limitMs) break
            if (stopOnMarker && freshObservation(clock.monotonicMs()) != null) break
            val speed = min(config.verticalMps, abs(error) + 0.1)
            flight.setVelocityTarget(gpsCentering(if (error > 0) speed else -speed))
            tick()
        }
        flight.setVelocityTarget(Setpoint.HOVER)
        logger.log(LogCategory.STATE, LogLevel.INFO, "landing_altitude_reached", mapOf("target" to targetM, "alt" to telemetry.state.value.altitudeM))
    }

    /**
     * Punkt zadany: prędkość pionowa [upMps] i poziomo lot nad stację według GPS (BODY), gdy znane są
     * pozycja drona, kurs i położenie stacji; w promieniu [LandingPlannerConfig.gpsCenteringDeadbandM] — zawis.
     */
    private fun gpsCentering(upMps: Double): Setpoint {
        val d = telemetry.state.value
        val station = stationLocation()?.takeIf { it.isValid }
        val location = d.location?.takeIf { it.isValid }
        val heading = d.headingDeg
        if (station == null || location == null || heading == null) return Setpoint(0.0, 0.0, upMps, 0.0)
        val (north, east) = location.northEastTo(station)
        if (hypot(north, east) <= config.gpsCenteringDeadbandM) return Setpoint(0.0, 0.0, upMps, 0.0)
        val h = Math.toRadians(heading)
        var vf = config.gpsCenteringKp * (north * cos(h) + east * sin(h))
        var vr = config.gpsCenteringKp * (-north * sin(h) + east * cos(h))
        val v = hypot(vf, vr)
        if (v > config.gpsCenteringMaxMps) {
            vf *= config.gpsCenteringMaxMps / v
            vr *= config.gpsCenteringMaxMps / v
        }
        return Setpoint(vf, vr, upMps, 0.0)
    }

    private fun gpsDistanceM(): Double? {
        val station = stationLocation()?.takeIf { it.isValid } ?: return null
        return telemetry.state.value.distanceToM(station)
    }

    /** Wznoszenie bez znanej wysokości: przez czas `m / v`. */
    private suspend fun climbBlind(meters: Double) {
        val ticks = ceil(meters / config.verticalMps * 1_000 / config.tickMs).toInt()
        repeat(ticks) {
            flight.setVelocityTarget(Setpoint(0.0, 0.0, config.verticalMps, 0.0))
            tick()
        }
        flight.setVelocityTarget(Setpoint.HOVER)
    }

    companion object {
        const val VISION_USER = "landing"
        private const val ALTITUDE_TOLERANCE_M = 0.15
        private const val AUTO_LANDING_GRACE_MS = 1_500L
    }
}

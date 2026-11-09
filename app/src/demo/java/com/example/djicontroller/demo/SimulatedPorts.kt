package com.example.djicontroller.demo

import com.example.djicontroller.core.control.ControlArbiter
import com.example.djicontroller.core.landing.LandingOutcome
import com.example.djicontroller.core.landing.LandingProcedure
import com.example.djicontroller.core.logging.command
import com.example.djicontroller.core.logging.marker
import com.example.djicontroller.core.logging.safety
import com.example.djicontroller.core.logging.system
import com.example.djicontroller.core.model.CommandResult
import com.example.djicontroller.core.model.ConnectionState
import com.example.djicontroller.core.model.ControlLoss
import com.example.djicontroller.core.model.ControlOwner
import com.example.djicontroller.core.model.DroneState
import com.example.djicontroller.core.model.MarkerObservation
import com.example.djicontroller.core.model.MissionPlan
import com.example.djicontroller.core.model.MissionProgress
import com.example.djicontroller.core.model.MissionStatus
import com.example.djicontroller.core.model.PowerAction
import com.example.djicontroller.core.model.Setpoint
import com.example.djicontroller.core.model.SetpointLimits
import com.example.djicontroller.core.model.StationCommand
import com.example.djicontroller.core.model.StationLink
import com.example.djicontroller.core.model.StationStatus
import com.example.djicontroller.core.model.StickAxes
import com.example.djicontroller.core.model.VideoFrame
import com.example.djicontroller.core.model.VirtualStickStatus
import com.example.djicontroller.core.model.WaypointAction
import com.example.djicontroller.core.port.CameraPort
import com.example.djicontroller.core.port.Clock
import com.example.djicontroller.core.port.ConnectionPort
import com.example.djicontroller.core.port.FlightControlPort
import com.example.djicontroller.core.port.FlightLogger
import com.example.djicontroller.core.port.LogLevel
import com.example.djicontroller.core.port.MissionPort
import com.example.djicontroller.core.port.VisionPort
import com.example.djicontroller.core.port.StationPort
import com.example.djicontroller.core.port.TelemetryPort
import com.example.djicontroller.vision.LandingPadLayout
import com.example.djicontroller.vision.SyntheticPadCamera
import com.example.djicontroller.vision.SyntheticPose
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.buffer
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.shareIn
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import kotlin.math.cos
import kotlin.math.hypot
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sin

/*
 * Porty z :core zaimplementowane na symulatorze. Kontrakty są takie jak u adapterów DJI:
 * polecenia asynchroniczne (start, lądowanie) zwracają Success od razu, a efekt widać
 * w telemetrii; wyłączność sterowania pilnuje wspólny ControlArbiter (Z2).
 */

class SimulatedConnection(sim: DroneSimulator) : ConnectionPort {
    override val state: StateFlow<ConnectionState> = sim.connection
}

class SimulatedTelemetry(sim: DroneSimulator) : TelemetryPort {
    override val state: StateFlow<DroneState> = sim.state
}

/** Misje WPML: lot po kolejnych punktach planu, z pauzą, wznowieniem i przerwaniem. */
class SimulatedMission(
    private val sim: DroneSimulator,
    private val arbiter: ControlArbiter,
    private val scope: CoroutineScope,
    private val logger: FlightLogger,
) : MissionPort {
    private val _progress = MutableStateFlow(MissionProgress())
    override val progress: StateFlow<MissionProgress> = _progress.asStateFlow()

    private val plans = mutableMapOf<String, MissionPlan>()
    private var job: Job? = null

    override suspend fun upload(plan: MissionPlan): CommandResult {
        if (sim.connection.value !is ConnectionState.Connected) return CommandResult.Failure("Brak połączenia z dronem")
        _progress.value = MissionProgress(plan.id, MissionStatus.UPLOADING, null, plan.waypoints.size)
        delay(400)
        plans[plan.id] = plan
        _progress.value = MissionProgress(plan.id, MissionStatus.READY, null, plan.waypoints.size)
        logger.system("sim_mission_uploaded", fields = mapOf("plan" to plan.id, "points" to plan.waypoints.size))
        return CommandResult.Success
    }

    override suspend fun start(missionId: String): CommandResult {
        val plan = plans[missionId] ?: return CommandResult.Failure("Misja $missionId nie została wysłana")
        if (sim.state.value.isFlying != true) return CommandResult.Failure("Dron nie jest w powietrzu")
        if (!arbiter.tryAcquire(ControlOwner.MISSION)) return CommandResult.Failure("Sterowanie zajęte: ${arbiter.owner.value}")
        job?.cancelAndJoin()
        sim.paused = false
        sim.setFlightMode("WAYPOINT")
        _progress.value = MissionProgress(plan.id, MissionStatus.EXECUTING, 0, plan.waypoints.size)
        job = scope.launch {
            for ((index, wp) in plan.waypoints.withIndex()) {
                _progress.value = _progress.value.copy(missionWaypointIndex = index)
                sim.moveTo(wp.location, wp.altitudeM, wp.speedMps ?: plan.speedMps)
                for (action in wp.actions) {
                    when (action) {
                        is WaypointAction.Hover -> delay((action.seconds * 1000 / sim.timeScale).toLong())
                        is WaypointAction.GimbalPitch -> sim.gimbalPitchDeg = action.pitchDeg
                        WaypointAction.TakePhoto -> logger.system("sim_photo", fields = mapOf("index" to index))
                    }
                }
            }
            _progress.value = _progress.value.copy(status = MissionStatus.FINISHED)
            sim.setFlightMode("GPS")
            arbiter.release(ControlOwner.MISSION)
        }
        return CommandResult.Success
    }

    override suspend fun pause(): CommandResult {
        if (_progress.value.status != MissionStatus.EXECUTING) return CommandResult.Failure("Misja nie jest wykonywana")
        sim.paused = true
        _progress.value = _progress.value.copy(status = MissionStatus.PAUSED)
        return CommandResult.Success
    }

    override suspend fun resume(): CommandResult {
        if (_progress.value.status != MissionStatus.PAUSED) return CommandResult.Failure("Misja nie jest wstrzymana")
        sim.paused = false
        _progress.value = _progress.value.copy(status = MissionStatus.EXECUTING)
        return CommandResult.Success
    }

    override suspend fun stop(): CommandResult {
        job?.cancelAndJoin()
        job = null
        sim.paused = false
        sim.setFlightMode("GPS")
        _progress.value = _progress.value.copy(status = MissionStatus.IDLE)
        arbiter.release(ControlOwner.MISSION)
        return CommandResult.Success
    }
}

/**
 * Start, lądowanie i Virtual Stick — ten sam kontrakt co `FlightControl` z :msdk-adapter:
 * pętla 20 Hz z watchdogiem 200 ms → zawis (Z4), ochrona lądowania z automatycznym potwierdzeniem (R5),
 * mapowanie osi [StickAxes] emulujące konwencję DJI (`roll` = przód, `pitch` = prawo).
 */
class SimulatedFlight(
    private val sim: DroneSimulator,
    private val arbiter: ControlArbiter,
    private val scope: CoroutineScope,
    private val clock: Clock,
    private val logger: FlightLogger,
    initialAxes: StickAxes = StickAxes.ROLL_FORWARD,
) : FlightControlPort {
    override val controlOwner: StateFlow<ControlOwner> = arbiter.owner
    private val _controlLost = MutableSharedFlow<ControlLoss>(extraBufferCapacity = 8)
    override val controlLost: Flow<ControlLoss> = _controlLost

    private val _virtualStick = MutableStateFlow(VirtualStickStatus(authority = "RC", axes = initialAxes))
    override val virtualStick: StateFlow<VirtualStickStatus> = _virtualStick.asStateFlow()

    private val limits = SetpointLimits()
    @Volatile private var target = Setpoint.HOVER
    @Volatile private var targetAtMs = 0L
    private var vsJob: Job? = null
    private var landingJob: Job? = null

    override suspend fun takeOff(): CommandResult {
        if (sim.connection.value !is ConnectionState.Connected) return CommandResult.Failure("Brak połączenia z dronem")
        if (sim.state.value.isFlying == true) return CommandResult.Success
        scope.launch { sim.takeOff() }
        logger.command("sim_takeoff", CommandResult.Success)
        return CommandResult.Success
    }

    override suspend fun startAutoLanding(confirmAutomatically: Boolean): CommandResult {
        if (sim.state.value.isFlying != true) return CommandResult.Failure("Dron nie jest w powietrzu")
        if (arbiter.owner.value == ControlOwner.VIRTUAL_STICK) stopVirtualStick()
        if (!arbiter.tryAcquire(ControlOwner.AUTO_LANDING)) return CommandResult.Failure("Sterowanie zajęte: ${arbiter.owner.value}")
        landingJob?.cancelAndJoin()
        landingJob = scope.launch {
            val confirmer = launch {
                sim.state.first { it.isLandingConfirmationNeeded == true }
                logger.safety("landing_confirmation_needed", LogLevel.WARN, mapOf("auto" to confirmAutomatically))
                if (confirmAutomatically) confirmLanding()
            }
            sim.descendAndLand()
            confirmer.cancel()
            arbiter.release(ControlOwner.AUTO_LANDING)
        }
        logger.command("sim_auto_landing", CommandResult.Success, mapOf("autoConfirm" to confirmAutomatically))
        return CommandResult.Success
    }

    override suspend fun cancelAutoLanding(): CommandResult {
        landingJob?.cancelAndJoin()
        landingJob = null
        sim.setFlightMode("GPS")
        arbiter.release(ControlOwner.AUTO_LANDING)
        return CommandResult.Success
    }

    override suspend fun confirmLanding(): CommandResult {
        sim.confirmLanding()
        logger.command("sim_landing_confirm", CommandResult.Success)
        return CommandResult.Success
    }

    override suspend fun startVirtualStick(): CommandResult {
        if (!arbiter.tryAcquire(ControlOwner.VIRTUAL_STICK)) return CommandResult.Failure("Sterowanie zajęte: ${arbiter.owner.value}")
        if (vsJob?.isActive == true) return CommandResult.Success
        setVelocityTarget(Setpoint.HOVER)
        sim.resetVelocityState()
        sim.setFlightMode("VIRTUAL_STICK")
        vsJob = scope.launch {
            var watchdog = false
            while (isActive) {
                val fresh = clock.monotonicMs() - targetAtMs <= WATCHDOG_MS
                if (watchdog == fresh) {
                    watchdog = !fresh
                    logger.system("sim_vs_watchdog", if (watchdog) LogLevel.WARN else LogLevel.INFO, mapOf("hover" to watchdog))
                }
                val sent = if (fresh) target else Setpoint.HOVER
                sim.applyVelocity(sent.asDjiAxes(_virtualStick.value.axes), VS_PERIOD_MS)
                _virtualStick.value = _virtualStick.value.copy(lastSent = sent, watchdogHover = !fresh)
                delay(VS_PERIOD_MS)
            }
        }
        _virtualStick.value = _virtualStick.value.copy(enabled = true, advancedMode = true, authority = "MSDK", loopActive = true)
        logger.system("sim_vs_on")
        return CommandResult.Success
    }

    override suspend fun stopVirtualStick(): CommandResult {
        vsJob?.cancelAndJoin()
        vsJob = null
        sim.setFlightMode("GPS")
        arbiter.release(ControlOwner.VIRTUAL_STICK)
        _virtualStick.value = _virtualStick.value.copy(
            enabled = false, advancedMode = false, authority = "RC", loopActive = false, lastSent = Setpoint.HOVER,
        )
        logger.system("sim_vs_off")
        return CommandResult.Success
    }

    override fun setVelocityTarget(setpoint: Setpoint) {
        target = setpoint.clamped(limits)
        targetAtMs = clock.monotonicMs()
    }

    override fun setStickAxes(axes: StickAxes): CommandResult {
        if (vsJob?.isActive == true) return CommandResult.Failure("Najpierw wyłącz Virtual Stick")
        _virtualStick.value = _virtualStick.value.copy(axes = axes)
        logger.system("sim_vs_axes", LogLevel.WARN, mapOf("axes" to axes))
        return CommandResult.Success
    }

    override suspend fun holdPosition(): CommandResult = when (arbiter.owner.value) {
        ControlOwner.VIRTUAL_STICK -> {
            setVelocityTarget(Setpoint.HOVER)
            CommandResult.Success
        }
        ControlOwner.AUTO_LANDING -> cancelAutoLanding()
        ControlOwner.MISSION, ControlOwner.NONE -> CommandResult.Success
    }

    /** Akcja testowa: pilot przejmuje drążki z aparatury. */
    fun simulateRcTakeover() {
        scope.launch {
            if (vsJob?.isActive == true) stopVirtualStick()
            _virtualStick.value = _virtualStick.value.copy(lastChangeReason = "RC_PAUSE_STOP")
            _controlLost.emit(ControlLoss("pilot poruszył drążkami RC-N2", takenOverByRc = true))
        }
    }

    private companion object {
        const val VS_PERIOD_MS = 50L
        const val WATCHDOG_MS = 200L

        /**
         * Emulacja DJI: aplikacja wpisuje przód/prawo w `roll`/`pitch` według [axes], a dron interpretuje
         * `roll` jako przód i `pitch` jako prawo. Przy złym mapowaniu dron w symulacji leci bokiem —
         * tak samo jak prawdziwy, więc test osi w Diagnostyce ma tu sens.
         */
        fun Setpoint.asDjiAxes(axes: StickAxes): Setpoint {
            val roll: Double
            val pitch: Double
            when (axes) {
                StickAxes.ROLL_FORWARD -> { roll = forwardMps; pitch = rightMps }
                StickAxes.PITCH_FORWARD -> { pitch = forwardMps; roll = rightMps }
            }
            return Setpoint(forwardMps = roll, rightMps = pitch, upMps = upMps, yawRateDegS = yawRateDegS)
        }
    }
}

/** Stacja: popychacz przycisku, ładowanie (tylko przy wyłączonym dronie, R1), status co zmianę. */
class SimulatedStation(
    private val sim: DroneSimulator,
    private val scope: CoroutineScope,
    private val clock: Clock,
    private val logger: FlightLogger,
    private val chargeRealSeconds: Double = 15.0,
) : StationPort {
    private val _link = MutableStateFlow<StationLink>(StationLink.Disconnected)
    override val link: StateFlow<StationLink> = _link.asStateFlow()

    @Volatile private var connected = true
    @Volatile private var charging = false
    @Volatile private var padClear = true
    private var chargeJob: Job? = null

    fun start() {
        publish()
        // Obecność drona na płycie wynika z jego pozycji i stanu lotu.
        scope.launch { sim.state.collect { publish() } }
    }

    override suspend fun send(command: StationCommand): CommandResult {
        if (!connected) return CommandResult.Failure("Brak łącza ze stacją")
        delay(300)
        val result = when (command) {
            is StationCommand.PowerButton -> when (command.action) {
                PowerAction.POWER_ON -> {
                    if (!sim.poweredOn) scope.launch { sim.powerOn() }
                    CommandResult.Success
                }
                PowerAction.POWER_OFF -> {
                    if (sim.state.value.areMotorsOn == true) {
                        CommandResult.Failure("Silniki pracują — wyłączenie zablokowane")
                    } else {
                        scope.launch { delay(800); sim.powerOff() }
                        CommandResult.Success
                    }
                }
            }
            is StationCommand.Charge -> if (command.on) startCharging() else stopCharging()
        }
        logger.command("sim_station_${command::class.simpleName}", result, mapOf("cmd" to command.toString()))
        return result
    }

    private fun startCharging(): CommandResult {
        if (sim.poweredOn) return CommandResult.Failure("Ładowanie tylko przy wyłączonym dronie (R1)")
        charging = true
        publish()
        chargeJob = scope.launch {
            sim.charge(chargeRealSeconds)
            charging = false
            publish()
        }
        return CommandResult.Success
    }

    private fun stopCharging(): CommandResult {
        chargeJob?.cancel()
        chargeJob = null
        charging = false
        publish()
        return CommandResult.Success
    }

    fun toggleLink() {
        connected = !connected
        logger.system("sim_station_link", LogLevel.WARN, mapOf("connected" to connected))
        publish()
    }

    fun togglePad() {
        padClear = !padClear
        logger.system("sim_station_pad", LogLevel.WARN, mapOf("clear" to padClear))
        publish()
    }

    private fun publish() {
        if (!connected) {
            _link.value = StationLink.Disconnected
            return
        }
        val d = sim.state.value
        val onPad = d.isFlying != true && sim.currentPosition.distanceTo(sim.station) < 1.5
        _link.value = StationLink.Connected(
            StationStatus(
                charging = charging,
                dronePresent = onPad,
                padClear = padClear,
                ready = !charging && sim.batteryPercent >= 95,
                receivedAtMs = clock.monotonicMs(),
            ),
        )
    }
}

/**
 * Precyzyjne lądowanie (L2–L5) na symulatorze: „detekcja markera” liczy położenie stacji
 * względem drona z pozycji symulatora (z zasięgiem kamery zależnym od wysokości),
 * centrowanie i opadanie idą przez Virtual Stick, przyziemienie przez automatyczne lądowanie.
 */
class SimulatedLanding(
    private val sim: DroneSimulator,
    private val flight: FlightControlPort,
    private val clock: Clock,
    private val logger: FlightLogger,
    private val markerLostTimeoutMs: Long = 3_000,
    private val camera: CameraPort? = null,
) : LandingProcedure {

    /**
     * Detekcja ArUco z :vision (podpinana przez [SimulatedBackend.attachVision]). Gdy jest, lądowanie
     * korzysta z PRAWDZIWEJ detekcji na obrazie syntetycznym; bez niej (testy JVM bez OpenCV) —
     * z położenia liczonego geometrycznie z symulatora.
     */
    @Volatile var vision: VisionPort? = null

    override suspend fun execute(attempt: Int): LandingOutcome {
        // L2: kamera w dół, zanim zaczniemy szukać markera.
        camera?.let { cam ->
            val gimbal = cam.pointGimbalDown()
            logger.command("sim_landing_gimbal_down", gimbal, mapOf("attempt" to attempt))
            withTimeoutOrNull(3_000) { cam.gimbalPitchDeg.first { it != null && it <= -85.0 } }
        }
        val visionPort = vision
        visionPort?.acquire(VISION_USER)
        logger.system("sim_landing_start", fields = mapOf("attempt" to attempt, "source" to if (visionPort != null) "aruco" else "geometria"))

        val vs = flight.startVirtualStick()
        if (vs is CommandResult.Failure) {
            visionPort?.release(VISION_USER)
            return LandingOutcome.Aborted("Virtual Stick: ${vs.reason}")
        }
        try {
            var lastSeenMs = clock.monotonicMs()
            var lastUsedFrame = Long.MIN_VALUE
            var lastCommand = Setpoint.HOVER
            while (true) {
                val now = clock.monotonicMs()
                val obs = if (visionPort != null) {
                    visionPort.observation.value?.takeIf { it.ageMs(now) <= MAX_OBSERVATION_AGE_MS }
                } else {
                    observeMarker().also { logger.marker(it, now) }
                }
                if (obs == null) {
                    flight.setVelocityTarget(Setpoint(0.0, 0.0, 0.3, 0.0)) // wznoś się, żeby objąć większy obszar
                    if (now - lastSeenMs > markerLostTimeoutMs) {
                        flight.setVelocityTarget(Setpoint.HOVER)
                        return LandingOutcome.MarkerLost("Marker niewidoczny dłużej niż ${markerLostTimeoutMs / 1000} s")
                    }
                } else {
                    lastSeenMs = now
                    if (obs.timestampMs != lastUsedFrame) {
                        lastUsedFrame = obs.timestampMs
                        val tolerance = max(0.1, obs.dzM * 0.08)
                        val descend = obs.horizontalErrorM < tolerance
                        lastCommand = Setpoint(
                            forwardMps = obs.dxM * 0.6,
                            rightMps = obs.dyM * 0.6,
                            upMps = if (descend) -min(0.8, 0.15 + obs.dzM * 0.2) else 0.0,
                            yawRateDegS = 0.0,
                        )
                        flight.setVelocityTarget(lastCommand)
                        if (obs.dzM <= 0.5 && obs.horizontalErrorM < 0.1) break
                    } else {
                        // Ta sama klatka co poprzednio — odświeżamy cel, żeby watchdog nie wymusił zawisu.
                        flight.setVelocityTarget(lastCommand)
                    }
                }
                delay(100)
            }
        } finally {
            // Także przy anulowaniu (STOP): zawis i oddanie Virtual Stick muszą się wykonać.
            withContext(NonCancellable) {
                flight.setVelocityTarget(Setpoint.HOVER)
                if (flight.controlOwner.value == ControlOwner.VIRTUAL_STICK) flight.stopVirtualStick()
                visionPort?.release(VISION_USER)
            }
        }

        val landing = flight.startAutoLanding()
        if (landing is CommandResult.Failure) return LandingOutcome.Aborted("Lądowanie automatyczne: ${landing.reason}")
        val landed = withTimeoutOrNull(20_000) { sim.state.first { it.isFlying == false && it.areMotorsOn == false } }
        if (landed != null) camera?.resetGimbal()
        return if (landed != null) LandingOutcome.Landed else LandingOutcome.Aborted("Dron nie przyziemił w ciągu 20 s")
    }

    private companion object {
        const val VISION_USER = "sim_landing"
        const val MAX_OBSERVATION_AGE_MS = 500L
    }

    /** Marker w polu widzenia kamery (FOV 80°) i niezasłonięty → położenie w układzie BODY. */
    private fun observeMarker(): MarkerObservation? {
        if (sim.markerHidden) return null
        val altitude = max(sim.currentAltitude, 0.05)
        val (n, e) = sim.currentPosition.northEastTo(sim.station)
        val visibleRadius = altitude * 0.84 // tan(40°)
        if (hypot(n, e) > visibleRadius || altitude > 15.0) return null
        val h = Math.toRadians(sim.currentHeading)
        return MarkerObservation(
            markerId = if (altitude > 2.0) 0 else 1,
            dxM = n * cos(h) + e * sin(h),
            dyM = -n * sin(h) + e * cos(h),
            dzM = altitude,
            yawDeg = 0.0,
            timestampMs = clock.monotonicMs(),
        )
    }
}

/**
 * Kamera w demo: syntetyczny obraz z prawdziwymi markerami ArUco ([SyntheticPadCamera]) liczony
 * z pozycji symulatora ok. 12 razy na sekundę. Te same klatki widzi podgląd ([SimulatedVideo])
 * i detektor z :vision — wariant demo sprawdza więc cały potok wizyjny.
 */
class SimulatedCamera(
    private val sim: DroneSimulator,
    scope: CoroutineScope,
    private val clock: Clock,
    private val logger: FlightLogger,
    private val layout: LandingPadLayout = LandingPadLayout.STATION,
    private val fps: Int = 12,
) : CameraPort {

    override val gimbalPitchDeg: StateFlow<Double?> =
        sim.gimbalPitch.map<Double, Double?> { it }.stateIn(scope, SharingStarted.Eagerly, sim.gimbalPitchDeg)

    override val frames: Flow<VideoFrame> = flow {
        val camera = try {
            SyntheticPadCamera(layout)
        } catch (e: Exception) {
            failed("init", e)
            return@flow
        } catch (e: LinkageError) {
            // np. UnsatisfiedLinkError, gdy biblioteka natywna OpenCV się nie załadowała
            failed("init", e)
            return@flow
        }
        logger.system("sim_camera_on", fields = mapOf("w" to camera.calibration.width, "h" to camera.calibration.height))
        try {
            val periodMs = 1000L / fps
            while (currentCoroutineContext().isActive) {
                val started = clock.monotonicMs()
                if (sim.poweredOn && !sim.linkCut) {
                    val (north, east) = sim.station.northEastTo(sim.currentPosition)
                    val pose = SyntheticPose(
                        northM = north,
                        eastM = east,
                        heightM = max(sim.currentAltitude, 0.15),
                        headingDeg = sim.currentHeading,
                        gimbalPitchDeg = sim.gimbalPitchDeg,
                        markerHidden = sim.markerHidden,
                    )
                    val frame = try {
                        camera.render(pose, clock.monotonicMs())
                    } catch (e: RuntimeException) {
                        // Pojedyncza nieudana klatka nie może zatrzymać podglądu ani detekcji.
                        if (!renderErrorLogged) failed("render", e)
                        renderErrorLogged = true
                        null
                    }
                    if (frame != null) emit(frame)
                }
                delay(max(5L, periodMs - (clock.monotonicMs() - started)))
            }
        } finally {
            camera.close()
            logger.system("sim_camera_off")
        }
    }
        .buffer(capacity = 1, onBufferOverflow = BufferOverflow.DROP_OLDEST)
        .flowOn(Dispatchers.Default)
        .shareIn(scope, SharingStarted.WhileSubscribed(), replay = 1)

    /** Powód braku obrazu syntetycznego (pokazywany w podglądzie); `null` = działa. */
    @Volatile var problem: String? = null
        private set

    @Volatile private var renderErrorLogged = false

    private fun failed(stage: String, e: Throwable) {
        problem = "Kamera syntetyczna: ${e::class.simpleName}: ${e.message}"
        logger.system("sim_camera_failed", LogLevel.ERROR, mapOf("stage" to stage, "error" to e.stackTraceToString()))
    }

    override suspend fun pointGimbalDown(): CommandResult {
        sim.gimbalPitchDeg = -90.0
        logger.command("sim_gimbal_down", CommandResult.Success)
        return CommandResult.Success
    }

    override suspend fun resetGimbal(): CommandResult {
        sim.gimbalPitchDeg = 0.0
        logger.command("sim_gimbal_reset", CommandResult.Success)
        return CommandResult.Success
    }
}

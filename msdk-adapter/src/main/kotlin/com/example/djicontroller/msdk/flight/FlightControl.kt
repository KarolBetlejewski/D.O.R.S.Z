package com.example.djicontroller.msdk.flight

import android.os.SystemClock
import com.example.djicontroller.core.control.ControlArbiter
import com.example.djicontroller.core.logging.command
import com.example.djicontroller.core.logging.safety
import com.example.djicontroller.core.logging.setpoint
import com.example.djicontroller.core.logging.system
import com.example.djicontroller.core.model.CommandResult
import com.example.djicontroller.core.model.ControlLoss
import com.example.djicontroller.core.model.ControlOwner
import com.example.djicontroller.core.model.Setpoint
import com.example.djicontroller.core.model.SetpointLimits
import com.example.djicontroller.core.model.StickAxes
import com.example.djicontroller.core.model.VirtualStickStatus
import com.example.djicontroller.core.port.FlightControlPort
import com.example.djicontroller.core.port.FlightLogger
import com.example.djicontroller.core.port.LogLevel
import com.example.djicontroller.core.port.MissionPort
import com.example.djicontroller.core.port.TelemetryPort
import com.example.djicontroller.msdk.common.awaitCompletion
import com.example.djicontroller.msdk.common.perform
import com.example.djicontroller.msdk.connection.SdkInitializer
import dji.sdk.keyvalue.key.FlightControllerKey
import dji.sdk.keyvalue.key.KeyTools
import dji.sdk.keyvalue.value.flightcontroller.FlightControlAuthorityChangeReason
import dji.sdk.keyvalue.value.flightcontroller.FlightCoordinateSystem
import dji.sdk.keyvalue.value.flightcontroller.RollPitchControlMode
import dji.sdk.keyvalue.value.flightcontroller.VerticalControlMode
import dji.sdk.keyvalue.value.flightcontroller.VirtualStickFlightControlParam
import dji.sdk.keyvalue.value.flightcontroller.YawControlMode
import dji.v5.manager.aircraft.virtualstick.VirtualStickManager
import dji.v5.manager.aircraft.virtualstick.VirtualStickState
import dji.v5.manager.aircraft.virtualstick.VirtualStickStateListener
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.filter
import kotlinx.coroutines.flow.launchIn
import kotlinx.coroutines.flow.onEach
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

/**
 * Sterowanie lotem (sekcja 5.6) — wzorzec `VirtualStickVM.kt` i `BasicAircraftControlVM.kt` z przykładu DJI.
 *
 * Virtual Stick w trybie zaawansowanym:
 * - `enableVirtualStick()` + `setVirtualStickAdvancedModeEnabled(true)`;
 * - pętla 20 Hz (`delay(50)`) wysyła `sendVirtualStickAdvancedParam` w trybie VELOCITY / BODY;
 * - watchdog: cel starszy niż 200 ms (`SystemClock.elapsedRealtime()`) → wysyłany jest [Setpoint.HOVER] (Z4);
 * - `VirtualStickStateListener`: każda zmiana właściciela sterowania inna niż `MSDK_REQUEST` w trakcie pracy pętli
 *   kończy pętlę i trafia do [controlLost] → `SafetySupervisor` (ZAWIS albo AWARIA).
 *
 * Zasada Z2: przed włączeniem Virtual Stick zatrzymujemy misję WPML ([MissionPort.stop]), a sterowanie
 * przejmujemy przez [ControlArbiter] — przy działającym lądowaniu automatycznym Virtual Stick się nie włączy.
 *
 * Lądowanie: `KeyStartAutoLanding` + nadzór do przyziemienia; przy `KeyIsLandingConfirmationNeeded`
 * (ochrona lądowania nad płytą stacji, R5) automatycznie `KeyConfirmLanding`, jeśli wołający o to poprosił.
 */
class FlightControl(
    private val sdk: SdkInitializer,
    private val scope: CoroutineScope,
    private val arbiter: ControlArbiter,
    private val telemetry: TelemetryPort,
    private val mission: MissionPort,
    private val logger: FlightLogger,
    initialAxes: StickAxes = StickAxes.ROLL_FORWARD,
    private val limits: SetpointLimits = SetpointLimits(),
    private val periodMs: Long = 50,
    private val watchdogMs: Long = 200,
) : FlightControlPort {

    private val vsManager
        get() = VirtualStickManager.getInstance()

    override val controlOwner: StateFlow<ControlOwner> = arbiter.owner

    private val _controlLost = MutableSharedFlow<ControlLoss>(extraBufferCapacity = 16)
    override val controlLost: Flow<ControlLoss> = _controlLost.asSharedFlow()

    private val _virtualStick = MutableStateFlow(VirtualStickStatus(axes = initialAxes))
    override val virtualStick: StateFlow<VirtualStickStatus> = _virtualStick.asStateFlow()

    @Volatile private var target: Setpoint = Setpoint.HOVER
    @Volatile private var targetTime: Long = 0L

    private val vsMutex = Mutex()
    @Volatile private var loopJob: Job? = null
    private var landingWatch: Job? = null

    private val stateListener = object : VirtualStickStateListener {
        override fun onVirtualStickStateUpdate(stickState: VirtualStickState) {
            val authority = stickState.currentFlightControlAuthorityOwner?.name
            _virtualStick.update {
                it.copy(
                    enabled = stickState.isVirtualStickEnable,
                    advancedMode = stickState.isVirtualStickAdvancedModeEnabled,
                    authority = authority,
                )
            }
            logger.system(
                "vs_state",
                fields = mapOf(
                    "enabled" to stickState.isVirtualStickEnable,
                    "advanced" to stickState.isVirtualStickAdvancedModeEnabled,
                    "authority" to authority,
                ),
            )
            if (loopJob?.isActive == true && !stickState.isVirtualStickEnable) {
                onAuthorityLost("VIRTUAL_STICK_DISABLED", "dron wyłączył Virtual Stick")
            }
        }

        override fun onChangeReasonUpdate(reason: FlightControlAuthorityChangeReason) {
            _virtualStick.update { it.copy(lastChangeReason = reason.name) }
            logger.safety("vs_authority_change", LogLevel.WARN, mapOf("reason" to reason.name, "loop" to (loopJob?.isActive == true)))
            if (reason.name == "MSDK_REQUEST") return
            if (loopJob?.isActive == true) onAuthorityLost(reason.name, describe(reason.name))
        }
    }

    init {
        sdk.initialized
            .filter { it }
            .onEach {
                vsManager.setVirtualStickStateListener(stateListener)
                logger.system("vs_listener_registered")
            }
            .launchIn(scope)
    }

    // ------------------------------------------------------------------ start / lądowanie

    override suspend fun takeOff(): CommandResult {
        if (!sdk.initialized.value) return notReady()
        val d = telemetry.state.value
        if (!d.connected) return CommandResult.Failure("Brak połączenia z kontrolerem lotu")
        if (d.isFlying == true) return CommandResult.Success
        val result = KeyTools.createKey(FlightControllerKey.KeyStartTakeoff).perform()
        logger.command("takeoff", result)
        return result
    }

    override suspend fun startAutoLanding(confirmAutomatically: Boolean): CommandResult {
        if (!sdk.initialized.value) return notReady()
        if (telemetry.state.value.isFlying != true) return CommandResult.Failure("Dron nie jest w powietrzu")
        when (arbiter.owner.value) {
            ControlOwner.AUTO_LANDING -> return CommandResult.Success
            ControlOwner.VIRTUAL_STICK -> logger.command("vs_stop_before_landing", stopVirtualStick())
            ControlOwner.MISSION -> return CommandResult.Failure("Najpierw zatrzymaj misję WPML (Z2)")
            ControlOwner.NONE -> Unit
        }
        if (!arbiter.tryAcquire(ControlOwner.AUTO_LANDING)) {
            return CommandResult.Failure("Sterowanie zajęte: ${arbiter.owner.value}")
        }
        val result = KeyTools.createKey(FlightControllerKey.KeyStartAutoLanding).perform()
        logger.command("auto_landing_start", result, mapOf("autoConfirm" to confirmAutomatically))
        if (result is CommandResult.Failure) {
            arbiter.release(ControlOwner.AUTO_LANDING)
            return result
        }
        landingWatch?.cancel()
        landingWatch = scope.launch { superviseLanding(confirmAutomatically) }
        return result
    }

    override suspend fun cancelAutoLanding(): CommandResult {
        if (!sdk.initialized.value) return notReady()
        landingWatch?.cancel()
        landingWatch = null
        val result = KeyTools.createKey(FlightControllerKey.KeyStopAutoLanding).perform()
        logger.command("auto_landing_cancel", result)
        arbiter.release(ControlOwner.AUTO_LANDING)
        return result
    }

    override suspend fun confirmLanding(): CommandResult {
        if (!sdk.initialized.value) return notReady()
        val result = KeyTools.createKey(FlightControllerKey.KeyConfirmLanding).perform()
        logger.command("landing_confirm", result)
        return result
    }

    /**
     * Nadzór lądowania automatycznego do przyziemienia: potwierdzenie ochrony lądowania (R5),
     * zwolnienie sterowania po wyłączeniu silników, wykrycie przerwania lądowania z aparatury.
     */
    private suspend fun superviseLanding(confirmAutomatically: Boolean) {
        val startedAt = SystemClock.elapsedRealtime()
        var lastConfirmAt = 0L
        var reportedConfirmation = false
        var notLandingSince: Long? = null
        while (arbiter.owner.value == ControlOwner.AUTO_LANDING) {
            val d = telemetry.state.value
            val now = SystemClock.elapsedRealtime()
            if (d.isFlying == false || d.areMotorsOn == false) {
                logger.system("auto_landing_done", fields = mapOf("ms" to now - startedAt))
                arbiter.release(ControlOwner.AUTO_LANDING)
                return
            }
            if (d.isLandingConfirmationNeeded == true) {
                if (!reportedConfirmation) {
                    logger.safety("landing_confirmation_needed", LogLevel.WARN, mapOf("auto" to confirmAutomatically, "alt" to d.altitudeM, "ultra" to d.ultrasonicHeightM))
                    reportedConfirmation = true
                }
                if (confirmAutomatically && now - lastConfirmAt >= CONFIRM_RETRY_MS) {
                    lastConfirmAt = now
                    confirmLanding()
                }
            }
            // Tryb lotu inny niż lądowanie przez dłuższy czas = lądowanie przerwane (np. drążek w górę na RC-N2).
            val mode = d.flightMode.orEmpty()
            if (now - startedAt > LANDING_MODE_GRACE_MS && !mode.contains("LANDING")) {
                val since = notLandingSince ?: now.also { notLandingSince = it }
                if (now - since > LANDING_MODE_GRACE_MS) {
                    logger.safety("auto_landing_interrupted", LogLevel.ERROR, mapOf("mode" to mode))
                    arbiter.syncFromAircraft(ControlOwner.NONE, "lądowanie przerwane: $mode")
                    _controlLost.tryEmit(ControlLoss("lądowanie automatyczne przerwane (tryb $mode)", takenOverByRc = true))
                    return
                }
            } else {
                notLandingSince = null
            }
            delay(LANDING_POLL_MS)
        }
    }

    // ------------------------------------------------------------------ Virtual Stick

    override suspend fun startVirtualStick(): CommandResult = vsMutex.withLock {
        if (!sdk.initialized.value) return@withLock notReady()
        if (loopJob?.isActive == true && arbiter.owner.value == ControlOwner.VIRTUAL_STICK) return@withLock CommandResult.Success
        if (!telemetry.state.value.connected) return@withLock CommandResult.Failure("Brak połączenia z kontrolerem lotu")

        // Z2: misja WPML i Virtual Stick nie mogą sterować jednocześnie.
        if (mission.progress.value.isActive || arbiter.owner.value == ControlOwner.MISSION) {
            val stopped = mission.stop()
            logger.command("vs_mission_stop", stopped)
            if (stopped is CommandResult.Failure) {
                return@withLock CommandResult.Failure("Nie udało się zatrzymać misji WPML: ${stopped.reason}")
            }
        }
        if (!arbiter.tryAcquire(ControlOwner.VIRTUAL_STICK)) {
            return@withLock CommandResult.Failure("Sterowanie zajęte: ${arbiter.owner.value}")
        }

        val enabled = awaitCompletion { cb -> vsManager.enableVirtualStick(cb) }
        logger.command("vs_enable", enabled)
        if (enabled is CommandResult.Failure) {
            arbiter.release(ControlOwner.VIRTUAL_STICK)
            return@withLock enabled
        }
        vsManager.setVirtualStickAdvancedModeEnabled(true)

        target = Setpoint.HOVER
        targetTime = SystemClock.elapsedRealtime()
        loopJob = scope.launch(Dispatchers.Default) { runLoop() }
        _virtualStick.update { it.copy(loopActive = true, watchdogHover = false) }
        logger.system("vs_loop_on", fields = mapOf("hz" to 1000 / periodMs, "watchdogMs" to watchdogMs, "axes" to _virtualStick.value.axes))
        CommandResult.Success
    }

    override suspend fun stopVirtualStick(): CommandResult = vsMutex.withLock {
        withContext(NonCancellable) { shutdownVirtualStick("stop") }
    }

    /** Zatrzymuje pętlę, wysyła ostatni zawis i wyłącza Virtual Stick. Woła się pod [vsMutex] albo z listenera. */
    private suspend fun shutdownVirtualStick(cause: String): CommandResult {
        loopJob?.cancelAndJoin()
        loopJob = null
        if (!sdk.initialized.value) {
            arbiter.release(ControlOwner.VIRTUAL_STICK)
            _virtualStick.update { it.copy(loopActive = false) }
            return notReady()
        }
        sendSafely(Setpoint.HOVER)
        try {
            vsManager.setVirtualStickAdvancedModeEnabled(false)
        } catch (e: RuntimeException) {
            logger.system("vs_advanced_off_failed", LogLevel.WARN, mapOf("error" to e.toString()))
        }
        val disabled = awaitCompletion { cb -> vsManager.disableVirtualStick(cb) }
        logger.command("vs_disable", disabled, mapOf("cause" to cause))
        arbiter.release(ControlOwner.VIRTUAL_STICK)
        _virtualStick.update { it.copy(loopActive = false, lastSent = Setpoint.HOVER, watchdogHover = false) }
        return disabled
    }

    override fun setVelocityTarget(setpoint: Setpoint) {
        target = setpoint.clamped(limits)
        targetTime = SystemClock.elapsedRealtime()
    }

    override fun setStickAxes(axes: StickAxes): CommandResult {
        if (loopJob?.isActive == true) return CommandResult.Failure("Najpierw wyłącz Virtual Stick")
        _virtualStick.update { it.copy(axes = axes) }
        logger.system("vs_axes", LogLevel.WARN, mapOf("axes" to axes))
        return CommandResult.Success
    }

    override suspend fun holdPosition(): CommandResult = when (arbiter.owner.value) {
        ControlOwner.VIRTUAL_STICK -> {
            setVelocityTarget(Setpoint.HOVER)
            logger.system("vs_hold")
            CommandResult.Success
        }
        ControlOwner.AUTO_LANDING -> cancelAutoLanding()
        // Misję zatrzymuje SafetySupervisor przez MissionPort.pause(); bez właściciela dron i tak wisi w GPS.
        ControlOwner.MISSION, ControlOwner.NONE -> CommandResult.Success
    }

    /** Pętla 20 Hz — jedyne miejsce, które wysyła `sendVirtualStickAdvancedParam`. */
    private suspend fun runLoop() {
        var lastLogged: Setpoint? = null
        var lastLogAt = 0L
        var watchdogActive = false
        while (currentCoroutineContext().isActive) {
            val now = SystemClock.elapsedRealtime()
            val stale = now - targetTime > watchdogMs
            val setpoint = if (stale) Setpoint.HOVER else target
            if (stale != watchdogActive) {
                watchdogActive = stale
                logger.system("vs_watchdog", if (stale) LogLevel.WARN else LogLevel.INFO, mapOf("hover" to stale, "ageMs" to now - targetTime))
            }
            sendSafely(setpoint)
            if (setpoint != lastLogged || now - lastLogAt >= SETPOINT_LOG_MS) {
                logger.setpoint(setpoint, if (stale) "watchdog" else "vs")
                lastLogged = setpoint
                lastLogAt = now
                _virtualStick.update { it.copy(lastSent = setpoint, watchdogHover = stale) }
            }
            delay(periodMs)
        }
    }

    private fun sendSafely(setpoint: Setpoint) {
        try {
            vsManager.sendVirtualStickAdvancedParam(setpoint.toParam(_virtualStick.value.axes))
        } catch (e: RuntimeException) {
            logger.system("vs_send_failed", LogLevel.ERROR, mapOf("error" to e.toString()))
        }
    }

    /** Dron odebrał sterowanie (aparatura, strefa, bateria) — kończymy pętlę i zgłaszamy utratę. */
    private fun onAuthorityLost(reasonName: String, description: String) {
        scope.launch {
            vsMutex.withLock {
                if (loopJob?.isActive != true) return@withLock
                withContext(NonCancellable) { shutdownVirtualStick("authority:$reasonName") }
            }
            arbiter.syncFromAircraft(ControlOwner.NONE, "Virtual Stick: $reasonName")
            val loss = ControlLoss(description, takenOverByRc = reasonName != "NEAR_BOUNDARY")
            logger.safety("vs_control_lost", LogLevel.ERROR, mapOf("reason" to reasonName, "rc" to loss.takenOverByRc))
            _controlLost.emit(loss)
        }
    }

    private fun notReady(): CommandResult.Failure {
        logger.system("flight_not_ready", LogLevel.WARN)
        return CommandResult.Failure("MSDK nie jest jeszcze zainicjalizowane")
    }

    private companion object {
        const val SETPOINT_LOG_MS = 200L
        const val LANDING_POLL_MS = 200L
        const val CONFIRM_RETRY_MS = 2_000L
        const val LANDING_MODE_GRACE_MS = 3_000L

        fun describe(reason: String): String = when (reason) {
            "RC_LOST" -> "utrata łącza z aparaturą"
            "RC_NOT_P_MODE" -> "aparatura nie jest w trybie N (Normal)"
            "RC_SWITCH" -> "przełączenie trybu lotu na aparaturze"
            "RC_PAUSE_STOP" -> "przycisk pauzy na aparaturze"
            "RC_ONE_KEY_GO_HOME" -> "powrót do domu wywołany z aparatury"
            "BATTERY_LOW_GO_HOME" -> "powrót do domu z powodu niskiej baterii"
            "BATTERY_SUPER_LOW_LANDING" -> "lądowanie z powodu krytycznej baterii"
            "NEAR_BOUNDARY" -> "zbliżenie do strefy zakazanej lub limitu odległości"
            "OSDK_REQUEST", "OSDK_LOST" -> "sterowanie przejęte przez OSDK"
            else -> reason
        }
    }
}

/**
 * Setpoint → parametr Virtual Stick: BODY, VELOCITY (nie ANGLE!), pion VELOCITY, kurs ANGULAR_VELOCITY.
 * Osie `pitch`/`roll` według [axes] — patrz [StickAxes] i test osi w Diagnostyce.
 */
internal fun Setpoint.toParam(axes: StickAxes): VirtualStickFlightControlParam =
    VirtualStickFlightControlParam().apply {
        rollPitchCoordinateSystem = FlightCoordinateSystem.BODY
        rollPitchControlMode = RollPitchControlMode.VELOCITY
        verticalControlMode = VerticalControlMode.VELOCITY
        yawControlMode = YawControlMode.ANGULAR_VELOCITY
        when (axes) {
            StickAxes.ROLL_FORWARD -> {
                roll = forwardMps
                pitch = rightMps
            }
            StickAxes.PITCH_FORWARD -> {
                pitch = forwardMps
                roll = rightMps
            }
        }
        verticalThrottle = upMps
        yaw = yawRateDegS
    }

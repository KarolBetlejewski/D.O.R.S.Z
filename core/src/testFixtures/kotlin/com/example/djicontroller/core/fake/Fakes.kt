package com.example.djicontroller.core.fake

import com.example.djicontroller.core.control.ControlArbiter
import com.example.djicontroller.core.landing.LandingOutcome
import com.example.djicontroller.core.landing.LandingProcedure
import com.example.djicontroller.core.model.CommandResult
import com.example.djicontroller.core.model.ConnectionState
import com.example.djicontroller.core.model.ControlLoss
import com.example.djicontroller.core.model.ControlOwner
import com.example.djicontroller.core.model.DroneState
import com.example.djicontroller.core.model.MissionPlan
import com.example.djicontroller.core.model.MissionProgress
import com.example.djicontroller.core.model.MissionStatus
import com.example.djicontroller.core.model.Setpoint
import com.example.djicontroller.core.model.StationCommand
import com.example.djicontroller.core.model.StationLink
import com.example.djicontroller.core.model.StationStatus
import com.example.djicontroller.core.model.VideoFrame
import com.example.djicontroller.core.port.BreakpointStore
import com.example.djicontroller.core.port.CameraPort
import com.example.djicontroller.core.port.Clock
import com.example.djicontroller.core.port.ConnectionPort
import com.example.djicontroller.core.port.FlightControlPort
import com.example.djicontroller.core.port.FlightLogger
import com.example.djicontroller.core.port.LogCategory
import com.example.djicontroller.core.port.LogLevel
import com.example.djicontroller.core.port.MissionPort
import com.example.djicontroller.core.port.StationPort
import com.example.djicontroller.core.port.TelemetryPort
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.atomic.AtomicLong

/*
 * Fake'i portów do testów jednostkowych na JVM. Każdy zapisuje wywołania w [calls]
 * i pozwala podmienić reakcję przez hook `on…`, żeby test mógł zasymulować drona lub stację.
 */

class FakeClock(startMs: Long = 1_000L) : Clock {
    private val now = AtomicLong(startMs)
    override fun wallMs(): Long = 1_700_000_000_000L + now.get()
    override fun monotonicMs(): Long = now.get()
    fun advance(ms: Long) { now.addAndGet(ms) }
}

data class LogEntry(val category: LogCategory, val level: LogLevel, val event: String, val fields: Map<String, Any?>)

class RecordingLogger : FlightLogger {
    val entries: MutableList<LogEntry> = CopyOnWriteArrayList()
    override fun log(category: LogCategory, level: LogLevel, event: String, fields: Map<String, Any?>) {
        entries += LogEntry(category, level, event, fields)
    }
    fun events(category: LogCategory? = null): List<String> =
        entries.filter { category == null || it.category == category }.map { it.event }
}

class FakeTelemetry(initial: DroneState = DroneState.UNKNOWN) : TelemetryPort {
    override val state = MutableStateFlow(initial)
    fun update(transform: (DroneState) -> DroneState) { state.value = transform(state.value) }
}

class FakeConnection(initial: ConnectionState = ConnectionState.Disconnected) : ConnectionPort {
    override val state = MutableStateFlow(initial)
    fun connectMini4Pro() { state.value = ConnectionState.Connected(0, "DJI_MINI_4_PRO", supported = true) }
    fun disconnect() { state.value = ConnectionState.Disconnected }
}

class FakeFlightControl(val arbiter: ControlArbiter) : FlightControlPort {
    val calls: MutableList<String> = CopyOnWriteArrayList()
    val setpoints: MutableList<Setpoint> = CopyOnWriteArrayList()
    override val controlOwner: StateFlow<ControlOwner> get() = arbiter.owner
    override val controlLost = MutableSharedFlow<ControlLoss>(extraBufferCapacity = 8)

    var onTakeOff: suspend () -> CommandResult = { CommandResult.Success }
    var onStartAutoLanding: suspend () -> CommandResult = { CommandResult.Success }

    override suspend fun takeOff(): CommandResult { calls += "takeOff"; return onTakeOff() }

    override suspend fun startAutoLanding(): CommandResult {
        calls += "startAutoLanding"
        if (!arbiter.tryAcquire(ControlOwner.AUTO_LANDING)) return CommandResult.Failure("sterowanie zajęte")
        return onStartAutoLanding()
    }

    override suspend fun cancelAutoLanding(): CommandResult {
        calls += "cancelAutoLanding"
        arbiter.release(ControlOwner.AUTO_LANDING)
        return CommandResult.Success
    }

    override suspend fun confirmLanding(): CommandResult { calls += "confirmLanding"; return CommandResult.Success }

    override suspend fun startVirtualStick(): CommandResult {
        calls += "startVirtualStick"
        return if (arbiter.tryAcquire(ControlOwner.VIRTUAL_STICK)) CommandResult.Success else CommandResult.Failure("sterowanie zajęte")
    }

    override suspend fun stopVirtualStick(): CommandResult {
        calls += "stopVirtualStick"
        arbiter.release(ControlOwner.VIRTUAL_STICK)
        return CommandResult.Success
    }

    override fun setVelocityTarget(setpoint: Setpoint) { setpoints += setpoint }

    override suspend fun holdPosition(): CommandResult {
        calls += "holdPosition"
        when (arbiter.owner.value) {
            ControlOwner.VIRTUAL_STICK -> setpoints += Setpoint.HOVER
            ControlOwner.AUTO_LANDING -> arbiter.release(ControlOwner.AUTO_LANDING)
            else -> Unit
        }
        return CommandResult.Success
    }
}

class FakeMission(val arbiter: ControlArbiter) : MissionPort {
    val calls: MutableList<String> = CopyOnWriteArrayList()
    val uploaded: MutableList<MissionPlan> = CopyOnWriteArrayList()
    override val progress = MutableStateFlow(MissionProgress())

    var onUpload: suspend (MissionPlan) -> CommandResult = { CommandResult.Success }
    /** Wołany po udanym starcie misji — test może tu zasymulować przelot. */
    var onStarted: suspend (MissionPlan) -> Unit = {}

    override suspend fun upload(plan: MissionPlan): CommandResult {
        calls += "upload:${plan.id}"
        val r = onUpload(plan)
        if (r.isSuccess) {
            uploaded += plan
            progress.value = MissionProgress(plan.id, MissionStatus.READY, null, plan.waypoints.size)
        }
        return r
    }

    override suspend fun start(missionId: String): CommandResult {
        calls += "start:$missionId"
        val plan = uploaded.lastOrNull { it.id == missionId } ?: return CommandResult.Failure("misja nie wysłana")
        if (!arbiter.tryAcquire(ControlOwner.MISSION)) return CommandResult.Failure("sterowanie zajęte")
        progress.value = MissionProgress(missionId, MissionStatus.EXECUTING, 0, plan.waypoints.size)
        onStarted(plan)
        return CommandResult.Success
    }

    override suspend fun pause(): CommandResult {
        calls += "pause"
        if (progress.value.status != MissionStatus.EXECUTING) return CommandResult.Failure("misja nie jest wykonywana")
        progress.value = progress.value.copy(status = MissionStatus.PAUSED)
        return CommandResult.Success
    }

    override suspend fun resume(): CommandResult {
        calls += "resume"
        if (progress.value.status != MissionStatus.PAUSED) return CommandResult.Failure("misja nie jest wstrzymana")
        progress.value = progress.value.copy(status = MissionStatus.EXECUTING)
        return CommandResult.Success
    }

    override suspend fun stop(): CommandResult {
        calls += "stop"
        progress.value = progress.value.copy(status = MissionStatus.IDLE)
        arbiter.release(ControlOwner.MISSION)
        return CommandResult.Success
    }

    /** Symuluje osiągnięcie punktu o indeksie [missionIndex] w bieżącej misji. */
    fun reach(missionIndex: Int) {
        progress.value = progress.value.copy(missionWaypointIndex = missionIndex)
    }

    /** Symuluje zakończenie bieżącej misji przez drona. */
    fun finish() {
        progress.value = progress.value.copy(status = MissionStatus.FINISHED)
        arbiter.release(ControlOwner.MISSION)
    }
}

class FakeStation(private val clock: Clock) : StationPort {
    val commands: MutableList<StationCommand> = CopyOnWriteArrayList()
    override val link = MutableStateFlow<StationLink>(StationLink.Disconnected)
    var onCommand: suspend (StationCommand) -> CommandResult = { CommandResult.Success }

    override suspend fun send(command: StationCommand): CommandResult {
        commands += command
        return onCommand(command)
    }

    fun publish(
        charging: Boolean = false,
        dronePresent: Boolean = true,
        padClear: Boolean = true,
        ready: Boolean = true,
    ) {
        link.value = StationLink.Connected(StationStatus(charging, dronePresent, padClear, ready, clock.monotonicMs()))
    }

    fun disconnect() { link.value = StationLink.Disconnected }
}

class FakeLanding(vararg outcomes: LandingOutcome) : LandingProcedure {
    private val queue = ArrayDeque(outcomes.toList())
    val attempts: MutableList<Int> = CopyOnWriteArrayList()
    var beforeOutcome: suspend (Int) -> Unit = {}

    override suspend fun execute(attempt: Int): LandingOutcome {
        attempts += attempt
        beforeOutcome(attempt)
        return queue.removeFirstOrNull() ?: LandingOutcome.Aborted("brak zaplanowanego wyniku w teście")
    }
}

class FakeCamera : CameraPort {
    override val frames = MutableSharedFlow<VideoFrame>(extraBufferCapacity = 1)
    val calls: MutableList<String> = CopyOnWriteArrayList()
    override suspend fun pointGimbalDown(): CommandResult { calls += "gimbalDown"; return CommandResult.Success }
    override suspend fun resetGimbal(): CommandResult { calls += "gimbalReset"; return CommandResult.Success }
}

class InMemoryBreakpointStore : BreakpointStore {
    val values: MutableMap<String, Int> = java.util.concurrent.ConcurrentHashMap()
    override suspend fun load(routeId: String): Int? = values[routeId]
    override suspend fun save(routeId: String, waypointIndex: Int) { values[routeId] = waypointIndex }
    override suspend fun clear(routeId: String) { values.remove(routeId) }
}

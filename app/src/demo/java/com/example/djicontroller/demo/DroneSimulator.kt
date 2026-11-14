package com.example.djicontroller.demo

import com.example.djicontroller.core.logging.system
import com.example.djicontroller.core.model.ConnectionState
import com.example.djicontroller.core.model.DroneState
import com.example.djicontroller.core.model.FailsafeAction
import com.example.djicontroller.core.model.GeoPoint
import com.example.djicontroller.core.model.Setpoint
import com.example.djicontroller.core.model.Velocity3D
import com.example.djicontroller.core.port.Clock
import com.example.djicontroller.core.port.FlightLogger
import com.example.djicontroller.core.port.LogLevel
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlin.math.abs
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.hypot
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sign
import kotlin.math.sin

/**
 * Symulowany Mini 4 Pro dla wariantu `demo`: kinematyka punktu (pozycja, wysokość, kurs),
 * bateria, zasilanie i łącze. Czas płynie [timeScale] razy szybciej niż w rzeczywistości,
 * żeby cały cykl dało się obejrzeć w kilka minut.
 *
 * Symulator nie odtwarza aerodynamiki — ma dawać orkiestratorowi i nadzorowi takie same
 * sygnały, jakie da prawdziwy dron przez MSDK (te same pola DroneState i ConnectionState).
 */
class DroneSimulator(
    private val scope: CoroutineScope,
    private val clock: Clock,
    private val logger: FlightLogger,
    val station: GeoPoint,
    val timeScale: Double = 3.0,
    /** Zużycie baterii w locie, %/s czasu symulowanego. */
    private val drainPercentPerSecond: Double = 0.3,
) {
    private val _state = MutableStateFlow(DroneState.UNKNOWN)
    val state: StateFlow<DroneState> = _state.asStateFlow()

    private val _connection = MutableStateFlow<ConnectionState>(ConnectionState.Initializing)
    val connection: StateFlow<ConnectionState> = _connection.asStateFlow()

    @Volatile var poweredOn: Boolean = false
        private set
    @Volatile var linkCut: Boolean = false
        private set

    /** Wstrzymanie ruchu (pauza misji / zawis). */
    @Volatile var paused: Boolean = false

    /** Marker zasłonięty — symulowana detekcja go nie widzi. */
    @Volatile var markerHidden: Boolean = false

    private val _gimbalPitch = MutableStateFlow(0.0)

    /** Kąt pochylenia gimbala, stopnie (−90 = kamera w dół) — odpowiednik `KeyGimbalAttitude`. */
    val gimbalPitch: StateFlow<Double> = _gimbalPitch.asStateFlow()

    var gimbalPitchDeg: Double
        get() = _gimbalPitch.value
        set(value) { _gimbalPitch.value = value.coerceIn(-90.0, 30.0) }

    /**
     * Dryf w trakcie Virtual Stick (m/s, kierunek „dokąd” w stopniach): prawdziwy dron w trybie VELOCITY sam
     * kompensuje wiatr, ale niedokładnie (podmuchy, błąd prędkości GPS) — tu jako stały dryf.
     */
    @Volatile var windMps: Double = 0.0
    @Volatile var windToDeg: Double = 90.0

    /** Ochrona lądowania: dron czeka na `KeyConfirmLanding` na wysokości [LANDING_PROTECTION_ALT_M]. */
    @Volatile var landingProtection: Boolean = true
    @Volatile private var landingConfirmed = false

    @Volatile private var batteryExact = 100.0
    private var position: GeoPoint = station
    private var altitude = 0.0
    private var heading = 0.0

    /** Start „SDK” i pętli baterii. Dron stoi wyłączony na stacji. */
    fun start() {
        scope.launch {
            delay(600)
            _connection.value = ConnectionState.Registering
            delay(900)
            publishConnection()
            logger.system("sim_sdk_ready")
        }
        scope.launch {
            while (isActive) {
                delay(TICK_MS)
                if (_state.value.areMotorsOn == true) {
                    batteryExact = max(0.0, batteryExact - drainPercentPerSecond * timeScale * TICK_MS / 1000.0)
                    publishBattery()
                }
            }
        }
    }

    // ------------------------------------------------------------------ zasilanie i łącze

    suspend fun powerOn() {
        delay(1_500)
        poweredOn = true
        position = station
        altitude = 0.0
        _state.value = DroneState(
            connected = !linkCut,
            batteryPercent = batteryExact.toInt(),
            location = station,
            altitudeM = 0.0,
            ultrasonicHeightM = 0.0,
            headingDeg = heading,
            velocity = Velocity3D(0.0, 0.0, 0.0),
            gpsSatelliteCount = 18,
            gpsSignalLevel = 5,
            isFlying = false,
            areMotorsOn = false,
            flightMode = "GPS",
            homeLocation = station,
            isLowBatteryWarning = false,
            isSeriousLowBatteryWarning = false,
            isFailSafe = false,
            failsafeAction = FailsafeAction.GO_HOME,
            isLandingConfirmationNeeded = false,
            updatedAtMs = clock.monotonicMs(),
        )
        publishBattery()
        publishConnection()
        logger.system("sim_power_on")
    }

    fun powerOff() {
        if (_state.value.areMotorsOn == true) {
            logger.system("sim_power_off_refused", LogLevel.WARN, mapOf("why" to "silniki pracują"))
            return
        }
        poweredOn = false
        _state.update { it.copy(connected = false, updatedAtMs = clock.monotonicMs()) }
        publishConnection()
        logger.system("sim_power_off")
    }

    fun setLinkCut(cut: Boolean) {
        linkCut = cut
        _state.update { it.copy(connected = poweredOn && !cut, isFailSafe = cut && it.isFlying == true) }
        publishConnection()
        logger.system("sim_drone_link", LogLevel.WARN, mapOf("cut" to cut))
    }

    private fun publishConnection() {
        _connection.value = if (poweredOn && !linkCut) {
            ConnectionState.Connected(productId = 1, productType = "DJI_MINI_4_PRO", supported = true)
        } else {
            ConnectionState.Disconnected
        }
    }

    // ------------------------------------------------------------------ bateria

    fun drainBattery(percent: Int) {
        batteryExact = max(0.0, batteryExact - percent)
        publishBattery()
    }

    /** Ładowanie do 100% w [realSeconds] sekund rzeczywistych. */
    suspend fun charge(realSeconds: Double) {
        val steps = max(1, (realSeconds * 1000 / TICK_MS).toInt())
        val perStep = (100.0 - batteryExact) / steps
        repeat(steps) {
            delay(TICK_MS)
            batteryExact = min(100.0, batteryExact + perStep)
        }
        batteryExact = 100.0
        publishBattery()
    }

    val batteryPercent: Int get() = batteryExact.toInt()

    private fun publishBattery() {
        val b = batteryExact.toInt()
        _state.update {
            it.copy(
                batteryPercent = b,
                isLowBatteryWarning = b <= 20,
                isSeriousLowBatteryWarning = b <= 10,
                updatedAtMs = clock.monotonicMs(),
            )
        }
    }

    // ------------------------------------------------------------------ ruch

    /** Start silników i wzniesienie na 1,2 m (jak `KeyStartTakeoff`). */
    suspend fun takeOff() {
        _state.update { it.copy(areMotorsOn = true, isFlying = true, flightMode = "TAKE_OFF") }
        moveTo(position, 1.2, speedMps = 0.0, verticalMps = 1.0)
        _state.update { it.copy(flightMode = "GPS") }
    }

    /**
     * Lot do punktu z prędkością [speedMps] i pionową [verticalMps] (czas symulowany).
     * Respektuje [paused]; jest anulowalny — anulowanie zostawia drona w zawisie.
     */
    suspend fun moveTo(target: GeoPoint, altitudeM: Double, speedMps: Double, verticalMps: Double = 2.5) {
        while (true) {
            delay(TICK_MS)
            if (paused || linkCut) {
                publishMotion(0.0, 0.0, 0.0)
                continue
            }
            val dt = TICK_MS / 1000.0 * timeScale
            val (n, e) = position.northEastTo(target)
            val dist = hypot(n, e)
            val hStep = speedMps * dt
            var vn = 0.0
            var ve = 0.0
            if (dist > 0.05 && hStep > 0) {
                if (dist <= hStep) {
                    position = target
                } else {
                    position = position.offsetBy(n / dist * hStep, e / dist * hStep)
                }
                vn = n / dist * speedMps
                ve = e / dist * speedMps
                heading = Math.toDegrees(atan2(e, n))
            }
            val dz = altitudeM - altitude
            val vStep = verticalMps * dt
            val climb = if (abs(dz) <= vStep) dz else vStep * sign(dz)
            altitude += climb
            publishMotion(vn, ve, -climb / dt)
            if (position.distanceTo(target) < 0.3 && abs(altitudeM - altitude) < 0.05) return
        }
    }

    private var vsNorth = 0.0
    private var vsEast = 0.0
    private var vsUp = 0.0

    /** Zeruje stan dynamiki Virtual Stick (wołane przy włączeniu pętli). */
    fun resetVelocityState() {
        vsNorth = 0.0
        vsEast = 0.0
        vsUp = 0.0
    }

    /**
     * Ruch z zadaną prędkością w układzie BODY przez [dtRealMs] (Virtual Stick).
     * Dron dochodzi do zadanej prędkości z opóźnieniem pierwszego rzędu (τ ≈ 0,35 s, jak Mini 4 Pro),
     * a dryf [windMps] dodatkowo go znosi — regulator nad markerem musi to kompensować.
     */
    fun applyVelocity(setpoint: Setpoint, dtRealMs: Long) {
        if (linkCut) {
            publishMotion(0.0, 0.0, 0.0)
            return
        }
        val dt = dtRealMs / 1000.0 * timeScale
        val h = Math.toRadians(heading)
        val cmdN = setpoint.forwardMps * cos(h) - setpoint.rightMps * sin(h)
        val cmdE = setpoint.forwardMps * sin(h) + setpoint.rightMps * cos(h)
        val k = min(1.0, dt / VS_TAU_S)
        vsNorth += (cmdN - vsNorth) * k
        vsEast += (cmdE - vsEast) * k
        vsUp += (setpoint.upMps - vsUp) * k
        val w = Math.toRadians(windToDeg)
        val vn = vsNorth + windMps * cos(w)
        val ve = vsEast + windMps * sin(w)
        position = position.offsetBy(vn * dt, ve * dt)
        altitude = max(0.0, altitude + vsUp * dt)
        heading = (heading + setpoint.yawRateDegS * dt + 360.0) % 360.0
        publishMotion(vn, ve, -vsUp)
    }

    /**
     * Pionowe lądowanie w bieżącym miejscu i wyłączenie silników (jak `KeyStartAutoLanding`).
     * Z [landingProtection] dron zatrzymuje się na 0,3 m i zgłasza `isLandingConfirmationNeeded`
     * (ochrona lądowania, R5) — dalej schodzi dopiero po [confirmLanding].
     */
    suspend fun descendAndLand() {
        _state.update { it.copy(flightMode = "AUTO_LANDING") }
        landingConfirmed = false
        if (landingProtection && altitude > LANDING_PROTECTION_ALT_M) {
            moveTo(position, LANDING_PROTECTION_ALT_M, speedMps = 0.0, verticalMps = 1.0)
            _state.update { it.copy(isLandingConfirmationNeeded = true, updatedAtMs = clock.monotonicMs()) }
            logger.system("sim_landing_protection", fields = mapOf("alt" to altitude))
            try {
                while (!landingConfirmed) delay(TICK_MS)
            } finally {
                _state.update { it.copy(isLandingConfirmationNeeded = false, updatedAtMs = clock.monotonicMs()) }
            }
        }
        moveTo(position, 0.0, speedMps = 0.0, verticalMps = 1.0)
        _state.update {
            it.copy(
                isFlying = false,
                areMotorsOn = false,
                flightMode = "GPS",
                velocity = Velocity3D(0.0, 0.0, 0.0),
                updatedAtMs = clock.monotonicMs(),
            )
        }
        logger.system("sim_touchdown", fields = mapOf("distanceFromStation" to position.distanceTo(station)))
    }

    fun setFlightMode(mode: String) = _state.update { it.copy(flightMode = mode) }

    /** Odpowiedź na ochronę lądowania (`KeyConfirmLanding`). */
    fun confirmLanding() {
        landingConfirmed = true
    }

    val currentPosition: GeoPoint get() = position
    val currentAltitude: Double get() = altitude
    val currentHeading: Double get() = heading

    private fun publishMotion(vn: Double, ve: Double, vd: Double) {
        _state.update {
            it.copy(
                location = position,
                altitudeM = altitude,
                ultrasonicHeightM = if (altitude < 10) altitude else null,
                headingDeg = if (heading > 180) heading - 360 else heading,
                velocity = Velocity3D(vn, ve, vd),
                updatedAtMs = clock.monotonicMs(),
            )
        }
    }

    companion object {
        const val TICK_MS = 100L
        const val VS_TAU_S = 0.35
        /** Poniżej przejścia L3 → L4 (0,5 m), żeby ochrona lądowania (R5) zadziałała też w demo. */
        const val LANDING_PROTECTION_ALT_M = 0.3
    }
}

package com.example.djicontroller.core.diagnostics

import com.example.djicontroller.core.model.ConnectionState
import com.example.djicontroller.core.model.DroneState
import com.example.djicontroller.core.model.FailsafeAction
import com.example.djicontroller.core.model.VirtualStickStatus
import com.example.djicontroller.core.model.VisionFrameResult
import com.example.djicontroller.core.port.CameraPort
import com.example.djicontroller.core.port.ConnectionPort
import com.example.djicontroller.core.port.FlightControlPort
import com.example.djicontroller.core.port.FlightLogger
import com.example.djicontroller.core.port.LogCategory
import com.example.djicontroller.core.port.LogLevel
import com.example.djicontroller.core.port.TelemetryPort
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import java.util.Locale

/** Stan jednej funkcji MSDK w raporcie zgodności. */
enum class CheckState { WAITING, OK, FAILED }

/** Jedna pozycja raportu zgodności z Mini 4 Pro (F0). */
data class CompatibilityCheck(
    val id: String,
    val group: String,
    val label: String,
    val state: CheckState,
    val value: String? = null,
    val note: String? = null,
)

/**
 * Nasłuch logu pod raport zgodności: błędy kluczy (`telemetry_key_failed`, `gimbal_attitude_failed`)
 * i wyniki poleceń (`takeoff`, `auto_landing_start`, `landing_confirm`, `vs_enable`, …).
 *
 * Jest zwykłym [FlightLogger] dopinanym do loggera aplikacji przed utworzeniem backendu DJI —
 * dzięki temu raport powstaje bez zmian w adapterach MSDK.
 */
class CompatibilityEvents : FlightLogger {

    /** Wynik polecenia: `true` = sukces, `false` = błąd (z powodem). */
    data class CommandOutcome(val ok: Boolean, val reason: String?)

    private val _keyErrors = MutableStateFlow<Map<String, String>>(emptyMap())
    val keyErrors: StateFlow<Map<String, String>> = _keyErrors.asStateFlow()

    private val _commands = MutableStateFlow<Map<String, CommandOutcome>>(emptyMap())
    val commands: StateFlow<Map<String, CommandOutcome>> = _commands.asStateFlow()

    override fun log(category: LogCategory, level: LogLevel, event: String, fields: Map<String, Any?>) {
        when {
            event == "telemetry_key_failed" -> {
                val key = fields["key"]?.toString() ?: return
                _keyErrors.update { it + (key to (fields["error"]?.toString() ?: "błąd")) }
            }
            event == "gimbal_attitude_failed" ->
                _keyErrors.update { it + ("KeyGimbalAttitude" to (fields["error"]?.toString() ?: "błąd")) }
            category == LogCategory.COMMAND && event in COMMAND_EVENTS && fields["ok"] is Boolean -> {
                val ok = fields["ok"] as Boolean
                val outcome = CommandOutcome(ok, if (ok) null else fields["reason"]?.toString())
                // Raz zaobserwowany sukces zostaje (późniejsza odmowa np. „dron nie lata” nie przekreśla klucza).
                _commands.update { m -> if (m[event]?.ok == true) m else m + (event to outcome) }
            }
        }
    }

    companion object {
        val COMMAND_EVENTS = setOf(
            "takeoff", "auto_landing_start", "auto_landing_cancel", "landing_confirm",
            "vs_enable", "gimbal_pitch", "failsafe_set",
        )
    }
}

/**
 * Raport zgodności z DJI Mini 4 Pro (faza F0): dla każdego klucza i funkcji MSDK, z których korzysta
 * aplikacja — czy przyszła wartość ([CheckState.OK]), czy jeszcze nie ([CheckState.WAITING]), czy SDK
 * zgłosiło błąd ([CheckState.FAILED]). Polecenia (start, lądowanie, potwierdzenie) dostają wynik
 * dopiero po pierwszym użyciu w Diagnostyce.
 *
 * Czysty Kotlin: wszystko wyliczane z portów i z [CompatibilityEvents].
 */
class CompatibilityMonitor(
    scope: CoroutineScope,
    connection: ConnectionPort,
    telemetry: TelemetryPort,
    flight: FlightControlPort,
    camera: CameraPort,
    visionResults: StateFlow<VisionFrameResult?>,
    private val events: CompatibilityEvents,
    /** Czy wynik pochodzi z kamery drona (a nie z pliku testowego). */
    private val isCameraSource: (VisionFrameResult) -> Boolean,
) {
    private val memory = Memory()

    /** Wartości zapamiętane od startu aplikacji („czy kiedykolwiek przyszło”). */
    private class Memory {
        var landingConfirmationSeenTrue = false
        var vsEnabledSeen = false
        var vsAdvancedSeen = false
        var frameSize: String? = null
        var gimbalPitch: Double? = null
        var last: DroneState = DroneState.UNKNOWN
    }

    val checks: StateFlow<List<CompatibilityCheck>> = combine(
        combine(connection.state, telemetry.state, camera.gimbalPitchDeg) { c, d, g -> Triple(c, d, g) },
        flight.virtualStick,
        visionResults,
        events.keyErrors,
        events.commands,
    ) { (conn, drone, gimbal), vs, vision, keyErrors, commands ->
        remember(drone, gimbal, vs, vision)
        build(conn, keyErrors, commands)
    }.stateIn(scope, SharingStarted.Eagerly, build(ConnectionState.Initializing, emptyMap(), emptyMap()))

    private fun remember(drone: DroneState, gimbal: Double?, vs: VirtualStickStatus, vision: VisionFrameResult?) {
        val m = memory
        // Pola telemetrii: zapamiętujemy ostatnią niepustą wartość (po rozłączeniu nie znikają z raportu).
        m.last = merge(m.last, drone)
        if (drone.isLandingConfirmationNeeded == true) m.landingConfirmationSeenTrue = true
        if (vs.enabled) m.vsEnabledSeen = true
        if (vs.advancedMode) m.vsAdvancedSeen = true
        if (gimbal != null) m.gimbalPitch = gimbal
        if (vision != null && vision.error == null && isCameraSource(vision)) m.frameSize = "${vision.frameWidth}×${vision.frameHeight}"
    }

    private fun build(
        conn: ConnectionState,
        keyErrors: Map<String, String>,
        commands: Map<String, CompatibilityEvents.CommandOutcome>,
    ): List<CompatibilityCheck> {
        val d = memory.last
        val list = mutableListOf<CompatibilityCheck>()

        fun key(id: String, label: String, value: Any?, note: String? = null, group: String = GROUP_TELEMETRY) {
            val error = keyErrors[id]
            list += when {
                error != null -> CompatibilityCheck(id, group, label, CheckState.FAILED, value?.let(::show), error)
                value != null -> CompatibilityCheck(id, group, label, CheckState.OK, show(value), note)
                else -> CompatibilityCheck(id, group, label, CheckState.WAITING, null, note)
            }
        }

        fun command(event: String, id: String, label: String, note: String) {
            val o = commands[event]
            list += when {
                o == null -> CompatibilityCheck(id, GROUP_COMMANDS, label, CheckState.WAITING, null, note)
                o.ok -> CompatibilityCheck(id, GROUP_COMMANDS, label, CheckState.OK, "wykonane", null)
                else -> CompatibilityCheck(id, GROUP_COMMANDS, label, CheckState.FAILED, null, o.reason)
            }
        }

        val product = (conn as? ConnectionState.Connected)
        list += CompatibilityCheck(
            id = "ProductType",
            group = GROUP_PRODUCT,
            label = "Model drona",
            state = when {
                product == null -> CheckState.WAITING
                product.supported -> CheckState.OK
                else -> CheckState.FAILED
            },
            value = product?.productType,
            note = if (product != null && !product.supported) "aplikacja jest przygotowana pod DJI Mini 4 Pro" else null,
        )
        key("KeyFirmwareVersion", "Wersja firmware drona", d.firmwareVersion, group = GROUP_PRODUCT)

        key("KeyConnection", "Łącze z kontrolerem lotu", d.connected.takeIf { it })
        key("KeyChargeRemainingInPercent", "Bateria [%]", d.batteryPercent)
        key("KeyAircraftLocation3D", "Pozycja GPS i wysokość", d.altitudeM?.let { "H ${fmt(it)} m" }.takeIf { d.hasValidLocation })
        key("KeyGPSSatelliteCount", "Liczba satelitów", d.gpsSatelliteCount)
        key("KeyGPSSignalLevel", "Poziom sygnału GPS", d.gpsSignalLevel)
        key("KeyCompassHeading", "Kurs (kompas)", d.headingDeg?.let { "${fmt(it)}°" })
        key("KeyAircraftVelocity", "Prędkość (D regulatora)", d.velocity?.let { "N ${fmt(it.northMps)} E ${fmt(it.eastMps)} m/s" })
        key(
            "KeyUltrasonicHeight", "Czujnik ToF (wysokość nisko)", d.ultrasonicHeightM?.let { "${fmt(it)} m" },
            note = "używany w L3/L4 poniżej 3 m",
        )
        key("KeyIsFlying", "Czy leci", d.isFlying)
        key("KeyAreMotorsOn", "Silniki", d.areMotorsOn)
        key("KeyFlightMode", "Tryb lotu", d.flightMode)
        key("KeyHomeLocation", "Punkt startu (home)", d.homeLocation?.takeIf { it.isValid }?.let { "${fmt6(it.latitude)}, ${fmt6(it.longitude)}" })
        key("KeyIsLowBatteryWarning", "Ostrzeżenie: niska bateria", d.isLowBatteryWarning)
        key("KeyIsSeriousLowBatteryWarning", "Ostrzeżenie: krytyczna bateria", d.isSeriousLowBatteryWarning)
        key("KeyIsFailSafe", "Stan failsafe", d.isFailSafe)
        key(
            "KeyFailsafeAction", "Akcja po utracie łącza", d.failsafeAction,
            note = if (d.failsafeAction != null && d.failsafeAction != FailsafeAction.GO_HOME) "zalecane: powrót do domu" else null,
        )
        key("KeyGoHomeHeight", "Wysokość powrotu RTH", d.goHomeHeightM?.let { "$it m" })
        key("KeyHeightLimit", "Limit wysokości", d.heightLimitM?.let { "$it m" })
        key(
            "KeyIsLandingConfirmationNeeded", "Ochrona lądowania (flaga)", d.isLandingConfirmationNeeded,
            note = if (memory.landingConfirmationSeenTrue) "zaobserwowano „true” — potwierdzanie działa" else "„true” jeszcze nie wystąpiło (pojawia się nisko nad płytą)",
        )

        key("KeyGimbalAttitude", "Orientacja gimbala", memory.gimbalPitch?.let { "pochylenie ${fmt(it)}°" }, group = GROUP_CAMERA)
        list += CompatibilityCheck(
            id = "CameraStream",
            group = GROUP_CAMERA,
            label = "Klatki z kamery (NV21) do wizji",
            state = if (memory.frameSize != null) CheckState.OK else CheckState.WAITING,
            value = memory.frameSize,
            note = if (memory.frameSize == null) "włącz „Detekcja i nakładka” w karcie Wizja" else null,
        )

        list += CompatibilityCheck(
            id = "VirtualStick",
            group = GROUP_CONTROL,
            label = "Virtual Stick: włączenie",
            state = when {
                memory.vsEnabledSeen -> CheckState.OK
                commands["vs_enable"]?.ok == false -> CheckState.FAILED
                else -> CheckState.WAITING
            },
            value = if (memory.vsEnabledSeen) "działa" else null,
            note = commands["vs_enable"]?.reason ?: if (!memory.vsEnabledSeen) "sprawdzane przy teście osi / R3" else null,
        )
        list += CompatibilityCheck(
            id = "VirtualStickAdvanced",
            group = GROUP_CONTROL,
            label = "Virtual Stick: tryb zaawansowany",
            state = if (memory.vsAdvancedSeen) CheckState.OK else CheckState.WAITING,
            value = if (memory.vsAdvancedSeen) "włączony" else null,
            note = if (!memory.vsAdvancedSeen) "potrzebny do sterowania prędkością w BODY" else null,
        )
        command("takeoff", "KeyStartTakeoff", "Start z aplikacji", "przycisk „Start” w karcie Lot testowy")
        command("auto_landing_start", "KeyStartAutoLanding", "Lądowanie automatyczne", "przycisk „Ląduj” albo test R5")
        command("landing_confirm", "KeyConfirmLanding", "Potwierdzenie lądowania", "wystąpi przy ochronie lądowania")
        command("auto_landing_cancel", "KeyStopAutoLanding", "Przerwanie lądowania", "STOP w trakcie lądowania")
        command("gimbal_pitch", "KeyRotateByAngle", "Obrót gimbala", "„Kamera w dół (−90°)”")
        command("failsafe_set", "KeyFailsafeAction (zapis)", "Ustawienie akcji failsafe", "„Ustaw: powrót do domu”")
        return list
    }

    /** Raport tekstowy do dokumentacji testów (DTP) i do logu. */
    fun report(header: String): String {
        val items = checks.value
        val sb = StringBuilder()
        sb.appendLine("RAPORT ZGODNOŚCI (F0) — DJI Mini 4 Pro")
        sb.appendLine(header)
        sb.appendLine()
        items.groupBy { it.group }.forEach { (group, rows) ->
            sb.appendLine("== $group")
            rows.forEach { c ->
                val tag = when (c.state) {
                    CheckState.OK -> "[OK]   "
                    CheckState.WAITING -> "[BRAK] "
                    CheckState.FAILED -> "[BŁĄD] "
                }
                sb.append(tag).append(c.label).append(" (").append(c.id).append(')')
                c.value?.let { sb.append(" = ").append(it) }
                c.note?.let { sb.append(" — ").append(it) }
                sb.appendLine()
            }
            sb.appendLine()
        }
        val ok = items.count { it.state == CheckState.OK }
        val waiting = items.count { it.state == CheckState.WAITING }
        val failed = items.count { it.state == CheckState.FAILED }
        sb.append("Podsumowanie: OK $ok · brak danych $waiting · błędy $failed (z ${items.size})")
        return sb.toString()
    }

    companion object {
        const val GROUP_PRODUCT = "Dron"
        const val GROUP_TELEMETRY = "Telemetria (klucze MSDK)"
        const val GROUP_CAMERA = "Kamera i gimbal"
        const val GROUP_CONTROL = "Sterowanie"
        const val GROUP_COMMANDS = "Polecenia"

        private fun fmt(v: Double) = String.format(Locale.US, "%.1f", v)
        private fun fmt6(v: Double) = String.format(Locale.US, "%.6f", v)
        private fun show(v: Any): String = when (v) {
            true -> "tak"
            false -> "nie"
            is FailsafeAction -> v.name
            else -> v.toString()
        }

        /** Nowe niepuste pola nadpisują stare; puste nie kasują tego, co już przyszło. */
        internal fun merge(old: DroneState, new: DroneState): DroneState = new.copy(
            batteryPercent = new.batteryPercent ?: old.batteryPercent,
            location = new.location?.takeIf { it.isValid } ?: old.location,
            altitudeM = new.altitudeM ?: old.altitudeM,
            ultrasonicHeightM = new.ultrasonicHeightM ?: old.ultrasonicHeightM,
            headingDeg = new.headingDeg ?: old.headingDeg,
            velocity = new.velocity ?: old.velocity,
            gpsSatelliteCount = new.gpsSatelliteCount ?: old.gpsSatelliteCount,
            gpsSignalLevel = new.gpsSignalLevel ?: old.gpsSignalLevel,
            isFlying = new.isFlying ?: old.isFlying,
            areMotorsOn = new.areMotorsOn ?: old.areMotorsOn,
            flightMode = new.flightMode ?: old.flightMode,
            homeLocation = new.homeLocation ?: old.homeLocation,
            isLowBatteryWarning = new.isLowBatteryWarning ?: old.isLowBatteryWarning,
            isSeriousLowBatteryWarning = new.isSeriousLowBatteryWarning ?: old.isSeriousLowBatteryWarning,
            isFailSafe = new.isFailSafe ?: old.isFailSafe,
            failsafeAction = new.failsafeAction ?: old.failsafeAction,
            isLandingConfirmationNeeded = new.isLandingConfirmationNeeded ?: old.isLandingConfirmationNeeded,
            goHomeHeightM = new.goHomeHeightM ?: old.goHomeHeightM,
            heightLimitM = new.heightLimitM ?: old.heightLimitM,
            firmwareVersion = new.firmwareVersion ?: old.firmwareVersion,
            connected = new.connected || old.connected,
        )
    }
}

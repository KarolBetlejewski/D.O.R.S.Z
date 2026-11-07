package com.example.djicontroller.core.logging

import com.example.djicontroller.core.model.CommandResult
import com.example.djicontroller.core.model.CycleState
import com.example.djicontroller.core.model.DroneState
import com.example.djicontroller.core.model.MarkerObservation
import com.example.djicontroller.core.model.Setpoint
import com.example.djicontroller.core.port.FlightLogger
import com.example.djicontroller.core.port.LogCategory
import com.example.djicontroller.core.port.LogLevel

/*
 * Ujednolicone wpisy logu dla czterech strumieni z zasady Z5:
 * telemetria, przejścia maszyny stanów, polecenia sterujące, obserwacje markerów.
 */

fun FlightLogger.transition(from: CycleState, to: CycleState, cause: String) =
    log(
        LogCategory.STATE,
        if (to is CycleState.Failure) LogLevel.ERROR else LogLevel.INFO,
        "transition",
        mapOf("from" to from.toString(), "to" to to.toString(), "cause" to cause),
    )

fun FlightLogger.command(name: String, result: CommandResult, fields: Map<String, Any?> = emptyMap()) =
    when (result) {
        CommandResult.Success -> log(LogCategory.COMMAND, LogLevel.INFO, name, fields + ("ok" to true))
        is CommandResult.Failure -> log(
            LogCategory.COMMAND,
            LogLevel.WARN,
            name,
            fields + mapOf("ok" to false, "reason" to result.reason, "code" to result.code),
        )
    }

fun FlightLogger.setpoint(setpoint: Setpoint, source: String) =
    log(
        LogCategory.COMMAND,
        LogLevel.DEBUG,
        "setpoint",
        mapOf(
            "src" to source,
            "fwd" to setpoint.forwardMps,
            "right" to setpoint.rightMps,
            "up" to setpoint.upMps,
            "yawRate" to setpoint.yawRateDegS,
        ),
    )

fun FlightLogger.telemetry(state: DroneState) =
    log(LogCategory.TELEMETRY, LogLevel.DEBUG, "drone", state.toLogFields())

fun FlightLogger.marker(observation: MarkerObservation?, frameTimestampMs: Long) =
    if (observation == null) {
        log(LogCategory.MARKER, LogLevel.DEBUG, "marker_none", mapOf("frame" to frameTimestampMs))
    } else {
        log(
            LogCategory.MARKER,
            LogLevel.DEBUG,
            "marker",
            mapOf(
                "id" to observation.markerId,
                "dx" to observation.dxM,
                "dy" to observation.dyM,
                "dz" to observation.dzM,
                "yaw" to observation.yawDeg,
                "frame" to observation.timestampMs,
                "lat" to observation.latencyMs,
                "reproj" to observation.reprojectionErrorPx,
            ),
        )
    }

fun FlightLogger.safety(event: String, level: LogLevel, fields: Map<String, Any?> = emptyMap()) =
    log(LogCategory.SAFETY, level, event, fields)

fun FlightLogger.system(event: String, level: LogLevel = LogLevel.INFO, fields: Map<String, Any?> = emptyMap()) =
    log(LogCategory.SYSTEM, level, event, fields)

/** Płaska mapa pól telemetrii — krótkie klucze, bo telemetria jest logowana kilka razy na sekundę. */
fun DroneState.toLogFields(): Map<String, Any?> = linkedMapOf(
    "conn" to connected,
    "bat" to batteryPercent,
    "lat" to location?.latitude,
    "lon" to location?.longitude,
    "alt" to altitudeM,
    "ultra" to ultrasonicHeightM,
    "hdg" to headingDeg,
    "vN" to velocity?.northMps,
    "vE" to velocity?.eastMps,
    "vD" to velocity?.downMps,
    "sats" to gpsSatelliteCount,
    "gps" to gpsSignalLevel,
    "flying" to isFlying,
    "motors" to areMotorsOn,
    "mode" to flightMode,
    "homeLat" to homeLocation?.latitude,
    "homeLon" to homeLocation?.longitude,
    "lowBat" to isLowBatteryWarning,
    "critBat" to isSeriousLowBatteryWarning,
    "failsafe" to isFailSafe,
    "fsAction" to failsafeAction,
    "landConfirm" to isLandingConfirmationNeeded,
    "upd" to updatedAtMs,
)

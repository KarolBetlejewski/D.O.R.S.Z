package com.example.djicontroller.core.logging

import com.example.djicontroller.core.port.FlightLogger
import com.example.djicontroller.core.port.TelemetryPort
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

/**
 * Zapisuje próbkę [com.example.djicontroller.core.model.DroneState] w stałym rytmie,
 * niezależnie od tego, jak często zmieniają się pojedyncze klucze. Daje równą oś czasu
 * do analizy lotów; zmiany stanu i polecenia są logowane osobno w chwili wystąpienia.
 */
class TelemetryRecorder(
    private val telemetry: TelemetryPort,
    private val logger: FlightLogger,
    private val periodMs: Long = 200L,
) {
    init {
        require(periodMs >= 20L) { "Okres próbkowania telemetrii musi wynosić co najmniej 20 ms" }
    }

    fun start(scope: CoroutineScope): Job = scope.launch {
        while (isActive) {
            logger.telemetry(telemetry.state.value)
            delay(periodMs)
        }
    }
}

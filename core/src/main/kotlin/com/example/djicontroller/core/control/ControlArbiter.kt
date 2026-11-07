package com.example.djicontroller.core.control

import com.example.djicontroller.core.model.ControlOwner
import com.example.djicontroller.core.port.FlightLogger
import com.example.djicontroller.core.port.LogCategory
import com.example.djicontroller.core.port.LogLevel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * Strażnik zasady Z2: w danej chwili ruchem drona steruje dokładnie jeden mechanizm —
 * misja WPML, Virtual Stick albo automatyczne lądowanie DJI.
 *
 * Jedna instancja jest współdzielona przez `FlightControl` i `MissionAdapter` z :msdk-adapter.
 * Przejęcie wymaga, żeby poprzedni właściciel najpierw oddał sterowanie ([release]);
 * dzięki temu np. Virtual Stick nie wystartuje w trakcie działającej misji.
 */
class ControlArbiter(private val logger: FlightLogger) {

    private val lock = Any()
    private val _owner = MutableStateFlow(ControlOwner.NONE)
    val owner: StateFlow<ControlOwner> = _owner.asStateFlow()

    /** Próba przejęcia sterowania. Zwraca `true`, gdy [requester] jest (już) właścicielem. */
    fun tryAcquire(requester: ControlOwner): Boolean = synchronized(lock) {
        require(requester != ControlOwner.NONE) { "NONE nie może przejąć sterowania" }
        val current = _owner.value
        when (current) {
            requester -> true
            ControlOwner.NONE -> {
                _owner.value = requester
                logger.log(LogCategory.COMMAND, LogLevel.INFO, "control_acquire", mapOf("owner" to requester))
                true
            }
            else -> {
                logger.log(
                    LogCategory.COMMAND,
                    LogLevel.WARN,
                    "control_denied",
                    mapOf("requester" to requester, "owner" to current),
                )
                false
            }
        }
    }

    /** Oddaje sterowanie, o ile [holder] jest bieżącym właścicielem. Zwraca `true`, gdy coś zwolniono. */
    fun release(holder: ControlOwner): Boolean = synchronized(lock) {
        if (holder == ControlOwner.NONE || _owner.value != holder) return@synchronized false
        _owner.value = ControlOwner.NONE
        logger.log(LogCategory.COMMAND, LogLevel.INFO, "control_release", mapOf("owner" to holder))
        true
    }

    /**
     * Wymusza stan zgłoszony przez drona (np. misja zakończyła się sama, aparatura przejęła drążki).
     * Używane wyłącznie przez adaptery do synchronizacji z rzeczywistością — nie do przejmowania sterowania.
     */
    fun syncFromAircraft(actual: ControlOwner, reason: String) = synchronized(lock) {
        val previous = _owner.value
        if (previous == actual) return@synchronized
        _owner.value = actual
        logger.log(
            LogCategory.COMMAND,
            LogLevel.WARN,
            "control_sync",
            mapOf("from" to previous, "to" to actual, "reason" to reason),
        )
    }
}

package com.example.djicontroller.msdk.flight

import com.example.djicontroller.core.logging.command
import com.example.djicontroller.core.model.CommandResult
import com.example.djicontroller.core.model.FailsafeAction
import com.example.djicontroller.core.port.DroneSettingsPort
import com.example.djicontroller.core.port.FlightLogger
import com.example.djicontroller.msdk.common.set
import com.example.djicontroller.msdk.connection.SdkInitializer
import dji.sdk.keyvalue.key.FlightControllerKey
import dji.sdk.keyvalue.key.KeyTools
import dji.sdk.keyvalue.value.flightcontroller.FailsafeAction as DjiFailsafeAction

/**
 * Ustawienia drona przed lotem (sekcja 5.8): zachowanie po utracie łącza z aparaturą (`KeyFailsafeAction`).
 * Dla patrolu ze stacją właściwe jest [FailsafeAction.GO_HOME] — punkt startu = stacja.
 */
class FlightSettings(
    private val sdk: SdkInitializer,
    private val logger: FlightLogger,
) : DroneSettingsPort {

    override suspend fun setFailsafeAction(action: FailsafeAction): CommandResult {
        val result = if (!sdk.initialized.value) {
            CommandResult.Failure("MSDK nie jest zainicjalizowane")
        } else {
            val djiName = when (action) {
                FailsafeAction.GO_HOME -> "GOHOME"
                FailsafeAction.HOVER -> "HOVER"
                FailsafeAction.LANDING -> "LANDING"
                FailsafeAction.UNKNOWN -> null
            }
            val value = djiName?.let { name -> DjiFailsafeAction.values().firstOrNull { it.name == name } }
            if (value == null) {
                CommandResult.Failure("Akcja $action nieobsługiwana przez MSDK")
            } else {
                KeyTools.createKey(FlightControllerKey.KeyFailsafeAction).set(value)
            }
        }
        logger.command("failsafe_set", result, mapOf("action" to action.name))
        return result
    }
}

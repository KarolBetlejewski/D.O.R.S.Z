package com.example.djicontroller.di

import com.example.djicontroller.core.control.ControlArbiter
import com.example.djicontroller.core.landing.LandingOutcome
import com.example.djicontroller.core.landing.LandingProcedure
import com.example.djicontroller.core.model.CommandResult
import com.example.djicontroller.core.model.ControlLoss
import com.example.djicontroller.core.model.ControlOwner
import com.example.djicontroller.core.model.MissionPlan
import com.example.djicontroller.core.model.MissionProgress
import com.example.djicontroller.core.model.Setpoint
import com.example.djicontroller.core.model.StationCommand
import com.example.djicontroller.core.model.StationLink
import com.example.djicontroller.core.port.FlightControlPort
import com.example.djicontroller.core.port.MissionPort
import com.example.djicontroller.core.port.StationPort
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.emptyFlow

/*
 * Porty, których implementacje powstają w kolejnych fazach planu (sekcja 8).
 * To NIE są atrapy udające działanie: każde polecenie kończy się jawnym
 * CommandResult.Failure z nazwą fazy, więc orkiestrator nie wystartuje cyklu
 * (stacja „rozłączona”), a STOP zostanie zalogowany jako nieudany.
 * Do czasu F2 dronem steruje wyłącznie pilot z aparatury RC-N2.
 */

private fun unavailable(what: String, phase: String) =
    CommandResult.Failure("$what — moduł powstaje w fazie $phase", "NOT_AVAILABLE")

/** Zastępuje `FlightControl` z :msdk-adapter do fazy F2. */
class UnavailableFlightControl(arbiter: ControlArbiter) : FlightControlPort {
    override val controlOwner: StateFlow<ControlOwner> = arbiter.owner
    override val controlLost: Flow<ControlLoss> = emptyFlow()
    override suspend fun takeOff() = unavailable("Start z aplikacji", "F2")
    override suspend fun startAutoLanding() = unavailable("Lądowanie z aplikacji", "F2")
    override suspend fun cancelAutoLanding() = unavailable("Anulowanie lądowania", "F2")
    override suspend fun confirmLanding() = unavailable("Potwierdzenie lądowania", "F2")
    override suspend fun startVirtualStick() = unavailable("Virtual Stick", "F2")
    override suspend fun stopVirtualStick() = unavailable("Virtual Stick", "F2")
    override fun setVelocityTarget(setpoint: Setpoint) = Unit
    override suspend fun holdPosition() = unavailable("Zawis z aplikacji", "F2")
}

/** Zastępuje `MissionAdapter` z :msdk-adapter do fazy F4. */
class UnavailableMission : MissionPort {
    override val progress: StateFlow<MissionProgress> = MutableStateFlow(MissionProgress()).asStateFlow()
    override suspend fun upload(plan: MissionPlan) = unavailable("Misje WPML", "F4")
    override suspend fun start(missionId: String) = unavailable("Misje WPML", "F4")
    override suspend fun pause() = unavailable("Misje WPML", "F4")
    override suspend fun resume() = unavailable("Misje WPML", "F4")
    override suspend fun stop() = unavailable("Misje WPML", "F4")
}

/** Zastępuje `StationClient` z :station do fazy F5. Łącze zawsze „rozłączone”. */
class UnavailableStation : StationPort {
    override val link: StateFlow<StationLink> = MutableStateFlow<StationLink>(StationLink.Disconnected).asStateFlow()
    override suspend fun send(command: StationCommand) = unavailable("Łącze ze stacją", "F5")
}

/** Zastępuje precyzyjne lądowanie z fazy F3. */
class UnavailableLanding : LandingProcedure {
    override suspend fun execute(attempt: Int): LandingOutcome =
        LandingOutcome.Aborted("Precyzyjne lądowanie powstaje w fazie F3")
}

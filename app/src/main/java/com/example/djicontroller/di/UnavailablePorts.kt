package com.example.djicontroller.di

import com.example.djicontroller.core.model.CommandResult
import com.example.djicontroller.core.model.MissionPlan
import com.example.djicontroller.core.model.MissionProgress
import com.example.djicontroller.core.model.StationCommand
import com.example.djicontroller.core.model.StationLink
import com.example.djicontroller.core.port.MissionPort
import com.example.djicontroller.core.port.StationPort
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/*
 * Porty, których implementacje powstają w kolejnych fazach planu (sekcja 8).
 * To NIE są atrapy udające działanie: każde polecenie kończy się jawnym
 * CommandResult.Failure z nazwą fazy, więc orkiestrator nie wystartuje cyklu
 * (stacja „rozłączona”).
 * Od F2 start, lądowanie i Virtual Stick działają (FlightControl z :msdk-adapter),
 * a od F3 precyzyjne lądowanie L1–L5 (LandingPlanner z :core).
 */

private fun unavailable(what: String, phase: String) =
    CommandResult.Failure("$what — moduł powstaje w fazie $phase", "NOT_AVAILABLE")

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


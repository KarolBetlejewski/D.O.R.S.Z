package com.example.djicontroller.core.model

/** Etapy na dolnym pasku ekranu głównego (sekcja 6). */
enum class CycleStage { START, PATROL, RETURN, LANDING, CHARGING, NONE }

/** Stany, w których dron jest (lub może być) w powietrzu i którymi steruje aplikacja. */
val CycleState.isAirborneStage: Boolean
    get() = when (this) {
        CycleState.TakingOff,
        is CycleState.Patrolling,
        is CycleState.Returning,
        is CycleState.Landing,
        is CycleState.Holding -> true
        CycleState.Ready,
        CycleState.Booting,
        CycleState.ShuttingDown,
        CycleState.Charging,
        is CycleState.Failure -> false
    }

/** Stany naziemne, w których aplikacja steruje stacją, a nie dronem. */
val CycleState.isGroundStage: Boolean
    get() = this == CycleState.Ready || this == CycleState.Booting ||
        this == CycleState.ShuttingDown || this == CycleState.Charging

val CycleState.stage: CycleStage
    get() = when (this) {
        CycleState.Booting, CycleState.TakingOff -> CycleStage.START
        is CycleState.Patrolling -> CycleStage.PATROL
        is CycleState.Returning -> CycleStage.RETURN
        is CycleState.Landing -> CycleStage.LANDING
        CycleState.ShuttingDown, CycleState.Charging -> CycleStage.CHARGING
        is CycleState.Holding -> resumeTo.stage
        CycleState.Ready, is CycleState.Failure -> CycleStage.NONE
    }

/** Krótka etykieta do górnego paska UI, np. „Patrol · punkt 3 z 6”. */
fun CycleState.label(): String = when (this) {
    CycleState.Ready -> "Gotowy"
    CycleState.Booting -> "Uruchamianie"
    CycleState.TakingOff -> "Start"
    is CycleState.Patrolling -> "Patrol · punkt ${waypoint + 1} z $total"
    is CycleState.Returning -> "Powrót · ${reason.label()}"
    is CycleState.Landing -> "Lądowanie · próba $attempt"
    CycleState.ShuttingDown -> "Wyłączanie"
    CycleState.Charging -> "Ładowanie"
    is CycleState.Holding -> "ZAWIS (STOP)"
    is CycleState.Failure -> "AWARIA: $cause"
}

fun ReturnReason.label(): String = when (this) {
    ReturnReason.LOW_BATTERY -> "niski poziom baterii"
    ReturnReason.ROUTE_DONE -> "trasa ukończona"
    ReturnReason.OPERATOR -> "polecenie operatora"
    ReturnReason.SAFETY -> "bezpieczeństwo"
}

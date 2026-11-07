package com.example.djicontroller.core.model

/** Stany cyklu pracy — dokładnie wg sekcji 4 dokumentu architektury. */
sealed interface CycleState {
    data object Ready : CycleState
    data object Booting : CycleState
    data object TakingOff : CycleState
    data class Patrolling(val waypoint: Int, val total: Int) : CycleState
    data class Returning(val reason: ReturnReason, val breakpoint: Int?) : CycleState
    data class Landing(val attempt: Int) : CycleState
    data object ShuttingDown : CycleState
    data object Charging : CycleState
    data class Holding(val resumeTo: CycleState) : CycleState   // STOP
    data class Failure(val cause: String) : CycleState
}

enum class ReturnReason { LOW_BATTERY, ROUTE_DONE, OPERATOR, SAFETY }

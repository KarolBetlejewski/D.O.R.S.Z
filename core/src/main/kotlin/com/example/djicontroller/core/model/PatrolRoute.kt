package com.example.djicontroller.core.model

/** Akcja wykonywana przez drona po osiągnięciu punktu (mapowana na akcje WPML w `KmzBuilder`). */
sealed interface WaypointAction {
    data object TakePhoto : WaypointAction
    data class GimbalPitch(val pitchDeg: Double) : WaypointAction
    data class Hover(val seconds: Double) : WaypointAction
}

data class PatrolWaypoint(
    val location: GeoPoint,
    /** Wysokość względem punktu startu (stacji), m. */
    val altitudeM: Double,
    /** Prędkość dolotu do punktu; `null` = prędkość przelotowa trasy. */
    val speedMps: Double? = null,
    /** Kurs drona w punkcie; `null` = zgodnie z kierunkiem lotu. */
    val headingDeg: Double? = null,
    val actions: List<WaypointAction> = emptyList(),
)

/**
 * Trasa patrolu zaplanowana na ekranie „Planowanie trasy”.
 *
 * [waypoints] to wyłącznie punkty patrolu. Punkt nad stacją ([stationApproach]) jest
 * dokładany automatycznie jako ostatni punkt misji (sekcja 5.3, krok 2), dzięki czemu
 * po ukończeniu trasy dron jest od razu w pozycji do lądowania.
 */
data class PatrolRoute(
    val id: String,
    val name: String,
    /** Środek pola lądowania na stacji. */
    val station: GeoPoint,
    val waypoints: List<PatrolWaypoint>,
    val cruiseSpeedMps: Double = 5.0,
    /** Wysokość punktu nad stacją, z której zaczyna się szukanie markera (8–10 m). */
    val approachAltitudeM: Double = 8.0,
) {
    val size: Int get() = waypoints.size

    val stationApproach: PatrolWaypoint
        get() = PatrolWaypoint(location = station, altitudeM = approachAltitudeM, speedMps = cruiseSpeedMps)

    /** Punkty wysyłane do drona: patrol od [fromIndex] + punkt nad stacją. */
    fun missionWaypoints(fromIndex: Int = 0): List<PatrolWaypoint> {
        require(fromIndex in 0..waypoints.size) { "fromIndex=$fromIndex poza zakresem 0..${waypoints.size}" }
        return waypoints.drop(fromIndex) + stationApproach
    }

    /** Plan misji do wysłania. Punkt przerwania wznawiamy, wysyłając trasę od [fromIndex] (rozwiązanie zastępcze z sekcji 5.3). */
    fun toMissionPlan(planId: String, fromIndex: Int = 0): MissionPlan = MissionPlan(
        id = planId,
        routeId = id,
        fromIndex = fromIndex,
        patrolPointCount = waypoints.size - fromIndex,
        waypoints = missionWaypoints(fromIndex),
        speedMps = cruiseSpeedMps,
    )

    /** Długość pozioma trasy od [fromIndex] do punktu nad stacją, m. */
    fun lengthM(fromIndex: Int = 0): Double = missionWaypoints(fromIndex)
        .zipWithNext { a, b -> a.location.distanceTo(b.location) }
        .sum()

    fun validate(limits: RouteLimits = RouteLimits()): List<RouteIssue> {
        val issues = mutableListOf<RouteIssue>()
        if (!station.isValid) issues += RouteIssue(null, "Nieprawidłowa pozycja stacji")
        if (waypoints.isEmpty()) issues += RouteIssue(null, "Trasa nie ma żadnego punktu")
        if (waypoints.size > limits.maxWaypoints) {
            issues += RouteIssue(null, "Za dużo punktów: ${waypoints.size} (limit ${limits.maxWaypoints})")
        }
        if (cruiseSpeedMps !in limits.minSpeedMps..limits.maxSpeedMps) {
            issues += RouteIssue(null, "Prędkość przelotowa $cruiseSpeedMps m/s poza zakresem ${limits.minSpeedMps}–${limits.maxSpeedMps}")
        }
        if (approachAltitudeM !in limits.minApproachAltitudeM..limits.maxApproachAltitudeM) {
            issues += RouteIssue(null, "Wysokość nad stacją $approachAltitudeM m poza zakresem ${limits.minApproachAltitudeM}–${limits.maxApproachAltitudeM}")
        }
        waypoints.forEachIndexed { i, wp ->
            if (!wp.location.isValid) issues += RouteIssue(i, "Nieprawidłowa pozycja punktu")
            if (wp.altitudeM !in limits.minAltitudeM..limits.maxAltitudeM) {
                issues += RouteIssue(i, "Wysokość ${wp.altitudeM} m poza zakresem ${limits.minAltitudeM}–${limits.maxAltitudeM}")
            }
            val speed = wp.speedMps
            if (speed != null && speed !in limits.minSpeedMps..limits.maxSpeedMps) {
                issues += RouteIssue(i, "Prędkość $speed m/s poza zakresem ${limits.minSpeedMps}–${limits.maxSpeedMps}")
            }
            if (wp.location.isValid && station.isValid) {
                val d = wp.location.distanceTo(station)
                if (d > limits.maxDistanceFromStationM) {
                    issues += RouteIssue(i, "Punkt ${d.toInt()} m od stacji (limit ${limits.maxDistanceFromStationM.toInt()} m)")
                }
            }
        }
        return issues
    }

    companion object {
        /**
         * Trasa powrotu do stacji dla przerwanego patrolu: wznoszenie nad bieżącą pozycją,
         * przelot na [returnAltitudeM], zniżanie do [approachAltitudeM] nad stacją.
         */
        fun returnRoute(
            id: String,
            from: GeoPoint,
            station: GeoPoint,
            returnAltitudeM: Double,
            approachAltitudeM: Double,
            speedMps: Double,
        ): PatrolRoute = PatrolRoute(
            id = id,
            name = "Powrót do stacji",
            station = station,
            waypoints = listOf(
                PatrolWaypoint(from, returnAltitudeM),
                PatrolWaypoint(station, returnAltitudeM),
            ),
            cruiseSpeedMps = speedMps,
            approachAltitudeM = approachAltitudeM,
        )
    }
}

/**
 * Misja w postaci gotowej do zamiany na KMZ/WPML.
 * Indeksy raportowane przez drona (`WaylineExecutingInfo.currentWaypointIndex`) dotyczą [waypoints];
 * [patrolIndexOf] tłumaczy je na indeks w pełnej trasie patrolu.
 */
data class MissionPlan(
    val id: String,
    val routeId: String,
    val fromIndex: Int,
    val patrolPointCount: Int,
    val waypoints: List<PatrolWaypoint>,
    val speedMps: Double,
) {
    /** Indeks punktu patrolu w pełnej trasie albo `null` dla punktu nad stacją. */
    fun patrolIndexOf(missionWaypointIndex: Int): Int? =
        if (missionWaypointIndex in 0 until patrolPointCount) fromIndex + missionWaypointIndex else null
}

/**
 * Limity walidacji trasy. Wysokość 120 m to granica kategorii otwartej w UE;
 * prędkość maksymalna misji WPML na Mini 4 Pro do potwierdzenia w fazie F0.
 */
data class RouteLimits(
    val minAltitudeM: Double = 2.0,
    val maxAltitudeM: Double = 120.0,
    val minApproachAltitudeM: Double = 4.0,
    val maxApproachAltitudeM: Double = 15.0,
    val minSpeedMps: Double = 0.5,
    val maxSpeedMps: Double = 15.0,
    val maxDistanceFromStationM: Double = 2_000.0,
    val maxWaypoints: Int = 200,
)

/** Problem z trasą; [waypointIndex] `null` dotyczy całej trasy. */
data class RouteIssue(val waypointIndex: Int?, val message: String)

package com.example.djicontroller.core.port

import com.example.djicontroller.core.model.CommandResult
import com.example.djicontroller.core.model.ConnectionState
import com.example.djicontroller.core.model.ControlLoss
import com.example.djicontroller.core.model.ControlOwner
import com.example.djicontroller.core.model.DroneState
import com.example.djicontroller.core.model.GeoPoint
import com.example.djicontroller.core.model.MarkerObservation
import com.example.djicontroller.core.model.MissionPlan
import com.example.djicontroller.core.model.MissionProgress
import com.example.djicontroller.core.model.Setpoint
import com.example.djicontroller.core.model.SimulatorStatus
import com.example.djicontroller.core.model.StickAxes
import com.example.djicontroller.core.model.StationCommand
import com.example.djicontroller.core.model.StationLink
import com.example.djicontroller.core.model.PowerAction
import com.example.djicontroller.core.model.StationStatus
import com.example.djicontroller.core.model.toStatus
import com.example.djicontroller.core.model.VideoFrame
import com.example.djicontroller.core.model.VirtualStickStatus
import com.example.djicontroller.core.model.VisionFrameResult
import com.example.djicontroller.core.model.VisionStats
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.map

/*
 * Porty, przez które logika z :core rozmawia z platformą. Implementacje produkcyjne są
 * w :msdk-adapter (DJI), :station (WebSocket) i :app (pliki); fake'i do testów JVM
 * w testFixtures modułu :core.
 *
 * Kontrakt wspólny dla wszystkich metod `suspend`: są anulowalne, nie rzucają wyjątków
 * dla błędów platformy (zwracają CommandResult.Failure) i mogą być wołane z dowolnego wątku.
 */

/** Połączenie z dronem: `SdkInitializer` + `DroneConnection` (sekcja 5.1). */
interface ConnectionPort {
    val state: StateFlow<ConnectionState>
}

/** Telemetria złożona z kluczy MSDK w jeden stan (sekcja 5.2, zasada Z1). */
interface TelemetryPort {
    val state: StateFlow<DroneState>
}

/**
 * Jedyny moduł wysyłający polecenia ruchu (sekcja 5.6). Implementacja musi:
 * - pilnować wyłączności sterowania przez [com.example.djicontroller.core.control.ControlArbiter] (Z2),
 * - wysyłać Virtual Stick w pętli 5–25 Hz z watchdogiem przechodzącym na [Setpoint.HOVER] (Z4).
 */
interface FlightControlPort {
    val controlOwner: StateFlow<ControlOwner>

    /** Zdarzenia utraty kontroli nad Virtual Stick (np. przejęcie z aparatury, strefa zakazana). */
    val controlLost: Flow<ControlLoss>

    /** Stan Virtual Stick (włączony, tryb zaawansowany, właściciel, ostatni wysłany punkt). */
    val virtualStick: StateFlow<VirtualStickStatus>

    /** `KeyStartTakeoff`. */
    suspend fun takeOff(): CommandResult

    /**
     * `KeyStartAutoLanding` — właściciel sterowania przechodzi na [ControlOwner.AUTO_LANDING].
     * Przy [confirmAutomatically] implementacja sama odpowiada `KeyConfirmLanding`, gdy dron zgłosi
     * `KeyIsLandingConfirmationNeeded` (ochrona lądowania nad płytą stacji, test R5).
     */
    suspend fun startAutoLanding(confirmAutomatically: Boolean = true): CommandResult

    /** `KeyStopAutoLanding`. */
    suspend fun cancelAutoLanding(): CommandResult

    /** `KeyConfirmLanding` — odpowiedź na ochronę lądowania (test R5). */
    suspend fun confirmLanding(): CommandResult

    /** Włącza Virtual Stick w trybie zaawansowanym i uruchamia pętlę wysyłania. */
    suspend fun startVirtualStick(): CommandResult

    /** Zatrzymuje pętlę i wyłącza Virtual Stick — dron przechodzi w zawis GPS, aparatura odzyskuje drążki. */
    suspend fun stopVirtualStick(): CommandResult

    /** Ustawia cel dla pętli Virtual Stick. Bez kolejnego wywołania przez czas watchdoga pętla wysyła [Setpoint.HOVER]. */
    fun setVelocityTarget(setpoint: Setpoint)

    /** Mapowanie osi na `pitch`/`roll` (patrz [StickAxes]). Dozwolone tylko przy wyłączonej pętli. */
    fun setStickAxes(axes: StickAxes): CommandResult

    /**
     * Natychmiastowy zawis niezależnie od tego, co steruje dronem poza misją:
     * Virtual Stick → [Setpoint.HOVER], automatyczne lądowanie → anulowanie. Idempotentne.
     * Misję WPML zatrzymuje się przez [MissionPort.pause].
     */
    suspend fun holdPosition(): CommandResult
}

/** Natywne misje WPML (sekcja 5.3); implementacja: `KmzBuilder` + `MissionAdapter`. */
interface MissionPort {
    val progress: StateFlow<MissionProgress>

    /** Buduje KMZ z [plan] i wysyła do drona (`pushKMZFileToAircraft`). */
    suspend fun upload(plan: MissionPlan): CommandResult

    /** `startMission` dla wcześniej wysłanego planu. Wymaga przejęcia sterowania jako [ControlOwner.MISSION]. */
    suspend fun start(missionId: String): CommandResult

    suspend fun pause(): CommandResult
    suspend fun resume(): CommandResult

    /** Przerywa misję; dron zawisa. Zwalnia właściciela [ControlOwner.MISSION]. */
    suspend fun stop(): CommandResult
}

/** Łącze ze stacją przez Wi-Fi (sekcja 5.7). */
interface StationPort {
    val link: StateFlow<StationLink>

    /** Wysyła polecenie i czeka na `ack` (z ponowieniami); [CommandResult.Failure] po wyczerpaniu prób. */
    suspend fun send(command: StationCommand): CommandResult

    /**
     * Status stacji razem ze stanem łącza (`charging`, `dronePresent`, `padClear`, `ready`, `isConnected`) —
     * kontrakt pod F5. Domyślnie wyliczany z [link]; implementacja WebSocket z :station może go nadpisać.
     */
    val statusUpdates: Flow<StationStatus>
        get() = link.map { it.toStatus() }

    /** Popychacz przycisku zasilania drona (sekwencję naciśnięć wykonuje firmware stacji, R2). */
    suspend fun triggerPowerButton(action: PowerAction): CommandResult = send(StationCommand.PowerButton(action))

    /** Ładowanie pola stacji; włączać wolno tylko przy wyłączonym dronie (R1). */
    suspend fun setCharging(enabled: Boolean): CommandResult = send(StationCommand.Charge(enabled))
}

/** Kamera i gimbal (sekcja 5.4). */
interface CameraPort {
    /** Najnowsze klatki (kolejka o pojemności 1 — starsze są odrzucane). */
    val frames: Flow<VideoFrame>

    /** Bieżące pochylenie gimbala z `GimbalKey.KeyGimbalAttitude`, stopnie (−90 = kamera w dół); `null` = nieznane. */
    val gimbalPitchDeg: StateFlow<Double?>

    /** `GimbalKey.KeyRotateByAngle` do −90° (kamera w dół). */
    suspend fun pointGimbalDown(): CommandResult

    /** Powrót gimbala do pozycji poziomej. */
    suspend fun resetGimbal(): CommandResult
}

/**
 * Detekcja markerów ArUco (:vision, sekcja 5.4). Detekcja działa tylko wtedy, gdy ktoś jej
 * potrzebuje — każdy użytkownik zgłasza się przez [acquire] i oddaje przez [release]
 * (np. nakładka w UI, test zawisu, procedura lądowania).
 */
interface VisionPort {
    /** Wynik ostatniej przetworzonej klatki; `null` = detekcja wyłączona albo jeszcze brak klatek. */
    val results: StateFlow<VisionFrameResult?>

    /** Najświeższa obserwacja lądowiska (z [results]); `null` = marker niewidoczny. */
    val observation: StateFlow<MarkerObservation?>

    /** Kto teraz korzysta z detekcji. Pusty zbiór = detekcja wyłączona. */
    val users: StateFlow<Set<String>>

    val stats: StateFlow<VisionStats>

    fun acquire(user: String)
    fun release(user: String)
    fun resetStats()
}

/** Symulator MSDK (`SimulatorManager`, wzorzec `SimulatorVM.kt`) — loty testowe bez śmigieł. */
interface SimulatorPort {
    val status: StateFlow<SimulatorStatus>

    /** `enableSimulator(InitializationSettings.createInstance(location, satellites))`. */
    suspend fun enable(location: GeoPoint, satellites: Int = 15): CommandResult

    suspend fun disable(): CommandResult
}

/** Trwały zapis punktu przerwania między cyklami (np. plik w pamięci aplikacji). */
interface BreakpointStore {
    suspend fun load(routeId: String): Int?
    suspend fun save(routeId: String, waypointIndex: Int)
    suspend fun clear(routeId: String)
}

/** Źródło czasu — wstrzykiwane, żeby logika była testowalna. */
interface Clock {
    /** Czas ścienny (epoch), ms — do znaczników czasu w logach. */
    fun wallMs(): Long

    /** Czas monotoniczny, ms — do mierzenia odstępów i wieku danych. */
    fun monotonicMs(): Long
}

/** Zegar systemowy JVM/Android (`System.nanoTime` jest monotoniczny na obu platformach). */
object SystemClock : Clock {
    override fun wallMs(): Long = System.currentTimeMillis()
    override fun monotonicMs(): Long = System.nanoTime() / 1_000_000L
}

/**
 * Odmierzanie czasu w pętlach sterowania (np. 20 Hz w lądowaniu). W aplikacji [CoroutineTicker] (`delay`);
 * w testach JVM — czas wirtualny: test przesuwa zegar i krok symulacji, więc minuty lotu trwają milisekundy.
 * Pętla, która czeka przez [Ticker], musi mierzyć czas tym samym [Clock], który test przesuwa.
 */
fun interface Ticker {
    suspend fun sleep(ms: Long)
}

object CoroutineTicker : Ticker {
    override suspend fun sleep(ms: Long) = delay(ms)
}

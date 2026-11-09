package com.example.djicontroller.vision

import com.example.djicontroller.core.logging.marker
import com.example.djicontroller.core.logging.system
import com.example.djicontroller.core.model.MarkerObservation
import com.example.djicontroller.core.model.VideoFrame
import com.example.djicontroller.core.model.VisionFrameResult
import com.example.djicontroller.core.model.VisionStats
import com.example.djicontroller.core.port.CameraPort
import com.example.djicontroller.core.port.Clock
import com.example.djicontroller.core.port.FlightLogger
import com.example.djicontroller.core.port.LogCategory
import com.example.djicontroller.core.port.LogLevel
import com.example.djicontroller.core.port.VisionPort
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.conflate
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull
import java.util.concurrent.atomic.AtomicReference

/** Testowe źródło klatek zamiast kamery drona (plik z obrazem lub wideo, render syntetyczny). */
class TestFrameSource(val name: String, val frames: Flow<VideoFrame>)

/**
 * Potok wizyjny (sekcja 5.4): klatki z [CameraPort] (albo z [TestFrameSource]) → [ArucoDetector]
 * na `Dispatchers.Default` → [VisionPort.results] / [VisionPort.observation].
 *
 * - Kolejka o pojemności 1: gdy detekcja nie nadąża, starsze klatki są pomijane (`conflate`),
 *   więc sterowanie zawsze dostaje najświeższy obraz.
 * - Detekcja działa tylko, gdy są użytkownicy ([acquire]) — bez nich nasłuch klatek MSDK jest wyłączony.
 * - Każda klatka jest logowana (`vision_frame`: czas detekcji i wiek klatki — test R4) razem z obserwacją markera.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class VisionPipeline(
    private val scope: CoroutineScope,
    private val camera: CameraPort,
    layout: LandingPadLayout,
    calibration: CameraCalibration,
    private val logger: FlightLogger,
    private val clock: Clock,
    private val detectorConfig: ArucoDetectorConfig = ArucoDetectorConfig(),
    private val statsWindow: Int = 300,
) : VisionPort {

    private val _results = MutableStateFlow<VisionFrameResult?>(null)
    override val results: StateFlow<VisionFrameResult?> = _results.asStateFlow()

    private val _observation = MutableStateFlow<MarkerObservation?>(null)
    override val observation: StateFlow<MarkerObservation?> = _observation.asStateFlow()

    private val _users = MutableStateFlow<Set<String>>(emptySet())
    override val users: StateFlow<Set<String>> = _users.asStateFlow()

    private val _stats = MutableStateFlow(VisionStats())
    override val stats: StateFlow<VisionStats> = _stats.asStateFlow()

    private val _layout = MutableStateFlow(layout)
    val layout: StateFlow<LandingPadLayout> = _layout.asStateFlow()

    private val _calibration = MutableStateFlow(calibration)
    val calibration: StateFlow<CameraCalibration> = _calibration.asStateFlow()

    private val _testSource = MutableStateFlow<TestFrameSource?>(null)
    val testSource: StateFlow<TestFrameSource?> = _testSource.asStateFlow()

    /** Problem uniemożliwiający detekcję (np. OpenCV się nie załadowało); `null` = OK. */
    private val _problem = MutableStateFlow<String?>(null)
    val problem: StateFlow<String?> = _problem.asStateFlow()

    private val pendingSnapshot = AtomicReference<CompletableDeferred<VideoFrame>?>(null)
    private val window = ArrayDeque<Sample>()
    private var windowFrames = 0
    private var windowWithMarker = 0

    private class Sample(val timestampMs: Long, val detectionMs: Long, val ageMs: Long, val marker: Boolean)

    override fun acquire(user: String) {
        _users.update { it + user }
        logger.system("vision_acquire", fields = mapOf("user" to user, "users" to _users.value.toString()))
    }

    override fun release(user: String) {
        _users.update { it - user }
        logger.system("vision_release", fields = mapOf("user" to user, "users" to _users.value.toString()))
    }

    override fun resetStats() {
        synchronized(window) {
            window.clear()
            windowFrames = 0
            windowWithMarker = 0
        }
        _stats.value = VisionStats()
    }

    fun setTestSource(source: TestFrameSource?) {
        _testSource.value = source
        logger.system("vision_source", fields = mapOf("source" to (source?.name ?: CAMERA_SOURCE)))
    }

    fun setLayout(layout: LandingPadLayout) {
        _layout.value = layout
        logger.system("vision_layout", fields = mapOf("layout" to layout.name))
    }

    fun setCalibration(calibration: CameraCalibration) {
        _calibration.value = calibration
        logger.system(
            "vision_calibration",
            fields = mapOf("src" to calibration.source, "w" to calibration.width, "fx" to calibration.fx, "hfov" to calibration.horizontalFovDeg),
        )
    }

    /** Następna przetworzona klatka (detekcja musi być aktywna) — do zapisu klatek do kalibracji. */
    suspend fun captureNextFrame(timeoutMs: Long = 3_000): VideoFrame? {
        val deferred = CompletableDeferred<VideoFrame>()
        pendingSnapshot.set(deferred)
        return withTimeoutOrNull(timeoutMs) { deferred.await() }.also { pendingSnapshot.compareAndSet(deferred, null) }
    }

    /** Uruchamia potok; wołać raz przy starcie aplikacji. */
    fun start(): Job = scope.launch(Dispatchers.Default) {
        val active = _users.map { it.isNotEmpty() }.distinctUntilChanged()
        combine(active, _testSource, _layout) { a, src, lay -> Triple(a, src, lay) }
            .collectLatest { (isActive, source, padLayout) ->
                if (!isActive) {
                    _results.value = null
                    _observation.value = null
                    return@collectLatest
                }
                if (!OpenCvRuntime.ensureLoaded()) {
                    _problem.value = "Nie udało się załadować OpenCV"
                    logger.system("vision_opencv_failed", LogLevel.ERROR)
                    return@collectLatest
                }
                _problem.value = null
                val sourceName = source?.name ?: CAMERA_SOURCE
                val frames = source?.frames ?: camera.frames
                val detector = ArucoDetector(padLayout, _calibration.value, detectorConfig, ::onDetectorError)
                logger.system("vision_on", fields = mapOf("source" to sourceName, "layout" to padLayout.name))
                try {
                    frames.conflate().collect { frame ->
                        detector.calibration = _calibration.value
                        // Pliki testowe są zdjęciami „z góry” — kompensacja gimbala tylko dla kamery drona.
                        val pitch = if (source == null) camera.gimbalPitchDeg.value else null
                        val result = detector.detect(frame, pitch, sourceName, clock::monotonicMs)
                        publish(result)
                        pendingSnapshot.getAndSet(null)?.complete(frame)
                    }
                } finally {
                    detector.close()
                    logger.system("vision_off", fields = mapOf("source" to sourceName))
                }
            }
    }

    private var errorCount = 0

    /** Błędy OpenCV: pierwszy z pełnym stosem, potem co setny — żeby nie zalać logu przy 15 fps. */
    private fun onDetectorError(stage: String, error: Throwable) {
        errorCount++
        if (errorCount == 1 || errorCount % 100 == 0) {
            logger.system(
                "vision_error",
                LogLevel.ERROR,
                mapOf("stage" to stage, "count" to errorCount, "error" to error.stackTraceToString()),
            )
        }
    }

    private fun publish(result: VisionFrameResult) {
        _results.value = result
        _observation.value = result.selected
        logger.log(
            LogCategory.MARKER,
            LogLevel.DEBUG,
            "vision_frame",
            mapOf(
                "src" to result.source,
                "frame" to result.frameTimestampMs,
                "w" to result.frameWidth,
                "h" to result.frameHeight,
                "detMs" to result.detectionMs,
                "ageMs" to result.frameAgeMs,
                "ids" to result.markers.joinToString(",") { it.id.toString() },
                "pose" to result.markers.count { it.observation != null },
                "err" to result.error,
            ),
        )
        logger.marker(result.selected, result.frameTimestampMs)
        updateStats(result)
    }

    private fun updateStats(result: VisionFrameResult) {
        val stats = synchronized(window) {
            window.addLast(Sample(result.frameTimestampMs, result.detectionMs, result.frameAgeMs, result.selected != null))
            while (window.size > statsWindow) window.removeFirst()
            windowFrames++
            if (result.selected != null) windowWithMarker++
            val det = window.map { it.detectionMs }.sorted()
            val age = window.map { it.ageMs }.sorted()
            val span = window.last().timestampMs - window.first().timestampMs
            VisionStats(
                frames = windowFrames,
                framesWithMarker = windowWithMarker,
                fps = if (window.size > 1 && span > 0) (window.size - 1) * 1000.0 / span else 0.0,
                detectionMsMean = det.average(),
                detectionMsP95 = det[((det.size - 1) * 0.95).toInt()],
                frameAgeMsMean = age.average(),
                frameAgeMsP95 = age[((age.size - 1) * 0.95).toInt()],
            )
        }
        _stats.value = stats
    }

    companion object {
        const val CAMERA_SOURCE = "kamera drona"
    }
}

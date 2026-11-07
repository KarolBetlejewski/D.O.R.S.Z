package com.example.djicontroller.msdk.camera

import android.view.Surface
import com.example.djicontroller.core.logging.command
import com.example.djicontroller.core.logging.system
import com.example.djicontroller.core.model.CommandResult
import com.example.djicontroller.core.model.FrameFormat
import com.example.djicontroller.core.model.VideoFrame
import com.example.djicontroller.core.port.CameraPort
import com.example.djicontroller.core.port.Clock
import com.example.djicontroller.core.port.FlightLogger
import com.example.djicontroller.core.port.LogLevel
import com.example.djicontroller.msdk.common.asFlow
import com.example.djicontroller.msdk.common.perform
import com.example.djicontroller.msdk.connection.SdkInitializer
import com.example.djicontroller.msdk.connection.whenInitialized
import dji.sdk.keyvalue.key.GimbalKey
import dji.sdk.keyvalue.key.KeyTools
import dji.sdk.keyvalue.value.common.ComponentIndexType
import dji.sdk.keyvalue.value.gimbal.GimbalAngleRotation
import dji.sdk.keyvalue.value.gimbal.GimbalAngleRotationMode
import dji.sdk.keyvalue.value.gimbal.GimbalResetType
import dji.v5.manager.datacenter.MediaDataCenter
import dji.v5.manager.interfaces.ICameraStreamManager
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.buffer
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.coroutines.flow.filter
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.launchIn
import kotlinx.coroutines.flow.onEach
import kotlinx.coroutines.flow.shareIn

/**
 * Kamera i gimbal (sekcja 5.4) — wzorzec `CameraStreamDetailVM` z przykładu DJI.
 *
 * - Podgląd: `putCameraStreamSurface` / `removeCameraStreamSurface` na powierzchni z UI.
 * - Klatki dla :vision: `addFrameListener(…, FrameFormat.NV21, …)` z kolejką o pojemności 1
 *   (najnowsza klatka wypiera starszą), znacznik czasu z [clock] (w aplikacji `SystemClock.elapsedRealtime()`),
 *   przetwarzanie na `Dispatchers.Default`. Nasłuch jest aktywny tylko wtedy, gdy ktoś zbiera [frames],
 *   bo dekodowanie do NV21 kosztuje CPU.
 * - Gimbal: `KeyRotateByAngle` (−90° = kamera w dół) i odczyt kąta z `KeyGimbalAttitude`.
 */
class CameraAdapter(
    private val sdk: SdkInitializer,
    scope: CoroutineScope,
    private val logger: FlightLogger,
    private val clock: Clock,
    private val cameraIndex: ComponentIndexType = ComponentIndexType.LEFT_OR_MAIN,
    /** Górny limit klatek kopiowanych dla :vision — ogranicza alokacje (klatka 1080p NV21 ≈ 3 MB). */
    private val maxFrameRateHz: Int = 15,
) : CameraPort {

    private val streamManager: ICameraStreamManager
        get() = MediaDataCenter.getInstance().cameraStreamManager

    private val previewLock = Any()
    private var preview: PreviewTarget? = null

    private data class PreviewTarget(val surface: Surface, val width: Int, val height: Int)

    private val _gimbalPitch = MutableStateFlow<Double?>(null)
    override val gimbalPitchDeg: StateFlow<Double?> = _gimbalPitch.asStateFlow()

    init {
        sdk.whenInitialized { KeyTools.createKey(GimbalKey.KeyGimbalAttitude, cameraIndex).asFlow() }
            .onEach { attitude -> _gimbalPitch.value = attitude?.pitch }
            .catch { e -> logger.system("gimbal_attitude_failed", LogLevel.ERROR, mapOf("error" to e.toString())) }
            .launchIn(scope)

        // Powierzchnia podpięta przed INITIALIZE_COMPLETE zostaje przekazana do SDK, gdy będzie gotowe.
        sdk.initialized
            .filter { it }
            .onEach { synchronized(previewLock) { preview }?.let { putSurface(it) } }
            .launchIn(scope)
    }

    /** Wywoływać z `SurfaceHolder.Callback.surfaceChanged`. */
    fun attachPreview(surface: Surface, width: Int, height: Int) {
        if (width <= 0 || height <= 0) {
            detachPreview(surface)
            return
        }
        val target = PreviewTarget(surface, width, height)
        synchronized(previewLock) { preview = target }
        if (sdk.initialized.value) putSurface(target)
    }

    /** Wywoływać z `SurfaceHolder.Callback.surfaceDestroyed`. */
    fun detachPreview(surface: Surface) {
        synchronized(previewLock) { if (preview?.surface == surface) preview = null }
        if (sdk.initialized.value) {
            streamManager.removeCameraStreamSurface(surface)
            logger.system("camera_preview_detached")
        }
    }

    private fun putSurface(target: PreviewTarget) {
        streamManager.putCameraStreamSurface(
            cameraIndex,
            target.surface,
            target.width,
            target.height,
            ICameraStreamManager.ScaleType.CENTER_INSIDE,
        )
        logger.system("camera_preview_attached", fields = mapOf("w" to target.width, "h" to target.height))
    }

    override val frames: Flow<VideoFrame> = callbackFlow {
        sdk.initialized.first { it }
        val minIntervalMs = 1_000L / maxFrameRateHz.coerceAtLeast(1)
        var lastAcceptedMs = Long.MIN_VALUE
        val listener = object : ICameraStreamManager.CameraFrameListener {
            override fun onFrame(
                frameData: ByteArray,
                offset: Int,
                length: Int,
                width: Int,
                height: Int,
                format: ICameraStreamManager.FrameFormat,
            ) {
                val now = clock.monotonicMs()
                if (now - lastAcceptedMs < minIntervalMs) return
                lastAcceptedMs = now
                // Bufor SDK jest wielokrotnego użytku — kopiujemy, zanim klatka opuści callback.
                trySend(VideoFrame(frameData.copyOfRange(offset, offset + length), width, height, FrameFormat.NV21, now))
            }
        }
        streamManager.addFrameListener(cameraIndex, ICameraStreamManager.FrameFormat.NV21, listener)
        logger.system("camera_frames_on", fields = mapOf("maxHz" to maxFrameRateHz))
        awaitClose {
            streamManager.removeFrameListener(listener)
            logger.system("camera_frames_off")
        }
    }
        .buffer(capacity = 1, onBufferOverflow = BufferOverflow.DROP_OLDEST)
        .flowOn(Dispatchers.Default)
        .shareIn(scope, SharingStarted.WhileSubscribed(), replay = 0)

    /** Gimbal na −90° (kamera w dół) — `GimbalKey.KeyRotateByAngle`, tryb kąta bezwzględnego. */
    override suspend fun pointGimbalDown(): CommandResult = rotatePitch(-90.0)

    /** Wyśrodkowanie osi pochylenia i odchylenia — `GimbalKey.KeyGimbalReset`. */
    override suspend fun resetGimbal(): CommandResult {
        if (!sdk.initialized.value) return notReady()
        val result = KeyTools.createKey(GimbalKey.KeyGimbalReset, cameraIndex).perform(GimbalResetType.PITCH_YAW)
        logger.command("gimbal_reset", result)
        return result
    }

    private suspend fun rotatePitch(pitchDeg: Double): CommandResult {
        if (!sdk.initialized.value) return notReady()
        val rotation = GimbalAngleRotation().apply {
            mode = GimbalAngleRotationMode.ABSOLUTE_ANGLE
            pitch = pitchDeg
            roll = 0.0
            yaw = 0.0
            duration = 1.0
        }
        val result = KeyTools.createKey(GimbalKey.KeyRotateByAngle, cameraIndex).perform(rotation)
        logger.command("gimbal_pitch", result, mapOf("pitch" to pitchDeg))
        return result
    }

    private fun notReady(): CommandResult.Failure {
        logger.system("camera_not_ready", LogLevel.WARN)
        return CommandResult.Failure("MSDK nie jest jeszcze zainicjalizowane")
    }
}

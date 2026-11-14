package com.example.djicontroller.vision

import com.example.djicontroller.core.model.DetectedMarker
import com.example.djicontroller.core.model.FrameFormat
import com.example.djicontroller.core.model.MarkerObservation
import com.example.djicontroller.core.model.PixelPoint
import com.example.djicontroller.core.model.VideoFrame
import com.example.djicontroller.core.model.VisionFrameResult
import org.opencv.calib3d.Calib3d
import org.opencv.core.CvType
import org.opencv.core.Mat
import org.opencv.core.MatOfDouble
import org.opencv.core.MatOfPoint
import org.opencv.core.MatOfPoint2f
import org.opencv.core.MatOfPoint3f
import org.opencv.core.Point
import org.opencv.core.Point3
import org.opencv.core.Rect
import org.opencv.core.Scalar
import org.opencv.core.Size
import org.opencv.imgproc.Imgproc
import org.opencv.objdetect.DetectorParameters
import org.opencv.objdetect.Dictionary
import org.opencv.objdetect.Objdetect
import kotlin.math.ceil
import kotlin.math.floor
import kotlin.math.hypot
import kotlin.math.max
import kotlin.math.min
import org.opencv.objdetect.ArucoDetector as CvArucoDetector

/** Metoda doprecyzowania narożników ArUco (`DetectorParameters.cornerRefinementMethod`). */
enum class CornerRefinement { NONE, SUBPIX, CONTOUR }

data class ArucoDetectorConfig(
    /** Poza z błędem reprojekcji większym niż tyle pikseli jest odrzucana. */
    val maxReprojectionErrorPx: Double = 3.0,
    /** Klatki szersze niż tyle są zmniejszane przed detekcją (czas detekcji ~ liczba pikseli). */
    val maxDetectionWidth: Int = 1280,
    /**
     * Doprecyzowanie narożników. Domyślnie [CornerRefinement.CONTOUR]: dopasowuje proste do krawędzi ramki,
     * więc działa też przy kilku pikselach na pole markera. `SUBPIX` przy małym markerze w obrazie
     * przesuwa narożniki na wewnętrzne krawędzie pól — log z emulatora (2026-11-09): na 8 m marker 16
     * wykryty w 120 z 122 klatek, a poza przeszła kontrolę reprojekcji tylko w 8.
     */
    val cornerRefinement: CornerRefinement = CornerRefinement.CONTOUR,
    /**
     * Gdy `solvePnP` przekroczy [maxReprojectionErrorPx], ale ID markera jest zdekodowane, zwróć pozę
     * zgrubną z narożników ([PoseConversion.fromCorners], `MarkerObservation.coarse = true`) zamiast nic.
     * Środek markera i kurs są wtedy nadal dobre — lądowanie nie „gubi” markera, który widać w kadrze.
     */
    val coarseFallback: Boolean = true,
    /** Poza zgrubna tylko przy błędzie reprojekcji ≤ tyle boku markera w pikselach (odrzuca zdeformowane narożniki). */
    val coarseMaxErrorFraction: Double = 0.25,
    /** Detekcja dwuprzebiegowa markerów zagnieżdżonych (patrz [LandingPadLayout]). */
    val nestedSearch: Boolean = true,
    /**
     * Mniejszy marker ma pierwszeństwo, o ile w obrazie ma co najmniej tyle pikseli boku —
     * z daleka mały marker ma kilka pikseli i jego poza jest zbyt zaszumiona.
     */
    val minPreferredSidePx: Double = 40.0,
)

/**
 * Detekcja markerów ArUco i estymacja pozy lądowiska (sekcja 5.4) — OpenCV `objdetect` + `solvePnP`.
 *
 * 1. NV21 → skala szarości: płaszczyzna Y to pierwsze `w·h` bajtów klatki (bez konwersji kolorów).
 * 2. `ArucoDetector.detectMarkers` na całej klatce (ew. zmniejszonej do [ArucoDetectorConfig.maxDetectionWidth]).
 * 3. Markery zagnieżdżone: detektor OpenCV z jednej pary współśrodkowych kandydatów zostawia tylko jeden,
 *    więc drugi szukamy w drugim przebiegu — po zamaskowaniu małego markera (szukamy dużego)
 *    albo w wyciętym środku dużego markera (szukamy małego).
 * 4. Dla każdego markera z [LandingPadLayout]: `solvePnP(SOLVEPNP_IPPE_SQUARE)` z [CameraCalibration],
 *    kontrola błędu reprojekcji, przeliczenie na BODY ([PoseConversion]).
 * 5. Do sterowania wybierany jest NAJMNIEJSZY poprawnie zdekodowany marker (o ile w obrazie ma
 *    co najmniej [ArucoDetectorConfig.minPreferredSidePx] boku) — przy niskiej wysokości daje
 *    najdokładniejszą pozę, a duży jest wtedy zwykle częściowo poza kadrem.
 *
 * Nie jest bezpieczny wątkowo — używać z jednej korutyny (robi to [VisionPipeline]).
 * Wymaga załadowanego OpenCV ([OpenCvRuntime.ensureLoaded]).
 */
class ArucoDetector(
    val layout: LandingPadLayout,
    calibration: CameraCalibration,
    private val config: ArucoDetectorConfig = ArucoDetectorConfig(),
    /** Błąd OpenCV na danym etapie (`detect`, `nested`, `pose`) — do logu; klatka i tak jest przetwarzana dalej. */
    private val onError: (stage: String, error: Throwable) -> Unit = { _, _ -> },
) : AutoCloseable {

    @Volatile
    var calibration: CameraCalibration = calibration

    // Biblioteka natywna musi być załadowana przed pierwszym obiektem OpenCV poniżej.
    init {
        check(OpenCvRuntime.ensureLoaded()) { "OpenCV niezaładowane" }
    }

    private val dictionary: Dictionary = Objdetect.getPredefinedDictionary(
        when (layout.dictionary) {
            ArucoDictionary.DICT_4X4_50 -> Objdetect.DICT_4X4_50
        },
    )
    private val params = DetectorParameters().apply {
        when (config.cornerRefinement) {
            CornerRefinement.NONE -> Unit
            CornerRefinement.SUBPIX -> set_cornerRefinementMethod(Objdetect.CORNER_REFINE_SUBPIX)
            CornerRefinement.CONTOUR -> set_cornerRefinementMethod(Objdetect.CORNER_REFINE_CONTOUR)
        }
    }
    private val detector = CvArucoDetector(dictionary, params)

    private val gray = Mat()
    private val scaled = Mat()

    /**
     * Przetwarza klatkę. [nowMs] — zegar monotoniczny tego samego źródła co [VideoFrame.timestampMs].
     * @param gimbalPitchDeg pochylenie gimbala do kompensacji odchylenia od pionu (`null` = −90°)
     */
    fun detect(frame: VideoFrame, gimbalPitchDeg: Double?, source: String, nowMs: () -> Long): VisionFrameResult {
        val started = nowMs()
        frameError = null
        val markers = try {
            detectMarkers(frame, gimbalPitchDeg)
        } catch (e: RuntimeException) {
            // Uszkodzona klatka lub błąd OpenCV nie może zatrzymać potoku — klatka bez markerów, błąd do logu.
            report("detect", e)
            emptyList()
        }
        val finished = nowMs()
        val age = finished - frame.timestampMs
        val withLatency = markers.map { m -> m.copy(observation = m.observation?.copy(latencyMs = age)) }
        val valid = withLatency.filter { it.observation != null }
        // Pierwszeństwo mają pozy z solvePnP; zgrubne tylko, gdy w klatce nie ma żadnej dokładnej.
        val pool = valid.filter { it.observation?.coarse == false }.ifEmpty { valid }
        val selected = (
            pool.filter { sidePx(it) >= config.minPreferredSidePx }.minByOrNull { it.sizeM ?: Double.MAX_VALUE }
                ?: pool.maxByOrNull { sidePx(it) }
            )?.observation
        return VisionFrameResult(
            frameTimestampMs = frame.timestampMs,
            frameWidth = frame.width,
            frameHeight = frame.height,
            markers = withLatency,
            selected = selected,
            detectionMs = finished - started,
            frameAgeMs = age,
            source = source,
            error = frameError,
        )
    }

    /** Pierwszy błąd w bieżącej klatce (pokazywany w nakładce). */
    private var frameError: String? = null

    private fun report(stage: String, e: Throwable) {
        if (frameError == null) frameError = "$stage: ${e.message ?: e::class.simpleName}"
        onError(stage, e)
    }

    private fun detectMarkers(frame: VideoFrame, gimbalPitchDeg: Double?): List<DetectedMarker> {
        val w = frame.width
        val h = frame.height
        if (w <= 0 || h <= 0 || frame.data.size < w * h) return emptyList()
        when (frame.format) {
            FrameFormat.NV21, FrameFormat.YUV420_888 -> Unit // płaszczyzna Y na początku bufora
            FrameFormat.RGBA_8888 -> return emptyList()
        }
        gray.create(h, w, CvType.CV_8UC1)
        gray.put(0, 0, frame.data, 0, w * h)

        val scale = if (w > config.maxDetectionWidth) config.maxDetectionWidth.toDouble() / w else 1.0
        val image = if (scale < 1.0) {
            Imgproc.resize(gray, scaled, Size(w * scale, h * scale), 0.0, 0.0, Imgproc.INTER_AREA)
            scaled
        } else {
            gray
        }

        val found = detectRaw(image, 0, 0)
        if (config.nestedSearch) searchNested(image, found)

        val calib = calibration.scaledTo(w, h)
        return found.map { (id, quad) ->
            val full = FloatArray(8) { i -> (quad[i] / scale).toFloat() }
            val padMarker = layout.marker(id)
            val observation = padMarker?.let {
                try {
                    estimatePose(it, full, calib, gimbalPitchDeg, frame.timestampMs)
                } catch (e: RuntimeException) {
                    report("pose", e)
                    null
                }
            }
            DetectedMarker(
                id = id,
                corners = List(4) { k -> PixelPoint(full[2 * k], full[2 * k + 1]) },
                sizeM = padMarker?.sizeM,
                observation = observation,
            )
        }
    }

    /** Jeden przebieg `detectMarkers`; narożniki przesunięte o (ox, oy). Tylko markery z konfiguracji lądowiska. */
    private fun detectRaw(image: Mat, ox: Int, oy: Int): MutableMap<Int, DoubleArray> {
        val corners = ArrayList<Mat>()
        val ids = Mat()
        val out = LinkedHashMap<Int, DoubleArray>()
        try {
            detector.detectMarkers(image, corners, ids)
            for (i in 0 until ids.rows()) {
                val id = ids.get(i, 0)[0].toInt()
                if (layout.marker(id) == null || id in out) continue
                val buf = FloatArray(8)
                corners[i].get(0, 0, buf)
                out[id] = DoubleArray(8) { k -> buf[k].toDouble() + if (k % 2 == 0) ox else oy }
            }
        } finally {
            corners.forEach { it.release() }
            ids.release()
        }
        return out
    }

    /** Drugi przebieg dla par rodzic–dziecko, z których znaleziono tylko jeden marker. */
    private fun searchNested(image: Mat, found: MutableMap<Int, DoubleArray>) {
        for (child in layout.markers) {
            val parent = layout.parentOf(child) ?: continue
            try {
                searchPair(image, found, child, parent)
            } catch (e: RuntimeException) {
                // Błąd drugiego przebiegu nie może skasować markerów znalezionych w pierwszym.
                report("nested", e)
            }
        }
    }

    private fun searchPair(image: Mat, found: MutableMap<Int, DoubleArray>, child: PadMarker, parent: PadMarker) {
        run {
            val childQuad = found[child.id]
            val parentQuad = found[parent.id]
            if (childQuad != null && parentQuad == null) {
                // Mały marker widoczny: zamaskuj go bielą i szukaj dużego wokół.
                val masked = image.clone()
                try {
                    Imgproc.fillConvexPoly(masked, toMatOfPoint(scaleQuad(childQuad, 1.35)), Scalar(255.0))
                    val roi = boundingRect(scaleQuad(childQuad, parent.sizeM / child.sizeM * 1.4), image) ?: return@run
                    val sub = masked.submat(roi)
                    try {
                        detectRaw(sub, roi.x, roi.y)[parent.id]?.let { found[parent.id] = it }
                    } finally {
                        sub.release()
                    }
                } finally {
                    masked.release()
                }
            } else if (parentQuad != null && childQuad == null) {
                // Duży marker widoczny: wytnij jego środkowe pola na białe tło i szukaj małego.
                val region = scaleQuad(parentQuad, CENTER_REGION_CELLS / 6.0)
                val roi = boundingRect(scaleQuad(parentQuad, CENTER_REGION_CELLS / 6.0 * 1.7), image) ?: return@run
                if (roi.width < MIN_ROI_PX || roi.height < MIN_ROI_PX) return@run
                val canvas = Mat(roi.height, roi.width, CvType.CV_8UC1, Scalar(255.0))
                val mask = Mat.zeros(roi.height, roi.width, CvType.CV_8UC1)
                val src = image.submat(roi)
                try {
                    val local = DoubleArray(8) { k -> region[k] - if (k % 2 == 0) roi.x else roi.y }
                    Imgproc.fillConvexPoly(mask, toMatOfPoint(local), Scalar(255.0))
                    src.copyTo(canvas, mask)
                    detectRaw(canvas, roi.x, roi.y)[child.id]?.let { found[child.id] = it }
                } finally {
                    canvas.release()
                    mask.release()
                    src.release()
                }
            }
        }
    }

    private fun estimatePose(
        marker: PadMarker,
        corners: FloatArray,
        calib: CameraCalibration,
        gimbalPitchDeg: Double?,
        timestampMs: Long,
    ): MarkerObservation? {
        val half = marker.sizeM / 2
        val objectPoints = MatOfPoint3f(
            Point3(-half, half, 0.0),
            Point3(half, half, 0.0),
            Point3(half, -half, 0.0),
            Point3(-half, -half, 0.0),
        )
        val imagePoints = MatOfPoint2f(*Array(4) { k -> Point(corners[2 * k].toDouble(), corners[2 * k + 1].toDouble()) })
        val cameraMatrix = Mat(3, 3, CvType.CV_64F).apply {
            put(0, 0, calib.fx, 0.0, calib.cx, 0.0, calib.fy, calib.cy, 0.0, 0.0, 1.0)
        }
        val dist = if (calib.distortion.isEmpty()) MatOfDouble() else MatOfDouble(*calib.distortion.toDoubleArray())
        val rvec = Mat()
        val tvec = Mat()
        val rotation = Mat()
        val projected = MatOfPoint2f()
        try {
            val ok = Calib3d.solvePnP(objectPoints, imagePoints, cameraMatrix, dist, rvec, tvec, false, Calib3d.SOLVEPNP_IPPE_SQUARE)
            if (!ok) return coarsePose(marker, corners, calib, gimbalPitchDeg, timestampMs, null)
            Calib3d.projectPoints(objectPoints, rvec, tvec, cameraMatrix, dist, projected)
            val p = projected.toArray()
            var err = 0.0
            for (k in 0 until 4) err += hypot(p[k].x - corners[2 * k], p[k].y - corners[2 * k + 1])
            err /= 4
            if (err > config.maxReprojectionErrorPx) return coarsePose(marker, corners, calib, gimbalPitchDeg, timestampMs, err)

            Calib3d.Rodrigues(rvec, rotation)
            val r = DoubleArray(9)
            rotation.get(0, 0, r)
            val t = DoubleArray(3)
            tvec.get(0, 0, t)
            if (t[2] <= 0) return null
            val pose = PoseConversion.toBody(t, r, marker.offsetRightM, marker.offsetUpM, gimbalPitchDeg)
            if (pose.heightM <= 0) return null
            return MarkerObservation(
                markerId = marker.id,
                dxM = pose.forwardM,
                dyM = pose.rightM,
                dzM = pose.heightM,
                yawDeg = pose.yawDeg,
                timestampMs = timestampMs,
                reprojectionErrorPx = err,
            )
        } finally {
            objectPoints.release()
            imagePoints.release()
            cameraMatrix.release()
            dist.release()
            rvec.release()
            tvec.release()
            rotation.release()
            projected.release()
        }
    }

    /** Poza zgrubna z narożników (patrz [ArucoDetectorConfig.coarseFallback]); `null`, gdy wyłączona lub narożniki złe. */
    private fun coarsePose(
        marker: PadMarker,
        corners: FloatArray,
        calib: CameraCalibration,
        gimbalPitchDeg: Double?,
        timestampMs: Long,
        reprojectionErrorPx: Double?,
    ): MarkerObservation? {
        if (!config.coarseFallback) return null
        var side = 0.0
        for (k in 0 until 4) {
            val n = (k + 1) % 4
            side += hypot((corners[2 * n] - corners[2 * k]).toDouble(), (corners[2 * n + 1] - corners[2 * k + 1]).toDouble())
        }
        side /= 4
        if (reprojectionErrorPx != null && reprojectionErrorPx > config.coarseMaxErrorFraction * side) return null
        val pose = PoseConversion.fromCorners(
            corners, calib.fx, calib.fy, calib.cx, calib.cy, marker.sizeM, marker.offsetRightM, marker.offsetUpM, gimbalPitchDeg,
        ) ?: return null
        if (pose.heightM <= 0) return null
        return MarkerObservation(
            markerId = marker.id,
            dxM = pose.forwardM,
            dyM = pose.rightM,
            dzM = pose.heightM,
            yawDeg = pose.yawDeg,
            timestampMs = timestampMs,
            reprojectionErrorPx = reprojectionErrorPx,
            coarse = true,
        )
    }

    override fun close() {
        gray.release()
        scaled.release()
    }

    private fun sidePx(m: DetectedMarker): Double {
        val c = m.corners
        return (0 until 4).sumOf { k ->
            val a = c[k]
            val b = c[(k + 1) % 4]
            hypot((b.x - a.x).toDouble(), (b.y - a.y).toDouble())
        } / 4
    }

    private companion object {
        /** Szerokość środkowego obszaru dużego markera przeszukiwanego w drugim przebiegu, w polach. */
        const val CENTER_REGION_CELLS = 1.9
        const val MIN_ROI_PX = 32

        /** Skalowanie czworokąta względem jego środka. */
        fun scaleQuad(q: DoubleArray, f: Double): DoubleArray {
            val cx = (q[0] + q[2] + q[4] + q[6]) / 4
            val cy = (q[1] + q[3] + q[5] + q[7]) / 4
            return DoubleArray(8) { k -> if (k % 2 == 0) cx + (q[k] - cx) * f else cy + (q[k] - cy) * f }
        }

        fun toMatOfPoint(q: DoubleArray): MatOfPoint =
            MatOfPoint(*Array(4) { k -> Point(q[2 * k], q[2 * k + 1]) })

        /** Prostokąt otaczający [q] przycięty do obrazu; `null`, gdy pusty. */
        fun boundingRect(q: DoubleArray, image: Mat): Rect? {
            val xs = doubleArrayOf(q[0], q[2], q[4], q[6])
            val ys = doubleArrayOf(q[1], q[3], q[5], q[7])
            val x0 = max(0, floor(xs.min()).toInt())
            val y0 = max(0, floor(ys.min()).toInt())
            val x1 = min(image.cols(), ceil(xs.max()).toInt())
            val y1 = min(image.rows(), ceil(ys.max()).toInt())
            if (x1 - x0 < 2 || y1 - y0 < 2) return null
            return Rect(x0, y0, x1 - x0, y1 - y0)
        }
    }
}

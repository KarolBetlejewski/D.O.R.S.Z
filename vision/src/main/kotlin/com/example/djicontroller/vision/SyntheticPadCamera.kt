package com.example.djicontroller.vision

import com.example.djicontroller.core.model.FrameFormat
import com.example.djicontroller.core.model.VideoFrame
import org.opencv.core.Core
import org.opencv.core.CvType
import org.opencv.core.Mat
import org.opencv.core.Point
import org.opencv.core.Rect
import org.opencv.core.Scalar
import org.opencv.core.Size
import org.opencv.imgproc.Imgproc
import org.opencv.objdetect.Objdetect
import kotlin.math.cos
import kotlin.math.floor
import kotlin.math.roundToInt
import kotlin.math.sin
import kotlin.math.tan

/**
 * Położenie kamery względem lądowiska dla renderu syntetycznego.
 * @property northM / eastM położenie drona względem środka lądowiska, m
 * @property heightM wysokość kamery nad płytą, m
 * @property headingDeg kurs drona (0 = północ, + w prawo)
 * @property gimbalPitchDeg pochylenie gimbala (−90 = w dół, 0 = poziomo)
 */
data class SyntheticPose(
    val northM: Double,
    val eastM: Double,
    val heightM: Double,
    val headingDeg: Double,
    val gimbalPitchDeg: Double = -90.0,
    val markerHidden: Boolean = false,
)

/**
 * Syntetyczna kamera: rzut perspektywiczny płyty lądowiska z PRAWDZIWYMI markerami ArUco
 * (te same identyfikatory i rozmiary co [LandingPadLayout]) i siatki terenu, przez model kamery
 * z [CameraCalibration]. Klatki NV21 trafiają do tego samego [ArucoDetector] co obraz z drona,
 * więc wariant demo i testy bez drona sprawdzają cały potok: detekcję, zagnieżdżenie, `solvePnP`
 * i znaki osi w [PoseConversion].
 *
 * Dowolne pochylenie gimbala (od −90° w dół do 0° poziomo): grunt z siatką w perspektywie i niebo nad horyzontem.
 * Kamera w dół: góra obrazu = przód drona. Płyta: góra markera wskazuje północ (obrót: [padHeadingDeg]).
 * Nie jest bezpieczna wątkowo.
 */
class SyntheticPadCamera(
    private val layout: LandingPadLayout,
    val calibration: CameraCalibration = CameraCalibration.mini4ProDefault(960, 540),
    private val padHeadingDeg: Double = 0.0,
    private val texturePx: Int = 900,
) : AutoCloseable {

    // Kolejność ma znaczenie: biblioteka natywna OpenCV musi być załadowana PRZED pierwszym `Mat(...)`
    // (inicjalizatory pól wykonują się w kolejności deklaracji).
    init {
        check(OpenCvRuntime.ensureLoaded()) { "OpenCV niezaładowane" }
    }

    private val w = calibration.width and 1.inv()
    private val h = calibration.height and 1.inv()
    private val ppm: Double = texturePx / layout.padSizeM
    private val texture: Mat = renderPadTexture(layout, texturePx)
    private val frame = Mat(h, w, CvType.CV_8UC1)
    private val homography = Mat(3, 3, CvType.CV_64F)

    fun render(pose: SyntheticPose, timestampMs: Long): VideoFrame {
        frame.setTo(Scalar(GROUND))
        drawSky(pose.gimbalPitchDeg)
        val cam = cameraAxes(pose)
        val c = doubleArrayOf(pose.eastM, pose.northM, pose.heightM)
        drawGrid(pose, cam, c)
        // Płyta tylko, gdy cała leży przed kamerą (sprawdzane w drawPad).
        if (!pose.markerHidden) drawPad(cam, c)
        val data = ByteArray(w * h * 3 / 2)
        frame.get(0, 0, data)              // pierwsze w·h bajtów = Y
        data.fill(NEUTRAL_CHROMA, w * h, data.size)
        return VideoFrame(data, w, h, FrameFormat.NV21, timestampMs)
    }

    /** Osie kamery w świecie ENU (wiersze macierzy świat→kamera): x, y, z. */
    private fun cameraAxes(pose: SyntheticPose): Array<DoubleArray> {
        val hd = Math.toRadians(pose.headingDeg)
        val delta = Math.toRadians(pose.gimbalPitchDeg + 90.0)
        val f = doubleArrayOf(sin(hd), cos(hd), 0.0)
        val r = doubleArrayOf(cos(hd), -sin(hd), 0.0)
        val d = doubleArrayOf(0.0, 0.0, -1.0)
        val z = DoubleArray(3) { cos(delta) * d[it] + sin(delta) * f[it] }
        val y = DoubleArray(3) { -cos(delta) * f[it] + sin(delta) * d[it] }
        return arrayOf(r, y, z)
    }

    private fun toCamera(cam: Array<DoubleArray>, world: DoubleArray, c: DoubleArray): DoubleArray =
        DoubleArray(3) { i -> cam[i][0] * (world[0] - c[0]) + cam[i][1] * (world[1] - c[1]) + cam[i][2] * (world[2] - c[2]) }

    private fun project(p: DoubleArray): Point? {
        if (p[2] < MIN_DEPTH_M) return null
        return Point(calibration.fx * p[0] / p[2] + calibration.cx, calibration.fy * p[1] / p[2] + calibration.cy)
    }

    private fun drawPad(cam: Array<DoubleArray>, c: DoubleArray) {
        val psi = Math.toRadians(padHeadingDeg)
        val right = doubleArrayOf(cos(psi), -sin(psi), 0.0)
        val up = doubleArrayOf(sin(psi), cos(psi), 0.0)
        // Wszystkie narożniki płyty muszą być przed kamerą — inaczej homografia „zawija” obraz.
        val half = layout.padSizeM / 2
        for ((sx, sy) in listOf(-1 to -1, -1 to 1, 1 to -1, 1 to 1)) {
            val world = DoubleArray(3) { right[it] * sx * half + up[it] * sy * half }
            if (toCamera(cam, world, c)[2] < MIN_DEPTH_M) return
        }
        // H = K · [R·right, R·up, −R·C] · S, gdzie S: piksel tekstury → metry płyty.
        val col0 = DoubleArray(3) { i -> cam[i][0] * right[0] + cam[i][1] * right[1] + cam[i][2] * right[2] }
        val col1 = DoubleArray(3) { i -> cam[i][0] * up[0] + cam[i][1] * up[1] + cam[i][2] * up[2] }
        val col2 = DoubleArray(3) { i -> -(cam[i][0] * c[0] + cam[i][1] * c[1] + cam[i][2] * c[2]) }
        val k = arrayOf(
            doubleArrayOf(calibration.fx, 0.0, calibration.cx),
            doubleArrayOf(0.0, calibration.fy, calibration.cy),
            doubleArrayOf(0.0, 0.0, 1.0),
        )
        val cols = arrayOf(col0, col1, col2)
        val m = Array(3) { i -> DoubleArray(3) { j -> (0 until 3).sumOf { n -> k[i][n] * cols[j][n] } } }
        val s = arrayOf(
            doubleArrayOf(1 / ppm, 0.0, -texturePx / 2.0 / ppm),
            doubleArrayOf(0.0, -1 / ppm, texturePx / 2.0 / ppm),
            doubleArrayOf(0.0, 0.0, 1.0),
        )
        val hs = DoubleArray(9) { idx ->
            val i = idx / 3
            val j = idx % 3
            (0 until 3).sumOf { n -> m[i][n] * s[n][j] }
        }
        homography.put(0, 0, *hs)
        Imgproc.warpPerspective(
            texture, frame, homography, Size(w.toDouble(), h.toDouble()),
            Imgproc.INTER_LINEAR, Core.BORDER_TRANSPARENT, Scalar(0.0),
        )
    }

    /** Siatka terenu (co 2/10/20 m zależnie od wysokości) — daje wrażenie ruchu nad gruntem. */
    private fun drawGrid(pose: SyntheticPose, cam: Array<DoubleArray>, c: DoubleArray) {
        // Kamera pochylona ku horyzontowi widzi dalej — większy zasięg i rzadsza siatka.
        val tiltDeg = pose.gimbalPitchDeg + 90.0
        val far = tiltDeg > 30.0
        val spacing = when {
            pose.heightM < 10 && !far -> 2.0
            pose.heightM < 10 -> 5.0
            pose.heightM < 40 && !far -> 10.0
            else -> 20.0
        }
        val range = if (far) (pose.heightM * 12 + 2 * spacing).coerceAtMost(400.0) else pose.heightM * 2.5 + 2 * spacing
        val color = Scalar(GRID)
        val e0 = floor((pose.eastM - range) / spacing) * spacing
        val n0 = floor((pose.northM - range) / spacing) * spacing
        val steps = (2 * range / spacing).toInt() + 2
        for (i in 0..steps) {
            val e = e0 + i * spacing
            val n = n0 + i * spacing
            for (j in 0 until steps) {
                val a = n0 + j * spacing
                val b = a + spacing
                line(cam, c, doubleArrayOf(e, a, 0.0), doubleArrayOf(e, b, 0.0), color)
                val p = e0 + j * spacing
                line(cam, c, doubleArrayOf(p, n, 0.0), doubleArrayOf(p + spacing, n, 0.0), color)
            }
        }
    }

    private fun line(cam: Array<DoubleArray>, c: DoubleArray, a: DoubleArray, b: DoubleArray, color: Scalar) {
        val pa = project(toCamera(cam, a, c)) ?: return
        val pb = project(toCamera(cam, b, c)) ?: return
        Imgproc.line(frame, pa, pb, color, 2)
    }

    /** Niebo nad linią horyzontu (widoczne, gdy kamera jest pochylona mniej niż ok. −68°). */
    private fun drawSky(pitchDeg: Double) {
        val down = -pitchDeg
        if (down >= 89.0) return
        val horizonY = (calibration.cy - calibration.fy * tan(Math.toRadians(down))).roundToInt()
        if (horizonY <= 0) return
        frame.submat(Rect(0, 0, w, horizonY.coerceAtMost(h))).setTo(Scalar(SKY))
    }

    override fun close() {
        texture.release()
        frame.release()
        homography.release()
    }

    companion object {
        private const val GROUND = 105.0
        private const val GRID = 80.0
        private const val SKY = 200.0
        private const val MIN_DEPTH_M = 0.05

        /** Biała płyta [LandingPadLayout.padSizeM] z markerami — także do wydruku. */
        fun renderPadTexture(layout: LandingPadLayout, sizePx: Int): Mat {
            check(OpenCvRuntime.ensureLoaded()) { "OpenCV niezaładowane" }
            val ppm = sizePx / layout.padSizeM
            val tex = Mat(sizePx, sizePx, CvType.CV_8UC1, Scalar(255.0))
            val dictionary = Objdetect.getPredefinedDictionary(
                when (layout.dictionary) {
                    ArucoDictionary.DICT_4X4_50 -> Objdetect.DICT_4X4_50
                },
            )
            // Najpierw markery samodzielne, potem zagnieżdżone (rysowane na wierzchu).
            for (m in layout.markers.sortedBy { if (it.nestedIn == null) 0 else 1 }) {
                val side = (m.sizeM * ppm).roundToInt()
                val img = Mat()
                Objdetect.generateImageMarker(dictionary, m.id, side, img)
                val cx = sizePx / 2.0 + m.offsetRightM * ppm
                val cy = sizePx / 2.0 - m.offsetUpM * ppm
                val x = (cx - side / 2.0).roundToInt()
                val y = (cy - side / 2.0).roundToInt()
                if (x >= 0 && y >= 0 && x + side <= sizePx && y + side <= sizePx) {
                    val roi = tex.submat(Rect(x, y, side, side))
                    img.copyTo(roi)
                    roi.release()
                }
                img.release()
            }
            return tex
        }
    }
}

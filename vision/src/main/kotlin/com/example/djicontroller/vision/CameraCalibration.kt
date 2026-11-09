package com.example.djicontroller.vision

import org.json.JSONArray
import org.json.JSONObject
import java.io.File

/**
 * Parametry wewnętrzne kamery dla `solvePnP` (macierz `cameraMatrix` i współczynniki `distCoeffs`),
 * wyznaczone dla rozdzielczości [width]×[height]. Dla klatek o innej rozdzielczości (ten sam kadr)
 * skaluje się je przez [scaledTo].
 *
 * Wartości domyślne ([mini4ProDefault]) wynikają z ogniskowej 24 mm (ekw. pełnej klatki) i kadru 16:9
 * na pełnej szerokości matrycy — dokładność rzędu kilku procent odległości. Do lądowania precyzyjnego
 * należy wykonać kalibrację szachownicą (OpenCV `calibrateCamera`) na klatkach zapisanych przyciskiem
 * „Zapisz klatkę” w Diagnostyce i wgrać wynik jako `camera_calibration.json` (format: [toJson]).
 */
data class CameraCalibration(
    val width: Int,
    val height: Int,
    val fx: Double,
    val fy: Double,
    val cx: Double,
    val cy: Double,
    /** k1, k2, p1, p2[, k3[, k4, k5, k6]] — konwencja OpenCV; pusta = bez dystorsji. */
    val distortion: List<Double> = emptyList(),
    /** Skąd pochodzą parametry — do logów i UI. */
    val source: String = "default",
) {
    init {
        require(width > 0 && height > 0 && fx > 0 && fy > 0) { "Nieprawidłowa kalibracja kamery" }
        require(distortion.size in setOf(0, 4, 5, 8, 12, 14)) { "distCoeffs: 0, 4, 5, 8, 12 lub 14 wartości" }
    }

    /** Poziome pole widzenia, stopnie. */
    val horizontalFovDeg: Double get() = Math.toDegrees(2 * kotlin.math.atan(width / (2 * fx)))

    fun scaledTo(w: Int, h: Int): CameraCalibration {
        if (w == width && h == height) return this
        val sx = w.toDouble() / width
        val sy = h.toDouble() / height
        return copy(width = w, height = h, fx = fx * sx, fy = fy * sy, cx = cx * sx, cy = cy * sy)
    }

    fun toJson(): String = JSONObject()
        .put("width", width)
        .put("height", height)
        .put("fx", fx)
        .put("fy", fy)
        .put("cx", cx)
        .put("cy", cy)
        .put("dist", JSONArray(distortion))
        .toString(2)

    companion object {
        /** Mini 4 Pro: 24 mm ekw. → fx = szerokość · 24/36 dla kadru 16:9 na pełnej szerokości matrycy (HFOV ≈ 73,7°). */
        fun mini4ProDefault(width: Int = 1280, height: Int = 720): CameraCalibration {
            val f = width * 24.0 / 36.0
            return CameraCalibration(width, height, f, f, width / 2.0, height / 2.0, source = "domyślna 24 mm")
        }

        fun fromJson(json: String, source: String = "plik"): CameraCalibration {
            val o = JSONObject(json)
            val dist = o.optJSONArray("dist")?.let { a -> List(a.length()) { a.getDouble(it) } }.orEmpty()
            return CameraCalibration(
                width = o.getInt("width"),
                height = o.getInt("height"),
                fx = o.getDouble("fx"),
                fy = o.getDouble("fy"),
                cx = o.getDouble("cx"),
                cy = o.getDouble("cy"),
                distortion = dist,
                source = source,
            )
        }

        /** Wczytuje [file], jeśli istnieje i jest poprawny; w przeciwnym razie `null` (wołający loguje powód). */
        fun load(file: File): Result<CameraCalibration?> = runCatching {
            if (!file.isFile) null else fromJson(file.readText(), source = file.name)
        }
    }
}

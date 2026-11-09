package com.example.djicontroller.ui

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.djicontroller.core.model.VisionFrameResult
import java.util.Locale
import kotlin.math.min

/**
 * Nakładka wizyjna na podgląd kamery (F2): obrys wykrytych markerów ArUco (wybrany do sterowania — pomarańczowy),
 * celownik środka kadru, wektor do środka lądowiska oraz błędy (dx, dy, dz, yaw) i czasy detekcji.
 *
 * Współrzędne klatki są mapowane tak jak obraz w podglądzie: `CENTER_INSIDE`
 * (cała klatka widoczna, wycentrowana) — jak `putCameraStreamSurface(..., ScaleType.CENTER_INSIDE)` w MSDK.
 */
@Composable
fun VisionOverlay(result: VisionFrameResult?, modifier: Modifier = Modifier) {
    Box(modifier) {
        Canvas(Modifier.fillMaxSize()) {
            val r = result
            val frameSize = if (r != null && r.frameWidth > 0 && r.frameHeight > 0) {
                Size(r.frameWidth.toFloat(), r.frameHeight.toFloat())
            } else {
                null
            }
            val fit = frameSize?.let { fitInside(it, size) }
            val center = fit?.let { Offset(it.left + it.width / 2, it.top + it.height / 2) } ?: this.center
            drawCrosshair(center)
            if (r == null || fit == null) return@Canvas
            val selectedId = r.selected?.markerId
            for (m in r.markers) {
                if (m.corners.size != 4) continue
                val path = Path()
                m.corners.forEachIndexed { i, p ->
                    val o = fit.map(p.x, p.y)
                    if (i == 0) path.moveTo(o.x, o.y) else path.lineTo(o.x, o.y)
                }
                path.close()
                val color = when {
                    m.id == selectedId -> PatrolColors.Accent
                    m.observation != null -> PatrolColors.Ok
                    else -> Color.White
                }
                drawPath(path, color, style = Stroke(width = 3.dp.toPx()))
                // Narożnik 0 (lewy górny markera) — pokazuje orientację.
                drawCircle(color, 4.dp.toPx(), fit.map(m.corners[0].x, m.corners[0].y))
            }
            r.markers.firstOrNull { it.id == selectedId }?.let { m ->
                val cx = m.corners.map { it.x }.average().toFloat()
                val cy = m.corners.map { it.y }.average().toFloat()
                drawLine(PatrolColors.Accent, center, fit.map(cx, cy), strokeWidth = 2.dp.toPx())
            }
        }
        VisionReadout(result, Modifier.align(Alignment.TopEnd).padding(6.dp))
    }
}

@Composable
private fun VisionReadout(result: VisionFrameResult?, modifier: Modifier) {
    val error = result?.error
    val lines = when {
        result == null -> listOf("ArUco: wyłączone")
        result.selected == null && result.markers.isNotEmpty() -> listOf(
            "ArUco: wykryto ${result.markers.joinToString { "id ${it.id}" }}, brak pozy",
            "detekcja ${result.detectionMs} ms · klatka ${result.frameAgeMs} ms",
        )
        result.selected == null -> listOf(
            "ArUco: brak markera",
            "detekcja ${result.detectionMs} ms · klatka ${result.frameAgeMs} ms",
        )
        else -> {
            val o = result.selected!!
            listOf(
                "ArUco id ${o.markerId}  (${result.markers.size} wykr.)",
                "dx ${f2(o.dxM)}  dy ${f2(o.dyM)} m",
                "dz ${f2(o.dzM)} m  yaw ${f1(o.yawDeg)}°",
                "detekcja ${result.detectionMs} ms · klatka ${result.frameAgeMs} ms",
                "reproj ${o.reprojectionErrorPx?.let { f2(it) } ?: "—"} px",
            )
        }
    }
    Column(
        modifier
            .clip(RoundedCornerShape(4.dp))
            .background(Color.Black.copy(alpha = 0.6f))
            .padding(horizontal = 8.dp, vertical = 4.dp),
    ) {
        lines.forEach { Text(it, color = Color.White, fontSize = 11.sp, fontFamily = FontFamily.Monospace) }
        if (error != null) {
            Text("błąd: $error", color = PatrolColors.Stop, fontSize = 11.sp, fontFamily = FontFamily.Monospace, maxLines = 2)
        }
    }
}

/** Prostokąt klatki wpasowanej w kontener (`CENTER_INSIDE`) i mapowanie pikseli klatki na ekran. */
private class FitRect(val left: Float, val top: Float, val width: Float, val height: Float, private val scale: Float) {
    fun map(x: Float, y: Float) = Offset(left + x * scale, top + y * scale)
}

private fun fitInside(frame: Size, container: Size): FitRect {
    val scale = min(container.width / frame.width, container.height / frame.height)
    val w = frame.width * scale
    val h = frame.height * scale
    return FitRect((container.width - w) / 2, (container.height - h) / 2, w, h, scale)
}

private fun DrawScope.drawCrosshair(c: Offset) {
    val arm = 18.dp.toPx()
    val gap = 5.dp.toPx()
    val stroke = 2.dp.toPx()
    val color = Color.White.copy(alpha = 0.85f)
    drawLine(color, Offset(c.x - arm, c.y), Offset(c.x - gap, c.y), stroke)
    drawLine(color, Offset(c.x + gap, c.y), Offset(c.x + arm, c.y), stroke)
    drawLine(color, Offset(c.x, c.y - arm), Offset(c.x, c.y - gap), stroke)
    drawLine(color, Offset(c.x, c.y + gap), Offset(c.x, c.y + arm), stroke)
}

private fun f2(v: Double) = String.format(Locale.US, "%+.2f", v)
private fun f1(v: Double) = String.format(Locale.US, "%+.1f", v)

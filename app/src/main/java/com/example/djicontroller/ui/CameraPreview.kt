package com.example.djicontroller.ui

import android.view.SurfaceHolder
import android.view.SurfaceView
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.viewinterop.AndroidView

/**
 * Podgląd z kamery drona: `SurfaceView` przekazywany do [VideoPreview]
 * (w `prod` do MSDK: `putCameraStreamSurface` / `removeCameraStreamSurface`).
 */
@Composable
fun CameraPreview(preview: VideoPreview, modifier: Modifier = Modifier) {
    AndroidView(
        modifier = modifier,
        factory = { context ->
            SurfaceView(context).apply {
                holder.addCallback(object : SurfaceHolder.Callback {
                    override fun surfaceCreated(holder: SurfaceHolder) = Unit

                    override fun surfaceChanged(holder: SurfaceHolder, format: Int, width: Int, height: Int) {
                        preview.attach(holder.surface, width, height)
                    }

                    override fun surfaceDestroyed(holder: SurfaceHolder) {
                        preview.detach(holder.surface)
                    }
                })
            }
        },
    )
}

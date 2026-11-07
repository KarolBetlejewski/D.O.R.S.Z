package com.example.djicontroller.ui

import android.view.SurfaceHolder
import android.view.SurfaceView
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.viewinterop.AndroidView
import com.example.djicontroller.msdk.camera.CameraAdapter

/**
 * Podgląd z kamery drona: `SurfaceView` przekazywany do MSDK przez [CameraAdapter]
 * (`putCameraStreamSurface` / `removeCameraStreamSurface`, wzorzec CameraStreamDetailFragment).
 */
@Composable
fun CameraPreview(camera: CameraAdapter, modifier: Modifier = Modifier) {
    AndroidView(
        modifier = modifier,
        factory = { context ->
            SurfaceView(context).apply {
                holder.addCallback(object : SurfaceHolder.Callback {
                    override fun surfaceCreated(holder: SurfaceHolder) = Unit

                    override fun surfaceChanged(holder: SurfaceHolder, format: Int, width: Int, height: Int) {
                        camera.attachPreview(holder.surface, width, height)
                    }

                    override fun surfaceDestroyed(holder: SurfaceHolder) {
                        camera.detachPreview(holder.surface)
                    }
                })
            }
        },
    )
}

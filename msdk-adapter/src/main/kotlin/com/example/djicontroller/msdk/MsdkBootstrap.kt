package com.example.djicontroller.msdk

import android.app.Application
import android.os.Build
import android.util.Log

/**
 * Punkt wejścia MSDK dla klasy `Application` z :app.
 *
 * :app nie może importować `dji.*` ani klas SDK (zasada warstw z sekcji 3.1), dlatego
 * wywołania wymagane przez SDK w `Application` przechodzą przez ten obiekt.
 */
object MsdkBootstrap {

    /**
     * Biblioteki natywne MSDK (m.in. `libSdkyclx_clx.so`) działają tylko na procesorze ARM64.
     * Emulator x86_64 udostępnia arm64 przez tłumaczenie binarne, ale zabezpieczenia DJI
     * kończą się wtedy SIGSEGV — dlatego na takim urządzeniu MSDK w ogóle nie ładujemy.
     */
    val isSupportedDevice: Boolean
        get() = Build.SUPPORTED_ABIS.firstOrNull() == "arm64-v8a"

    val unsupportedReason: String
        get() = "Wariant prod wymaga telefonu z procesorem ARM64 (to urządzenie: ${Build.SUPPORTED_ABIS.firstOrNull()}). " +
            "Na emulatorze uruchom wariant demoDebug."

    /**
     * Wymagane przez MSDK V5 w `Application.attachBaseContext`, zaraz po `super.attachBaseContext`.
     * Wzorzec: `DJIAircraftApplication` w android-sdk-v5-sample.
     */
    fun installHelper(application: Application) {
        if (!isSupportedDevice) {
            Log.e("MsdkBootstrap", unsupportedReason)
            return
        }
        com.cySdkyc.clx.Helper.install(application)
    }
}

package com.example.djicontroller.msdk

import android.app.Application

/**
 * Punkt wejścia MSDK dla klasy `Application` z :app.
 *
 * :app nie może importować `dji.*` ani klas SDK (zasada warstw z sekcji 3.1), dlatego
 * wywołania wymagane przez SDK w `Application` przechodzą przez ten obiekt.
 */
object MsdkBootstrap {

    /**
     * Wymagane przez MSDK V5 w `Application.attachBaseContext`, zaraz po `super.attachBaseContext`.
     * Wzorzec: `DJIAircraftApplication` w android-sdk-v5-sample.
     */
    fun installHelper(application: Application) {
        com.cySdkyc.clx.Helper.install(application)
    }
}

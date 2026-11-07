package com.example.djicontroller

import android.app.Application
import android.content.Context
import com.example.djicontroller.di.AppContainer
import com.example.djicontroller.msdk.MsdkBootstrap
import org.maplibre.android.MapLibre

class PatrolApp : Application() {

    lateinit var container: AppContainer
        private set

    override fun attachBaseContext(base: Context?) {
        super.attachBaseContext(base)
        // Wymaganie MSDK V5 (wzorzec DJIAircraftApplication) — przez :msdk-adapter, bez importu dji.* w :app.
        MsdkBootstrap.installHelper(this)
    }

    override fun onCreate() {
        super.onCreate()
        // MapLibre musi być zainicjalizowane przed utworzeniem pierwszego MapView.
        MapLibre.getInstance(this)
        container = AppContainer(this)
        container.start()
    }
}

/** Skrót do grafu zależności z dowolnego `Context`. */
val Context.appContainer: AppContainer
    get() = (applicationContext as PatrolApp).container

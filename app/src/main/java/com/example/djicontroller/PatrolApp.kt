package com.example.djicontroller

import android.app.Application
import android.content.Context
import com.example.djicontroller.di.AppContainer
import com.example.djicontroller.di.platformBootstrap
import org.maplibre.android.MapLibre

class PatrolApp : Application() {

    lateinit var container: AppContainer
        private set

    override fun attachBaseContext(base: Context?) {
        super.attachBaseContext(base)
        // prod: wymaganie MSDK V5 (Helper.install przez :msdk-adapter); demo: nic.
        platformBootstrap.install(this)
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

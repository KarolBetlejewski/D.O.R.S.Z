package com.example.djicontroller.msdk.connection

import com.example.djicontroller.msdk.MsdkBootstrap
import android.content.Context
import com.example.djicontroller.core.logging.system
import com.example.djicontroller.core.port.FlightLogger
import com.example.djicontroller.core.port.LogLevel
import dji.v5.common.error.IDJIError
import dji.v5.common.register.DJISDKInitEvent
import dji.v5.manager.SDKManager
import dji.v5.manager.interfaces.SDKManagerCallback
import dji.v5.network.DJINetworkManager
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.util.concurrent.atomic.AtomicBoolean

/** Etap uruchamiania MSDK. */
sealed interface SdkState {
    data object NotStarted : SdkState
    data object Initializing : SdkState

    /** `INITIALIZE_COMPLETE` przyszło, trwa `registerApp()` (pierwsza rejestracja wymaga internetu). */
    data object Registering : SdkState
    data object Registered : SdkState
    data class RegistrationFailed(val reason: String, val code: String?) : SdkState
}

/**
 * Inicjalizacja i rejestracja MSDK (sekcja 5.1) — wzorzec `MSDKManagerVM` z przykładu DJI:
 * `SDKManager.init()` → `INITIALIZE_COMPLETE` → `registerApp()` → `onProductConnect()`.
 * Jak w przykładzie, po odzyskaniu sieci ponawiamy rejestrację, jeśli wcześniej się nie udała.
 *
 * Wywołania SDK (callbacki) przychodzą z wątków SDK — stan wystawiamy jako `StateFlow`.
 */
class SdkInitializer(
    context: Context,
    private val logger: FlightLogger,
) {
    private val appContext = context.applicationContext
    private val started = AtomicBoolean(false)

    private val _state = MutableStateFlow<SdkState>(SdkState.NotStarted)
    val state: StateFlow<SdkState> = _state.asStateFlow()

    /** `true` od `INITIALIZE_COMPLETE` — od tej chwili wolno używać `KeyManager` i `MediaDataCenter`. */
    private val _initialized = MutableStateFlow(false)
    val initialized: StateFlow<Boolean> = _initialized.asStateFlow()

    /** Identyfikator połączonego produktu z `onProductConnect`, `null` po `onProductDisconnect`. */
    private val _productId = MutableStateFlow<Int?>(null)
    val productId: StateFlow<Int?> = _productId.asStateFlow()

    /** Wołać raz, z `Application.onCreate` (wątek główny). Kolejne wywołania są ignorowane. */
    fun initialize() {
        if (!started.compareAndSet(false, true)) return
        if (!MsdkBootstrap.isSupportedDevice) {
            // Emulator x86/x86_64: biblioteki DJI wywróciłyby proces (SIGSEGV) — zostajemy bez MSDK.
            _state.value = SdkState.RegistrationFailed(MsdkBootstrap.unsupportedReason, "UNSUPPORTED_ABI")
            logger.system("msdk_unsupported_device", LogLevel.ERROR, mapOf("abis" to android.os.Build.SUPPORTED_ABIS.joinToString()))
            return
        }
        _state.value = SdkState.Initializing
        logger.system("msdk_init_start")

        SDKManager.getInstance().init(appContext, object : SDKManagerCallback {
            override fun onInitProcess(event: DJISDKInitEvent, totalProcess: Int) {
                logger.system("msdk_init_event", fields = mapOf("event" to event.name, "progress" to totalProcess))
                if (event == DJISDKInitEvent.INITIALIZE_COMPLETE) {
                    _initialized.value = true
                    register("init_complete")
                }
            }

            override fun onRegisterSuccess() {
                _state.value = SdkState.Registered
                logger.system("msdk_registered")
            }

            override fun onRegisterFailure(error: IDJIError) {
                val reason = error.description()?.takeIf { it.isNotBlank() } ?: error.toString()
                _state.value = SdkState.RegistrationFailed(reason, error.errorCode())
                logger.system(
                    "msdk_register_failed",
                    LogLevel.ERROR,
                    mapOf("code" to error.errorCode(), "reason" to reason),
                )
            }

            override fun onProductConnect(productId: Int) {
                _productId.value = productId
                logger.system("product_connect", fields = mapOf("productId" to productId))
            }

            override fun onProductDisconnect(productId: Int) {
                _productId.value = null
                logger.system("product_disconnect", LogLevel.WARN, mapOf("productId" to productId))
            }

            override fun onProductChanged(productId: Int) {
                logger.system("product_changed", fields = mapOf("productId" to productId))
            }

            override fun onDatabaseDownloadProgress(current: Long, total: Long) {
                if (current == total) logger.system("msdk_database_ready", fields = mapOf("bytes" to total))
            }
        })

        DJINetworkManager.getInstance().addNetworkStatusListener { isAvailable ->
            if (isAvailable && _initialized.value && !SDKManager.getInstance().isRegistered) {
                register("network_available")
            }
        }
    }

    private fun register(reason: String) {
        _state.value = SdkState.Registering
        logger.system("msdk_register", fields = mapOf("reason" to reason))
        SDKManager.getInstance().registerApp()
    }
}

package com.example.djicontroller.msdk.connection

import com.example.djicontroller.core.logging.system
import com.example.djicontroller.core.model.ConnectionState
import com.example.djicontroller.core.port.Clock
import com.example.djicontroller.core.port.ConnectionPort
import com.example.djicontroller.core.port.FlightLogger
import com.example.djicontroller.core.port.LogLevel
import com.example.djicontroller.msdk.common.asFlow
import dji.sdk.keyvalue.key.FlightControllerKey
import dji.sdk.keyvalue.key.KeyTools
import dji.sdk.keyvalue.key.ProductKey
import dji.sdk.keyvalue.value.product.ProductType
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.launchIn
import kotlinx.coroutines.flow.runningFold
import kotlinx.coroutines.flow.stateIn

/**
 * Stan połączenia z dronem (sekcja 5.1) jako [ConnectionPort] dla :core.
 *
 * Łączy stan MSDK, `onProductConnect/Disconnect`, `ProductKey.KeyProductType` i
 * `FlightControllerKey.KeyConnection`. [ConnectionState.Connected] pojawia się dopiero,
 * gdy znany jest typ produktu — dzięki temu orkiestrator nie odrzuci drona tylko dlatego,
 * że typ dotarł chwilę po `onProductConnect`.
 *
 * Ponowne połączenie po włączeniu drona przez stację (R2) obsługuje samo MSDK; tutaj
 * logujemy czas od rozłączenia do połączenia, żeby zmierzyć czas uruchomienia drona.
 */
class DroneConnection(
    private val sdk: SdkInitializer,
    scope: CoroutineScope,
    private val logger: FlightLogger,
    private val clock: Clock,
) : ConnectionPort {

    private val productType: Flow<ProductType?> = sdk.whenInitialized { KeyTools.createKey(ProductKey.KeyProductType).asFlow() }
    private val fcConnected: Flow<Boolean?> = sdk.whenInitialized { KeyTools.createKey(FlightControllerKey.KeyConnection).asFlow() }

    override val state: StateFlow<ConnectionState> =
        combine(sdk.state, sdk.productId, productType, fcConnected) { sdkState, productId, type, fc ->
            when (sdkState) {
                SdkState.NotStarted, SdkState.Initializing -> ConnectionState.Initializing
                SdkState.Registering -> ConnectionState.Registering
                is SdkState.RegistrationFailed -> ConnectionState.RegistrationFailed(sdkState.reason, sdkState.code)
                SdkState.Registered -> when {
                    productId == null || fc != true -> ConnectionState.Disconnected
                    type == null || type.name == "UNKNOWN" -> ConnectionState.Disconnected
                    else -> ConnectionState.Connected(
                        productId = productId,
                        productType = type.name,
                        supported = type == ProductType.DJI_MINI_4_PRO,
                    )
                }
            }
        }
            .distinctUntilChanged()
            .stateIn(scope, SharingStarted.Eagerly, ConnectionState.Initializing)

    init {
        // Log zmian połączenia z czasem od ostatniego rozłączenia (pomiar do testu R2).
        state
            .runningFold(Pair<ConnectionState?, Long?>(null, null)) { (previous, disconnectedAt), current ->
                val now = clock.monotonicMs()
                when {
                    current is ConnectionState.Connected && previous !is ConnectionState.Connected -> {
                        logger.system(
                            "drone_connected",
                            fields = mapOf(
                                "product" to current.productType,
                                "supported" to current.supported,
                                "msSinceDisconnect" to disconnectedAt?.let { now - it },
                            ),
                        )
                        if (!current.supported) {
                            logger.system("drone_unsupported", LogLevel.ERROR, mapOf("product" to current.productType))
                        }
                        Pair(current, null)
                    }
                    current !is ConnectionState.Connected && previous is ConnectionState.Connected -> {
                        logger.system("drone_disconnected", LogLevel.WARN, mapOf("state" to current.toString()))
                        Pair(current, now)
                    }
                    else -> Pair(current, disconnectedAt)
                }
            }
            .launchIn(scope)
    }
}

/**
 * Strumień zależny od SDK: przed `INITIALIZE_COMPLETE` emituje `null` i nie dotyka `KeyManager`,
 * potem przełącza się na [factory]. Fabryka tworzy klucz leniwie, dopiero gdy SDK jest gotowe.
 */
@OptIn(ExperimentalCoroutinesApi::class)
fun <T> SdkInitializer.whenInitialized(factory: () -> Flow<T?>): Flow<T?> =
    initialized.flatMapLatest { ready -> if (ready) factory() else flowOf(null) }

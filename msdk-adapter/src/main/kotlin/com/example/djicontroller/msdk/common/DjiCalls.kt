package com.example.djicontroller.msdk.common

import com.example.djicontroller.core.model.CommandResult
import dji.sdk.keyvalue.key.DJIKey
import dji.v5.common.callback.CommonCallbacks
import dji.v5.common.error.IDJIError
import dji.v5.manager.KeyManager
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withTimeoutOrNull
import kotlin.coroutines.resume

/** Domyślny limit czasu na odpowiedź SDK — callback nie zawsze przychodzi po utracie łącza. */
internal const val DEFAULT_ACTION_TIMEOUT_MS = 10_000L

/**
 * Strumień wartości klucza (sekcja 5.2). Wzorzec wywołania jak w `MediaVM` / `PerceptionVM` z przykładu DJI:
 * `KeyManager.listen(key, holder, getOnce = true) { old, new -> }`.
 *
 * Każdy kolektor dostaje własnego `holder`, a `awaitClose` anuluje nasłuch tylko tego klucza
 * i tylko tego holdera — nie zrywa nasłuchów innych modułów na tym samym kluczu.
 * `null` oznacza, że SDK nie zna wartości (np. po rozłączeniu komponentu).
 */
fun <T> DJIKey<T>.asFlow(): Flow<T?> = callbackFlow {
    val key = this@asFlow
    val holder = Any()
    KeyManager.getInstance().listen(key, holder, true) { _, newValue -> trySend(newValue) }
    awaitClose { KeyManager.getInstance().cancelListen(key, holder) }
}

/** Akcja klucza bez parametru (np. `KeyStartTakeoff`) jako funkcja `suspend`. */
suspend fun <R> DJIKey.ActionKey<*, R>.perform(timeoutMs: Long = DEFAULT_ACTION_TIMEOUT_MS): CommandResult =
    withTimeoutOrNull(timeoutMs) {
        suspendCancellableCoroutine { cont ->
            KeyManager.getInstance().performAction(this@perform, object : CommonCallbacks.CompletionCallbackWithParam<R> {
                override fun onSuccess(result: R?) {
                    if (cont.isActive) cont.resume(CommandResult.Success)
                }

                override fun onFailure(error: IDJIError) {
                    if (cont.isActive) cont.resume(error.toFailure())
                }
            })
        }
    } ?: CommandResult.Failure("Brak odpowiedzi SDK w ciągu ${timeoutMs / 1000} s", "TIMEOUT")

/** Akcja klucza z parametrem (np. `KeyRotateByAngle`) jako funkcja `suspend`. */
suspend fun <P, R> DJIKey.ActionKey<P, R>.perform(param: P, timeoutMs: Long = DEFAULT_ACTION_TIMEOUT_MS): CommandResult =
    withTimeoutOrNull(timeoutMs) {
        suspendCancellableCoroutine { cont ->
            KeyManager.getInstance().performAction(this@perform, param, object : CommonCallbacks.CompletionCallbackWithParam<R> {
                override fun onSuccess(result: R?) {
                    if (cont.isActive) cont.resume(CommandResult.Success)
                }

                override fun onFailure(error: IDJIError) {
                    if (cont.isActive) cont.resume(error.toFailure())
                }
            })
        }
    } ?: CommandResult.Failure("Brak odpowiedzi SDK w ciągu ${timeoutMs / 1000} s", "TIMEOUT")

/** Ustawienie wartości klucza (np. `KeyFailsafeAction`) jako funkcja `suspend`. */
suspend fun <P> DJIKey<P>.set(value: P, timeoutMs: Long = DEFAULT_ACTION_TIMEOUT_MS): CommandResult =
    withTimeoutOrNull(timeoutMs) {
        suspendCancellableCoroutine { cont ->
            KeyManager.getInstance().setValue(this@set, value, object : CommonCallbacks.CompletionCallback {
                override fun onSuccess() {
                    if (cont.isActive) cont.resume(CommandResult.Success)
                }

                override fun onFailure(error: IDJIError) {
                    if (cont.isActive) cont.resume(error.toFailure())
                }
            })
        }
    } ?: CommandResult.Failure("Brak odpowiedzi SDK w ciągu ${timeoutMs / 1000} s", "TIMEOUT")

fun IDJIError.toFailure(): CommandResult.Failure =
    CommandResult.Failure(reason = description()?.takeIf { it.isNotBlank() } ?: toString(), code = errorCode())

/**
 * Metoda menedżera MSDK z `CommonCallbacks.CompletionCallback` (np. `enableVirtualStick`,
 * `enableSimulator`) jako funkcja `suspend` z limitem czasu.
 */
suspend fun awaitCompletion(
    timeoutMs: Long = DEFAULT_ACTION_TIMEOUT_MS,
    call: (CommonCallbacks.CompletionCallback) -> Unit,
): CommandResult =
    withTimeoutOrNull(timeoutMs) {
        suspendCancellableCoroutine { cont ->
            try {
                call(object : CommonCallbacks.CompletionCallback {
                    override fun onSuccess() {
                        if (cont.isActive) cont.resume(CommandResult.Success)
                    }

                    override fun onFailure(error: IDJIError) {
                        if (cont.isActive) cont.resume(error.toFailure())
                    }
                })
            } catch (e: RuntimeException) {
                if (cont.isActive) cont.resume(CommandResult.Failure(e.message ?: e.toString(), "EXCEPTION"))
            }
        }
    } ?: CommandResult.Failure("Brak odpowiedzi SDK w ciągu ${timeoutMs / 1000} s", "TIMEOUT")


package com.storytellerf.summer.data.llmd

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.ServiceConnection
import android.os.IBinder
import android.os.RemoteException
import com.storytellerf.llmd.ipc.ILlmdChatCallback
import com.storytellerf.llmd.ipc.ILlmdService
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.withTimeoutOrNull
import org.json.JSONObject

class LlmdServiceConnection(context: Context) {
    private val appContext = context.applicationContext
    private val bindingScope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private val bindingMutex = Mutex()
    private var service: ILlmdService? = null
    private var bound = false
    private var boundTarget: LlmdTarget? = null
    private var pendingBinding: CompletableDeferred<ILlmdService>? = null

    private val connection = object : ServiceConnection {
        override fun onServiceConnected(name: ComponentName?, binder: IBinder?) {
            bindingScope.launch {
                bindingMutex.withLock {
                    if (!bound || name?.packageName != boundTarget?.packageName) return@withLock

                    val connectedService = ILlmdService.Stub.asInterface(binder)
                    service = connectedService
                    pendingBinding?.complete(connectedService)
                    pendingBinding = null
                }
            }
        }

        override fun onServiceDisconnected(name: ComponentName?) {
            bindingScope.launch {
                clearBindingIfCurrentTarget(
                    name,
                    LlmdException("Local llmd IPC service disconnected"),
                )
            }
        }

        override fun onBindingDied(name: ComponentName?) {
            bindingScope.launch {
                clearBindingIfCurrentTarget(
                    name,
                    LlmdException("Local llmd IPC service binding died"),
                )
            }
        }
    }

    suspend fun health(target: LlmdTarget): String {
        return requestIpc(target) { callback ->
            requireService(target).healthAsync(callback)
        }
    }

    suspend fun models(target: LlmdTarget): String = requestIpc(target) { callback ->
        requireService(target).listModelsAsync(callback)
    }

    suspend fun chatCompletion(target: LlmdTarget, requestJson: String): String {
        awaitEngineReady(target)
        return requestIpc(target) { callback ->
            requireService(target).chatCompletionAsync(requestJson, callback)
        }
    }

    suspend fun activeModel(target: LlmdTarget): String {
        val response = awaitEngineReady(target)
        if (response.isNull("activeModel") || response.optString("activeModel").isBlank()) {
            throw LlmdRecognitionException("Select and start a model in LLMD; no active model was reported")
        }
        return response.getString("activeModel")
    }

    private suspend fun awaitEngineReady(target: LlmdTarget): JSONObject =
        withTimeoutOrNull(REQUEST_TIMEOUT_MILLIS) { pollEngineReady(target) }
            ?: throw LlmdRecognitionException("LLMD engine initialization timed out; check model status in LLMD and try again")

    private suspend fun pollEngineReady(target: LlmdTarget): JSONObject {
        while (true) {
            val response = JSONObject(health(target))
            response.optJSONObject("error")?.let { error ->
                if (error.optString("type") == "authorization_required") throw LlmdAuthorizationException()
                throw llmdRecognitionError(error.optString("type"), error.optString("message"))
            }
            if (response.has("serviceEnabled") && !response.getBoolean("serviceEnabled") && response.optString("engineState") != "error") {
                throw LlmdRecognitionException("LLMD service is off; open LLMD, choose a model and switch the service on")
            }
            if (!response.has("engineReady") || response.getBoolean("engineReady")) return response
            if (response.optString("engineState") == "error") {
                throw llmdRecognitionError("llmd_error", response.optString("engineError"))
            }
            delay(500)
        }
    }

    private suspend fun requestIpc(
        target: LlmdTarget,
        call: suspend (ILlmdChatCallback) -> Unit,
    ): String =
        try {
            requestAsync(call)
        } catch (error: RemoteException) {
            clearBindingIfCurrentTarget(target, error)
            throw LlmdException("Local llmd IPC request failed: ${error.message.orEmpty()}", error)
        }

    private suspend fun requestAsync(call: suspend (ILlmdChatCallback) -> Unit): String {
        val response = CompletableDeferred<String>()
        val callback = object : ILlmdChatCallback.Stub() {
            override fun onComplete(responseJson: String) {
                response.complete(responseJson)
            }
        }
        try {
            call(callback)
        } catch (error: RemoteException) {
            response.completeExceptionally(error)
        }

        return try {
            withTimeout(REQUEST_TIMEOUT_MILLIS) { response.await() }
                .takeIf(String::isNotEmpty)
                ?: throw LlmdException("Local llmd IPC returned an empty response")
        } catch (error: TimeoutCancellationException) {
            throw LlmdException("Timed out waiting for local llmd IPC response", error)
        }
    }

    private suspend fun requireService(target: LlmdTarget): ILlmdService {
        val binding = bindingMutex.withLock {
            if (boundTarget != target) {
                clearBindingLocked(LlmdException("Local llmd IPC target changed"))
            }
            service?.let { return it }
            pendingBinding ?: startBindingLocked(target)
        }
        return try {
            withTimeout(BIND_TIMEOUT_MILLIS) { binding.await() }
        } catch (error: TimeoutCancellationException) {
            clearPendingBinding(binding)
            throw LlmdException("Timed out waiting for local llmd IPC service", error)
        }
    }

    private fun startBindingLocked(target: LlmdTarget): CompletableDeferred<ILlmdService> {
        val binding = CompletableDeferred<ILlmdService>()
        pendingBinding = binding
        val intent = Intent(ACTION_BIND_IPC)
            .setComponent(ComponentName(target.packageName, LlmdTarget.SERVICE_CLASS_NAME))
        // Inference is part of the foreground caller's work; ordinary bindings only grant
        // visible-process priority, which some OEM memory managers reclaim during inference.
        if (appContext.bindService(intent, connection, Context.BIND_AUTO_CREATE or Context.BIND_IMPORTANT)) {
            bound = true
            boundTarget = target
        } else {
            pendingBinding = null
            binding.completeExceptionally(
                LlmdException("Local llmd ${target.displayName} IPC service is unavailable"),
            )
        }
        return binding
    }

    private suspend fun clearPendingBinding(binding: CompletableDeferred<ILlmdService>) {
        bindingMutex.withLock {
            if (pendingBinding === binding) {
                clearBindingLocked(LlmdException("Timed out waiting for local llmd IPC service"))
            }
        }
    }

    private suspend fun clearBinding(cause: Throwable) {
        bindingMutex.withLock {
            clearBindingLocked(cause)
        }
    }

    private suspend fun clearBindingIfCurrentTarget(name: ComponentName?, cause: Throwable) {
        bindingMutex.withLock {
            if (name?.packageName == boundTarget?.packageName) {
                clearBindingLocked(cause)
            }
        }
    }

    private suspend fun clearBindingIfCurrentTarget(target: LlmdTarget, cause: Throwable) {
        bindingMutex.withLock {
            if (target == boundTarget) {
                clearBindingLocked(cause)
            }
        }
    }

    fun close() {
        val closeCause = LlmdException("Local llmd IPC service closed")
        if (bindingMutex.tryLock()) {
            try {
                clearBindingLocked(closeCause)
            } finally {
                bindingMutex.unlock()
            }
            bindingScope.cancel()
        } else {
            bindingScope.launch {
                clearBinding(closeCause)
                bindingScope.cancel()
            }
        }
    }

    private fun clearBindingLocked(cause: Throwable) {
        service = null
        pendingBinding?.completeExceptionally(cause)
        pendingBinding = null
        if (bound) {
            runCatching { appContext.unbindService(connection) }
            bound = false
        }
        boundTarget = null
    }

    companion object {
        const val ACTION_AUTHORIZE_CALLER = "com.storytellerf.llmd.action.AUTHORIZE_CALLER"
        const val ACTION_BIND_IPC = "com.storytellerf.llmd.action.BIND_IPC"
        const val EXTRA_CALLER_PACKAGE = "caller_package"
        private const val BIND_TIMEOUT_MILLIS = 10_000L
        private const val REQUEST_TIMEOUT_MILLIS = 120_000L
    }
}

class LlmdException(message: String, cause: Throwable? = null) : Exception(message, cause)

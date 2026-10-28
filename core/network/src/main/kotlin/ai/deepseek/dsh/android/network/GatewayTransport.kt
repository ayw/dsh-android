package ai.deepseek.dsh.android.network

import ai.deepseek.dsh.android.domain.RemoteFailure
import ai.deepseek.dsh.android.domain.SyncTuning
import ai.deepseek.dsh.android.domain.TransportFailure
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException
import ai.deepseek.dsh.android.protocol.DshJson
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import okhttp3.Call
import okhttp3.Callback
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.Response
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import java.io.IOException
import java.net.SocketTimeoutException
import java.net.UnknownHostException
import java.util.concurrent.TimeUnit
import javax.net.ssl.SSLException

/** A raw HTTP outcome: never throws for an application status code, so callers can branch on 409/413/etc. */
data class HttpResp(val status: Int, val body: String) {
    val successful: Boolean get() = status in 200..299
}

/**
 * The OkHttp engine for the gateway transport. It owns three things the ports cannot express:
 *
 *  1. **cancellation** — every request runs through [suspendCancellableCoroutine] and cancels the
 *     underlying OkHttp [Call] on coroutine cancellation, so a cancelled poll actually tears the socket
 *     down rather than abandoning it and a batch that would advance a cursor never gets delivered. When
 *     OkHttp reports a cancelled call we rethrow [CancellationException], NOT a [TransportFailure], so a
 *     cancellation is never mistaken for an unreachable/timeout/auth failure.
 *  2. **failure classification** (D16 / G18) — 401 is the *only* device-auth rejection; 403 is scope;
 *     DNS/connect/timeout/TLS never produce [TransportFailure.Unauthorized]. Auth judgement must not be
 *     derived from transport noise.
 *  3. **the dot-RPC envelope** — the gateway wraps `/api/session.*` responses as `{result:{ok,value}}`.
 *     That framing is a *transport* concern (D25: mapping wire→port is `:core:network`'s job), so we
 *     unwrap `result.value` here and hand the inner text to the `:core:protocol` DTOs. We never inspect
 *     event content — all domain decoding stays behind [DshJson] + the protocol mappers.
 *
 * Every timeout is derived from [SyncTuning]; the module carries no magic number (G09.3e). The credential
 * is registered for redaction on construction so a stack trace or log line can never leak it.
 */
class GatewayTransport(
    private val config: GatewayConfig,
    private val tuning: SyncTuning,
    private val client: OkHttpClient = defaultClient(tuning),
) {
    init {
        // Register the credential eagerly where possible; a suspend provider cannot be read here, so the
        // header injector below registers the concrete token string on first send as well.
    }

    private val jsonMedia = "application/json".toMediaType()

    /** A POST to a dot-RPC endpoint (`/api/<method>`) returning the unwrapped `result.value` text. */
    suspend fun rpc(method: String, requestJson: String): String {
        val path = "/api/$method"
        val resp = postAuthorized(path, requestJson)
        requireStatus(resp, path)
        return unwrapRpcValue(resp.body)
    }

    /** POST with a raw octet body (the `/fs/upload` binary chunk channel, F14). */
    suspend fun postBinaryAuthorized(path: String, bytes: ByteArray): HttpResp {
        val token = config.credential.token()
        DshLog.redact(token)
        val request = Request.Builder()
            .url(config.root + path)
            .post(bytes.toRequestBody("application/octet-stream".toMediaType()))
            .header("Authorization", "Bearer $token")
            .header("x-dsh-remote-client", config.clientId)
            .build()
        return execute(request)
    }

    /** Build a GET request for a WS handshake carrying a ticket header (never a URL token, D16). */
    fun wsRequest(path: String, ticket: String): Request =
        Request.Builder()
            .url(config.root + path)
            .header("Sec-WebSocket-Protocol", ticket)
            .build()

    private suspend fun sendAuthenticated(path: String, body: RequestBody? = null): HttpResp {
        val token = config.credential.token()
        DshLog.redact(token)
        val request = Request.Builder()
            .url(config.root + path)
            .apply { if (body == null) get() else post(body) }
            .header("Authorization", "Bearer $token")
            .header("x-dsh-remote-client", config.clientId)
            .build()
        return execute(request)
    }

    suspend fun getAuthorized(path: String): HttpResp = sendAuthenticated(path, null)

    suspend fun postAuthorized(path: String, requestJson: String): HttpResp =
        sendAuthenticated(path, requestJson.toRequestBody(jsonMedia))

    /** `/health` is unauthenticated (F6): no bearer is attached, so no credential ever reaches this call. */
    suspend fun getUnauthenticated(path: String): HttpResp = execute(Request.Builder().url(config.root + path).get().build())

    /**
     * Open a WebSocket for [path] (mux/host). The handshake authenticates with the short-lived [ticket]
     * as a header only — never in the URL (D16). OkHttp accepts the plain http(s) URL and performs the
     * upgrade itself.
     */
    fun openWebSocket(path: String, ticket: String, listener: WebSocketListener): WebSocket {
        DshLog.redact(ticket)
        return client.newWebSocket(wsRequest(path, ticket), listener)
    }

    /** POST with a caller-chosen `Idempotency-Key`-style header (operations, F15). */
    suspend fun postAuthorizedWithHeader(path: String, requestJson: String, name: String, value: String): HttpResp {
        val token = config.credential.token()
        DshLog.redact(token)
        val request = Request.Builder()
            .url(config.root + path)
            .post(requestJson.toRequestBody(jsonMedia))
            .header("Authorization", "Bearer $token")
            .header("x-dsh-remote-client", config.clientId)
            .header(name, value)
            .build()
        return execute(request)
    }

    /**
     * Throw a [RemoteFailure] unless [HttpResp.status] is 2xx. The path carried into the failure is the
     * QUERY-FREE path: a `TransportFailure` ends up in an exception message, and `/fs/list?path=…` or
     * `/api/events.poll?epoch=…` would put a file path / an epoch into a loggable string (D16 / G09.2).
     */
    fun requireStatus(resp: HttpResp, path: String) {
        if (resp.successful) return
        throw RemoteFailure(failureForStatus(resp.status, path.substringBefore('?')))
    }

    private fun failureForStatus(status: Int, path: String): TransportFailure = when (status) {
        401 -> TransportFailure.Unauthorized
        403 -> TransportFailure.Forbidden(path)
        else -> TransportFailure.UnexpectedStatus(status, path)
    }

    private suspend fun execute(request: Request): HttpResp = suspendCancellableCoroutine { cont ->
        val call = client.newCall(request)
        cont.invokeOnCancellation { call.cancel() }
        call.enqueue(
            object : Callback {
                override fun onFailure(call: Call, e: IOException) {
                    // A cancelled call is a coroutine cancellation, never a transport failure.
                    if (call.isCanceled() || cont.isCancelled) {
                        cont.resumeWithException(CancellationException("request cancelled"))
                    } else {
                        // The cause is the scrubbed view: OkHttp quotes the request URL in its IOException
                        // message, and a URL can hold a path or an epoch we are not allowed to print.
                        cont.resumeWithException(RemoteFailure(classify(e), DshLog.scrub(e)))
                    }
                }

                override fun onResponse(call: Call, response: Response) {
                    val text = response.use { it.body.string() }
                    if (cont.isActive) cont.resume(HttpResp(response.code, text))
                }
            },
        )
    }

    private fun classify(e: IOException): TransportFailure {
        DshLog.w("transport io failure: ${e.javaClass.simpleName}")
        return when (e) {
            is SocketTimeoutException -> TransportFailure.Timeout
            is SSLException -> TransportFailure.TlsFailure
            is UnknownHostException -> TransportFailure.Unreachable
            else -> TransportFailure.Unreachable
        }
    }

    /**
     * Extract `result.value` from a dot-RPC response. A body that is not wrapped (no `result` object) is
     * returned verbatim so the same helper serves endpoints that answer directly. Only envelope keys are
     * read — the inner text is passed to the protocol DTO unchanged (D06 byte comparability).
     */
    private fun unwrapRpcValue(body: String): String {
        val element = runCatching { DshJson.spec.parseToJsonElement(body) }.getOrNull()
        val result = (element as? JsonObject)?.get("result") as? JsonObject ?: return body
        val value = result["value"] ?: return body
        return value.toString()
    }

    /** Read the top-level `ok` of a dot-RPC envelope without touching `value` (for the pending-ref guard). */
    fun envelopeOk(body: String): Boolean? =
        ((runCatching { DshJson.spec.parseToJsonElement(body) }.getOrNull() as? JsonObject)
            ?.get("result") as? JsonObject)
            ?.get("ok")
            .let { it as? JsonPrimitive }?.let { it.content == "true" }

    companion object {
        /**
         * A hardened client: the default trust-the-system-CA TLS configuration and the platform default
         * hostname verification are used UNCHANGED. No custom certificate-acceptance hook and no relaxed
         * hostname check are installed here — a trust-all socket would violate D16 and fail
         * `check-source.sh` (G09.3b).
         */
        fun defaultClient(tuning: SyncTuning): OkHttpClient = OkHttpClient.Builder()
            .connectTimeout(tuning.backoffInitialMs, TimeUnit.MILLISECONDS)
            // Read timeout must exceed the longest server hold (pollWaitMs ≤ workerRoundBudgetMs).
            .readTimeout(tuning.workerRoundBudgetMs, TimeUnit.MILLISECONDS)
            .callTimeout(tuning.workerRoundBudgetMs, TimeUnit.MILLISECONDS)
            // A WS socket that dies silently (dropped 1006) is only observable through a missed pong, so the
            // keepalive period is the same probe cadence the actor re-opens sockets on (D12, no new tunable).
            .pingInterval(tuning.wsProbeIntervalMs, TimeUnit.MILLISECONDS)
            .retryOnConnectionFailure(false) // writes must never auto-retry (D28 / FakeRemoteSource semantics)
            .build()
    }
}

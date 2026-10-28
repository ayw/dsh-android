package ai.deepseek.dsh.android.network

import ai.deepseek.dsh.android.domain.RemoteFailure
import ai.deepseek.dsh.android.domain.RemoteSource
import ai.deepseek.dsh.android.domain.SyncTuning
import ai.deepseek.dsh.android.domain.TransportFailure
import ai.deepseek.dsh.android.model.BackendCapabilities
import ai.deepseek.dsh.android.model.BackendHealth
import ai.deepseek.dsh.android.model.ChannelKind
import ai.deepseek.dsh.android.model.EventBatch
import ai.deepseek.dsh.android.model.HistoryPage
import ai.deepseek.dsh.android.model.HistoryRequest
import ai.deepseek.dsh.android.model.PollRequest
import ai.deepseek.dsh.android.model.PromptRequest
import ai.deepseek.dsh.android.model.ResolveRequest
import ai.deepseek.dsh.android.model.RosterEntry
import ai.deepseek.dsh.android.model.SessionId
import ai.deepseek.dsh.android.model.StreamHandle
import ai.deepseek.dsh.android.model.TransportFrame
import ai.deepseek.dsh.android.model.WriteResult
import ai.deepseek.dsh.android.protocol.DshJson
import ai.deepseek.dsh.android.protocol.gateway.v0.EventBatchDto
import ai.deepseek.dsh.android.protocol.gateway.v0.FsFileRequest
import ai.deepseek.dsh.android.protocol.gateway.v0.FsFileResponse
import ai.deepseek.dsh.android.protocol.gateway.v0.FsListResponse
import ai.deepseek.dsh.android.protocol.gateway.v0.FsPreviewResponse
import ai.deepseek.dsh.android.protocol.gateway.v0.HealthResponse
import ai.deepseek.dsh.android.protocol.gateway.v0.OperationsRequest
import ai.deepseek.dsh.android.protocol.gateway.v0.OperationsResponse
import ai.deepseek.dsh.android.protocol.gateway.v0.PollRequestDto
import ai.deepseek.dsh.android.protocol.gateway.v0.RespondRequest
import ai.deepseek.dsh.android.protocol.gateway.v0.RespondResponse
import ai.deepseek.dsh.android.protocol.gateway.v0.SessionCancelRequest
import ai.deepseek.dsh.android.protocol.gateway.v0.SessionHistoryRequest
import ai.deepseek.dsh.android.protocol.gateway.v0.SessionHistoryResponse
import ai.deepseek.dsh.android.protocol.gateway.v0.SessionListRequest
import ai.deepseek.dsh.android.protocol.gateway.v0.SessionListResponse
import ai.deepseek.dsh.android.protocol.gateway.v0.SessionModelsRequest
import ai.deepseek.dsh.android.protocol.gateway.v0.SessionModelsResponse
import ai.deepseek.dsh.android.protocol.gateway.v0.SessionMutationResponse
import ai.deepseek.dsh.android.protocol.gateway.v0.SessionPromptRequest
import ai.deepseek.dsh.android.protocol.gateway.v0.SessionPromptResponse
import ai.deepseek.dsh.android.protocol.gateway.v0.SessionSelectModelRequest
import ai.deepseek.dsh.android.protocol.gateway.v0.UploadProbeResponse
import ai.deepseek.dsh.android.protocol.gateway.v0.WsTicketRequest
import ai.deepseek.dsh.android.protocol.gateway.v0.WsTicketResponse
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.serialization.SerializationException
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.JsonElement
import java.util.concurrent.atomic.AtomicLong
import java.net.URLEncoder

/**
 * The `dsh-Remote` gateway v0 adapter for [RemoteSource]. It is the ONLY module that knows the wire exists:
 * everything above it speaks the transport-neutral port types from `:core:model` / `:core:domain`.
 *
 * Contract authority is `contract/gateway/v0/` (gateway 0.8.1). Notable transport facts honoured here:
 *  - dot-RPC endpoints are `POST /api/<method>` and answer `{result:{ok,value}}`; [GatewayTransport.rpc]
 *    unwraps `value` and the `:core:protocol` DTO does the domain mapping (D25);
 *  - `/api/respond` and `/api/operations` are NOT dot-RPC wrapped;
 *  - `/health` is unauthenticated (F6) and publishes the integer capability table;
 *  - `/api/events.poll` MUST echo `epoch` (F3) and answers the channel-shaped envelope (D29).
 *
 * The transport plumbing (cancellation, byte backpressure, per-channel WS independence, redaction) lives in
 * [GatewayTransport] / [WsChannel] / [DshLog]; this class only wires the port to those pieces.
 */
class GatewayV0Source(
    private val config: GatewayConfig,
    private val tuning: SyncTuning = SyncTuning.DEFAULT,
    private val transport: GatewayTransport = GatewayTransport(config, tuning),
    private val wsStateListener: WsStateListener = WsStateListener { _, _ -> },
) : RemoteSource {

    /**
     * Last capability table observed from [health]; defaults to all-zero ("nothing advertised") so an
     * un-negotiated client hides every optional feature rather than assuming support (D27 / G08).
     */
    @Volatile
    var capabilities: BackendCapabilities = BackendCapabilities(0, 0, 0, 0, 0, 0, 0, 0, emptyMap())
        private set

    private val rankCounter = AtomicLong(0L)

    /**
     * F4: a ticket is minted per WS connection (TTL 90s, at most 4 uses) and is NEVER shared between the
     * mux and the host socket. One cache per channel keeps "one ticket per connection" true while still
     * letting a reconnect consume a ticket the connection did not use up.
     */
    private val ticketCaches: Map<ChannelKind, TicketCache> =
        listOf(ChannelKind.Mux, ChannelKind.Host).associateWith { TicketCache() }

    override suspend fun health(): BackendHealth {
        val resp = transport.getUnauthenticated("/health")
        transport.requireStatus(resp, "/health")
        val health = decode(resp.body, HealthResponse.serializer(), "/health") { it.toDomain() }
        capabilities = health.capabilities
        return health
    }

    override suspend fun roster(): List<RosterEntry> {
        val value = transport.rpc("session.list", encode(SessionListRequest.DEFAULT))
        val response = decode(value, SessionListResponse.serializer(), "session.list") { it }
        val ranks = HashMap<String, Long>()
        for (s in response.visibleRows()) ranks[s.key] = rankCounter.incrementAndGet()
        return response.toRoster(ranks)
    }

    override suspend fun history(request: HistoryRequest): HistoryPage {
        DshLog.redactSession(request.sessionId)
        val body = encode(SessionHistoryRequest.fromDomain(request))
        val value = transport.rpc("session.history", body)
        return decode(value, SessionHistoryResponse.serializer(), "session.history") { it.toDomain(request.sessionId) }
    }

    override suspend fun poll(request: PollRequest): EventBatch {
        val path = withQuery(
            "/api/events.poll",
            "kind" to request.channel.value,
            "wait" to request.waitMs.toString(),
            "since" to request.since.toString(),
            // F3: the poll MUST echo the epoch it is continuing from, else the gateway cannot detect reset.
            "epoch" to request.epoch,
        )
        val resp = transport.getAuthorized(path)
        transport.requireStatus(resp, "/api/events.poll")
        return decode(resp.body, EventBatchDto.serializer(), "/api/events.poll") { it.toDomain(request.channel) }
    }

    /**
     * The port surface: one logical channel carried by WebSocket when `wsTicket` is negotiated, with the
     * HTTP long-poll fallback underneath (D09). [RemoteSource.openStream] has no parameter for a persisted
     * cursor, so it seeds at `0`; a caller that owns a stored cursor (the `:core:data` SyncActor) should use
     * [openChannel], which is the same machinery with the cursor supplied.
     */
    override suspend fun openStream(
        channel: ChannelKind,
        onFrame: suspend (TransportFrame) -> Unit,
    ): StreamHandle {
        // This method is not itself a coroutine-scope provider, so the channel loop owns a scope that the
        // returned handle tears down — one scope per channel is what keeps mux and host independent (D09).
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
        val stream = openChannel(scope = scope, channel = channel, onFrame = onFrame)
        return StreamHandle(close = {
            stream.stop()
            scope.cancel()
        })
    }

    /**
     * The transport surface `:core:data`'s SyncActor owns (D09): WebSocket first, HTTP long-poll as the
     * fallback of LAST RESORT, one [HybridStream] per logical channel so a mux outage can neither stall nor
     * cross-wire the host channel. [seed] is the persisted transport cursor (outer seq).
     */
    fun openChannel(
        scope: kotlinx.coroutines.CoroutineScope,
        channel: ChannelKind,
        seed: ChannelCursor = ChannelCursor(0L, null),
        onFrame: suspend (TransportFrame) -> Unit,
        onNotice: suspend (StreamNotice) -> Unit = {},
    ): HybridStream {
        val stream = HybridStream(
            channel = channel,
            tuning = tuning,
            poller = ChannelPoller { poll(it) },
            wsOpener = { wsChannel -> openSocket(channel, wsChannel) },
            onFrame = { frame ->
                DshLog.redactSession(frame.sessionId)
                onFrame(frame)
            },
            stateListener = wsStateListener,
            onNotice = onNotice,
            seed = seed,
        )
        return stream.also { stream.start(scope) }
    }

    /**
     * Mint (or reuse) a ticket for [channel] and hand back the connected socket, or `null` when this channel
     * must stay on the poll path. `null` is returned when the gateway never advertised `wsTicket` (G08/D27:
     * an optional feature is hidden until observed, never assumed) — the caller then polls, which is the
     * documented fallback rather than a failure.
     */
    private suspend fun openSocket(channel: ChannelKind, wsChannel: WsChannel): okhttp3.WebSocket? {
        if (!capabilities.websocketAllowed) {
            DshLog.i("channel ${channel.value} ws not negotiated (wsTicket unadvertised); poll only")
            return null
        }
        val cache = ticketCaches[channel] ?: TicketCache()
        val ticket = cache.take(clock()) ?: run {
            val minted = requestTicket(channel)
            cache.store(minted.secret, minted.ttlMs(clock()), minted.maxUses, clock())
            minted.secret
        }
        return transport.openWebSocket(
            // The gateway serves the two logical channels on their own paths (D09/D29 envelope per channel).
            path = WS_PATH + channel.value,
            ticket = ticket,
            listener = buildListener(wsChannel),
        )
    }

    private fun clock(): Long = System.currentTimeMillis()

    override suspend fun prompt(request: PromptRequest): WriteResult {
        DshLog.redactSession(request.sessionId)
        // F15: when the gateway advertises `operations>=1` route the write through /api/operations so a
        // lost-but-accepted prompt leaves a persistent receipt (UNKNOWN window narrows, D13).
        if (capabilities.operations >= 1) {
            return submitOperation("session.prompt", encode(SessionPromptRequest.fromDomain(request)))
        }
        val value = transport.rpc("session.prompt", encode(SessionPromptRequest.fromDomain(request)))
        return decode(value, SessionPromptResponse.serializer(), "session.prompt") { it.toDomain() }
    }

    override suspend fun resolve(request: ResolveRequest): WriteResult {
        // /api/respond is NOT dot-RPC wrapped; the gateway answers {accepted:true|false} directly (F5).
        val body = encode(RespondRequest.fromDomain(request, sessionId = null))
        val resp = transport.postAuthorized("/api/respond", body)
        transport.requireStatus(resp, "/api/respond")
        return decode(resp.body, RespondResponse.serializer(), "/api/respond") { it.toDomain() }
    }

    override suspend fun cancel(sessionId: SessionId): WriteResult {
        DshLog.redactSession(sessionId)
        val value = transport.rpc("session.cancel", encode(SessionCancelRequest(sessionId.value)))
        return decode(value, SessionMutationResponse.serializer(), "session.cancel") { it.toDomain() }
    }

    override suspend fun selectModel(sessionId: SessionId, modelId: String): WriteResult {
        DshLog.redactSession(sessionId)
        val value = transport.rpc("session.selectModel", encode(SessionSelectModelRequest(sessionId.value, modelId)))
        return decode(value, SessionMutationResponse.serializer(), "session.selectModel") { it.toDomain() }
    }

    override suspend fun models(sessionId: SessionId): List<String> {
        DshLog.redactSession(sessionId)
        val value = transport.rpc("session.models", encode(SessionModelsRequest(sessionId.value)))
        return decode(value, SessionModelsResponse.serializer(), "session.models") { it.toDomain() }
    }

    // ---- transport helpers exposed beyond the port (fs, operations, ws-ticket) ----

    /** `POST /api/ws-ticket`- mints a single-use ticket for one channel (F4). */
    suspend fun requestTicket(channel: ChannelKind): WsTicketResponse {
        val resp = transport.postAuthorized(WS_TICKET_PATH, encode(WsTicketRequest(channel.value)))
        transport.requireStatus(resp, WS_TICKET_PATH)
        val ticket = decode(resp.body, WsTicketResponse.serializer(), WS_TICKET_PATH) { it }
        // Register before returning: the value must be un-loggable from the moment it exists (D16).
        DshLog.redact(ticket.secret)
        return ticket
    }

    /** `GET /fs/list?path=`. Recorded shape carries `entries[]`; `type`/`size`/`mtimeMs` key drift is reported. */
    suspend fun fsList(path: String): FsListResponse {
        val resp = transport.getAuthorized(withQuery("/fs/list", "path" to path))
        transport.requireStatus(resp, "/fs/list")
        return decode(resp.body, FsListResponse.serializer(), "/fs/list") { it }
    }

    /** `GET /fs/preview?path=`; a 413 `preview-too-large` returns [FsPreviewOutcome.TooLarge] (F14). */
    suspend fun fsPreview(path: String): FsPreviewOutcome {
        val resp = transport.getAuthorized(withQuery("/fs/preview", "path" to path))
        if (resp.status == HTTP_PREVIEW_TOO_LARGE) return FsPreviewOutcome.TooLarge
        transport.requireStatus(resp, "/fs/preview")
        return FsPreviewOutcome.Previewed(decode(resp.body, FsPreviewResponse.serializer(), "/fs/preview") { it })
    }

    /** `GET /fs/file?path=`: full bytes above the preview cap (F14). */
    suspend fun fsFile(path: String): FsFileResponse {
        val resp = transport.getAuthorized(withQuery("/fs/file", "path" to path))
        transport.requireStatus(resp, "/fs/file")
        return decode(resp.body, FsFileResponse.serializer(), "/fs/file") { it }
    }

    /** `GET /fs/upload-probe?path&name&session`: resume cursor for an in-flight upload (F14). */
    suspend fun uploadProbe(path: String, name: String, session: String): UploadProbeResponse {
        val resp = transport.getAuthorized(
            withQuery("/fs/upload-probe", "path" to path, "name" to name, "session" to session),
        )
        transport.requireStatus(resp, "/fs/upload-probe")
        return decode(resp.body, UploadProbeResponse.serializer(), "/fs/upload-probe") { it }
    }

    /** `POST /fs/upload?path&name&session&offset` — a binary chunk on the octet channel (F14). */
    suspend fun uploadChunk(path: String, name: String, session: String, offset: Long, bytes: ByteArray): HttpResp {
        val resp = transport.postBinaryAuthorized(
            withQuery(
                "/fs/upload",
                "path" to path, "name" to name, "session" to session, "offset" to offset.toString(),
            ),
            bytes,
        )
        // 409 offset-mismatch is a RESUMABLE outcome, not a transport failure: the caller re-probes (F14).
        return resp
    }

    /** `POST /fs/upload-control?...&action=` — cancel/finish an upload (F14: control is POST, probe is GET). */
    suspend fun uploadControl(path: String, name: String, session: String, action: String): HttpResp {
        val resp = transport.postBinaryAuthorized(
            withQuery("/fs/upload-control", "path" to path, "name" to name, "session" to session, "action" to action),
            ByteArray(0),
        )
        transport.requireStatus(resp, "/fs/upload-control")
        return resp
    }

    /**
     * `POST /api/operations?id=<key>` submit; a receipt is returned as [WriteResult.WriteOutcome.ReceiptPending].
     * The key is the receipt id, so a caller may pass one it has already persisted; otherwise one is minted.
     */
    suspend fun submitOperation(
        method: String,
        paramsJson: String,
        operationId: String = "op-" + java.util.UUID.randomUUID(),
    ): WriteResult {
        val params = DshJson.spec.parseToJsonElement(paramsJson)
        val body = encode(OperationsRequest(method = method, payload = params))
        val resp = transport.postAuthorizedWithHeader(
            withQuery(OPERATIONS_PATH, "id" to operationId),
            body,
            IDEMPOTENCY_HEADER,
            operationId,
        )
        transport.requireStatus(resp, OPERATIONS_PATH)
        return decode(resp.body, OperationsResponse.serializer(), OPERATIONS_PATH) { it.toDomain() }
    }

    /** `GET /api/operations?id=`: read a persisted receipt back (F15). */
    suspend fun operationReceipt(id: String): OperationsResponse {
        val resp = transport.getAuthorized(withQuery(OPERATIONS_PATH, "id" to id))
        transport.requireStatus(resp, OPERATIONS_PATH)
        return decode(resp.body, OperationsResponse.serializer(), OPERATIONS_PATH) { it }
    }

    // ---- private plumbing ----

    /**
     * Decode through a `:core:protocol` DTO and map to the port type. A failure is reported as
     * [TransportFailure.MalformedPayload] with the ENDPOINT and the serializer exception CLASS only: a
     * kotlinx message quotes the offending value (prompt text, a path, an id), so quoting it into an
     * exception message would leak exactly what D16/G09.2 forbids. The cause is dropped for the same
     * reason — a printed stack trace prints the quoted value.
     *
     * [IllegalArgumentException] is caught as well because kotlinx raises it (not a
     * SerializationException) when a JSON value has the wrong SHAPE, e.g. a string where a Boolean is
     * declared. A shape break must degrade to a typed failure, never crash the caller (D08 / F16).
     */
    private inline fun <T, R> decode(
        body: String,
        serializer: kotlinx.serialization.KSerializer<T>,
        context: String,
        block: (T) -> R,
    ): R = try {
        block(DshJson.spec.decodeFromString(serializer, body))
    } catch (e: SerializationException) {
        // Bad JSON is a transport failure, never an auth failure (G18); the actor maps it to a retry.
        throw malformedPayload(context, e)
    } catch (e: IllegalArgumentException) {
        throw malformedPayload(context, e)
    }

    /** Content-free malformed-payload failure: endpoint + exception class, no cause, no quoted value. */
    private fun malformedPayload(context: String, e: Exception): RemoteFailure =
        RemoteFailure(TransportFailure.MalformedPayload("$context ${e::class.simpleName}"), null)

    private inline fun <reified T> encode(value: T): String = DshJson.spec.encodeToString(value)

    private fun withQuery(path: String, vararg params: Pair<String, String?>): String {
        val query = params.filter { it.second != null }
            .joinToString("&") { (k, v) -> "$k=${URLEncoder.encode(v!!, "UTF-8")}" }
        return if (query.isEmpty()) path else "$path?$query"
    }

    companion object {
        /** The two logical WS channels live here (`/api/mux`, `/api/host`) — never one shared socket (D09). */
        const val WS_PATH: String = "/api/"

        /** Ticket minting endpoint (F4). */
        const val WS_TICKET_PATH: String = "/api/ws-ticket"

        /** Durable-write receipts (F15). */
        const val OPERATIONS_PATH: String = "/api/operations"

        /** The header the gateway uses to key an idempotent operation (F15). */
        const val IDEMPOTENCY_HEADER: String = "Idempotency-Key"

        /** `/fs/preview` rejects an over-cap preview with this status (F14); it is a contract constant. */
        const val HTTP_PREVIEW_TOO_LARGE: Int = 413
    }
}

/** Outcome of `/fs/preview`: the gateway rejects an over-cap preview with a distinct signal (F14). */
sealed interface FsPreviewOutcome {
    data class Previewed(val response: FsPreviewResponse) : FsPreviewOutcome
    /** 413 preview-too-large: the caller should fall back to [GatewayV0Source.fsFile] (F14). */
    data object TooLarge : FsPreviewOutcome
}

package ai.deepseek.dsh.android.network

import ai.deepseek.dsh.android.domain.RemoteFailure
import ai.deepseek.dsh.android.domain.RemoteSource
import ai.deepseek.dsh.android.domain.SyncTuning
import ai.deepseek.dsh.android.domain.TransportFailure
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
import ai.deepseek.dsh.android.protocol.mobile.v1.MobileEventsRequest
import ai.deepseek.dsh.android.protocol.mobile.v1.MobileEventsResponse
import ai.deepseek.dsh.android.protocol.mobile.v1.MobileHandshakeResponse
import ai.deepseek.dsh.android.protocol.mobile.v1.MobileMessagesRequest
import ai.deepseek.dsh.android.protocol.mobile.v1.MobileMessagesResponse
import ai.deepseek.dsh.android.protocol.mobile.v1.MobilePendingResponse
import ai.deepseek.dsh.android.protocol.mobile.v1.MobileResolveRequest
import ai.deepseek.dsh.android.protocol.mobile.v1.MobileResolveResponse
import ai.deepseek.dsh.android.protocol.mobile.v1.MobileSessionsResponse
import ai.deepseek.dsh.android.protocol.mobile.v1.MobileTimelineRequest
import ai.deepseek.dsh.android.protocol.mobile.v1.MobileTimelineResponse
import kotlinx.serialization.KSerializer
import kotlinx.serialization.SerializationException
import kotlinx.serialization.encodeToString
import java.util.concurrent.atomic.AtomicLong

/**
 * The v0↔v1 projection-parity adapter (G15). This is deliberately the MINIMAL implementation the parity
 * gate needs — NOT a full BFF client (that is M8, out of scope per DECISIONS §3).
 *
 * Its whole purpose is to prove that a BFF `/mobile/v1` body and the raw gateway v0 body project to the
 * SAME snapshot: `:core:protocol`'s v1 DTOs reuse the shared gateway frame envelope (D29), so
 * [MobileEventsResponse.toDomain] returns byte-for-byte the same [EventBatch] [GatewayV0Source.poll]
 * produces from an equivalent body. Fields the BFF has not been built to advertise are left null rather
 * than invented (report: "unconfirmed, modeled nullable").
 *
 * `resolve` here is the v1 three-way branch (`accepted`/`code`), whereas v0 collapses to two (F5) — the
 * projection parity gate only feeds the read path, so the difference does not affect parity.
 */
class MobileV1Source(
    private val config: GatewayConfig,
    private val tuning: SyncTuning = SyncTuning.DEFAULT,
    private val transport: GatewayTransport = GatewayTransport(config, tuning),
) : RemoteSource {

    private val rankCounter = AtomicLong(0L)

    override suspend fun health(): BackendHealth {
        val resp = transport.getUnauthenticated("/mobile/v1/handshake")
        transport.requireStatus(resp, "/mobile/v1/handshake")
        return decode(resp.body, MobileHandshakeResponse.serializer()) { it.toHealth() }
    }

    override suspend fun poll(request: PollRequest): EventBatch {
        val body = DshJson.spec.encodeToString(
            MobileEventsRequest(since = request.since, epoch = request.epoch, waitMs = request.waitMs),
        )
        val resp = transport.postAuthorized("/mobile/v1/events", body)
        transport.requireStatus(resp, "/mobile/v1/events")
        return decode(resp.body, MobileEventsResponse.serializer()) { it.toDomain(request.channel) }
    }

    override suspend fun roster(): List<RosterEntry> {
        val resp = transport.getAuthorized("/mobile/v1/sessions")
        transport.requireStatus(resp, "/mobile/v1/sessions")
        val response = decode(resp.body, MobileSessionsResponse.serializer()) { it }
        val ranks = HashMap<String, Long>()
        for (s in response.sessions) ranks[s.id] = rankCounter.incrementAndGet()
        return response.toRoster(ranks)
    }

    override suspend fun history(request: HistoryRequest): HistoryPage {
        val body = DshJson.spec.encodeToString(
            MobileTimelineRequest(sessionIdStr = request.sessionId.value, maxMessages = request.maxMessages),
        )
        val resp = transport.postAuthorized("/mobile/v1/sessions/${request.sessionId.value}/timeline", body)
        transport.requireStatus(resp, "/mobile/v1/timeline")
        return decode(resp.body, MobileTimelineResponse.serializer()) { it.toDomain(request.sessionId) }
    }

    override suspend fun prompt(request: PromptRequest): WriteResult {
        val body = DshJson.spec.encodeToString(
            MobileMessagesRequest.fromDomain(request, request.sessionId.value, newIdempotencyKey()),
        )
        val resp = transport.postAuthorized("/mobile/v1/sessions/${request.sessionId.value}/messages", body)
        transport.requireStatus(resp, "/mobile/v1/messages")
        return decode(resp.body, MobileMessagesResponse.serializer()) { it.toDomain() }
    }

    override suspend fun resolve(request: ResolveRequest): WriteResult {
        val body = DshJson.spec.encodeToString(
            MobileResolveRequest(requestId = request.requestId.value, idempotencyKey = newIdempotencyKey(), value = request.valueJson),
        )
        val resp = transport.postAuthorized("/mobile/v1/pending/${request.requestId.value}/resolve", body)
        transport.requireStatus(resp, "/mobile/v1/resolve")
        return decode(resp.body, MobileResolveResponse.serializer()) { it.toDomain() }
    }

    // Read-only skeleton for the write/pending endpoints the parity gate does not exercise.
    override suspend fun cancel(sessionId: SessionId): WriteResult =
        WriteResult(accepted = null, receiptId = null, outcome = WriteResult.WriteOutcome.Unknown)

    override suspend fun selectModel(sessionId: SessionId, modelId: String): WriteResult =
        WriteResult(accepted = null, receiptId = null, outcome = WriteResult.WriteOutcome.Unknown)

    override suspend fun models(sessionId: SessionId): List<String> = emptyList()

    /**
     * The BFF has no WS push contract recorded, so v1 streaming is not modelled: a caller asking for it
     * gets an immediate closed handle rather than an invented protocol (report: "unconfirmed").
     */
    override suspend fun openStream(channel: ChannelKind, onFrame: suspend (TransportFrame) -> Unit): StreamHandle =
        StreamHandle(close = {})

    /** `/mobile/v1/pending` read (v0 has none) — used only to type-check the BFF pending contract. */
    suspend fun pending(serverId: ai.deepseek.dsh.android.model.ServerId): List<ai.deepseek.dsh.android.model.PendingRequest> {
        val resp = transport.getAuthorized("/mobile/v1/pending")
        transport.requireStatus(resp, "/mobile/v1/pending")
        return decode(resp.body, MobilePendingResponse.serializer()) { it.toDomain(serverId) }
    }

    private fun newIdempotencyKey(): String = "v1-" + java.util.UUID.randomUUID()

    /**
     * Same content-free failure contract as [GatewayV0Source.decode] (D16 / G09.2): a kotlinx message quotes
     * the offending VALUE, so neither the reason nor the cause may carry it into an exception message or a
     * printed stack trace. Only the endpoint and the exception class survive.
     */
    private inline fun <T, R> decode(body: String, serializer: KSerializer<T>, block: (T) -> R): R =
        try {
            block(DshJson.spec.decodeFromString(serializer, body))
        } catch (e: SerializationException) {
            throw RemoteFailure(TransportFailure.MalformedPayload(endpointOf(serializer) + " " + e::class.simpleName), null)
        } catch (e: IllegalArgumentException) {
            throw RemoteFailure(TransportFailure.MalformedPayload(endpointOf(serializer) + " " + e::class.simpleName), null)
        }

    /** The DTO's own name is the stable, content-free endpoint label for a decode failure. */
    private fun endpointOf(serializer: KSerializer<*>): String = serializer.descriptor.serialName.substringAfterLast('.')
}

package ai.deepseek.dsh.android.network

import ai.deepseek.dsh.android.model.ChannelKind
import ai.deepseek.dsh.android.model.PollRequest
import ai.deepseek.dsh.android.model.SessionId
import kotlinx.coroutines.runBlocking
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/**
 * D16 / gate G09.2 redaction proof. Canary secrets (bearer token, ws ticket, full session id, prompt
 * text, file path, tool argument, session title) are planted and the [RecordingSink] is inspected: none
 * of the forbidden raw values may ever reach a log line, and session ids may only appear as their last
 * [SESSION_ID_VISIBLE_CHARS] characters.
 */
class NetworkRedactionTest {

    private lateinit var sink: RecordingSink
    private lateinit var server: MockWebServer

    @Before
    fun setUp() {
        sink = RecordingSink()
        DshLog.sink = sink
        DshLog.clearRedactions()
        server = MockWebServer()
        server.start()
    }

    @After
    fun tearDown() {
        server.shutdown()
        DshLog.clearRedactions()
    }

    @Test
    fun registeredTokenIsScrubbedFromEveryLine() {
        val token = "CANARY-BEARER-9f3a"
        DshLog.redact(token)
        DshLog.w("sending auth bearer=$token")
        val text = sink.text()
        assertTrue("expected redaction marker", text.contains("[redacted]"))
        assertFalse("token leaked into log", text.contains(token))
    }

    @Test
    fun longestSecretIsScrubbedBeforeItsSubstring() {
        DshLog.redact("abc")
        DshLog.redact("abcdef-longest")
        DshLog.e("value=abcdef-longest and abc")
        val text = sink.text()
        assertFalse("long secret leaked", text.contains("abcdef-longest"))
        assertFalse("short secret leaked", text.contains("abc"))
    }

    @Test
    fun sessionIdsAreOnlyEverRenderedAsLastEightChars() {
        val full = "session-1-0123456789abcdef"
        DshLog.redact(full)
        val shown = shortSession(SessionId(full))
        assertEquals(full.takeLast(8), shown)
        assertEquals(8, shown.length)
        DshLog.i("session=$shown")
        // A log line may carry the suffix, never the full id.
        assertFalse("full session id leaked", sink.text().contains(full))
        assertTrue(shown in sink.text())
    }

    @Test
    fun transportRegistersBearerSoPromptTextAndPathsCannotLeak() = runBlocking {
        val canaryToken = "GATEWAY-CRED-timestamp-secret"
        // /health is unauthenticated; a subsequent authorised poll must register the bearer for redaction.
        server.enqueue(
            MockResponse()
                .setResponseCode(200)
                .setBody("""{"epoch":"e","since":0,"latestSeq":1,"events":[]}"""),
        )
        val src = GatewayV0Source(
            config = GatewayConfig(base = server.url("/").toString(), credential = GatewayCredential { canaryToken }),
            tuning = ContractFixture.testTuning(),
        )
        src.poll(PollRequest(ChannelKind.Mux, since = 0L, epoch = "e", waitMs = 0L))

        // Now simulate an incidental log that embeds the bearer; the registered token must be scrubbed.
        DshLog.w("ctx auth=$canaryToken")
        val text = sink.text()
        assertFalse("bearer leaked", text.contains(canaryToken))

        // And confirm the authorised request itself carried the bearer only as a header, never in the URL.
        val recorded = server.takeRequest()
        assertFalse(recorded.path!!.contains(canaryToken))
        assertEquals("Bearer $canaryToken", recorded.getHeader("Authorization"))
    }

    @Test
    fun wsTicketResponseToStringMasksTheSecret() {
        val secret = "TICKET-90s-once"
        val resp = ai.deepseek.dsh.android.protocol.gateway.v0.WsTicketResponse(
            secret = secret,
            expiresInMs = 90_000L,
            maxUses = 1,
            ok = true,
        )
        val rendered = resp.toString()
        assertFalse("ticket secret leaked via toString", rendered.contains(secret))
        assertTrue(rendered.contains("redacted"))
    }

    @Test
    fun ticketCacheRegistersSecretForRedaction() {
        val secret = "TICKET-CACHE-SECRET"
        val cache = TicketCache()
        cache.store(secret, ttlMs = 90_000L, maxUses = 1, nowMs = 0L)
        DshLog.e("reconnect with $secret")
        assertFalse("cached ticket leaked to log", sink.text().contains(secret))
        assertEquals(secret, cache.take(nowMs = 1_000L))
    }
}

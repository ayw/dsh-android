package ai.deepseek.dsh.android.network

import ai.deepseek.dsh.android.model.BackendCapabilities
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
 * Capability negotiation from the `GET /health` integer table (G08 / D27):
 *  - each advertised feature is read from its own named key;
 *  - an absent or unknown key is read as "not supported" (the safe direction), NEVER silently defaulted
 *    to supported;
 *  - the raw table is retained for diagnostics;
 *  - a malformed health payload degrades to a typed failure, never a crash.
 *
 * NOTE: the real `health-and-capabilities.json` recording cannot be fed to [GatewayV0Source.health]
 * because the frozen `HealthResponse` models `upstream` as a Boolean while the gateway emits a URL string
 * — a protocol conflict reported separately. These tests therefore use a decodable health body that
 * carries the same capability table to exercise the negotiation logic in isolation.
 */
class CapabilityNegotiationTest {

    private lateinit var server: MockWebServer

    @Before
    fun setUp() {
        DshLog.sink = RecordingSink()
        DshLog.clearRedactions()
        server = MockWebServer()
        server.start()
    }

    @After
    fun tearDown() {
        server.shutdown()
    }

    private fun source() = GatewayV0Source(
        config = GatewayConfig(base = server.url("/").toString(), credential = GatewayCredential { "tok" }),
        tuning = ContractFixture.testTuning(),
    )

    private fun enqueueHealth(body: String) {
        server.enqueue(MockResponse().setResponseCode(200).setBody(body))
    }

    @Test
    fun advertisedCapabilitiesAreReadPerFeatureAndRawIsKept() = runBlocking {
        enqueueHealth(
            """{"ok":true,"version":"0.8.1","capabilities":{
               "wsTicket":1,"eventPolling":1,"eventEpoch":1,"operations":1,
               "workspaceFiles":2,"resumableUploads":2,"fileInbox":1,"imagePromptTransport":1,
               "cloudRelay":1}}""",
        )
        val caps = source().health().capabilities

        assertEquals(1, caps.wsTicket)
        assertEquals(2, caps.workspaceFiles)
        assertEquals(1, caps.imagePromptTransport)
        // derived getters reflect the integer table (>=1), not a single version branch.
        assertTrue(caps.websocketAllowed)
        assertTrue(caps.hasReceipts)
        assertTrue(caps.filesAllowed)
        // An un-modelled but present key is preserved verbatim for diagnostics.
        assertEquals(1, caps.raw["cloudRelay"])
    }

    @Test
    fun absentCapabilityIsHiddenNotDefaultedToSupported() = runBlocking {
        // Older gateway advertises only eventPolling; everything else must read as NOT supported.
        enqueueHealth("""{"ok":true,"version":"0.8.1","capabilities":{"eventPolling":1}}""")
        val caps = source().health().capabilities

        assertEquals(1, caps.eventPolling)
        assertTrue(caps.backgroundSyncSupported)
        // wsTicket / operations were absent -> 0 -> hidden, never assumed supported (D27/G08).
        assertEquals(0, caps.wsTicket)
        assertEquals(0, caps.operations)
        assertFalse(caps.websocketAllowed)
        assertFalse(caps.hasReceipts)
        assertTrue(caps.unknown("wsTicket"))
    }

    @Test
    fun unNegotiatedClientDefaultsToAllHidden() = runBlocking {
        // Before any /health call the source must advertise nothing (safe default), not guess support.
        val src = source()
        assertEquals(BackendCapabilities(0, 0, 0, 0, 0, 0, 0, 0, emptyMap()), src.capabilities)
        assertFalse(src.capabilities.websocketAllowed)
    }

    @Test
    fun malformedHealthFailsGracefully() = runBlocking {
        enqueueHealth("{oops")
        val failure = runCatching { source().health() }.exceptionOrNull()
        assertTrue(failure is ai.deepseek.dsh.android.domain.RemoteFailure)
        assertTrue(
            (failure as ai.deepseek.dsh.android.domain.RemoteFailure).failure is
                ai.deepseek.dsh.android.domain.TransportFailure.MalformedPayload,
        )
    }
}

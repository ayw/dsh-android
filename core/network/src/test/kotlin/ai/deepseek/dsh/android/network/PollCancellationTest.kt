package ai.deepseek.dsh.android.network

import ai.deepseek.dsh.android.model.ChannelKind
import ai.deepseek.dsh.android.model.PollRequest
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.util.concurrent.TimeUnit

/**
 * A cancelled poll must NEVER deliver a batch — an abandoned in-flight poll could otherwise advance a
 * transport cursor past events the actor never saw. [GatewayTransport] routes every call through
 * `suspendCancellableCoroutine` and cancels the underlying OkHttp [okhttp3.Call] on cancellation, so a
 * cancelled poll tears the socket down and rethrows [CancellationException] rather than a
 * [ai.deepseek.dsh.android.domain.TransportFailure] (a cancellation is not an unreachable/timeout).
 */
class PollCancellationTest {

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

    @Test
    fun cancellingAnInFlightPollThrowsCancellationAndDeliversNoBatch() {
        runBlocking {
            // The gateway holds the long poll open well past the cancellation point.
            server.enqueue(
                MockResponse()
                    .setResponseCode(200)
                    .setBody("""{"epoch":"e","since":8,"latestSeq":9,"events":[]}""")
                    .setBodyDelay(5, TimeUnit.SECONDS),
            )

            val poll = async(Dispatchers.IO) {
                source().poll(PollRequest(ChannelKind.Mux, since = 8L, epoch = "e", waitMs = 25_000L))
            }

            // Wait until the request has actually reached the server so we cancel a live call, not a pending one.
            val arrived = withContext(Dispatchers.IO) { server.takeRequest(2, TimeUnit.SECONDS) }
            assertTrue("poll request never reached the server", arrived != null)

            poll.cancel()
            val thrown = runCatching { poll.await() }.exceptionOrNull()
            assertTrue("expected CancellationException, got $thrown", thrown is CancellationException)
        }
    }

    @Test
    fun cancelledPollDoesNotAdvanceCursor() = runBlocking {
        server.enqueue(
            MockResponse()
                .setResponseCode(200)
                .setBody("""{"epoch":"e","since":8,"latestSeq":99,"events":[{"seq":99,"event":{}}]}""")
                .setBodyDelay(5, TimeUnit.SECONDS),
        )

        var deliveredLatest: Long? = null
        val job = launch(Dispatchers.IO) {
            runCatching {
                val batch = source().poll(PollRequest(ChannelKind.Mux, since = 8L, epoch = "e", waitMs = 25_000L))
                deliveredLatest = batch.latestSeq
            }
        }
        withContext(Dispatchers.IO) { server.takeRequest(2, TimeUnit.SECONDS) }
        delay(50)
        job.cancel()
        job.join()

        // No batch was delivered past the cancellation, so the cursor never moved.
        assertEquals(null, deliveredLatest)
    }
}

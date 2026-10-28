package ai.deepseek.dsh.android.data

import ai.deepseek.dsh.android.model.SessionId
import ai.deepseek.dsh.android.network.DshLog
import ai.deepseek.dsh.android.network.LogSink
import ai.deepseek.dsh.android.network.RecordingSink
import ai.deepseek.dsh.android.network.shortSession
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/**
 * G09.2：数据层可登记的 canary 一旦交给 `DshLog`，就不得原样到达 sink。
 * 覆盖 token / ws ticket / URL query 三类，以及会话 id 只允许 8 位后缀的渲染规则。
 * 用 [RecordingSink] 断言的是“门面最终写出什么”，不依赖 Logcat。
 */
class LogRedactionTest {

    private val token = "canary-token-7f3c"
    private val ticket = "canary-ticket-9a1b"
    private val url = "https://gateway.example/api/ws?ticket=$ticket&epoch=4411"
    private val query = "ticket=$ticket&epoch=4411"
    private val session = SessionId("sess-0123456789abcdef")

    private lateinit var sink: RecordingSink
    private lateinit var previousSink: LogSink

    @Before
    fun setUp() {
        previousSink = DshLog.sink
        sink = RecordingSink()
        DshLog.sink = sink
        DshLog.clearRedactions()
    }

    @After
    fun tearDown() {
        DshLog.clearRedactions()
        DshLog.sink = previousSink
    }

    @Test
    fun registrableSecretsCoverCredentialTicketAndQuery() {
        val secrets = Redaction.registrableSecrets(token, ticket, url)
        assertTrue(secrets.contains(token))
        assertTrue(secrets.contains(ticket))
        assertTrue(secrets.contains(query))
        // 空值不登记，清单才可逐个断言。
        assertTrue(Redaction.registrableSecrets("", null, "https://h/p").isEmpty())
        assertTrue(Redaction.registrableSecrets("", null, "https://h/p?").isEmpty())
    }

    @Test
    fun registeredCanariesNeverReachTheSink() {
        Redaction.registrableSecrets(token, ticket, url).forEach(DshLog::redact)

        DshLog.i("dial $url with token=$token")
        DshLog.w("ws ticket=$ticket reused")

        val text = sink.text()
        assertFalse(text.contains(token))
        assertFalse(text.contains(ticket))
        assertFalse(text.contains(query))
        assertFalse(Redaction.messageContainsAny(text, Redaction.registrableSecrets(token, ticket, url)))
        assertTrue(text.contains("[redacted]"))
    }

    @Test
    fun fullSessionIdIsContainedAndOnlyTheLastEightSurvive() {
        DshLog.redactSession(session)

        DshLog.i("session ${session.value} resumed")
        DshLog.i("session ${shortSession(session)} resumed")

        val text = sink.text()
        assertFalse(text.contains(session.value))
        assertTrue(text.contains(shortSession(session)))
        // 规则本身是 8 位后缀，不是“看起来短一点”。
        assertEquals("89abcdef", shortSession(session))
    }

    @Test
    fun scrubStripsUrlQueryFromThrowable() {
        val failure = IllegalStateException("GET https://gateway.example/api/ws?ticket=$ticket&epoch=4411 failed")

        val scrubbed = DshLog.scrub(failure)!!

        assertFalse(scrubbed.message.orEmpty().contains(ticket))
        assertFalse(scrubbed.message.orEmpty().contains("epoch=4411"))
        // 类名保留，便于诊断来自哪一层；消息已清洗。
        assertTrue(scrubbed.toString().contains("IllegalStateException"))

        DshLog.e("poll failed", failure)
        val text = sink.text()
        assertFalse(text.contains(ticket))
        assertFalse(text.contains("epoch=4411"))
    }
}

package ai.deepseek.dsh.android.data

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import ai.deepseek.dsh.android.database.DshDatabase
import ai.deepseek.dsh.android.database.SessionEntity
import ai.deepseek.dsh.android.model.ServerId
import ai.deepseek.dsh.android.model.SessionId
import java.security.KeyStore
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/**
 * Infrastructure probe: Room (real SQLite) and the AndroidKeyStore provider under Robolectric.
 * It is not a contract test; it tells us whether D21's Robolectric path is available on this host.
 */
@RunWith(RobolectricTestRunner::class)
class RoomKeystoreProbeTest {

    @Test
    fun roomInMemoryRoundTrip() = runBlocking {
        val context: Context = ApplicationProvider.getApplicationContext()
        val db = DshDatabase.inMemoryForTest(context)
        val serverId = ServerId("probe")
        db.sessionDao().upsertSessions(
            listOf(
                SessionEntity(
                    serverId = serverId,
                    sessionId = SessionId("s-1"),
                    title = "t",
                    updatedAtMs = 1L,
                    running = false,
                    archived = false,
                    workspaceId = null,
                    rosterRank = 1L,
                    lastSeenRank = 1L,
                ),
            ),
        )
        val row = db.sessionDao().session(serverId.value, "s-1")
        assertNotNull(row)
        assertEquals("t", row?.title)
        db.close()
    }

    @Test
    fun androidKeyStoreProviderAvailability() {
        val report = StringBuilder()
        runCatching {
            val ks = KeyStore.getInstance("AndroidKeyStore")
            ks.load(null)
            ks.isKeyEntry("probe-alias")
        }.onSuccess { report.append("keystore=ok entry=$it") }.onFailure { report.append("keystore=fail ${it.javaClass.name}:${it.message}") }
        runCatching {
            val cipher = javax.crypto.Cipher.getInstance("AES/GCM/NoPadding")
            report.append(" | cipher=${cipher.algorithm}")
        }.onFailure { report.append(" | cipher=fail ${it.message}") }
        println("PROBE " + report.toString())
    }
}

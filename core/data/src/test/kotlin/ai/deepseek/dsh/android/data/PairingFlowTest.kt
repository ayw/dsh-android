package ai.deepseek.dsh.android.data

import ai.deepseek.dsh.android.domain.FakeClock
import ai.deepseek.dsh.android.domain.usecase.PairedServer
import ai.deepseek.dsh.android.domain.usecase.PairingParseResult
import ai.deepseek.dsh.android.domain.usecase.PairingProposal
import ai.deepseek.dsh.android.domain.usecase.PairingUseCase
import ai.deepseek.dsh.android.domain.usecase.RedirectAuthDisposition
import ai.deepseek.dsh.android.domain.usecase.SecretWriter
import ai.deepseek.dsh.android.domain.usecase.decideRedirectAuth
import ai.deepseek.dsh.android.model.ConnectionIdentity
import ai.deepseek.dsh.android.model.IdentityDigest
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * G19：配对流程的不变量。用 spy 端口证明“确认之前零持久化”，
 * 并把 http→https 升级、跨主机剥离 Authorization、未知 base 失败三条安全规则钉死在这里。
 */
class PairingFlowTest {

    private val token = "qr-token-canary-31e9"

    @Test
    fun invalidQrIsRejectedWithoutAnySideEffect() {
        val fixture = Fixture(qr())
        val result = fixture.coordinator.parse("https://not-a-pair-qr")

        assertTrue(result is PairingParseResult.Invalid)
        assertEquals(0, fixture.writer.writes)
        assertEquals(0, fixture.persister.persists)
        assertTrue(fixture.order.isEmpty())
    }

    @Test
    fun validMultiAddressQrOnlyYieldsAnInMemoryProposal() {
        val fixture = Fixture(qr())
        val result = fixture.coordinator.parse(qr())

        val proposal = (result as PairingParseResult.Proposal).proposal
        assertEquals(token, proposal.token)
        assertEquals(2, proposal.candidates.size)
        // 解析阶段不得落任何东西：确认是唯一写点。
        assertEquals(0, fixture.writer.writes)
        assertEquals(0, fixture.persister.persists)
        assertTrue(fixture.order.isEmpty())
    }

    @Test
    fun confirmUpgradesSameHostHttpToHttpsAndWritesCredentialLast() = runBlocking {
        val fixture = Fixture(qr())

        val paired = fixture.coordinator.confirm(fixture.parseProposal(), "http://gateway.local:8080").getOrThrow()

        assertEquals("https://gateway.local:8080", paired.storedBase)
        assertTrue(paired.upgradedToHttps)
        assertEquals(IdentityDigest.of("https://gateway.local:8080", token), paired.identity.digest)
        assertEquals(1, fixture.persister.persists)
        assertEquals(1, fixture.writer.writes)
        // 文档化的写序：服务器行先、凭据后；没有凭据的服务器行是惰性残骸，可以重新配对覆盖。
        assertEquals(listOf("server", "credential"), fixture.order)
        assertEquals(paired.identity, fixture.writer.lastIdentity)
    }

    @Test
    fun unknownChosenBaseFailsWithoutWrites() = runBlocking {
        val fixture = Fixture(qr())

        val result = fixture.coordinator.confirm(fixture.parseProposal(), "https://evil.example:9443")

        assertTrue(result.isFailure)
        assertEquals(0, fixture.writer.writes)
        assertEquals(0, fixture.persister.persists)
        assertTrue(fixture.order.isEmpty())
    }

    @Test
    fun crossHostOrCrossPortRedirectStripsAuthorization() {
        assertEquals(
            RedirectAuthDisposition.Strip,
            decideRedirectAuth("https://a.example:8080/api", "https://b.example:8080/api"),
        )
        assertEquals(
            RedirectAuthDisposition.Strip,
            decideRedirectAuth("https://a.example:8080/api", "https://a.example:9443/api"),
        )
        assertEquals(
            RedirectAuthDisposition.Carry,
            decideRedirectAuth("http://a.example:8080/api", "https://a.example:8080/api"),
        )
    }

    private fun qr(): String {
        val addresses = "http://gateway.local:8080,https://gateway.local:8080"
        return "dshremote://pair?token=$token&server=$addresses"
    }

    /** 每个用例一套独立 spy：共享同一份顺序表，但不跨用例污染。 */
    private class Fixture(private val proposalQr: String) {
        val order = mutableListOf<String>()
        val writer = RecordingSecretWriter(order)
        val persister = RecordingServerPersister(order)
        val coordinator = PairingCoordinator(PairingUseCase(FakeClock(startWallClockMs = 1_000L)), writer, persister)

        fun parseProposal(): PairingProposal =
            (coordinator.parse(proposalQr) as PairingParseResult.Proposal).proposal
    }

    private class RecordingSecretWriter(private val order: MutableList<String>) : SecretWriter {
        var writes = 0
        var lastIdentity: ConnectionIdentity? = null

        override suspend fun write(identity: ConnectionIdentity, token: String) {
            writes += 1
            lastIdentity = identity
            order += "credential"
        }
    }

    private class RecordingServerPersister(private val order: MutableList<String>) : ServerPersister {
        var persists = 0
        var last: PairedServer? = null

        override suspend fun persist(server: PairedServer) {
            persists += 1
            last = server
            order += "server"
        }
    }
}

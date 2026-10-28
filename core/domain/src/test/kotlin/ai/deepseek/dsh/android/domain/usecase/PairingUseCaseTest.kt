package ai.deepseek.dsh.android.domain.usecase

import ai.deepseek.dsh.android.domain.FakeClock
import ai.deepseek.dsh.android.domain.repository.DomainError
import ai.deepseek.dsh.android.model.ConnectionIdentity
import ai.deepseek.dsh.android.model.IdentityDigest
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertTrue

class PairingUseCaseTest {
    private val clock = FakeClock(startWallClockMs = 1_000L)

    // Fixed vector: sha256(base-trailing-slash-trimmed + "\n" + token), lowercase hex (F7).
    private val vectorBase = "http://192.168.1.9:8787"
    private val vectorToken = "tok123"
    private val vectorDigest = "4320f4d6bfd542ebfd65ff26f84c7bdf926b078e81ec5574a4fe6d9c7f0d2fef"

    @Test
    fun `digest is byte-stable for the fixed vector and ignores trailing slash`() {
        assertEquals(vectorDigest, IdentityDigest.of(vectorBase, vectorToken))
        assertEquals(vectorDigest, IdentityDigest.of("$vectorBase/", vectorToken))
    }

    @Test
    fun `multi address qr is deduped by normalized base preserving order`() {
        val qr = "dshremote://pair?token=TOK&server=https://a:1,https://a:1/,https://b:2"

        val proposal = assertIs<PairingParseResult.Proposal>(PairingUseCase(clock).parse(qr)).proposal

        assertEquals(listOf("https://a:1", "https://b:2"), proposal.candidates.map { it.base })
        // every candidate carries its own identity digest
        proposal.candidates.forEach { assertEquals(IdentityDigest.of(it.base, "TOK"), it.identityDigest) }
    }

    @Test
    fun `an unconfirmed proposal writes nothing`() {
        val writer = RecordingSecretWriter()
        val qr = "dshremote://pair?token=TOK&server=https://a:1"

        val parsed = PairingUseCase(clock).parse(qr)

        assertIs<PairingParseResult.Proposal>(parsed)
        // parsing alone never touches the secret store
        assertTrue(writer.writes.isEmpty())
    }


    @Test
    fun `confirm persists the secret once with the matching identity digest`() = runTest {
        val writer = RecordingSecretWriter()
        val qr = "dshremote://pair?token=$vectorToken&server=$vectorBase"
        val proposal = assertIs<PairingParseResult.Proposal>(PairingUseCase(clock).parse(qr)).proposal

        val paired = assertIs<PairedServer>(
            PairingUseCase(clock).confirm(proposal, chosenBase = vectorBase, secretWriter = writer).getOrThrow(),
        )

        assertEquals(1, writer.writes.size)
        assertEquals(vectorDigest, paired.identity.digest)
        assertEquals(vectorDigest, writer.writes.single().first.digest)
        assertEquals(vectorToken, writer.writes.single().second)
        assertEquals(vectorBase, paired.storedBase)
        assertEquals(false, paired.upgradedToHttps)
        assertEquals(1_000L, paired.addedAtMs)
    }


    @Test
    fun `http is upgraded to an existing same-host https base`() = runTest {
        val writer = RecordingSecretWriter()
        val qr = "dshremote://pair?token=TOK&server=http://h:1,https://h:1"
        val proposal = assertIs<PairingParseResult.Proposal>(PairingUseCase(clock).parse(qr)).proposal

        val paired = PairingUseCase(clock).confirm(proposal, chosenBase = "http://h:1", secretWriter = writer)
            .getOrThrow()

        assertEquals("https://h:1", paired.storedBase)
        assertEquals(true, paired.upgradedToHttps)
        assertEquals(IdentityDigest.of("https://h:1", "TOK"), paired.identity.digest)
    }


    @Test
    fun `confirming a base that is not a candidate fails and writes nothing`() = runTest {
        val writer = RecordingSecretWriter()
        val qr = "dshremote://pair?token=TOK&server=https://a:1"
        val proposal = assertIs<PairingParseResult.Proposal>(PairingUseCase(clock).parse(qr)).proposal

        val result = PairingUseCase(clock).confirm(proposal, chosenBase = "https://evil:9", secretWriter = writer)

        assertTrue(result.isFailure)
        assertIs<DomainError.Rejected>(result.exceptionOrNull())
        assertTrue(writer.writes.isEmpty())
    }

    @Test
    fun `cross host redirect strips authorization while same host keeps it`() {
        assertEquals(
            RedirectAuthDisposition.Carry,
            decideRedirectAuth("https://h:1/a", "https://h:1/b"),
        )
        // http -> https on the same host:port still counts as same origin
        assertEquals(
            RedirectAuthDisposition.Carry,
            decideRedirectAuth("http://h:1/a", "https://h:1/a"),
        )
        assertEquals(
            RedirectAuthDisposition.Strip,
            decideRedirectAuth("https://h:1/a", "https://other:1/b"),
        )
        // host:port change (different port) also strips
        assertEquals(
            RedirectAuthDisposition.Strip,
            decideRedirectAuth("https://h:1/a", "https://h:2/a"),
        )
    }

    @Test
    fun `https upgrade requires same host and port`() {
        assertTrue(isHttpsUpgradeAllowed("http://h:1", "https://h:1"))
        assertTrue(!isHttpsUpgradeAllowed("http://h:1", "https://h:2"))
        assertTrue(!isHttpsUpgradeAllowed("http://h:1", "https://other:1"))
        assertTrue(!isHttpsUpgradeAllowed("https://h:1", "https://h:1"))
    }

    @Test
    fun `missing token is rejected`() {
        val result = PairingUseCase(clock).parse("dshremote://pair?server=https://a:1")
        assertIs<PairingParseResult.Invalid>(result)
    }

    @Test
    fun `identity digest equals a value class boundary`() {
        // ConnectionIdentity enforces 64-char lowercase hex, which our digest satisfies.
        val identity = ConnectionIdentity(IdentityDigest.of(vectorBase, vectorToken))
        assertEquals(64, identity.digest.length)
    }
}

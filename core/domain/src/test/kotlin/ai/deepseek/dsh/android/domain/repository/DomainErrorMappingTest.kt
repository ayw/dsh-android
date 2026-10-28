package ai.deepseek.dsh.android.domain.repository

import ai.deepseek.dsh.android.domain.RemoteFailure
import ai.deepseek.dsh.android.domain.TransportFailure
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs

class DomainErrorMappingTest {

    @Test
    fun `auth only comes from unauthorized or 401`() {
        assertEquals(DomainError.AuthExpired, toDomainError(TransportFailure.Unauthorized))
        assertEquals(DomainError.AuthExpired, toDomainError(TransportFailure.UnexpectedStatus(401, "/api/respond")))
    }

    @Test
    fun `403 is forbidden carrying the resource`() {
        val error = toDomainError(TransportFailure.Forbidden("/fs/list"))
        assertIs<DomainError.Forbidden>(error)
        assertEquals("/fs/list", error.resource)
        assertEquals(DomainError.Forbidden("/api/x"), toDomainError(TransportFailure.UnexpectedStatus(403, "/api/x")))
    }

    @Test
    fun `connectivity and tls and probe collapse to unreachable`() {
        assertEquals(DomainError.Unreachable, toDomainError(TransportFailure.Unreachable))
        assertEquals(DomainError.Unreachable, toDomainError(TransportFailure.TlsFailure))
        assertEquals(DomainError.Unreachable, toDomainError(TransportFailure.AuthProbeUnavailable))
        // 5xx is a server-side "unknown", not an auth signal.
        assertEquals(DomainError.Unreachable, toDomainError(TransportFailure.UnexpectedStatus(503, "/api/respond")))
    }

    @Test
    fun `timeout and 408 stay timeout never auth`() {
        assertEquals(DomainError.Timeout, toDomainError(TransportFailure.Timeout))
        assertEquals(DomainError.Timeout, toDomainError(TransportFailure.UnexpectedStatus(408, "/api/respond")))
    }

    @Test
    fun `409 maps to conflict`() {
        assertEquals(DomainError.Conflict, toDomainError(TransportFailure.UnexpectedStatus(409, "/api/respond")))
    }

    @Test
    fun `malformed payload is a rejection`() {
        assertEquals(
            DomainError.Rejected("malformed_payload:bad json"),
            toDomainError(TransportFailure.MalformedPayload("bad json")),
        )
    }

    @Test
    fun `unknown status codes are rejected`() {
        assertEquals(DomainError.Rejected("http_404"), toDomainError(TransportFailure.UnexpectedStatus(404, "/api/x")))
        assertEquals(DomainError.Rejected("http_429"), toDomainError(TransportFailure.UnexpectedStatus(429, "/api/x")))
    }

    @Test
    fun `remote failure unwraps to its transport failure`() {
        val remote = RemoteFailure(TransportFailure.Timeout)
        assertEquals(DomainError.Timeout, toDomainError(remote))
        assertIs<DomainError>(remote.toDomainError())
    }

    @Test
    fun `non remote throwable becomes internal rejection`() {
        assertEquals(DomainError.Rejected("internal"), RuntimeException("boom").toDomainError())
    }

    @Test
    fun `domain call maps thrown remote failure into typed result`() {
        val result = kotlinx.coroutines.runBlocking {
            domainCall<Int> { throw RemoteFailure(TransportFailure.Unauthorized) }
        }
        val error = result.exceptionOrNull()
        assertIs<DomainError.AuthExpired>(error)
    }
}

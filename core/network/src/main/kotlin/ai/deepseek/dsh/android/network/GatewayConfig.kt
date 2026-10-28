package ai.deepseek.dsh.android.network

/**
 * Everything the transport needs that is NOT a body field: the stored base URL, the gateway-issued
 * bearer credential and a stable client id header.
 *
 * Security invariants (D16):
 *  - the bearer [credential] is the GATEWAY's own device credential obtained during pairing; it is
 *    never the DSH owner token (the app never holds that), it is never placed in a URL, and it is only
 *    ever sent as an `Authorization` header;
 *  - the credential is supplied lazily by [GatewayCredential] so the module never persists it — persistence
 *    is `:core:data`'s AndroidKeyStore AES-256-GCM SecretStore, out of scope here;
 *  - [base] is kept exactly as the caller stored it: no normalisation, because the identity digest (F7)
 *    is derived from the raw stored base and any rewriting would break it.
 */
data class GatewayConfig(
    val base: String,
    val credential: GatewayCredential,
    val clientId: String = DEFAULT_CLIENT_ID,
) {
    /** Trailing slash trimmed only for URL joining; [base] itself stays verbatim for digest parity. */
    val root: String get() = base.trimEnd('/')

    companion object {
        const val DEFAULT_CLIENT_ID: String = "app"
    }
}

/** Supplies the current bearer credential. Registered via [DshLog.redact] so it can never be logged. */
fun interface GatewayCredential {
    suspend fun token(): String
}

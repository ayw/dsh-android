package ai.deepseek.dsh.android.network

import ai.deepseek.dsh.android.model.SessionId

/**
 * The single logging facade for the whole client (DECISIONS.md **D16**).
 *
 * This file is the ONE place allowed to touch [android.util.Log]; the `check-source.sh` gate (G09.3a)
 * fails the build if `android.util.Log` appears anywhere else. Everything routes through [DshLog.sink],
 * so production logs to Logcat while tests install an in-memory sink and assert on what was written.
 *
 * The facade enforces the D16/G09.2 redaction contract:
 *  - every registered secret ([redact], e.g. the gateway bearer credential, a ws ticket, a URL query)
 *    is replaced with `[redacted]` before the text reaches the sink;
 *  - session ids are only ever rendered as their last 8 characters via [shortSession]; the raw id is
 *    scrubbed as a secret too, so a caller that forgets and interpolates a full id is still contained.
 *
 * No log line may contain a token, ticket, URL query, prompt text, tool argument, file path or session
 * title. Those values never enter this facade from the transport layer at all; the redaction sink is
 * defence in depth, verified by a `*Redaction*`-named test.
 */
object DshLog {
    /** The tag used for every line so logcat filtering is possible without per-call-site tags. */
    const val TAG: String = "dsh-net"

    /** Pluggable destination: production delegates to Logcat, tests install [RecordingSink]. */
    @Volatile
    var sink: LogSink = LogcatSink

    private val secrets = mutableListOf<String>()

    /** Register a raw secret so it is scrubbed from every subsequent line. Blank values are ignored. */
    fun redact(secret: String?) {
        if (secret.isNullOrEmpty()) return
        synchronized(secrets) {
            if (secret !in secrets) secrets += secret
        }
    }

    /**
     * Register a session id so a caller that interpolates the FULL id into a message is still contained;
     * [shortSession] is the sanctioned rendering and is a suffix of the registered value, so it survives.
     */
    fun redactSession(session: SessionId) = redact(session.value.takeIf { it.isNotEmpty() })

    /** Forget all registered secrets (used when an identity is retired / between tests). */
    fun clearRedactions() {
        synchronized(secrets) { secrets.clear() }
    }

    fun v(message: String) = emit(Level.V, message)
    fun d(message: String) = emit(Level.D, message)
    fun i(message: String) = emit(Level.I, message)
    fun w(message: String, error: Throwable? = null) = emit(Level.W, message, error)
    fun e(message: String, error: Throwable? = null) = emit(Level.E, message, error)

    private fun emit(level: Level, message: String, error: Throwable? = null) {
        // The throwable is scrubbed too: an upstream IOException message can carry a request URL
        // (query included), and `android.util.Log.w(tag, msg, tr)` would print it verbatim.
        sink.write(level, TAG, sanitize(message), scrub(error))
    }

    /** Replace every registered secret with a fixed marker and normalise full session ids. */
    fun sanitize(raw: String): String {
        var out = raw
        val snapshot = synchronized(secrets) { secrets.toList() }
        // Scrub longest secrets first so a secret is a substring of another cannot survive.
        for (secret in snapshot.sortedByDescending { it.length }) {
            if (secret.isNotEmpty()) out = out.replace(secret, REDACTED)
        }
        return out
    }

    /**
     * A copy of [error] that keeps the class name, the stack trace and the cause chain but carries a
     * sanitized message, so handing a throwable to Logcat (or to a test sink that prints `error.message`)
     * can never print a registered secret or a URL query. An upstream `IOException` from the HTTP stack
     * quotes the request it failed on, and a request URL is where a file path or a prompt fragment ends
     * up — hence the query is stripped as well, not merely the registered secrets (D16 / G09.2).
     */
    fun scrub(error: Throwable?): Throwable? {
        if (error == null) return null
        val sanitized = stripQuery(sanitize(error.message.orEmpty()))
        val cause = error.cause?.let { if (it === error) null else scrub(it) }
        val unchanged = sanitized == error.message && cause === error.cause
        if (unchanged) return error
        return ScrubbedThrowable(error, sanitized, cause)
    }

    /** Replace `?a=b&c=d` style query sections with a marker: a query is never safe to print. */
    fun stripQuery(text: String): String = QUERY_PATTERN.replace(text, REDACTED)

    /** A throwable view whose message has been run through [sanitize]; identity/stack of [source] kept. */
    private class ScrubbedThrowable(
        private val source: Throwable,
        message: String,
        cause: Throwable?,
    ) : Throwable(message, cause) {
        init { stackTrace = source.stackTrace }

        override fun toString(): String = "${source.javaClass.name}: $message"
    }
}

private val QUERY_PATTERN = Regex("[?&][A-Za-z0-9_%.-]+=[^&#\\s\"]*")

/** Masked rendering of a session id: only the last 8 characters are ever safe to print (D16). */
fun shortSession(session: SessionId): String = session.value.takeLast(SESSION_ID_VISIBLE_CHARS)

private const val REDACTED = "[redacted]"

/** Number of trailing session-id characters a log line may carry. Not a tunable threshold. */
private const val SESSION_ID_VISIBLE_CHARS = 8

enum class Level { V, D, I, W, E }

/** Destination abstraction so the facade is testable without Android framework access. */
fun interface LogSink {
    fun write(level: Level, tag: String, message: String, error: Throwable?)
}

/** Default sink. This is the only call site permitted to use [android.util.Log]. */
private object LogcatSink : LogSink {
    override fun write(level: Level, tag: String, message: String, error: Throwable?) {
        when (level) {
            Level.V -> android.util.Log.v(tag, message)
            Level.D -> android.util.Log.d(tag, message)
            Level.I -> android.util.Log.i(tag, message)
            Level.W -> if (error != null) android.util.Log.w(tag, message, error) else android.util.Log.w(tag, message)
            Level.E -> if (error != null) android.util.Log.e(tag, message, error) else android.util.Log.e(tag, message)
        }
    }
}

/** An in-memory sink used by the redaction test; every written message is concatenated for assertions. */
class RecordingSink : LogSink {
    private val lines = mutableListOf<String>()

    override fun write(level: Level, tag: String, message: String, error: Throwable?) {
        synchronized(lines) { lines += "${level.name}:$message" + (error?.message?.let { ":$it" } ?: "") }
    }

    fun text(): String = synchronized(lines) { lines.joinToString("\n") }
}

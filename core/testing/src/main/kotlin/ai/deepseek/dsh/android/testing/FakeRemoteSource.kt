package ai.deepseek.dsh.android.testing

import ai.deepseek.dsh.android.domain.RemoteSource
import ai.deepseek.dsh.android.domain.RemoteFailure
import ai.deepseek.dsh.android.domain.TransportFailure
import ai.deepseek.dsh.android.model.BackendHealth
import ai.deepseek.dsh.android.model.ChannelKind
import ai.deepseek.dsh.android.model.EventBatch
import ai.deepseek.dsh.android.model.HistoryPage
import ai.deepseek.dsh.android.model.HistoryRequest
import ai.deepseek.dsh.android.model.PollRequest
import ai.deepseek.dsh.android.model.PromptRequest
import ai.deepseek.dsh.android.model.ResolveRequest
import ai.deepseek.dsh.android.model.RosterEntry
import ai.deepseek.dsh.android.model.SessionId
import ai.deepseek.dsh.android.model.StreamHandle
import ai.deepseek.dsh.android.model.TransportFrame
import ai.deepseek.dsh.android.model.WriteResult
import kotlinx.coroutines.delay
import kotlinx.coroutines.ensureActive
import kotlin.coroutines.coroutineContext

/**
 * `RemoteSource` 的脚本化替身。它存在的意义不是省事，而是把“写操作永不自动重试”“401 是 claim 不是
 * verdict”这类契约变成可断言的事实：每次调用都记进 [calls]，注入的失败只生效一次，
 * poll 支持真取消（`pollDelayMs` + `ensureActive`），这样“取消的 poll 不推进 cursor”能被测出来，
 * 而不是靠注释声明。
 */
class FakeRemoteSource(
    healthResponse: BackendHealth = Fixtures.health(),
) : RemoteSource {

    val calls = mutableListOf<String>()
    val pollRequests = mutableListOf<PollRequest>()
    val historyRequests = mutableListOf<HistoryRequest>()
    val openedChannels = mutableListOf<ChannelKind>()
    var closedStreams = 0
        private set

    var healthResponse: BackendHealth = healthResponse
    var rosterResponse: List<RosterEntry> = emptyList()

    /** 每个方法按顺序消费一个脚本值；队列空时回落到 [idle*] 默认值。 */
    val pollScript = ArrayDeque<EventBatch>()
    val historyScript = ArrayDeque<HistoryPage>()
    val promptScript = ArrayDeque<WriteResult>()
    val resolveScript = ArrayDeque<WriteResult>()

    /** 一次性失败注入：命中后清空，因此“重试了一次”会立刻表现为“没有第二次失败”。 */
    val pendingFailures = mutableMapOf<String, TransportFailure>()

    /** >0 时 poll 会挂起这么久，用于测试中途取消。 */
    var pollDelayMs: Long = 0L

    var idlePollBatch: EventBatch = EventBatch(
        channel = ChannelKind.Mux,
        epoch = "epoch-test",
        since = 0,
        latestSeq = 0,
        truncated = false,
        reset = false,
        waitSupported = true,
        baseline = null,
        frames = emptyList(),
    )

    /** openStream 收到的回调，测试用它手工喂 WS 帧。 */
    private var streamListener: (suspend (TransportFrame) -> Unit)? = null

    override suspend fun health(): BackendHealth = call("health") { healthResponse }

    override suspend fun roster(): List<RosterEntry> = call("roster") { rosterResponse }

    override suspend fun history(request: HistoryRequest): HistoryPage = call("history") {
        historyRequests += request
        historyScript.removeFirstOrNull()
            ?: HistoryPage(sessionId = request.sessionId, frames = emptyList(), hasMore = false)
    }

    override suspend fun poll(request: PollRequest): EventBatch = call("poll") {
        pollRequests += request
        if (pollDelayMs > 0) {
            delay(pollDelayMs)
            coroutineContext.ensureActive()
        }
        pollScript.removeFirstOrNull() ?: idlePollBatch.copy(channel = request.channel, since = request.since)
    }

    override suspend fun openStream(
        channel: ChannelKind,
        onFrame: suspend (TransportFrame) -> Unit,
    ): StreamHandle {
        record("openStream:${channel.value}")
        openedChannels += channel
        streamListener = onFrame
        return StreamHandle(
            close = {
                closedStreams += 1
                streamListener = null
            },
        )
    }

    /** 模拟 WS 推送一帧；没有打开的通道就是测试写错了。 */
    suspend fun emit(frame: TransportFrame) {
        val listener = streamListener ?: error("no open stream to emit ${frame.type}")
        listener(frame)
    }

    override suspend fun prompt(request: PromptRequest): WriteResult = call("prompt") {
        promptScript.removeFirstOrNull() ?: Fixtures.accepted()
    }

    override suspend fun resolve(request: ResolveRequest): WriteResult = call("resolve") {
        resolveScript.removeFirstOrNull() ?: Fixtures.accepted()
    }

    override suspend fun cancel(sessionId: SessionId): WriteResult = call("cancel") {
        promptScript.removeFirstOrNull() ?: Fixtures.accepted()
    }

    override suspend fun selectModel(sessionId: SessionId, modelId: String): WriteResult =
        call("selectModel") { Fixtures.accepted() }

    override suspend fun models(sessionId: SessionId): List<String> = call("models") {
        listOf("model-a", "model-b")
    }

    /** 让第 [count] 次之后的同名调用抛该失败；只生效一次。 */
    fun failNext(method: String, failure: TransportFailure) {
        pendingFailures[method] = failure
    }

    fun callCount(method: String): Int = calls.count { it == method || it.startsWith("$method:") }

    private suspend fun <T> call(name: String, block: suspend () -> T): T {
        record(name)
        pendingFailures.remove(name)?.let { throw RemoteFailure(it) }
        return block()
    }

    private fun record(name: String) {
        calls += name
    }
}

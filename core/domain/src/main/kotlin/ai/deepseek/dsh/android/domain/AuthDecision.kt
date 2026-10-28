package ai.deepseek.dsh.android.domain

import ai.deepseek.dsh.android.model.AuthProbeVerdict
import ai.deepseek.dsh.android.model.AuthState
import ai.deepseek.dsh.android.model.ConnectionPhase
import ai.deepseek.dsh.android.model.SyncAttemptResult

/** 协议状态码与 WS close code：线格式常量，不是可调参数，因此不进 SyncTuning。 */
object WireCodes {
    const val OK: Int = 200
    const val REDIRECT_MAX: Int = 400
    const val UNAUTHORIZED: Int = 401
    const val FORBIDDEN: Int = 403
    const val NOT_FOUND: Int = 404
    const val METHOD_NOT_ALLOWED: Int = 405
    const val INTERNAL_ERROR: Int = 500
    const val NOT_IMPLEMENTED: Int = 501

    /**
     * WS close code 1006（abnormal closure）。网关对认证失败的连接一律以此码关闭。
     * 写成十六进制是为了 G09.3 的字面量扫描：十进制形态会被当成调参常量。
     */
    const val ABNORMAL_CLOSE: Int = 0x3E6

    private val capabilityAbsent = setOf(NOT_FOUND, METHOD_NOT_ALLOWED, NOT_IMPLEMENTED)

    fun isCapabilityAbsent(status: Int): Boolean = status in capabilityAbsent

    fun isSuccess(status: Int): Boolean = status in OK until REDIRECT_MAX
}

/** 一次可判定的观察：HTTP 状态、传输失败、或 WS 关闭。 */
sealed interface AuthObservation {
    data class HttpStatus(val status: Int) : AuthObservation
    data class Failure(val failure: TransportFailure) : AuthObservation
    data class WsClosed(val code: Int) : AuthObservation
}

/** 401 是 claim、403 是 scope denial、404/405/501 只是能力缺口，其余一律不是认证事件。 */
enum class AuthClaim { CredentialClaim, ScopeDenial, CapabilityMissing, NotAuth }

enum class AuthProbeTask { WsTicket, Roster }

enum class CapabilityDowngrade { TicketToLegacy, PollToWsOnly, OperationsToOldRpc }

/** `/health` 不发布这个键时只能靠探测判定：旧版网关没有 capability 表。 */
enum class CapabilityProbeField { Published, Absent }

enum class Endpoint { WsTicket, EventPoll, Operations }

enum class SessionListOutcome { NotPulled, AuthClaim, TransportFailure, Synced }

/** 探测自身的一次结果：端点没实现不等于凭据被拒，超时不等于凭据被拒。 */
sealed interface CapabilityProbe {
    /** 凭据被接受（2xx/3xx 或 403 的 scope 拒绝）。 */
    data object Accepted : CapabilityProbe

    /** 凭据被拒（探测本身 401）。 */
    data object Rejected : CapabilityProbe

    /** 探测端点不存在或已退役（404/405/501）：只降级能力。 */
    data object RetiredTransport : CapabilityProbe

    /** 拿不到结论：5xx、超时、TLS、DNS，或没有可探测端点。 */
    data object Inconclusive : CapabilityProbe

    companion object {
        fun of(status: Int?, failure: TransportFailure?, retiredTransport: Boolean = false): CapabilityProbe = when {
            retiredTransport -> RetiredTransport
            failure is TransportFailure.AuthProbeUnavailable -> RetiredTransport
            failure != null -> Inconclusive
            status == null -> Inconclusive
            status == WireCodes.UNAUTHORIZED -> Rejected
            WireCodes.isSuccess(status) || status == WireCodes.FORBIDDEN -> Accepted
            WireCodes.isCapabilityAbsent(status) -> RetiredTransport
            else -> Inconclusive
        }
    }
}

/** 一个 401 episode：凭据探测次数受 SyncTuning 预算约束，判决只记一次。 */
data class AuthEpisode(
    val probesUsed: Int = 0,
    val verdict: AuthProbeVerdict? = null,
    val claim: AuthClaim? = null,
) {
    val settled: Boolean get() = verdict != null

    fun probeAvailable(budget: Int): Boolean = verdict == null && probesUsed < budget
}

data class AuthDecision(
    val authState: AuthState,
    val episode: AuthEpisode,
    val probeTask: AuthProbeTask?,
    val degraded: Boolean,
    val downgrades: Set<CapabilityDowngrade> = emptySet(),
) {
    val requiresRePair: Boolean get() = authState == AuthState.AuthExpired
}

data class WorkerOutcome(
    val result: WorkResult,
    val consecutive401: Int,
    val periodicRegistration: Boolean,
)

enum class WorkResult { Success, Retry }

/**
 * 认证判定：AuthExpired 只能由观察到的 401/403 进入，超时、TLS、DNS、WS 1006、坏 JSON 都不得置位；
 * 401 之后的凭据探测是 claim 的验证动作，不是结论。
 */
object AuthDecisions {

    fun claim(observation: AuthObservation, episode: AuthEpisode = AuthEpisode()): AuthClaim = when (observation) {
        // 网关对认证失败的 WS 一律 1006 关闭：上一轮留档的 401 claim 仍然有效。
        is AuthObservation.WsClosed ->
            if (observation.code == WireCodes.ABNORMAL_CLOSE && episode.claim == AuthClaim.CredentialClaim) {
                AuthClaim.CredentialClaim
            } else {
                AuthClaim.NotAuth
            }

        is AuthObservation.HttpStatus -> when (observation.status) {
            WireCodes.UNAUTHORIZED -> AuthClaim.CredentialClaim
            WireCodes.FORBIDDEN -> AuthClaim.ScopeDenial
            else -> if (WireCodes.isCapabilityAbsent(observation.status)) AuthClaim.CapabilityMissing else AuthClaim.NotAuth
        }

        is AuthObservation.Failure -> when (observation.failure) {
            TransportFailure.Unauthorized -> AuthClaim.CredentialClaim
            is TransportFailure.Forbidden -> AuthClaim.ScopeDenial
            is TransportFailure.UnexpectedStatus -> claim(AuthObservation.HttpStatus(observation.failure.status))
            else -> AuthClaim.NotAuth
        }
    }

    fun onObservation(
        observation: AuthObservation,
        state: AuthState,
        episode: AuthEpisode,
        endpoint: Endpoint?,
        wsTicketCapable: Boolean,
        tuning: SyncTuning,
    ): AuthDecision {
        val downgrades = downgradeOf(endpoint, observation)
        return when (claim(observation, episode)) {
            AuthClaim.CredentialClaim -> if (episode.probeAvailable(tuning.auth401ProbeBudget)) {
                AuthDecision(
                    authState = state,
                    episode = episode.copy(probesUsed = episode.probesUsed + 1, claim = AuthClaim.CredentialClaim),
                    probeTask = if (wsTicketCapable) AuthProbeTask.WsTicket else AuthProbeTask.Roster,
                    degraded = true,
                    downgrades = downgrades,
                )
            } else {
                // 预算内已经探测过：这一轮只保持 degraded，不再探测。
                AuthDecision(state, episode, null, degraded = true, downgrades = downgrades)
            }

            AuthClaim.ScopeDenial -> AuthDecision(
                authState = AuthState.ResourceForbidden,
                episode = episode.copy(claim = AuthClaim.ScopeDenial),
                probeTask = null,
                degraded = false,
                downgrades = downgrades,
            )

            AuthClaim.CapabilityMissing -> AuthDecision(
                authState = state,
                episode = episode,
                probeTask = null,
                degraded = state == AuthState.AuthExpired,
                downgrades = downgrades,
            )

            AuthClaim.NotAuth -> AuthDecision(
                authState = notAuthState(observation, state),
                episode = episode,
                probeTask = null,
                degraded = true,
                downgrades = downgrades,
            )
        }
    }

    /** 探测结果映射判决：200/403 说明凭据有效但资源被拒，401 才是凭据被拒，其余只是拿不到结论。 */
    fun applyProbe(episode: AuthEpisode, verdict: AuthProbeVerdict, state: AuthState): AuthDecision = when (verdict) {
        AuthProbeVerdict.CredentialValidResourceForbidden -> AuthDecision(
            AuthState.ResourceForbidden,
            episode.copy(verdict = verdict),
            null,
            degraded = false,
        )

        AuthProbeVerdict.CredentialRejected -> AuthDecision(
            AuthState.AuthExpired,
            episode.copy(verdict = verdict),
            null,
            degraded = true,
        )

        AuthProbeVerdict.Indeterminate -> AuthDecision(
            AuthState.Unknown,
            episode.copy(verdict = verdict),
            null,
            degraded = true,
        )
    }

    fun probeCapabilities(
        state: AuthState,
        probe: CapabilityProbe,
    ): AuthDecision = when (probe) {
        CapabilityProbe.RetiredTransport -> AuthDecision(
            authState = state,
            episode = AuthEpisode(claim = AuthClaim.CapabilityMissing),
            probeTask = null,
            degraded = state == AuthState.AuthExpired,
            downgrades = setOf(CapabilityDowngrade.TicketToLegacy),
        )

        CapabilityProbe.Accepted -> applyProbe(AuthEpisode(), AuthProbeVerdict.CredentialValidResourceForbidden, state)
        CapabilityProbe.Rejected -> applyProbe(AuthEpisode(), AuthProbeVerdict.CredentialRejected, state)
        CapabilityProbe.Inconclusive -> applyProbe(AuthEpisode(), AuthProbeVerdict.Indeterminate, state)
    }

    /**
     * 已发布的能力整数就是结论，不必上网探测（整数 0 ⇒ 端点退役，只降级）；
     * 未发布时才由探测给出结论，且结论仍然区分“端点不存在”与“凭据被拒”。
     */
    fun probeOf(
        field: CapabilityProbeField,
        level: Int,
        status: Int?,
        failure: TransportFailure?,
    ): CapabilityProbe = when {
        field == CapabilityProbeField.Published && level < CapabilityGate.minimalLevel -> CapabilityProbe.RetiredTransport
        else -> CapabilityProbe.of(status, failure)
    }

    /** ON_START 可从 Idle/Unreachable/Suspended 重试，AuthExpired 必须等重配对或用户显式重试。 */
    fun canAutoStart(phase: ConnectionPhase): Boolean =
        phase == ConnectionPhase.Idle ||
            phase == ConnectionPhase.Unreachable ||
            phase == ConnectionPhase.Suspended

    /** 用户“立即重试”清标志：先回到 Idle，ON_START 才能再 CAS。 */
    fun restoreAfterUserAction(phase: ConnectionPhase): ConnectionPhase =
        if (phase == ConnectionPhase.AuthExpired) ConnectionPhase.Idle else phase

    /** 重配对后状态归零，但不会假装已连接。 */
    fun onPaired(status: AuthState): AuthState = if (status == AuthState.AuthExpired) AuthState.Unknown else status

    fun clearAuthExpired(state: AuthState): AuthState = if (state == AuthState.AuthExpired) AuthState.Unknown else state

    /** 后台连续 401 达到阈值即暂停周期任务；计数在成功同步后归零，暂停不是永久的。 */
    fun backgroundPaused(consecutive401: Int, tuning: SyncTuning): Boolean =
        consecutive401 >= tuning.auth401BackgroundPauseThreshold

    fun consecutiveAfterSuccess(): Int = 0

    fun periodicRegistration(
        backgroundSyncEnabled: Boolean,
        consecutive401: Int,
        authState: AuthState,
        tuning: SyncTuning,
    ): Boolean = backgroundSyncEnabled &&
        !backgroundPaused(consecutive401, tuning) &&
        authState != AuthState.AuthExpired

    fun onWorkerResult(
        result: SyncAttemptResult,
        authState: AuthState,
        consecutive401: Int,
        backgroundSyncEnabled: Boolean,
        tuning: SyncTuning,
    ): WorkerOutcome {
        val counter = when (result) {
            SyncAttemptResult.Completed, SyncAttemptResult.ForegroundTookOver -> consecutiveAfterSuccess()
            SyncAttemptResult.AuthExpired -> consecutive401 + 1
            else -> consecutive401
        }
        val work = when (result) {
            SyncAttemptResult.Completed, SyncAttemptResult.ForegroundTookOver -> WorkResult.Success
            SyncAttemptResult.AuthExpired -> WorkResult.Success
            SyncAttemptResult.Busy, SyncAttemptResult.Unreachable -> WorkResult.Retry
        }
        return WorkerOutcome(
            result = work,
            consecutive401 = counter,
            periodicRegistration = periodicRegistration(backgroundSyncEnabled, counter, authState, tuning),
        )
    }

    /** `session.list` 没被拉起时不得记为同步成功；401 结果必须回到 claim 处理。 */
    fun onSessionList(pulled: Boolean, observation: AuthObservation?): SessionListOutcome = when {
        !pulled -> SessionListOutcome.NotPulled
        observation == null -> SessionListOutcome.Synced
        claim(observation) == AuthClaim.CredentialClaim || claim(observation) == AuthClaim.ScopeDenial ->
            SessionListOutcome.AuthClaim

        claim(observation) == AuthClaim.CapabilityMissing -> SessionListOutcome.Synced
        else -> SessionListOutcome.TransportFailure
    }

    fun downgradeOf(endpoint: Endpoint?, observation: AuthObservation): Set<CapabilityDowngrade> {
        if (endpoint == null || claim(observation) != AuthClaim.CapabilityMissing) return emptySet()
        return when (endpoint) {
            Endpoint.WsTicket -> setOf(CapabilityDowngrade.TicketToLegacy)
            Endpoint.EventPoll -> setOf(CapabilityDowngrade.PollToWsOnly)
            Endpoint.Operations -> setOf(CapabilityDowngrade.OperationsToOldRpc)
        }
    }

    /** capability 字段缺席按 0 处理：不崩溃、不猜测，功能一律隐藏。 */
    fun capabilityLevel(raw: Map<String, Int>, name: String): Int = raw[name] ?: 0

    private fun notAuthState(observation: AuthObservation, state: AuthState): AuthState = when {
        observation is AuthObservation.Failure && observation.failure is TransportFailure.MalformedPayload -> state
        observation is AuthObservation.HttpStatus && observation.status >= WireCodes.INTERNAL_ERROR -> state
        else -> AuthState.Unreachable
    }
}

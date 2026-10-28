package ai.deepseek.dsh.android.protocol.gateway.v0

import ai.deepseek.dsh.android.model.WriteResult

/**
 * Shared mapping from a gateway mutation response to the transport-neutral [WriteResult].
 *
 * F5: v0 `/api/respond` (and every mutation) returns HTTP 200 with only `accepted:true|false`; the
 * 409 three-way split is a BFF-v1 concept and the v0 allowlist is empty (equivalently: everything not
 * accepted is STALE). `accepted == null` means the response carried no verdict → [WriteResult.WriteOutcome.Unknown].
 *
 * F15: when `operations >= 1` the client routes the write through `/api/operations` and a receipt that
 * exists but has not settled maps to [WriteResult.WriteOutcome.ReceiptPending], narrowing the UNKNOWN
 * window (D13).
 */
fun writeResultOf(
    accepted: Boolean?,
    receiptId: String? = null,
    forbidden: Boolean = false,
): WriteResult = WriteResult(
    accepted = accepted,
    receiptId = receiptId,
    outcome = when {
        forbidden -> WriteResult.WriteOutcome.Forbidden
        accepted == true -> WriteResult.WriteOutcome.Accepted
        accepted == false -> WriteResult.WriteOutcome.Rejected
        receiptId != null -> WriteResult.WriteOutcome.ReceiptPending
        else -> WriteResult.WriteOutcome.Unknown
    },
)

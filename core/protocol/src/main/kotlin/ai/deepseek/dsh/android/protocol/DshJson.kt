package ai.deepseek.dsh.android.protocol

import kotlinx.serialization.json.Json

/**
 * D06: one [Json] instance for the whole client. The gateway wire and the BFF wire are both
 * decoded through this, which is what makes G15 v0/v1 byte-comparability provable.
 *
 * - [ignoreUnknownKeys] the newer backend may add fields the client has not caught up with.
 * - [explicitNulls] off, so an absent field and an explicit `null` round-trip identically and we
 *   never emit `"x":null` noise; the absent-vs-null distinction is preserved by giving every
 *   nullable property a `null` default (see the JsonSpecGoldenTest "missing-vs-null" case).
 * - [encodeDefaults] off, so a request carries only what the caller set.
 * - [classDiscriminator] `type` matches the wire envelope's `type` key (DurableEvent variants).
 *
 * There is deliberately **no** `coerceInputValues = true` blanket: coercion would silently turn a
 * `null` the schema forbids into a default and hide a contract break. Missing-vs-null is handled
 * per-field by nullable-with-default properties, not by a global switch.
 */
object DshJson {
    val spec: Json = Json {
        ignoreUnknownKeys = true
        explicitNulls = false
        encodeDefaults = false
        classDiscriminator = "type"
    }
}

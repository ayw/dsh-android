@file:JvmName("JsonExtras")

package ai.deepseek.dsh.android.protocol

import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull

/**
 * Tolerant readers used by every mapper and by the durable-event codec. They exist so a single
 * malformed or missing field degrades to `null` instead of throwing — a crash on an unrecognised
 * shape is exactly what D08/F16 forbid. None of these is `@Serializable`; they are pure navigation.
 */

internal fun JsonElement?.asObject(): JsonObject? = this as? JsonObject

/** String value; a JSON `null` reads as absent (`null`), an empty string stays an empty string. */
internal fun JsonObject.string(key: String): String? {
    val p = this[key] as? JsonPrimitive ?: return null
    return if (p is JsonNull) null else p.content
}

internal fun JsonObject.int(key: String): Int? = (this[key] as? JsonPrimitive)?.content?.toIntOrNull()

internal fun JsonObject.long(key: String): Long? = (this[key] as? JsonPrimitive)?.content?.toLongOrNull()

internal fun JsonObject.boolean(key: String): Boolean? = (this[key] as? JsonPrimitive)?.booleanOrNull

internal fun JsonObject.obj(key: String): JsonObject? = this[key] as? JsonObject

internal fun JsonObject.arr(key: String): JsonArray? = this[key] as? JsonArray

/** `JsonNull` and structural null both mean "absent" here. */
internal fun JsonElement?.isPresent(): Boolean = this != null && this !== JsonNull

/** Raw sub-element as canonical JSON text, used to store `payloadJson`/`argumentsJson` verbatim (D06). */
internal fun JsonElement?.rawOrEmptyObject(): String = when {
    isPresent() -> toString()
    else -> "{}"
}

/** Convenience for the request builders: a flat object of present fields only. */
internal fun buildObject(vararg pairs: Pair<String, JsonElement?>): JsonObject =
    JsonObject(pairs.filter { it.second.isPresent() }.associate { it.first to it.second!! })

internal val EMPTY_OBJ: JsonObject = JsonObject(emptyMap())

internal fun toJson(value: String): JsonElement = JsonPrimitive(value)

internal fun toJson(value: Long): JsonElement = JsonPrimitive(value)

internal fun toJson(value: Int): JsonElement = JsonPrimitive(value)

internal fun toJson(value: Boolean): JsonElement = JsonPrimitive(value)

internal fun JsonElement.asArrayOrEmpty(): JsonArray = if (this is JsonArray) this else JsonArray(emptyList())

internal inline fun <T> JsonObject.mapArr(key: String, transform: (JsonObject) -> T?): List<T> {
    val array = arr(key) ?: return emptyList()
    return array.mapNotNull { (it as? JsonObject)?.let(transform) }
}

package com.machankura.dedun.compose.network.serialization

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonBuilder

private val defaultJsonBuilder: (JsonBuilder.() -> Unit) = {
    ignoreUnknownKeys = true
    coerceInputValues = true
}

val CommonJson = Json {
    defaultJsonBuilder()
}

val CommonJsonImplicitNulls = Json {
    defaultJsonBuilder()
    explicitNulls = false
}

val CommonJsonEncodeDefaults = Json {
    defaultJsonBuilder()
    encodeDefaults = true
}



inline fun <reified T> Json.decodeFromStringOrNull(string: String?): T? {
    if (string.isNullOrEmpty()) return null

    return try {
        decodeFromString(string)
    } catch (_: Exception) {
        null
    }
}

inline fun <reified T> String?.decodeFromJsonStringOrNull(): T? {
    return CommonJson.decodeFromStringOrNull(this)
}

/**
 * Encodes an object to a JSON string using CommonJson serializer.
 *
 * Note: When working with JsonObject, use `toString()` instead of this function
 * to ensure proper formatting and compatibility with iOS.
 */
inline fun <reified T> T.encodeToJsonString(): String {
    return CommonJson.encodeToString(this)
}

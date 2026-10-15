package com.storytellerf.summer.data.llmd

import kotlinx.serialization.json.*

sealed interface LlmdStatus {
    data object Checking : LlmdStatus
    data object NotInstalled : LlmdStatus
    data object UpdateRequired : LlmdStatus
    data object AuthorizationRequired : LlmdStatus
    data class Available(
        val service: LlmdServiceStatus,
        val activeModel: String?,
        val selectedModel: String?,
        val models: List<String>,
    ) : LlmdStatus
    data class Unavailable(val message: String, val installationKnown: Boolean = false) : LlmdStatus
}

enum class LlmdServiceStatus(val value: String, val label: String) {
    Off("off", "Off"), Starting("starting", "Loading"), Ready("ready", "Ready"),
    Stopping("stopping", "Stopping"), Error("error", "Error"), Unknown("unknown", "Unknown"),
}

fun interface LlmdStatusReader {
    suspend fun read(target: LlmdTarget): LlmdStatus
}

internal fun authorizationRequired(response: String): Boolean =
    Json.parseToJsonElement(response).jsonObject["error"]?.jsonObject
        ?.get("type")?.jsonPrimitive?.contentOrNull == "authorization_required"

internal fun parseLlmdStatus(healthJson: String, modelsJson: String): LlmdStatus {
    if (authorizationRequired(healthJson) || authorizationRequired(modelsJson)) return LlmdStatus.AuthorizationRequired
    val health = Json.parseToJsonElement(healthJson).jsonObject
    val inventory = Json.parseToJsonElement(modelsJson).jsonObject
    require("error" !in health && "error" !in inventory) { "Could not read LLMD status" }
    fun model(key: String): String? {
        val value = health[key]?.jsonPrimitive ?: return null
        if (value is JsonNull) return null
        require(value.isString) { "Invalid model report" }
        return value.content.takeIf { it.isNotBlank() }
    }
    return LlmdStatus.Available(
        service = LlmdServiceStatus.entries.singleOrNull { it.value == health["serviceState"]?.jsonPrimitive?.contentOrNull }
            ?: LlmdServiceStatus.Unknown,
        activeModel = model("activeModel"),
        selectedModel = model("selectedModel"),
        models = inventory.getValue("data").jsonArray.map {
            val id = it.jsonObject.getValue("id").jsonPrimitive
            require(id.isString && id.content.isNotBlank()) { "Invalid model inventory" }
            id.content
        },
    )
}

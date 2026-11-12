package com.ny.my.health.core

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive

/** 사용자 식별자는 반환하거나 보관하지 않고 정확한 일치 여부만 판정한다. */
object DrivePrincipalProof {
    fun matches(payload: String, selectedName: String): Boolean = try {
        val user = (Json.parseToJsonElement(payload) as? JsonObject)?.get("user") as? JsonObject
        val email = user?.get("emailAddress") as? JsonPrimitive
        val me = user?.get("me") as? JsonPrimitive
        email != null && email.isString && email.content == selectedName &&
            me != null && !me.isString && me.content == "true"
    } catch (_: IllegalArgumentException) {
        false
    }
}

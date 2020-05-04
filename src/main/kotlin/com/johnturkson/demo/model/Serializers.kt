package com.johnturkson.demo.model

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonConfiguration

val httpRequestSerializer = Json(
    JsonConfiguration(
        encodeDefaults = true,
        ignoreUnknownKeys = true
    )
)

val websocketRequestSerializer = Json(
    JsonConfiguration(
        encodeDefaults = true,
        ignoreUnknownKeys = true,
        classDiscriminator = "channel"
    )
)

val websocketResponseSerializer = Json(
    JsonConfiguration(
        encodeDefaults = true,
        ignoreUnknownKeys = true,
        classDiscriminator = "name"
    )
)

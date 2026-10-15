package com.storytellerf.summer.data.llmd

/** Classifies service errors without exposing model paths or request contents. */
class LlmdRecognitionException internal constructor(message: String) : Exception(message)

internal fun llmdRecognitionError(type: String, message: String): LlmdRecognitionException =
    LlmdRecognitionException(when {
        type == "model_changed" -> "LLMD model changed during recognition; try again"
        message.contains("Model file", ignoreCase = true) -> "LLMD has no usable model; import a model in LLMD"
        message.contains("queue is full", ignoreCase = true) || message.contains("not ready", ignoreCase = true) ||
            message.contains("not initialized", ignoreCase = true) -> "LLMD engine is unavailable or busy; check model status in LLMD and try again"
        message.contains("schema", ignoreCase = true) || message.contains("grammar", ignoreCase = true) ||
            message.contains("response_format", ignoreCase = true) -> "LLMD could not generate structured recognition results; check LLMD version and model support"
        message.contains("permission", ignoreCase = true) || message.contains("URI", ignoreCase = true) ->
            "LLMD could not access the image; select the image again"
        else -> "LLMD recognition failed; check the engine error in LLMD"
    })

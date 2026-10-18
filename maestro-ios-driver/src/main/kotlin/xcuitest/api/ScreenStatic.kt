package xcuitest.api

import com.fasterxml.jackson.annotation.JsonIgnoreProperties

// smithr#1246 Phase C: POST /screenStatic — the settle's "is the screen static?" loop, run on the
// runner. Used only when GET /capabilities advertises `screenStatic` >= 1.

data class ScreenStaticRequest(val timeoutMs: Long)

@JsonIgnoreProperties(ignoreUnknown = true)
data class ScreenStaticResponse(
    val isScreenStatic: Boolean = false,
    val attempts: Int = 0,
    val frames: Int = 0,
    val elapsedMs: Long = 0,
)

package xcuitest.api

import com.fasterxml.jackson.annotation.JsonIgnoreProperties

// smithr#1246 Phase C: POST /screenStatic — the settle's "is the screen static?" loop, run on the
// runner. Used only when GET /capabilities advertises `screenStatic` >= 1.

// artha#5049: threshold, the fraction of pixels two frames may differ by and still be static
data class ScreenStaticRequest(val timeoutMs: Long, val threshold: Double = 0.0)

@JsonIgnoreProperties(ignoreUnknown = true)
data class ScreenStaticResponse(
    val isScreenStatic: Boolean = false,
    val attempts: Int = 0,
    val frames: Int = 0,
    val elapsedMs: Long = 0,
)

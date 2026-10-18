package xcuitest.api

import com.fasterxml.jackson.annotation.JsonIgnoreProperties
import com.fasterxml.jackson.annotation.JsonInclude

// smithr#1184: the XCTest runner's near-side lookups. The same messages as maestro_android.proto's
// capabilities/find/waitUntil, as JSON over the runner's HTTP server.

@JsonIgnoreProperties(ignoreUnknown = true)
data class NearSideCapabilities(
    val nearSide: Int = 0,
    val ops: List<String> = emptyList(),
    val selectorFields: List<String> = emptyList(),
    // smithr#1246 Phase C: >= 1 when the runner answers POST /screenStatic
    val screenStatic: Int = 0,
)

// Absent fields are left out: the runner refuses a field it does not know, and a null is not "any".
@JsonInclude(JsonInclude.Include.NON_NULL)
data class NearSideSelectorRequest(
    val textRegex: String? = null,
    val idRegex: String? = null,
    val enabled: Boolean? = null,
    val selected: Boolean? = null,
    val checked: Boolean? = null,
    val focused: Boolean? = null,
    val index: Int? = null,
    val below: NearSideSelectorRequest? = null,
    val above: NearSideSelectorRequest? = null,
    val leftOf: NearSideSelectorRequest? = null,
    val rightOf: NearSideSelectorRequest? = null,
)

// screenWidth/screenHeight 0 = the device's own size, as /deviceInfo reports it
data class NearSideFindRequest(
    val selector: NearSideSelectorRequest,
    val limit: Int,
    val screenWidth: Int = 0,
    val screenHeight: Int = 0,
)

data class NearSideWaitUntilRequest(
    val find: NearSideFindRequest,
    val condition: String,
    val timeoutMs: Long,
)

@JsonIgnoreProperties(ignoreUnknown = true)
data class NearSideElement(
    val attributes: Map<String, String> = emptyMap(),
    val clickable: Boolean? = null,
    val enabled: Boolean? = null,
    val focused: Boolean? = null,
    val checked: Boolean? = null,
    val selected: Boolean? = null,
)

@JsonIgnoreProperties(ignoreUnknown = true)
data class NearSideFindResponse(
    val matches: List<NearSideElement> = emptyList(),
    val truncated: Boolean = false,
    val deviceMs: Long = 0,
)

@JsonIgnoreProperties(ignoreUnknown = true)
data class NearSideWaitUntilResponse(
    val ok: Boolean,
    val elapsedMs: Long = 0,
    val last: NearSideFindResponse = NearSideFindResponse(),
)

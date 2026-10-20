package xcuitest

import com.fasterxml.jackson.core.JsonProcessingException
import com.fasterxml.jackson.module.kotlin.jacksonObjectMapper
import hierarchy.ViewHierarchy
import maestro.utils.HttpClient
import maestro.utils.network.XCUITestServerError
import okhttp3.*
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.RequestBody.Companion.toRequestBody
import org.slf4j.LoggerFactory
import xcuitest.api.*
import xcuitest.installer.XCTestInstaller
import kotlin.time.Duration.Companion.seconds

class XCTestDriverClient(
    private val installer: XCTestInstaller,
    private val okHttpClient: OkHttpClient = HttpClient.build(
        name = "XCTestDriverClient",
        readTimeout = 200.seconds,
        connectTimeout = 1.seconds,
        callTimeout = 200.seconds
    ),
    private val reinstallDriver: Boolean = true,
) {
    private val logger = LoggerFactory.getLogger(XCTestDriverClient::class.java)

    private lateinit var client: XCTestClient

    constructor(installer: XCTestInstaller, client: XCTestClient, reinstallDriver: Boolean = true): this(installer, reinstallDriver = reinstallDriver) {
        this.client = client
    }

    fun restartXCTestRunner() {
        // smithr physical-iOS: on a physical iPhone the XCTest runner is started outside
        // Maestro and is already serving on the driver port; uninstalling it here would
        // kill that runner. So a runner whose channel is alive is kept, and
        // installer.start() attaches to it; only a runner that does not answer is
        // uninstalled and reinstalled, as upstream does. A simulator whose runner is not
        // up yet takes the upstream path unchanged.
        if (reinstallDriver && !installer.isChannelAlive()) {
            logger.trace("Restarting XCTest Runner (uninstalling, installing and starting)")
            installer.uninstall()
            logger.trace("XCTest Runner uninstalled, will install and start it")
        } else if (reinstallDriver) {
            logger.info("[smithr-patch] XCTest runner already alive — skipping uninstall")
        }

        client = installer.start()
    }

    private val mapper = jacksonObjectMapper()

    fun viewHierarchy(installedApps: Set<String>, excludeKeyboardElements: Boolean): ViewHierarchy {
        val responseString = executeJsonRequest(
            "viewHierarchy",
            ViewHierarchyRequest(installedApps, excludeKeyboardElements)
        )
        return mapper.readValue(responseString, ViewHierarchy::class.java)
    }

    fun screenshot(compressed: Boolean): ByteArray {
        val url = client.xctestAPIBuilder("screenshot")
            .addQueryParameter("compressed", compressed.toString())
            .build()

        return executeJsonRequest(url)
    }

    fun terminateApp(appId: String) {
        executeJsonRequest("terminateApp", TerminateAppRequest(appId))
    }

    fun launchApp(appId: String) {
        executeJsonRequest("launchApp", LaunchAppRequest(appId))
    }

    fun keyboardInfo(installedApps: Set<String>): KeyboardInfoResponse {
        val response = executeJsonRequest(
            "keyboard",
            KeyboardInfoRequest(installedApps)
        )
        return mapper.readValue(response, KeyboardInfoResponse::class.java)
    }

    fun isScreenStatic(): IsScreenStaticResponse {
        val responseString = executeGetRequest("isScreenStatic", "threshold" to IosStaticThreshold.value().toString())
        return mapper.readValue(responseString, IsScreenStaticResponse::class.java)
    }

    fun runningAppId(appIds: Set<String>): GetRunningAppIdResponse {
        val response = executeJsonRequest(
            "runningApp",
            GetRunningAppRequest(appIds)
        )
        return mapper.readValue(response, GetRunningAppIdResponse::class.java)
    }

    @Deprecated("swipeV2 is the latest one getting used everywhere because it requires one http call")
    fun swipe(
        appId: String,
        startX: Double,
        startY: Double,
        endX: Double,
        endY: Double,
        duration: Double,
    ) {
        executeJsonRequest("swipe",
            SwipeRequest(
                appId = appId,
                startX = startX,
                startY = startY,
                endX = endX,
                endY = endY,
                duration = duration
            )
        )
    }

    fun swipeV2(
        installedApps: Set<String>,
        startX: Double,
        startY: Double,
        endX: Double,
        endY: Double,
        duration: Double,
    ) {
        executeJsonRequest("swipeV2",
            SwipeRequest(
                startX = startX,
                startY = startY,
                endX = endX,
                endY = endY,
                duration = duration,
                appIds = installedApps
            )
        )
    }

    fun inputText(
        text: String,
        appIds: Set<String>,
    ) {
        executeJsonRequest("inputText", InputTextRequest(text, appIds))
    }

    fun tap(
        x: Float,
        y: Float,
        duration: Double? = null,
    ) {
        executeJsonRequest("touch", TouchRequest(
            x = x,
            y = y,
            duration = duration
        ))
    }

    fun setOrientation(orientation: String) {
        executeJsonRequest("setOrientation", SetOrientationRequest(orientation))
    }

    fun pressKey(name: String) {
        executeJsonRequest("pressKey", PressKeyRequest(name))
    }

    fun pressButton(name: String) {
        executeJsonRequest("pressButton", PressButtonRequest(name))
    }

    fun eraseText(charactersToErase: Int, appIds: Set<String>) {
        executeJsonRequest("eraseText", EraseTextRequest(charactersToErase, appIds))
    }

    fun deviceInfo(httpUrl: HttpUrl = client.xctestAPIBuilder("deviceInfo").build()): DeviceInfo {
        val response = executeJsonRequest(httpUrl, Unit)
        return mapper.readValue(response, DeviceInfo::class.java)
    }

    fun isChannelAlive(): Boolean {
        return installer.isChannelAlive()
    }

    /** smithr#1184: what near-side lookups the runner answers, or null for a runner without them (404). */
    fun nearSideCapabilities(): NearSideCapabilities? {
        val request = Request.Builder()
            .url(client.xctestAPIBuilder("capabilities").build())
            .get()
            .build()

        return okHttpClient.newCall(request).execute().use { response ->
            val body = response.body?.string() ?: ""
            when {
                response.isSuccessful -> mapper.readValue(body, NearSideCapabilities::class.java)
                response.code == 404 -> null
                else -> {
                    handleExceptions(response.code, "capabilities", body)
                    null
                }
            }
        }
    }

    /** smithr#1184: resolve a selector on the device, polling there until [NearSideWaitUntilRequest.condition] holds. */
    fun nearSideWaitUntil(request: NearSideWaitUntilRequest): NearSideWaitUntilResponse {
        val response = executeJsonRequest("waitUntil", request)
        return mapper.readValue(response, NearSideWaitUntilResponse::class.java)
    }

    /** smithr#1246 Phase C: only called when [nearSideCapabilities] advertised `screenStatic`. */
    fun screenStatic(timeoutMs: Long): ScreenStaticResponse {
        val response = executeJsonRequest("screenStatic", ScreenStaticRequest(timeoutMs, IosStaticThreshold.value()))
        return mapper.readValue(response, ScreenStaticResponse::class.java)
    }

    fun close() {
        installer.close()
    }

    fun setPermissions(permissions: Map<String, String>) {
        executeJsonRequest("setPermissions", SetPermissionsRequest(permissions))
    }

    private fun executeJsonRequest(httpUrl: HttpUrl, body: Any): String {
        val mediaType = "application/json; charset=utf-8".toMediaType()
        val bodyData = mapper.writeValueAsString(body).toRequestBody(mediaType)

        val requestBuilder = Request.Builder()
            .addHeader("Content-Type", "application/json")
            .url(httpUrl)
            .post(bodyData)

        return okHttpClient
            .newCall(requestBuilder.build())
            .execute().use { processResponse(it, httpUrl.toString()) }
    }

    private fun executeJsonRequest(httpUrl: HttpUrl): ByteArray {
        val request = Request.Builder()
            .get()
            .url(httpUrl)
            .build()

        return okHttpClient
            .newCall(request)
            .execute().use {
                val bytes = it.body?.bytes() ?: ByteArray(0)
                if (!it.isSuccessful) {
                    //handle exception
                    val responseBodyAsString = String(bytes)
                    handleExceptions(it.code, request.url.pathSegments.first(), responseBodyAsString)
                }
                bytes
            }
    }

    private fun executeJsonRequest(pathSegment: String, body: Any): String {
        val mediaType = "application/json; charset=utf-8".toMediaType()
        val bodyData = mapper.writeValueAsString(body).toRequestBody(mediaType)

        val requestBuilder = Request.Builder()
            .addHeader("Content-Type", "application/json")
            .url(client.xctestAPIBuilder(pathSegment).build())
            .post(bodyData)

        return okHttpClient
            .newCall(requestBuilder.build())
            .execute().use { processResponse(it, pathSegment) }
    }

    private fun executeGetRequest(pathSegment: String, vararg query: Pair<String, String>): String {
        val url = client.xctestAPIBuilder(pathSegment)
            .apply { query.forEach { (name, value) -> addQueryParameter(name, value) } }
            .build()
        val requestBuilder = Request.Builder().url(url).get()

        return okHttpClient
            .newCall(requestBuilder.build())
            .execute().use { processResponse(it, pathSegment) }
    }

    private fun processResponse(response: Response, url: String): String {
        val responseBodyAsString = response.body?.bytes()?.let { bytes -> String(bytes) } ?: ""

        return if (!response.isSuccessful) {
            val code = response.code
            handleExceptions(code, url, responseBodyAsString)
        } else {
            responseBodyAsString
        }
    }

    /**
     * Maestro#3: the ONE place a 500 "XCTest recorded an issue during /<endpoint>" (artha#5055)
     * is classified. On a read-only probe in [POLLED_READ_ONLY_PROBES] it is a transient MISS:
     * the probe's natural negative answer when it has a safe one, else a
     * [XCUITestServerError.TransientIssue] for the polling caller to absorb. On anything else
     * (actions, and any 500 without the marker) this returns null and the 500 stays fatal.
     * Called only where the 500 would otherwise be an UnknownFailure: timeouts, 4xx and app
     * crashes keep their own classification.
     */
    private fun transientIssueMiss(code: Int, pathString: String, responseBodyAsString: String): String? {
        if (code != 500 || XCTEST_ISSUE_MARKER !in responseBodyAsString) return null
        if (pathString !in POLLED_READ_ONLY_PROBES) return null

        logger.warn("[DriverIssues] absorbed transient issue on /$pathString as a miss; the caller keeps polling. body: $responseBodyAsString")
        return POLLED_READ_ONLY_PROBES.getValue(pathString)
            ?: throw XCUITestServerError.TransientIssue(pathString, responseBodyAsString)
    }

    private fun handleExceptions(
        code: Int,
        pathString: String,
        responseBodyAsString: String,
    ): String {
        logger.warn("XCTestDriver request failed. Status code: $code, path: $pathString, body: $responseBodyAsString");
        val error = try {
            mapper.readValue(responseBodyAsString, Error::class.java)
        } catch (_: JsonProcessingException) {
            Error("Unable to parse error", "unknown")
        }
        when {
            code == 408 -> {
                logger.error("Request for $pathString timeout, body: $responseBodyAsString")
                throw XCUITestServerError.OperationTimeout(error.errorMessage, pathString)
            }
            code in 400..499 -> {
                logger.error("Request for $pathString failed with bad request ${code}, body: $responseBodyAsString")
                throw XCUITestServerError.BadRequest(
                    "Request for $pathString failed with bad request ${code}, body: $responseBodyAsString",
                    responseBodyAsString
                )
            }
            error.errorMessage.contains("Lost connection to the application.*".toRegex()) -> {
                logger.error("Request for $pathString failed, because of app crash, body: $responseBodyAsString")
                throw XCUITestServerError.AppCrash(
                    "Request for $pathString failed, due to app crash with message ${error.errorMessage}"
                )
            }
            error.errorMessage.contains("Application [a-zA-Z0-9.]+ is not running".toRegex()) -> {
                logger.error("Request for $pathString failed, because of app crash, body: $responseBodyAsString")
                throw XCUITestServerError.AppCrash(
                    "Request for $pathString failed, due to app crash with message ${error.errorMessage}"
                )
            }
            error.errorMessage.contains("Error getting main window kAXErrorCannotComplete") -> {
                logger.error("Request for $pathString failed, because of app crash, body: $responseBodyAsString")
                throw XCUITestServerError.AppCrash(
                    "Request for $pathString failed, due to app crash with message ${error.errorMessage}"
                )
            }
            error.errorMessage.contains("Error getting main window.*".toRegex()) -> {
                logger.error("Request for $pathString failed, because of app crash, body: $responseBodyAsString")
                throw XCUITestServerError.AppCrash(
                    "Request for $pathString failed, due to app crash with message ${error.errorMessage}"
                )
            }
            else -> {
                // Maestro#3: only what would be an UnknownFailure can be a miss; a crash stays a crash
                transientIssueMiss(code, pathString, responseBodyAsString)?.let { return it }
                logger.error("Request for $pathString failed, because of unknown reason, body: $responseBodyAsString")
                throw XCUITestServerError.UnknownFailure(
                    "Request for $pathString failed, code: ${code}, body: $responseBodyAsString"
                )
            }
        }
    }

    companion object {
        /** The runner's 500 body for a request during which XCTest recorded an issue (artha#5055). */
        const val XCTEST_ISSUE_MARKER = "XCTest recorded an issue"

        /**
         * Maestro#3: idempotent, read-only probes that their callers ALREADY poll inside a timeout,
         * each mapped to its miss. A JSON value is the probe's own negative answer, which the
         * caller already handles as "not yet"; null means no answer is safe (a lookup answered
         * "not found" would pass an assertNotVisible), so the miss is a TransientIssue that only
         * the polling caller absorbs. Actions (tap, inputText, swipe, launchApp, ...) are never here.
         */
        val POLLED_READ_ONLY_PROBES: Map<String, String?> = mapOf(
            // the settle loop (ScreenStatic.iosSettleStatic) asks again until static or its timeout
            "isScreenStatic" to """{"isScreenStatic":false}""",
            // artha#5049: the runner-side settle (fastStatic); ScreenStatic.iosSettleStatic falls
            // back to the upstream isScreenStatic loop on a miss, as on any runner failure
            "screenStatic" to null,
            // the near-side lookup; Maestro.findElementWithTimeout falls back to hierarchy polling
            "waitUntil" to null,
            // the hierarchy poll in Maestro.findElementWithTimeout asks again within its timeout
            "viewHierarchy" to null,
        )
    }
}

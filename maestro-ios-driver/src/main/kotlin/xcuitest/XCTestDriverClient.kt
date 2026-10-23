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
import java.util.concurrent.atomic.AtomicInteger
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
        val responseString = executeJsonRequest("isScreenStatic")
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
        val response = executeJsonRequest("screenStatic", ScreenStaticRequest(timeoutMs))
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

    private fun executeJsonRequest(pathSegment: String): String {
        val requestBuilder = Request.Builder()
            .url(client.xctestAPIBuilder(pathSegment).build())
            .get()

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

    /** smithr#1334: transient misses absorbed so far by this client, so a starved host stays visible. */
    private val transientMisses = AtomicInteger()
    val transientMissCount: Int get() = transientMisses.get()

    /**
     * Maestro#3: the ONE place a 500 on a polled read-only probe is classified as a transient MISS.
     * On a probe in [POLLED_READ_ONLY_PROBES] a 500 is a miss when [missReason] names why: the
     * probe's natural negative answer when it has a safe one, else a
     * [XCUITestServerError.TransientIssue] for the polling caller to absorb within its own timeout.
     * On anything else (actions, and any 500 without a known miss shape) this returns null and
     * the 500 stays fatal. Every miss is logged with a running count.
     */
    private fun transientIssueMiss(code: Int, pathString: String, responseBodyAsString: String): String? {
        val reason = missReason(code, pathString, responseBodyAsString) ?: return null

        val n = transientMisses.incrementAndGet()
        logger.warn("[DriverIssues] transient miss #$n on /$pathString ($reason): absorbed as a miss; the caller asks again within its own timeout. body: $responseBodyAsString")
        return POLLED_READ_ONLY_PROBES.getValue(pathString)
            ?: throw XCUITestServerError.TransientIssue(pathString, responseBodyAsString)
    }

    /** Why a 500 on a polled read-only probe is a miss, or null when it is not one. */
    private fun missReason(code: Int, pathString: String, responseBodyAsString: String): String? {
        if (code != 500 || pathString !in POLLED_READ_ONLY_PROBES) return null
        return when {
            // smithr#1334: the runner's bundle-ID lookup for the foreground app timed out (1 s)
            UNKNOWN_BUNDLE_ID_MARKER in responseBodyAsString -> "the runner's bundle-ID lookup timed out"
            // artha#5055 / Maestro#3
            XCTEST_ISSUE_MARKER in responseBodyAsString -> "XCTest recorded an issue"
            // smithr#1334: the near-side handler's own catch-all; the runner could not read the tree
            NEAR_SIDE_DRIVER_ERROR_MARKER in responseBodyAsString -> "near-side driverError"
            else -> null
        }
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
        // smithr#1334: "Application unknowBundleId is not running" is the runner failing to name the
        // foreground app in time, not the app stopping; the crash branches below would read it as
        // an AppCrash. On a polled read-only probe it is a miss; anywhere else it falls through.
        if (UNKNOWN_BUNDLE_ID_MARKER in responseBodyAsString) {
            transientIssueMiss(code, pathString, responseBodyAsString)?.let { return it }
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
         * smithr#1334: the near-side handlers' (/waitUntil) answer to ANY error the runner threw
         * while reading the tree, e.g. an AppError, whose `localizedDescription` the runner sends
         * as "The operation couldn't be completed. (maestro_driver_iosUITests.AppError error 1.)".
         * A request the runner could not answer, never an answer: CI run 39938290433.
         */
        const val NEAR_SIDE_DRIVER_ERROR_MARKER = "\"reason\":\"driverError\""

        /**
         * smithr#1334: the bundle ID the runner substitutes when its 1 s request for the foreground
         * process's bundle ID gets no reply (XCUIApplication+Helper.m), so the foreground app it
         * then asks for "is not running". Seen on a CPU-starved guest; the app was running.
         */
        const val UNKNOWN_BUNDLE_ID_MARKER = "Application unknowBundleId is not running"

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

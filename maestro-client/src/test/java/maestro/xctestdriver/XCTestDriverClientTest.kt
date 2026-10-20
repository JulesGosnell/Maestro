package maestro.xctestdriver

import com.fasterxml.jackson.module.kotlin.jacksonObjectMapper
import com.google.common.truth.Truth.assertThat
import maestro.ios.MockXCTestInstaller
import maestro.utils.network.XCUITestServerError
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okhttp3.mockwebserver.RecordedRequest
import okhttp3.mockwebserver.SocketPolicy
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.MethodSource
import xcuitest.XCTestClient
import xcuitest.IosStaticThreshold
import xcuitest.XCTestDriverClient
import xcuitest.api.DeviceInfo
import xcuitest.api.Error
import xcuitest.api.NearSideFindRequest
import xcuitest.api.NearSideSelectorRequest
import xcuitest.api.NearSideWaitUntilRequest
import java.net.InetAddress

class XCTestDriverClientTest {

    @Test
    fun `it should return the 4xx response as is without retrying`() {
        // given
        val mockWebServer = MockWebServer()
        val mapper = jacksonObjectMapper()
        val error = Error(errorMessage = "This is bad request, failure", errorCode = "bad-request")
        val mockResponse = MockResponse().apply {
            setResponseCode(401)
            setBody(mapper.writeValueAsString(error))
        }
        mockWebServer.enqueue(mockResponse)
        mockWebServer.start(InetAddress.getByName("localhost"), 22087)
        val httpUrl = mockWebServer.url("/deviceInfo")

        // when
        val simulator = MockXCTestInstaller.Simulator()
        val mockXCTestInstaller = MockXCTestInstaller(simulator)
        val xcTestDriverClient = XCTestDriverClient(
            mockXCTestInstaller,
            XCTestClient("localhost", 22087)
        )


        // then
        assertThrows<XCUITestServerError.BadRequest> {
            xcTestDriverClient.deviceInfo(httpUrl)
        }
        mockXCTestInstaller.assertInstallationRetries(0)
        mockWebServer.shutdown()
    }

    @Test
    fun `it should return the 200 response as is without retrying`() {
        // given
        val mockWebServer = MockWebServer()
        val mapper = jacksonObjectMapper()
        val expectedDeviceInfo = DeviceInfo(1123, 5000, 1223, 1123)
        val mockResponse = MockResponse().apply {
            setResponseCode(200)
            setBody(mapper.writeValueAsString(expectedDeviceInfo))
        }
        mockWebServer.enqueue(mockResponse)
        mockWebServer.start(InetAddress.getByName("localhost"), 22087)
        val httpUrl = mockWebServer.url("/deviceInfo")

        // when
        val simulator = MockXCTestInstaller.Simulator()
        val mockXCTestInstaller = MockXCTestInstaller(simulator)
        val xcTestDriverClient = XCTestDriverClient(
            mockXCTestInstaller,
            XCTestClient("localhost", 22087)
        )
        val actualDeviceInfo = xcTestDriverClient.deviceInfo(httpUrl)

        // then
        assertThat(actualDeviceInfo).isEqualTo(expectedDeviceInfo)
        mockXCTestInstaller.assertInstallationRetries(0)
        mockWebServer.shutdown()
    }

    @ParameterizedTest
    @MethodSource("provideAppCrashMessage")
    fun `it should throw app crash exception correctly`(errorMessage: String) {
        // given
        val mockWebServer = MockWebServer()
        val mapper = jacksonObjectMapper()
        val expectedDeviceInfo = Error(errorMessage = errorMessage, errorCode = "internal")
        val mockResponse = MockResponse().apply {
            setResponseCode(500)
            setBody(mapper.writeValueAsString(expectedDeviceInfo))
        }
        mockWebServer.enqueue(mockResponse)
        mockWebServer.start(InetAddress.getByName( "localhost"), 22087)
        val httpUrl = mockWebServer.url("/deviceInfo")

        // when
        val simulator = MockXCTestInstaller.Simulator()
        val mockXCTestInstaller = MockXCTestInstaller(simulator)
        val xcTestDriverClient = XCTestDriverClient(
            mockXCTestInstaller,
            XCTestClient("localhost", 22087)
        )


        // then
        assertThrows<XCUITestServerError.AppCrash> {
            xcTestDriverClient.deviceInfo(httpUrl)
        }
        mockXCTestInstaller.assertInstallationRetries(0)
        mockWebServer.shutdown()
    }

    // ── Maestro#3: a 500 "XCTest recorded an issue" on a polled read-only probe is a miss ──

    private fun issueBody(endpoint: String, detail: String = "Failed to resolve query: Failed to resolve remote element") =
        jacksonObjectMapper().writeValueAsString(
            Error(errorMessage = "XCTest recorded an issue during /$endpoint: $detail", errorCode = "internal")
        )

    private fun <T> against500(body: String, call: (XCTestDriverClient) -> T): T {
        val mockWebServer = MockWebServer()
        mockWebServer.enqueue(MockResponse().setResponseCode(500).setBody(body))
        mockWebServer.start(InetAddress.getByName("localhost"), 22087)
        try {
            val mockXCTestInstaller = MockXCTestInstaller(MockXCTestInstaller.Simulator())
            val client = XCTestDriverClient(mockXCTestInstaller, XCTestClient("localhost", 22087))
            return call(client).also { mockXCTestInstaller.assertInstallationRetries(0) }
        } finally {
            mockWebServer.shutdown()
        }
    }

    private fun <T> against200(body: String, call: (XCTestDriverClient) -> T): Pair<T, RecordedRequest> {
        val mockWebServer = MockWebServer()
        mockWebServer.enqueue(MockResponse().setResponseCode(200).setBody(body))
        mockWebServer.start(InetAddress.getByName("localhost"), 22087)
        try {
            val client = XCTestDriverClient(MockXCTestInstaller(MockXCTestInstaller.Simulator()), XCTestClient("localhost", 22087))
            val result = call(client)
            return result to mockWebServer.takeRequest()
        } finally {
            mockWebServer.shutdown()
        }
    }

    private fun <T> withThreshold(value: String?, block: () -> T): T {
        val previous = System.getProperty(IosStaticThreshold.PROPERTY)
        if (value == null) System.clearProperty(IosStaticThreshold.PROPERTY) else System.setProperty(IosStaticThreshold.PROPERTY, value)
        try {
            return block()
        } finally {
            if (previous == null) System.clearProperty(IosStaticThreshold.PROPERTY) else System.setProperty(IosStaticThreshold.PROPERTY, previous)
        }
    }

    @Test
    fun `artha#5049 - isScreenStatic asks the runner for the default 0_05 percent threshold`() {
        val (response, request) = withThreshold(null) { against200("""{"isScreenStatic":true}""") { it.isScreenStatic() } }
        assertThat(response.isScreenStatic).isTrue()
        assertThat(request.requestUrl!!.encodedPath).isEqualTo("/isScreenStatic")
        assertThat(request.requestUrl!!.queryParameter("threshold")!!.toDouble()).isEqualTo(0.0005)
    }

    @Test
    fun `artha#5049 - the -D flag sets the threshold, 0 restoring the exact comparison`() {
        val (_, request) = withThreshold("0") { against200("""{"isScreenStatic":false}""") { it.isScreenStatic() } }
        assertThat(request.requestUrl!!.queryParameter("threshold")!!.toDouble()).isEqualTo(0.0)
    }

    @Test
    fun `artha#5049 - an unreadable threshold is the default`() {
        assertThat(withThreshold("lots") { IosStaticThreshold.value() }).isEqualTo(IosStaticThreshold.DEFAULT)
        assertThat(withThreshold("1.5") { IosStaticThreshold.value() }).isEqualTo(IosStaticThreshold.DEFAULT)
    }

    @Test
    fun `artha#5049 - screenStatic carries the threshold in its body`() {
        val (_, request) = withThreshold(null) {
            against200("""{"isScreenStatic":true,"attempts":1,"frames":2,"elapsedMs":10}""") { it.screenStatic(3000) }
        }
        val body = jacksonObjectMapper().readTree(request.body.readUtf8())
        assertThat(body["timeoutMs"].asLong()).isEqualTo(3000)
        assertThat(body["threshold"].asDouble()).isEqualTo(0.0005)
    }

    @Test
    fun `isScreenStatic 500 with the XCTest-issue marker is not-static, not a throw`() {
        val body = issueBody("isScreenStatic", "Failed to get screenshot: Timed out while requesting screenshot.")
        val response = against500(body) { it.isScreenStatic() }
        assertThat(response.isScreenStatic).isFalse()
    }

    @Test
    fun `waitUntil 500 with the XCTest-issue marker is a TransientIssue miss, not an UnknownFailure`() {
        val error = assertThrows<XCUITestServerError.TransientIssue> {
            against500(issueBody("waitUntil")) { it.nearSideWaitUntil(aWaitUntil()) }
        }
        assertThat(error.endpoint).isEqualTo("waitUntil")
    }

    @Test
    fun `viewHierarchy 500 with the XCTest-issue marker is a TransientIssue miss, not an UnknownFailure`() {
        val error = assertThrows<XCUITestServerError.TransientIssue> {
            against500(issueBody("viewHierarchy")) { it.viewHierarchy(emptySet(), false) }
        }
        assertThat(error.endpoint).isEqualTo("viewHierarchy")
    }

    @Test
    fun `artha#5049 - the CI kAXErrorInvalidUIElement viewHierarchy 500, verbatim, is a TransientIssue miss`() {
        // run 39349667231, zeuglodon carer: the body as the runner sent it
        val body = """{"errorMessage":"XCTest recorded an issue during \/viewHierarchy: Failed to resolve query: Failed to resolve remote element AX element pid: 17212, elementOrHash.elementID: 1506933048378721526.9999. (Underlying Error: Error kAXErrorInvalidUIElement getting snapshot for element <AXUIElementRef 0x600000c197d0> {pid=17212} {uid=[ID:9999 hash:0x14e9b39f98e904f6]})","code":"internal"}"""
        val error = assertThrows<XCUITestServerError.TransientIssue> {
            against500(body) { it.viewHierarchy(emptySet(), false) }
        }
        assertThat(error.endpoint).isEqualTo("viewHierarchy")
    }

    @Test
    fun `artha#5049 - the CI isScreenStatic screenshot-timeout 500, verbatim, is not-static`() {
        // run 39330293260, megalodon family
        val body = """{"code":"internal","errorMessage":"XCTest recorded an issue during \/isScreenStatic: Failed to get screenshot: Timed out while requesting screenshot."}"""
        val response = against500(body) { it.isScreenStatic() }
        assertThat(response.isScreenStatic).isFalse()
    }

    @Test
    fun `screenStatic 500 with the XCTest-issue marker is a TransientIssue miss, not an UnknownFailure`() {
        val error = assertThrows<XCUITestServerError.TransientIssue> {
            against500(issueBody("screenStatic", "Failed to get screenshot: Timed out while requesting screenshot.")) {
                it.screenStatic(500)
            }
        }
        assertThat(error.endpoint).isEqualTo("screenStatic")
    }

    @ParameterizedTest
    @MethodSource("provideActions")
    fun `an ACTION 500 with the XCTest-issue marker is still fatal`(action: Action) {
        assertThrows<XCUITestServerError.UnknownFailure> {
            against500(issueBody(action.endpoint)) { action.call(it) }
        }
    }

    @ParameterizedTest
    @MethodSource("provideProbes")
    fun `a read-only probe 500 WITHOUT the marker is still fatal`(probe: Action) {
        val body = jacksonObjectMapper().writeValueAsString(
            Error(errorMessage = "Something else went wrong during /${probe.endpoint}", errorCode = "internal")
        )
        assertThrows<XCUITestServerError.UnknownFailure> {
            against500(body) { probe.call(it) }
        }
    }

    @Test
    fun `a read-only probe 500 with the marker AND an app crash is still an AppCrash`() {
        assertThrows<XCUITestServerError.AppCrash> {
            against500(issueBody("viewHierarchy", "Lost connection to the application (pid 19985).")) {
                it.viewHierarchy(emptySet(), false)
            }
        }
    }

    class Action(val endpoint: String, val call: (XCTestDriverClient) -> Any?) {
        override fun toString() = endpoint
    }

    companion object {

        private fun aWaitUntil() = NearSideWaitUntilRequest(
            find = NearSideFindRequest(selector = NearSideSelectorRequest(idRegex = "button-continue"), limit = 10),
            condition = "visible",
            timeoutMs = 500,
        )

        @JvmStatic
        fun provideActions(): List<Action> = listOf(
            Action("touch") { it.tap(1f, 2f) },
            Action("inputText") { it.inputText("x", emptySet()) },
            Action("swipeV2") { it.swipeV2(emptySet(), 0.0, 0.0, 1.0, 1.0, 0.1) },
            Action("launchApp") { it.launchApp("com.app.id") },
            Action("terminateApp") { it.terminateApp("com.app.id") },
            Action("pressKey") { it.pressKey("delete") },
            Action("eraseText") { it.eraseText(3, emptySet()) },
        )

        @JvmStatic
        fun provideProbes(): List<Action> = listOf(
            Action("isScreenStatic") { it.isScreenStatic() },
            Action("waitUntil") { it.nearSideWaitUntil(aWaitUntil()) },
            Action("viewHierarchy") { it.viewHierarchy(emptySet(), false) },
        )

        @JvmStatic
        fun provideAppCrashMessage(): Array<String> {
            return arrayOf(
                "Application com.app.id is not running",
                "Lost connection to the application (pid 19985).",
                "Error getting main window kAXErrorCannotComplete",
                "Error getting main window Unknown kAXError value -25218"
            )
        }
    }
}

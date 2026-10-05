package maestro

import com.google.common.truth.Truth.assertThat
import com.sun.net.httpserver.HttpServer
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import java.lang.reflect.Proxy
import java.net.InetSocketAddress

/**
 * smithr#974: setAirplaneMode -> the lease's network cut. A fake smithr control
 * plane records every request and answers with whatever the test scripts.
 */
class SmithrNetworkCutTest {

    data class Req(val method: String, val path: String, val contentType: String?, val body: String)

    private lateinit var server: HttpServer
    private val requests = mutableListOf<Req>()
    private var replyStatus = 200
    private var replyBody = """{"network":"offline","verify":{"status":"VERIFIED","probes":[]}}"""
    private var envMap = mutableMapOf<String, String>()

    @BeforeEach
    fun setUp() {
        server = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0)
        server.createContext("/") { ex ->
            requests += Req(
                ex.requestMethod, ex.requestURI.path,
                ex.requestHeaders.getFirst("Content-Type"),
                ex.requestBody.readBytes().decodeToString(),
            )
            val bytes = replyBody.toByteArray()
            ex.sendResponseHeaders(replyStatus, bytes.size.toLong())
            ex.responseBody.use { it.write(bytes) }
        }
        server.start()
        envMap = mutableMapOf(
            "SMITHR_LEASE_ID" to "lease-abc-123",
            "SMITHR_GRANTER_URL" to "http://127.0.0.1:${server.address.port}/",
        )
        SmithrNetworkCut.env = { envMap[it] }
        // Clean slate: force the recorded state back to online via a VERIFIED call.
        SmithrNetworkCut.restoreIfOffline()
        requests.clear()
    }

    @AfterEach
    fun tearDown() {
        replyStatus = 200
        replyBody = """{"network":"online","verify":{"status":"VERIFIED"}}"""
        SmithrNetworkCut.restoreIfOffline()
        server.stop(0)
        SmithrNetworkCut.env = System::getenv
    }

    @Test
    fun `airplane ON posts offline to the injected lease on the injected granter`() {
        SmithrNetworkCut.set(true)
        assertThat(requests).containsExactly(
            Req("POST", "/api/leases/lease-abc-123/network", "application/json", """{"state":"offline"}""")
        )
        assertThat(SmithrNetworkCut.offline).isTrue()
    }

    @Test
    fun `airplane OFF posts online`() {
        SmithrNetworkCut.set(true)
        requests.clear()
        replyBody = """{"network":"online","verify":{"status":"VERIFIED"}}"""
        SmithrNetworkCut.set(false)
        assertThat(requests.single().body).isEqualTo("""{"state":"online"}""")
        assertThat(SmithrNetworkCut.offline).isFalse()
    }

    @Test
    fun `missing lease id FAILS and sends nothing`() {
        envMap.remove("SMITHR_LEASE_ID")
        val e = assertThrows<SmithrNetworkCutException> { SmithrNetworkCut.set(true) }
        assertThat(e.message).contains("setAirplaneMode: no smithr lease id injected")
        assertThat(requests).isEmpty()
    }

    @Test
    fun `blank lease id FAILS`() {
        envMap["SMITHR_LEASE_ID"] = "  "
        assertThrows<SmithrNetworkCutException> { SmithrNetworkCut.set(true) }
        assertThat(requests).isEmpty()
    }

    @Test
    fun `missing granter url FAILS and sends nothing`() {
        envMap.remove("SMITHR_GRANTER_URL")
        val e = assertThrows<SmithrNetworkCutException> { SmithrNetworkCut.set(true) }
        assertThat(e.message).contains("no smithr control-plane URL injected")
        assertThat(requests).isEmpty()
    }

    @Test
    fun `UNVERIFIED on 200 FAILS with the body`() {
        replyBody = """{"network":"offline","verify":{"status":"UNVERIFIED","probes":["unreadable"]}}"""
        val e = assertThrows<SmithrNetworkCutException> { SmithrNetworkCut.set(true) }
        assertThat(e.message).contains("UNVERIFIED")
        assertThat(e.message).contains("unreadable")
        assertThat(SmithrNetworkCut.offline).isFalse()
    }

    @Test
    fun `FAILED 502 FAILS with the body`() {
        replyStatus = 502
        replyBody = """{"network":"offline","verify":{"status":"FAILED","probes":["still answers"]}}"""
        val e = assertThrows<SmithrNetworkCutException> { SmithrNetworkCut.set(true) }
        assertThat(e.message).contains("HTTP 502")
        assertThat(e.message).contains("still answers")
    }

    @Test
    fun `421 wrong instance FAILS naming the granter, never retries elsewhere`() {
        replyStatus = 421
        replyBody = """{"error":"wrong_instance","granted_by":"prognathodon"}"""
        val e = assertThrows<SmithrNetworkCutException> { SmithrNetworkCut.set(true) }
        assertThat(e.message).contains("prognathodon")
        assertThat(requests).hasSize(1)
    }

    @Test
    fun `501 physical FAILS`() {
        replyStatus = 501
        replyBody = """{"error":"not_supported"}"""
        assertThrows<SmithrNetworkCutException> { SmithrNetworkCut.set(true) }
    }

    @Test
    fun `non-2xx even with a VERIFIED body FAILS`() {
        replyStatus = 500
        assertThrows<SmithrNetworkCutException> { SmithrNetworkCut.set(true) }
    }

    @Test
    fun `non-JSON body FAILS`() {
        replyBody = "<html>proxy error</html>"
        val e = assertThrows<SmithrNetworkCutException> { SmithrNetworkCut.set(true) }
        assertThat(e.message).contains("proxy error")
    }

    @Test
    fun `unreachable granter FAILS`() {
        envMap["SMITHR_GRANTER_URL"] = "http://127.0.0.1:1"
        assertThrows<SmithrNetworkCutException> { SmithrNetworkCut.set(true) }
    }

    // ── Wiring through maestro.Maestro, both platforms' entry point ──────────

    private val driverCalls = mutableListOf<String>()
    private fun fakeDriver(): Driver = Proxy.newProxyInstance(
        Driver::class.java.classLoader, arrayOf(Driver::class.java)
    ) { _, m, _ ->
        driverCalls += m.name
        when (m.returnType) {
            java.lang.Boolean.TYPE -> false
            else -> null
        }
    } as Driver

    @Test
    fun `Maestro setAirplaneModeState goes to smithr, never to the driver`() {
        val maestro = Maestro(fakeDriver())
        maestro.setAirplaneModeState(true)
        assertThat(requests.single().body).isEqualTo("""{"state":"offline"}""")
        assertThat(maestro.isAirplaneModeEnabled()).isTrue()
        assertThat(driverCalls).doesNotContain("setAirplaneMode")
        assertThat(driverCalls).doesNotContain("isAirplaneModeEnabled")
    }

    @Test
    fun `Maestro close restores ONLINE when the session left it offline`() {
        val maestro = Maestro(fakeDriver())
        maestro.setAirplaneModeState(true)
        requests.clear()
        replyBody = """{"network":"online","verify":{"status":"VERIFIED"}}"""
        maestro.close()
        assertThat(requests.single().body).isEqualTo("""{"state":"online"}""")
        assertThat(driverCalls).contains("close")
    }

    @Test
    fun `Maestro close sends nothing when the session never went offline`() {
        Maestro(fakeDriver()).close()
        assertThat(requests).isEmpty()
    }
}

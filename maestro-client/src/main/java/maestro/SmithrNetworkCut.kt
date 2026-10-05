package maestro

import com.fasterxml.jackson.databind.ObjectMapper
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.time.Duration

/**
 * smithr: `setAirplaneMode` is the lease's per-lease network cut (smithr#974).
 *
 * Airplane ON  => POST <granter>/api/leases/<id>/network {"state":"offline"}
 * Airplane OFF => POST <granter>/api/leases/<id>/network {"state":"online"}
 *
 * The lease id and the GRANTING control plane's URL are INJECTED by the sidecar
 * (SMITHR_LEASE_ID / SMITHR_GRANTER_URL, read from the proxy's lease.env). They
 * are never guessed: a wrong id cuts another device. Absent => the step FAILS.
 *
 * Success is ONLY verify.status == VERIFIED. UNVERIFIED, FAILED and any non-2xx
 * fail the step with the response body in the message.
 */
class SmithrNetworkCutException(message: String, cause: Throwable? = null) : RuntimeException(message, cause)

object SmithrNetworkCut {

    const val LEASE_ID_ENV = "SMITHR_LEASE_ID"
    const val GRANTER_URL_ENV = "SMITHR_GRANTER_URL"

    /** What THIS process last set and smithr VERIFIED. true = offline. */
    @Volatile
    var offline: Boolean = false
        private set

    @Volatile
    private var hookInstalled = false

    // Seams for the test harness only.
    var env: (String) -> String? = System::getenv
    var timeout: Duration = Duration.ofSeconds(120)

    @Synchronized
    fun set(airplaneOn: Boolean) {
        val state = if (airplaneOn) "offline" else "online"
        val leaseId = env(LEASE_ID_ENV)?.trim().orEmpty()
        val granter = env(GRANTER_URL_ENV)?.trim()?.trimEnd('/').orEmpty()
        if (leaseId.isEmpty()) {
            throw SmithrNetworkCutException("setAirplaneMode: no smithr lease id injected ($LEASE_ID_ENV unset)")
        }
        if (granter.isEmpty()) {
            throw SmithrNetworkCutException("setAirplaneMode: no smithr control-plane URL injected ($GRANTER_URL_ENV unset)")
        }
        val url = "$granter/api/leases/$leaseId/network"
        val response = try {
            HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(10)).build().send(
                HttpRequest.newBuilder(URI.create(url))
                    .timeout(timeout)
                    .header("Content-Type", "application/json")
                    .POST(HttpRequest.BodyPublishers.ofString("{\"state\":\"$state\"}"))
                    .build(),
                HttpResponse.BodyHandlers.ofString(),
            )
        } catch (e: Exception) {
            throw SmithrNetworkCutException("setAirplaneMode($state): POST $url failed: ${e.message}", e)
        }
        val body = response.body().orEmpty()
        val verify = runCatching { ObjectMapper().readTree(body).path("verify").path("status").asText("") }
            .getOrDefault("")
        if (response.statusCode() !in 200..299 || verify != "VERIFIED") {
            throw SmithrNetworkCutException(
                "setAirplaneMode($state): smithr did not VERIFY the cut " +
                    "(HTTP ${response.statusCode()}, verify.status=${verify.ifEmpty { "<none>" }}) POST $url -> $body"
            )
        }
        offline = airplaneOn
        if (airplaneOn && !hookInstalled) {
            hookInstalled = true
            Runtime.getRuntime().addShutdownHook(Thread { restoreIfOffline() })
        }
    }

    /** A journey that dies offline must still end ONLINE. Never throws. */
    fun restoreIfOffline() {
        if (!offline) return
        runCatching { set(false) }.onFailure {
            System.err.println("[smithr] restoring lease network to online FAILED (lease release will restore it): ${it.message}")
        }
    }
}

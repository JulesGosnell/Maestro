package maestro.utils.network

class InputFieldNotFound : Throwable("Unable to find focused input field")
class UnknownFailure(errorResponse: String) : Throwable(errorResponse)

sealed class XCUITestServerResult<out T> {
    data class Success<T>(val data: T): XCUITestServerResult<T>()
    data class Failure(val errors: XCUITestServerError): XCUITestServerResult<Nothing>()
}

sealed class XCUITestServerError: Throwable() {
    data class UnknownFailure(val errorResponse: String) : XCUITestServerError()
    data class NetworkError(val errorResponse: String): XCUITestServerError()
    data class AppCrash(val errorResponse: String): XCUITestServerError()
    data class OperationTimeout(val errorResponse: String, val operation: String): XCUITestServerError()
    data class BadRequest(val errorResponse: String, val clientMessage: String): XCUITestServerError()

    /**
     * Maestro#3: the XCTest runner answered 500 "XCTest recorded an issue during /<endpoint>"
     * (artha#5055) to a read-only probe whose caller polls it, and there is no answer the caller
     * could take as "not yet" without risk — for a lookup, "not found" would PASS an
     * assertNotVisible. It is a MISS, not an answer: a polling caller asks again within its own
     * timeout and never lets a miss stand in for its last answer. Anywhere else it is fatal,
     * like the UnknownFailure it replaces. Not an Exception on purpose: only the sites that
     * name it absorb it.
     */
    data class TransientIssue(val endpoint: String, val errorResponse: String): XCUITestServerError() {
        override val message: String get() = "XCTest recorded an issue during /$endpoint (transient miss): $errorResponse"
    }
}
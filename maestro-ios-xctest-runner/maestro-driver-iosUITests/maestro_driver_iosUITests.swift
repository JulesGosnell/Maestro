import XCTest
import FlyingFox
import os

final class maestro_driver_iosUITests: XCTestCase {
   
    private static let logger = Logger(
        subsystem: Bundle.main.bundleIdentifier!,
        category: "maestro_driver_iosUITests"
    )

    private static var swizzledOutIdle = false

    override func setUpWithError() throws {
        // XCTest internals sometimes use XCTAssert* instead of exceptions.
        // Setting `continueAfterFailure` so that the xctest runner does not stop
        // when an XCTest internal error happes (eg: when using .allElementsBoundByIndex
        // on a ReactNative app)
        continueAfterFailure = true
    }

    override class func setUp() {
        logger.trace("setUp")
    }

    func testHttpServer() async throws {
        let server = XCTestHTTPServer()
        maestro_driver_iosUITests.logger.info("Will start HTTP server")
        try await server.start()
    }

    // artha#5055: an XCTest issue must not end the server.
    //
    // testHttpServer never returns: it IS the HTTP server. An XCUI call that a driver request
    // makes can record an issue: a screenshot that times out, or a query whose element vanished
    // mid-resolve (kAXErrorInvalidUIElement on a StatusBar). Even with continueAfterFailure = true,
    // XCTest then begins Tear Down while the server task keeps answering requests. The next XCUI
    // call raises "Nesting the use of control flow interruptions (exceptions) is not permitted"
    // (NSInternalInconsistencyException), xcodebuild exits with code 75, and Maestro's next
    // request gets connection refused.
    //
    // So the issue is never handed to XCTest (no super.record). It is logged instead, and
    // DriverIssues counts it, so the request that caused it answers an error (see
    // IssueGuardedHandler) and the next request is served normally. The log line keeps the
    // "error: -[maestro_driver_iosUITests…" text that XCTest itself would have printed, so any
    // grep for runner issues still finds it.
    //
    // The one exception is an error THROWN by testHttpServer itself (e.g. the port is taken:
    // "Bind … Address already in use"). The server is already gone, so the test must fail as
    // before, and xcodebuild must report it. A route handler's error never reaches here:
    // FlyingFox turns it into a 500.
    override func record(_ issue: XCTIssue) {
        if issue.type == .thrownError {
            super.record(issue)
            return
        }
        DriverIssues.record(issue)
    }

    override class func tearDown() {
        logger.trace("tearDown")
    }
}

/// artha#5055: the XCTest issues the runner swallowed (see `record(_:)` above), counted so a
/// route handler can tell whether one was recorded while it ran.
enum DriverIssues {
    private static let lock = NSLock()
    private static var issues: [String] = []
    private static var total = 0
    private static let keep = 32

    static func record(_ issue: XCTIssue) {
        let text = issue.compactDescription
        NSLog("[DriverIssues] artha#5055 swallowed an XCTest issue; the server keeps running: error: -[maestro_driver_iosUITests.maestro_driver_iosUITests testHttpServer] : %@", text)
        lock.lock(); defer { lock.unlock() }
        issues.append(text)
        if issues.count > keep { issues.removeFirst(issues.count - keep) }
        total += 1
    }

    /// Monotonic count of issues recorded so far.
    static var count: Int { lock.lock(); defer { lock.unlock() }; return total }

    /// The issues recorded since `count` returned `mark` (at most the last `keep`).
    static func since(_ mark: Int) -> [String] {
        lock.lock(); defer { lock.unlock() }
        let n = min(total - mark, issues.count)
        return n > 0 ? Array(issues.suffix(n)) : []
    }
}

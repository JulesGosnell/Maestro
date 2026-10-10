import XCTest
import FlyingFox

/// #4697: an NSException raised inside a route handler escaped Swift's `catch`, reached XCTest's
/// own handler, which asserted and tore the runner down. It must come back as an HTTP 500, and the
/// server must go on serving.
final class ObjCExceptionTests: XCTestCase {

    func testRaisedExceptionBecomesAnErrorNamingIt() {
        XCTAssertThrowsError(try catchingObjCException { raiseStaleSnapshot() }) { error in
            XCTAssertEqual(error.localizedDescription, "NSException NSInternalInconsistencyException: stale snapshot")
        }
    }

    func testValuesAndSwiftErrorsPassThrough() throws {
        XCTAssertEqual(try catchingObjCException { 42 }, 42)
        XCTAssertThrowsError(try catchingObjCException { throw AppError(type: .timeout, message: "slow") }) { error in
            XCTAssertEqual((error as? AppError)?.type, .timeout)
        }
    }

    func testHandlerThatRaisesAnswers500AndTheServerKeepsServing() async throws {
        let server = HTTPServer(address: try .inet(ip4: "127.0.0.1", port: 22187), timeout: 10)
        await server.appendRoute("raise", to: RaisingHandler())
        await server.appendRoute("ok") { _ in HTTPResponse(statusCode: .ok) }
        let serving = Task { try await server.run() }
        defer { serving.cancel() }
        try await server.waitUntilListening()

        for _ in 0..<3 {
            let (body, status) = try await post("raise")
            XCTAssertEqual(status, 500)
            XCTAssertTrue(String(decoding: body, as: UTF8.self).contains("NSException NSInternalInconsistencyException: stale snapshot"))
        }
        let (_, status) = try await post("ok")
        XCTAssertEqual(status, 200)
    }

    private func post(_ route: String) async throws -> (Data, Int) {
        var request = URLRequest(url: URL(string: "http://127.0.0.1:22187/\(route)")!)
        request.httpMethod = "POST"
        let (data, response) = try await URLSession.shared.data(for: request)
        return (data, (response as! HTTPURLResponse).statusCode)
    }
}

private func raiseStaleSnapshot() {
    NSException(name: .internalInconsistencyException, reason: "stale snapshot", userInfo: nil).raise()
}

/// Shaped like the runner's handlers: main actor, an await, then a synchronous stretch that raises.
@MainActor
private struct RaisingHandler: HTTPHandler {
    func handleRequest(_ request: HTTPRequest) async throws -> HTTPResponse {
        _ = try await request.bodyData
        do {
            try catchingObjCException { raiseStaleSnapshot() }
            return HTTPResponse(statusCode: .ok)
        } catch {
            return AppError(message: "Raising handler failed. Error: \(error.localizedDescription)").httpResponse
        }
    }
}

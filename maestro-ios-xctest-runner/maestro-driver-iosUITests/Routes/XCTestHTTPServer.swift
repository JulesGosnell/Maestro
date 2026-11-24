import FlyingFox
import Foundation

enum Route: String, CaseIterable {
    case runningApp
    case swipe
    case swipeV2
    case inputText
    case touch
    case screenshot
    case isScreenStatic
    case pressKey
    case pressButton
    case eraseText
    case deviceInfo
    case setOrientation
    case setPermissions
    case viewHierarchy
    case status
    case keyboard
    case launchApp
    case terminateApp
    case capabilities
    case find
    case waitUntil
    case screenStatic

    func toHTTPRoute() -> HTTPRoute {
        return HTTPRoute(rawValue)
    }
}

/// smithr (artha#5603): the runner's per-request limit, from the env the client launches it with
/// (IOSDriverTimeouts.kt). A missing or bad value keeps the default; never the old 100 s.
enum DriverTimeouts {
    static let variable = "MAESTRO_IOS_DRIVER_TIMEOUT_MS"
    static let defaultMs = 600_000

    static func handlerMs(environment: [String: String]) -> Int {
        guard let raw = environment[variable], let ms = Int(raw.trimmingCharacters(in: .whitespaces)), ms > 0 else {
            return defaultMs
        }
        return ms
    }

    static func handlerSeconds(environment: [String: String]) -> TimeInterval {
        TimeInterval(handlerMs(environment: environment)) / 1000
    }
}

struct XCTestHTTPServer {
    func start() async throws {
        let port = ProcessInfo.processInfo.environment["PORT"]?.toUInt16()
        // smithr (artha#5603): the handler timeout is MAESTRO_IOS_DRIVER_TIMEOUT_MS (default 600 s),
        // passed by the client at launch; it was a hard-wired 100 s
        let server = HTTPServer(address: try .inet(ip4: "127.0.0.1", port: port ?? 22087),
                                timeout: DriverTimeouts.handlerSeconds(environment: ProcessInfo.processInfo.environment))
        
        for route in Route.allCases {
            let handler = await RouteHandlerFactory.createRouteHandler(route: route)
            await server.appendRoute(route.toHTTPRoute(), to: IssueGuardedHandler(route: route, inner: handler))
        }
        
        try await server.run()
    }
}

/// artha#5055: a request during which XCTest recorded an issue answers an error, so the
/// caller does not take a degraded answer (e.g. an empty screenshot) for a good one. The issue
/// itself was swallowed by `maestro_driver_iosUITests.record(_:)`, so the server keeps
/// serving: the NEXT request is answered normally.
///
/// Attribution is by time window: requests that overlap can each see the other's issue.
struct IssueGuardedHandler: HTTPHandler {
    let route: Route
    let inner: HTTPHandler

    func handleRequest(_ request: HTTPRequest) async throws -> HTTPResponse {
        let mark = DriverIssues.count
        let response = try await inner.handleRequest(request)
        let issues = DriverIssues.since(mark)
        guard !issues.isEmpty else { return response }
        NSLog("%@", "[DriverIssues] /\(route.rawValue) recorded \(issues.count) XCTest issue(s); answering an error instead of \(response.statusCode.code)")
        return AppError(message: "XCTest recorded an issue during /\(route.rawValue): \(issues.joined(separator: " | "))").httpResponse
    }
}

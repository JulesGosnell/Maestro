import FlyingFox
import XCTest
import os
import MaestroDriverLib

// smithr#1184: near-side element lookups. The host hands the runner a selector and gets back only
// the matches, instead of pulling the whole hierarchy (/viewHierarchy) and searching it itself.
//
// The tree searched is the one /viewHierarchy returns (ViewHierarchyHandler builds both), minus two
// costs measured there: the springboard's status bars and frame are reused for a few seconds
// instead of fetched on every lookup, and nothing is serialised. Matching mirrors the host function for function:
// IOSDriver.mapViewHierarchy, ViewHierarchy.from (filterOutOfBounds), Orchestra.buildFilter and
// maestro.Filters, as maestro-nearside's NearSideMatcher does for Android. The wire format is that
// of maestro_android.proto's FindRequest/FindResponse/WaitUntilRequest/WaitUntilResponse, as JSON.
//
// Additive: the host uses this only when GET /capabilities advertises `nearSide`.

enum NearSide {
    static let version = 1
    static let ops = ["find", "waitUntil"]
    static let selectorFields = NearSideSelector.fields
    static let defaultLimit = 10
    static let defaultPollMs = 100
}

enum NearSideError: Error {
    case badRequest(String)

    var response: HTTPResponse {
        switch self {
        case .badRequest(let detail):
            return nearSideJSON(["ok": false, "reason": "badRequest", "detail": detail], status: .badRequest)
        }
    }
}

func nearSideJSON(_ object: Any, status: HTTPStatusCode = .ok) -> HTTPResponse {
    let body = (try? JSONSerialization.data(withJSONObject: object)) ?? Data()
    return HTTPResponse(statusCode: status, body: body)
}

private func nearSideDriverError(_ error: Error) -> HTTPResponse {
    nearSideJSON(["ok": false, "reason": "driverError", "detail": error.localizedDescription],
                 status: .internalServerError)
}

private func nearSideBody(_ request: HTTPRequest) async throws -> [String: Any] {
    let data = try await request.bodyData
    guard let dict = try? JSONSerialization.jsonObject(with: data) as? [String: Any] else {
        throw NearSideError.badRequest("body must be a JSON object")
    }
    return dict
}

private func nearSideMs(since start: DispatchTime) -> Int {
    Int((DispatchTime.now().uptimeNanoseconds - start.uptimeNanoseconds) / 1_000_000)
}

// MARK: - Wire form

extension NearSideNode {
    // NearSideElement: a match without its children
    var json: [String: Any] {
        var out: [String: Any] = ["attributes": attributes]
        clickable.map { out["clickable"] = $0 }
        enabled.map { out["enabled"] = $0 }
        focused.map { out["focused"] = $0 }
        checked.map { out["checked"] = $0 }
        selected.map { out["selected"] = $0 }
        return out
    }
}

// MARK: - Hierarchy

@MainActor
enum NearSideHierarchy {
    private static let springboard = XCUIApplication(bundleIdentifier: RunningApp.springboardBundleId)

    /// The tree /viewHierarchy would return, as the host's TreeNode, and the app it was taken from.
    static func current() async throws -> (root: NearSideNode, app: XCUIApplication) {
        let handler = ViewHierarchyHandler()
        let foregroundApp = try catchingObjCException { RunningApp.getForegroundApp() }
        guard let foregroundApp = foregroundApp else {
            return (NearSideNode(try catchingObjCException { try handler.elementHierarchy(xcuiElement: springboard) }), springboard)
        }
        let appHierarchy = try catchingObjCException { try handler.getHierarchyWithFallback(foregroundApp) }
        try await SystemPermissionHelper.handleSystemPermissionAlertIfNeeded(appHierarchy: appHierarchy, foregroundApp: foregroundApp)

        return try catchingObjCException {
            let (statusBars, deviceFrame) = springboardParts(handler)
            let root = try handler.assembleAppViewHierarchy(
                appHierarchy: appHierarchy,
                statusBars: statusBars,
                deviceFrame: deviceFrame
            )
            return (NearSideNode(root), foregroundApp)
        }
    }

    // The status bars and springboard frame, fetched exactly as /viewHierarchy fetches them but
    // reused for springboardTTLms: they cost more than the app's own tree (85 ms of a 155 ms
    // /viewHierarchy, measured), and a lookup polling every 100 ms would pay that on every poll.
    // So status-bar content (clock, battery) may be up to springboardTTLms old.
    static let springboardTTLms = 3000
    private static var springboardCache: (at: DispatchTime, statusBars: [AXElement], frame: CGRect)?

    private static func springboardParts(_ handler: ViewHierarchyHandler) -> ([AXElement], CGRect) {
        if let cached = springboardCache, nearSideMs(since: cached.at) < springboardTTLms {
            return (cached.statusBars, cached.frame)
        }
        let statusBars = handler.fullStatusBars(springboard) ?? []
        let frame = springboard.frame
        springboardCache = (DispatchTime.now(), statusBars, frame)
        return (statusBars, frame)
    }

    /// The host's DeviceInfo grid (widthPoints/heightPoints), as /deviceInfo computes it.
    static func screenSize(foregroundApp: XCUIApplication) throws -> (Int, Int) {
        let (width, height, _) = try catchingObjCException { try ScreenSizeHelper.actualScreenSize(foregroundApp: foregroundApp) }
        return (Int(width), Int(height))
    }
}

// MARK: - Handlers

struct NearSideFind {
    let selector: NearSideSelector
    let limit: Int
    let screen: (Int, Int)?

    // FindRequest {selector, limit, screenWidth, screenHeight}
    static func parse(_ any: Any?) throws -> NearSideFind {
        guard let dict = any as? [String: Any] else { throw NearSideError.badRequest("find must be an object") }
        let limit = (dict["limit"] as? Int).flatMap { $0 > 0 ? $0 : nil } ?? NearSide.defaultLimit
        var screen: (Int, Int)?
        if let w = dict["screenWidth"] as? Int, let h = dict["screenHeight"] as? Int, w > 0, h > 0 {
            screen = (w, h)
        }
        do {
            return NearSideFind(selector: try NearSideSelector.parse(dict["selector"]), limit: limit, screen: screen)
        } catch NearSideSelectorError.invalid(let detail) {
            throw NearSideError.badRequest(detail)
        }
    }

    // FindResponse {matches, truncated, deviceMs}
    @MainActor
    func run() async throws -> (response: [String: Any], found: Bool) {
        let start = DispatchTime.now()
        let (root, app) = try await NearSideHierarchy.current()
        let (width, height) = try screen ?? NearSideHierarchy.screenSize(foregroundApp: app)
        let result = NearSideMatcher(screenWidth: width, screenHeight: height)
            .find(root: root, selector: selector, limit: limit)
        return ([
            "matches": result.matches.map { $0.json },
            "truncated": result.truncated,
            "deviceMs": nearSideMs(since: start),
        ], !result.matches.isEmpty)
    }
}

@MainActor
struct CapabilitiesHandler: HTTPHandler {
    func handleRequest(_ request: HTTPRequest) async throws -> HTTPResponse {
        nearSideJSON([
            "nearSide": NearSide.version,
            "ops": NearSide.ops,
            "selectorFields": NearSide.selectorFields,
            // smithr#1246 Phase C: POST /screenStatic (ScreenDiffHandler.swift)
            "screenStatic": ScreenStatic.version,
        ])
    }
}

// POST /find  FindRequest → FindResponse
@MainActor
struct FindHandler: HTTPHandler {
    func handleRequest(_ request: HTTPRequest) async throws -> HTTPResponse {
        do {
            let find = try NearSideFind.parse(try await nearSideBody(request))
            return nearSideJSON(try await find.run().response)
        } catch let error as NearSideError {
            return error.response
        } catch {
            NSLog("[near-side] find failed: \(error)")
            return nearSideDriverError(error)
        }
    }
}

// POST /waitUntil  WaitUntilRequest {find, condition: visible|gone, timeoutMs, pollMs}
//   → WaitUntilResponse {ok, elapsedMs, last}. The poll loop runs here, and never overruns timeoutMs.
@MainActor
struct WaitUntilHandler: HTTPHandler {
    func handleRequest(_ request: HTTPRequest) async throws -> HTTPResponse {
        let start = DispatchTime.now()
        do {
            let body = try await nearSideBody(request)
            let find = try NearSideFind.parse(body["find"])
            let condition = body["condition"] as? String
            guard condition == "visible" || condition == "gone" else {
                throw NearSideError.badRequest("condition must be visible or gone")
            }
            guard let timeoutMs = body["timeoutMs"] as? Int, timeoutMs >= 0 else {
                throw NearSideError.badRequest("timeoutMs is required")
            }
            let pollMs = max(10, (body["pollMs"] as? Int).flatMap { $0 > 0 ? $0 : nil } ?? NearSide.defaultPollMs)

            while true {
                let pollStart = DispatchTime.now()
                let (last, found) = try await find.run()
                let met = (condition == "visible") == found
                let elapsed = nearSideMs(since: start)
                // Stop when the next poll could not finish inside timeoutMs.
                let pollCost = nearSideMs(since: pollStart)
                if met || elapsed + min(pollMs, timeoutMs - elapsed) + pollCost > timeoutMs {
                    return nearSideJSON(["ok": met, "elapsedMs": elapsed, "last": last])
                }
                try await Task.sleep(nanoseconds: UInt64(min(pollMs, timeoutMs - elapsed)) * 1_000_000)
            }
        } catch let error as NearSideError {
            return error.response
        } catch {
            NSLog("[near-side] waitUntil failed: \(error)")
            return nearSideDriverError(error)
        }
    }
}

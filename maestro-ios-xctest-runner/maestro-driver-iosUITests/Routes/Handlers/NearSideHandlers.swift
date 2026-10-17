import FlyingFox
import XCTest
import os

// smithr#1184: near-side element queries. The selector is resolved ON THE DEVICE with an
// XCUIElementQuery + NSPredicate and only the matches come back, instead of the host pulling
// the whole hierarchy (viewHierarchy) and searching it. The whole tree is never serialised here.
//
// Additive: the host uses these only when GET /capabilities advertises `nearSide`, and only
// for selectors made purely of `selectorFields`. Anything else stays on the full-dump path.

enum NearSide {
    static let version = 1
    static let ops = ["find", "waitUntil"]
    static let selectorFields = ["id", "text", "textRegex", "label", "index", "enabled"]
    static let defaultLimit = 10

    // Orchestra.REGEX_OPTIONS: IGNORE_CASE, DOT_MATCHES_ALL, MULTILINE.
    static let regexFlags = "(?ism)"
}

struct NearSideSelector {
    // Regex, full match on the accessibility identifier (Filters.idMatches).
    var id: String?
    // Exact equality against text (title, else value), hintText (placeholderValue) or accessibilityText (label).
    var text: String?
    // Regex, full match against the same three attributes, or literal equality (Filters.textMatches).
    var textRegex: String?
    // Exact equality against the accessibility label.
    var label: String?
    // Position in the matches sorted by (y, x); negative counts from the end (Filters.index).
    var index: Int?
    var enabled: Bool?

    static func parse(_ any: Any?) throws -> NearSideSelector {
        guard let dict = any as? [String: Any] else {
            throw NearSideError.badRequest("selector must be an object")
        }
        let unknown = Set(dict.keys).subtracting(NearSide.selectorFields)
        if !unknown.isEmpty {
            // Never ignore a field: a dropped `below` would match the wrong element silently.
            throw NearSideError.unsupportedSelector("unsupported selector fields: \(unknown.sorted())")
        }
        var s = NearSideSelector()
        s.id = try string(dict, "id")
        s.text = try string(dict, "text")
        s.textRegex = try string(dict, "textRegex")
        s.label = try string(dict, "label")
        if let v = dict["index"] {
            guard let n = v as? NSNumber, CFGetTypeID(n) != CFBooleanGetTypeID() else {
                throw NearSideError.badRequest("index must be a number")
            }
            s.index = n.intValue
        }
        if let v = dict["enabled"] {
            guard let n = v as? NSNumber, CFGetTypeID(n) == CFBooleanGetTypeID() else {
                throw NearSideError.badRequest("enabled must be a boolean")
            }
            s.enabled = n.boolValue
        }
        if s.id == nil && s.text == nil && s.textRegex == nil && s.label == nil && s.enabled == nil {
            throw NearSideError.badRequest("selector matches everything")
        }
        return s
    }

    private static func string(_ dict: [String: Any], _ key: String) throws -> String? {
        guard let v = dict[key] else { return nil }
        guard let s = v as? String else { throw NearSideError.badRequest("\(key) must be a string") }
        return s
    }

    func predicate() -> NSPredicate {
        var parts: [NSPredicate] = []
        if let id = id {
            parts.append(NSPredicate(format: "identifier MATCHES %@", NearSide.regexFlags + id))
        }
        if let text = text {
            parts.append(Self.textPredicate { key in NSPredicate(format: "%K == %@", key, text) })
        }
        if let regex = textRegex {
            let flagged = NearSide.regexFlags + regex
            parts.append(Self.textPredicate { key in
                NSPredicate(format: "%K MATCHES %@ OR %K == %@", key, flagged, key, regex)
            })
        }
        if let label = label {
            parts.append(NSPredicate(format: "label == %@", label))
        }
        if let enabled = enabled {
            parts.append(NSPredicate(format: "enabled == %@", NSNumber(value: enabled)))
        }
        return NSCompoundPredicate(andPredicateWithSubpredicates: parts)
    }

    // IOSDriver.mapViewHierarchy: text = title, or value when title is empty; hintText =
    // placeholderValue; accessibilityText = label. textMatches unions all three.
    private static func textPredicate(_ match: (String) -> NSPredicate) -> NSPredicate {
        let text = NSCompoundPredicate(orPredicateWithSubpredicates: [
            NSCompoundPredicate(andPredicateWithSubpredicates: [
                NSPredicate(format: "title != nil AND title != ''"), match("title"),
            ]),
            NSCompoundPredicate(andPredicateWithSubpredicates: [
                NSPredicate(format: "title == nil OR title == ''"), match("value"),
            ]),
        ])
        return NSCompoundPredicate(orPredicateWithSubpredicates: [text, match("placeholderValue"), match("label")])
    }
}

enum NearSideError: Error {
    case badRequest(String)
    case unsupportedSelector(String)

    var response: HTTPResponse {
        switch self {
        case .badRequest(let detail):
            return nearSideJSON(["ok": false, "reason": "badRequest", "detail": detail], status: .badRequest)
        case .unsupportedSelector(let detail):
            return nearSideJSON(["ok": false, "reason": "unsupportedSelector", "detail": detail], status: .badRequest)
        }
    }
}

func nearSideJSON(_ object: Any, status: HTTPStatusCode = .ok) -> HTTPResponse {
    let body = (try? JSONSerialization.data(withJSONObject: object)) ?? Data()
    return HTTPResponse(statusCode: status, body: body)
}

func nearSideDriverError(_ error: Error) -> HTTPResponse {
    nearSideJSON(["ok": false, "reason": "driverError", "detail": error.localizedDescription],
                 status: .internalServerError)
}

func nearSideBody(_ request: HTTPRequest) async throws -> [String: Any] {
    let data = try await request.bodyData
    guard let dict = try? JSONSerialization.jsonObject(with: data) as? [String: Any] else {
        throw NearSideError.badRequest("body must be a JSON object")
    }
    return dict
}

func nearSideMs(since start: DispatchTime) -> Int {
    Int((DispatchTime.now().uptimeNanoseconds - start.uptimeNanoseconds) / 1_000_000)
}

@MainActor
struct NearSideResolver {
    struct Match {
        let identifier: String
        let text: String
        let label: String
        let frame: CGRect
        let enabled: Bool
        let visible: Bool

        var json: [String: Any] {
            [
                "id": identifier,
                "text": text,
                "label": label,
                "bounds": [frame.minX, frame.minY, frame.width, frame.height].map { Double($0) },
                "enabled": enabled,
                "visible": visible,
            ]
        }
    }

    struct Result {
        let matches: [Match]
        let total: Int
        let queryMs: Int
    }

    private static let springboard = XCUIApplication(bundleIdentifier: RunningApp.springboardBundleId)

    // `bundleId` lets the host skip foreground-app detection when it already knows the app.
    static func resolve(_ selector: NearSideSelector, bundleId: String?) throws -> Result {
        let start = DispatchTime.now()
        let app: XCUIApplication = try catchingObjCException {
            if let bundleId = bundleId { return XCUIApplication(bundleIdentifier: bundleId) }
            return RunningApp.getForegroundApp() ?? springboard
        }
        let predicate = selector.predicate()
        let snapshots = try catchingObjCException { () -> [XCUIElementSnapshot] in
            var found = try allMatchingSnapshots(app.descendants(matching: .any).matching(predicate))
            // viewHierarchy appends springboard's status bars to the app's tree; so must we.
            if app.bundleID != RunningApp.springboardBundleId {
                found += try allMatchingSnapshots(springboard.statusBars.descendants(matching: .any).matching(predicate))
            }
            return found
        }
        let queryMs = nearSideMs(since: start)

        var matches = try catchingObjCException { deepest(snapshots).map { s -> Match in
            let title = s.title
            let value = (s.value as? String) ?? ""
            return Match(
                identifier: s.identifier,
                text: title.isEmpty ? value : title,
                label: s.label,
                frame: s.frame,
                enabled: s.isEnabled,
                visible: !s.frame.isEmpty && s.frame.intersects(rootFrame(of: s) ?? app.frame)
            )
        } }
        let total = matches.count
        if let index = selector.index {
            let sorted = matches.sorted { ($0.frame.minY, $0.frame.minX) < ($1.frame.minY, $1.frame.minX) }
            let resolved = index >= 0 ? index : sorted.count + index
            matches = (resolved >= 0 && resolved < sorted.count) ? [sorted[resolved]] : []
        }
        return Result(matches: matches, total: total, queryMs: queryMs)
    }

    // One query evaluation returning every match's snapshot (XCTest's private
    // -allMatchingSnapshotsWithError:, as WebDriverAgent uses). Reading attributes through
    // XCUIElement instead re-resolves the query on every property access.
    private static func allMatchingSnapshots(_ query: XCUIElementQuery) throws -> [XCUIElementSnapshot] {
        let selector = NSSelectorFromString("allMatchingSnapshotsWithError:")
        guard query.responds(to: selector) else {
            return try query.allElementsBoundByIndex.map { try $0.snapshot() }
        }
        typealias Fn = @convention(c) (AnyObject, Selector, AutoreleasingUnsafeMutablePointer<NSError?>?) -> NSArray?
        let fn = unsafeBitCast(query.method(for: selector), to: Fn.self)
        var error: NSError?
        let raw = fn(query, selector, &error)
        if let error = error { throw error }
        let objects = (raw as? [AnyObject]) ?? []
        let snapshots = objects.compactMap { $0 as? XCUIElementSnapshot }
        if snapshots.count != objects.count {
            throw AppError(message: "allMatchingSnapshotsWithError: returned objects that are not XCUIElementSnapshot")
        }
        return snapshots
    }

    // The application's frame, from the snapshot tree this query already fetched. `app.frame`
    // would resolve the application again: one more round trip to the app per find.
    private static func rootFrame(of snapshot: XCUIElementSnapshot) -> CGRect? {
        guard var node = snapshot as? NSObject, node.responds(to: NSSelectorFromString("parent")) else { return nil }
        while let parent = node.value(forKey: "parent") as? NSObject { node = parent }
        return (node as? XCUIElementSnapshot)?.frame
    }

    // Filters.deepestMatchingElement: a match with another match beneath it is dropped, so a
    // React Native container whose label aggregates its children's does not win over the child.
    private static func deepest(_ snapshots: [XCUIElementSnapshot]) -> [XCUIElementSnapshot] {
        guard snapshots.count > 1 else { return snapshots }
        let ids = Set(snapshots.map { ObjectIdentifier($0 as AnyObject) })
        func hasMatchBelow(_ s: XCUIElementSnapshot) -> Bool {
            for child in s.children {
                if ids.contains(ObjectIdentifier(child as AnyObject)) || hasMatchBelow(child) { return true }
            }
            return false
        }
        return snapshots.filter { !hasMatchBelow($0) }
    }
}

@MainActor
struct CapabilitiesHandler: HTTPHandler {
    func handleRequest(_ request: HTTPRequest) async throws -> HTTPResponse {
        nearSideJSON([
            "nearSide": NearSide.version,
            "ops": NearSide.ops,
            "selectorFields": NearSide.selectorFields,
        ])
    }
}

// POST /find {selector, limit?: 10, bundleId?} → {matches, truncated, deviceMs}
@MainActor
struct FindHandler: HTTPHandler {
    func handleRequest(_ request: HTTPRequest) async throws -> HTTPResponse {
        let start = DispatchTime.now()
        do {
            let body = try await nearSideBody(request)
            let selector = try NearSideSelector.parse(body["selector"])
            let limit = max(1, (body["limit"] as? Int) ?? NearSide.defaultLimit)
            let result = try NearSideResolver.resolve(selector, bundleId: body["bundleId"] as? String)
            return nearSideJSON([
                "matches": result.matches.prefix(limit).map { $0.json },
                "truncated": result.matches.count > limit,
                "deviceMs": nearSideMs(since: start),
                "queryMs": result.queryMs,
            ])
        } catch let error as NearSideError {
            return error.response
        } catch {
            NSLog("[near-side] find failed: \(error)")
            return nearSideDriverError(error)
        }
    }
}

// POST /waitUntil {selector, condition: visible|gone|enabled, timeoutMs, pollMs?: 100, bundleId?}
//   → {ok, elapsedMs, polls, matched?, deviceMs}. The poll loop runs here, not on the host.
@MainActor
struct WaitUntilHandler: HTTPHandler {
    enum Condition: String { case visible, gone, enabled }

    func handleRequest(_ request: HTTPRequest) async throws -> HTTPResponse {
        let start = DispatchTime.now()
        do {
            let body = try await nearSideBody(request)
            let selector = try NearSideSelector.parse(body["selector"])
            guard let raw = body["condition"] as? String, let condition = Condition(rawValue: raw) else {
                throw NearSideError.badRequest("condition must be one of visible, gone, enabled")
            }
            guard let timeoutMs = body["timeoutMs"] as? Int, timeoutMs >= 0 else {
                throw NearSideError.badRequest("timeoutMs is required")
            }
            let pollMs = max(10, (body["pollMs"] as? Int) ?? 100)
            let bundleId = body["bundleId"] as? String

            var polls = 0
            while true {
                polls += 1
                let pollStart = DispatchTime.now()
                let result = try NearSideResolver.resolve(selector, bundleId: bundleId)
                let hit: NearSideResolver.Match?
                let met: Bool
                switch condition {
                case .visible:
                    hit = result.matches.first { $0.visible }
                    met = hit != nil
                case .enabled:
                    hit = result.matches.first { $0.visible && $0.enabled }
                    met = hit != nil
                case .gone:
                    hit = nil
                    met = !result.matches.contains { $0.visible }
                }
                let elapsed = nearSideMs(since: start)
                // Stop when the next poll could not finish inside timeoutMs: the op never overruns.
                let pollCost = nearSideMs(since: pollStart)
                if met || elapsed + min(pollMs, timeoutMs - elapsed) + pollCost > timeoutMs {
                    var response: [String: Any] = [
                        "ok": met,
                        "elapsedMs": elapsed,
                        "polls": polls,
                        "deviceMs": elapsed,
                    ]
                    if !met { response["reason"] = "timeout" }
                    if let hit = hit { response["matched"] = hit.json }
                    return nearSideJSON(response)
                }
                let remaining = timeoutMs - elapsed
                try await Task.sleep(nanoseconds: UInt64(min(pollMs, remaining)) * 1_000_000)
            }
        } catch let error as NearSideError {
            return error.response
        } catch {
            NSLog("[near-side] waitUntil failed: \(error)")
            return nearSideDriverError(error)
        }
    }
}

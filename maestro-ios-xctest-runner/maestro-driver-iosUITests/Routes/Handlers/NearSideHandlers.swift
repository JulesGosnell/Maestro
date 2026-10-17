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
    // NearSideSelector.SELECTOR_FIELDS
    static let selectorFields = [
        "textRegex", "idRegex", "enabled", "selected", "checked", "focused",
        "index", "below", "above", "leftOf", "rightOf",
    ]
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

// MARK: - Selector

/// maestro-nearside's NearSideSelector. Regexes arrive as the pattern the host compiled.
final class NearSideSelector {
    struct Pattern {
        let source: String
        let regex: NSRegularExpression

        // Orchestra.REGEX_OPTIONS: IGNORE_CASE, DOT_MATCHES_ALL, MULTILINE.
        init(_ source: String) throws {
            self.source = source
            do {
                // Regex.matches is a whole-input match: \A and \z ignore MULTILINE, as it does.
                regex = try NSRegularExpression(
                    pattern: "\\A(?:\(source))\\z",
                    options: [.caseInsensitive, .dotMatchesLineSeparators, .anchorsMatchLines]
                )
            } catch {
                throw NearSideError.badRequest("invalid regex: \(source)")
            }
        }

        func matches(_ value: String) -> Bool {
            regex.firstMatch(in: value, range: NSRange(value.startIndex..., in: value)) != nil
        }
    }

    var textRegex: Pattern?
    var idRegex: Pattern?
    var enabled: Bool?
    var selected: Bool?
    var checked: Bool?
    var focused: Bool?
    var index: Int?
    var below: NearSideSelector?
    var above: NearSideSelector?
    var leftOf: NearSideSelector?
    var rightOf: NearSideSelector?

    static func parse(_ any: Any?) throws -> NearSideSelector {
        guard let dict = any as? [String: Any] else {
            throw NearSideError.badRequest("selector must be an object")
        }
        let unknown = Set(dict.keys).subtracting(NearSide.selectorFields)
        if !unknown.isEmpty {
            // Never ignore a field: a dropped one would match the wrong element, silently.
            throw NearSideError.badRequest("unsupported selector fields: \(unknown.sorted())")
        }
        let s = NearSideSelector()
        s.textRegex = try string(dict, "textRegex").map(Pattern.init)
        s.idRegex = try string(dict, "idRegex").map(Pattern.init)
        s.enabled = try bool(dict, "enabled")
        s.selected = try bool(dict, "selected")
        s.checked = try bool(dict, "checked")
        s.focused = try bool(dict, "focused")
        if let v = dict["index"] {
            guard let n = v as? NSNumber, CFGetTypeID(n) != CFBooleanGetTypeID() else {
                throw NearSideError.badRequest("index must be a number")
            }
            s.index = n.intValue
        }
        s.below = try dict["below"].map(parse)
        s.above = try dict["above"].map(parse)
        s.leftOf = try dict["leftOf"].map(parse)
        s.rightOf = try dict["rightOf"].map(parse)
        return s
    }

    private static func string(_ dict: [String: Any], _ key: String) throws -> String? {
        guard let v = dict[key] else { return nil }
        guard let s = v as? String else { throw NearSideError.badRequest("\(key) must be a string") }
        return s
    }

    private static func bool(_ dict: [String: Any], _ key: String) throws -> Bool? {
        guard let v = dict[key] else { return nil }
        guard let n = v as? NSNumber, CFGetTypeID(n) == CFBooleanGetTypeID() else {
            throw NearSideError.badRequest("\(key) must be a boolean")
        }
        return n.boolValue
    }
}

// MARK: - Nodes

/// maestro.TreeNode as IOSDriver.mapViewHierarchy builds it. Structural equality is part of the
/// matching semantics: the host's filters dedupe with distinct()/toSet(). A class, so the matcher
/// can memoise per node and the structural hash is computed once. `clickable` is always null on
/// iOS, so it is left out.
final class NearSideNode: Hashable {
    let attributes: [String: String]
    let children: [NearSideNode]
    let enabled: Bool
    let focused: Bool
    let checked: Bool
    let selected: Bool
    private let structuralHash: Int

    // IOSDriver.CHECKABLE_ELEMENTS: checkBox, switch, toggle
    private static let checkableTypes: Set<Int> = [12, 40, 41]

    // IOSDriver.mapViewHierarchy
    init(_ element: AXElement) {
        let checked = NearSideNode.checkableTypes.contains(element.elementType) && element.value == "1"
        let text: String
        if let title = element.title {
            text = title.isEmpty ? (element.value ?? "") : title
        } else {
            text = ""
        }
        attributes = [
            "accessibilityText": element.label,
            "title": element.title ?? "",
            "value": element.value ?? "",
            "text": text,
            "hintText": element.placeholderValue ?? "",
            "resource-id": element.identifier,
            "bounds": NearSideNode.boundsString(element.frame),
            "enabled": String(element.enabled),
            "focused": String(element.hasFocus),
            "selected": String(element.selected),
            "checked": String(checked),
        ]
        children = (element.children ?? []).map(NearSideNode.init)
        enabled = element.enabled
        focused = element.hasFocus
        self.checked = checked
        selected = element.selected
        structuralHash = NearSideNode.hash(attributes, children)
    }

    private init(copying node: NearSideNode, children: [NearSideNode]) {
        attributes = node.attributes
        self.children = children
        enabled = node.enabled
        focused = node.focused
        checked = node.checked
        selected = node.selected
        structuralHash = NearSideNode.hash(attributes, children)
    }

    private static func hash(_ attributes: [String: String], _ children: [NearSideNode]) -> Int {
        var hasher = Hasher()
        hasher.combine(attributes)
        children.forEach { hasher.combine($0.structuralHash) }
        return hasher.finalize()
    }

    func hash(into hasher: inout Hasher) {
        hasher.combine(structuralHash)
    }

    // The flags are derived from attributes ("enabled", "focused", ...), so attributes cover them.
    static func == (lhs: NearSideNode, rhs: NearSideNode) -> Bool {
        lhs === rhs || (lhs.structuralHash == rhs.structuralHash
            && lhs.attributes == rhs.attributes && lhs.children == rhs.children)
    }

    func withChildren(_ children: [NearSideNode]) -> NearSideNode {
        NearSideNode(copying: self, children: children)
    }

    // hierarchy.AXFrame.boundsString: Float arithmetic, then Float.toInt()
    private static func boundsString(_ frame: AXFrame) -> String {
        let x = Float(frame.x), y = Float(frame.y)
        return "[\(toInt(x)),\(toInt(y))][\(toInt(x + Float(frame.width))),\(toInt(y + Float(frame.height)))]"
    }

    // Kotlin's Float.toInt(): truncates, NaN is 0, out of range clamps
    private static func toInt(_ f: Float) -> Int {
        if f.isNaN { return 0 }
        if f >= Float(Int32.max) { return Int(Int32.max) }
        if f <= Float(Int32.min) { return Int(Int32.min) }
        return Int(f)
    }

    func aggregate() -> [NearSideNode] {
        var out: [NearSideNode] = []
        func walk(_ n: NearSideNode) {
            out.append(n)
            n.children.forEach(walk)
        }
        walk(self)
        return out
    }

    var json: [String: Any] {
        [
            "attributes": attributes,
            "enabled": enabled,
            "focused": focused,
            "checked": checked,
            "selected": selected,
        ]
    }
}

// MARK: - Matcher

/// maestro-nearside's NearSideMatcher: ViewHierarchy.from (filterOutOfBounds),
/// Orchestra.buildFilter and maestro.Filters, function for function. Kotlin's Set is
/// insertion-ordered, so every set here is an ordered, de-duplicated array.
struct NearSideMatcher {
    typealias NodeFilter = ([NearSideNode]) -> [NearSideNode]

    let screenWidth: Int
    let screenHeight: Int

    func find(root: NearSideNode, selector: NearSideSelector, limit: Int) -> (matches: [NearSideNode], truncated: Bool) {
        let visibleRoot = filterOutOfBounds(root) ?? root
        let matches = filter(selector)(visibleRoot.aggregate())
        return (Array(matches.prefix(limit)), matches.count > limit)
    }

    // ViewHierarchy.kt: TreeNode.filterOutOfBounds
    private func filterOutOfBounds(_ node: NearSideNode) -> NearSideNode? {
        if node.attributes["ignoreBoundsFiltering"] == "true" {
            return node
        }
        let filtered = node.children.compactMap(filterOutOfBounds)
        let visiblePercentage = Self.bounds(of: node).map(visiblePercentage) ?? 0.0
        if visiblePercentage < 0.1 && filtered.isEmpty {
            return nil
        }
        return node.withChildren(filtered)
    }

    // Orchestra.buildFilter, restricted to the fields NearSideSelector carries
    private func filter(_ selector: NearSideSelector) -> NodeFilter {
        var basicFilters: [NodeFilter] = []
        var relativeFilters: [NodeFilter] = []

        if let regex = selector.textRegex { basicFilters.append(textMatches(regex)) }
        if let regex = selector.idRegex { basicFilters.append(idMatches(regex)) }

        if let other = selector.below { relativeFilters.append(relativeTo(filter(other)) { b, o in b.y > o.y }) }
        if let other = selector.above { relativeFilters.append(relativeTo(filter(other)) { b, o in b.y < o.y }) }
        if let other = selector.leftOf { relativeFilters.append(relativeTo(filter(other)) { b, o in b.x < o.x }) }
        if let other = selector.rightOf { relativeFilters.append(relativeTo(filter(other)) { b, o in b.x > o.x }) }

        if let expected = selector.enabled { basicFilters.append { $0.filter { $0.enabled == expected } } }
        if let expected = selector.selected { basicFilters.append { $0.filter { $0.selected == expected } } }
        if let expected = selector.checked { basicFilters.append { $0.filter { $0.checked == expected } } }
        if let expected = selector.focused { basicFilters.append { $0.filter { $0.focused == expected } } }

        let basicFilter: NodeFilter = basicFilters.isEmpty
            ? { $0 }
            : deepestMatchingElement(intersect(basicFilters))

        let resultFilter = intersect([basicFilter] + relativeFilters)

        // clickableFirst() is a stable sort on `clickable`, which is null for every iOS node: identity.
        guard let idx = selector.index else { return resultFilter }
        let pick = index(idx)
        return { nodes in pick(resultFilter(nodes)) }
    }

    // ---- maestro.Filters ----

    private static func distinct(_ nodes: [NearSideNode]) -> [NearSideNode] {
        var seen = Set<NearSideNode>()
        return nodes.filter { seen.insert($0).inserted }
    }

    private func intersect(_ filters: [NodeFilter]) -> NodeFilter {
        return { nodes in
            guard let first = filters.first else { return nodes }
            var result = Self.distinct(first(nodes))
            for f in filters.dropFirst() {
                let other = Set(f(nodes))
                result = result.filter { other.contains($0) }
            }
            return result
        }
    }

    private func textMatches(_ pattern: NearSideSelector.Pattern) -> NodeFilter {
        func matches(_ value: String) -> Bool {
            let stripped = value.replacingOccurrences(of: "\n", with: " ")
            return pattern.matches(value) || pattern.source == value
                || pattern.matches(stripped) || pattern.source == stripped
        }
        return { nodes in
            let text = nodes.filter { $0.attributes["text"].map(matches) ?? false }
            let hint = nodes.filter { $0.attributes["hintText"].map(matches) ?? false }
            let accessibility = nodes.filter { $0.attributes["accessibilityText"].map(matches) ?? false }
            return Self.distinct(text + hint + accessibility)
        }
    }

    private func idMatches(_ pattern: NearSideSelector.Pattern) -> NodeFilter {
        return { nodes in
            let exact = nodes.filter { $0.attributes["resource-id"].map(pattern.matches) ?? false }
            let withoutPrefix = nodes.filter { node in
                guard let id = node.attributes["resource-id"] else { return false }
                // substringAfterLast('/')
                let suffix = id.range(of: "/", options: .backwards).map { String(id[$0.upperBound...]) } ?? id
                return pattern.matches(suffix)
            }
            return Self.distinct(exact + withoutPrefix)
        }
    }

    // Filters.relativeTo as a membership test: it only ever feeds intersect behind the basic
    // filter, which keeps the basic filter's order, so the host's distance sort never shows.
    private func relativeTo(_ otherFilter: @escaping NodeFilter, _ predicate: @escaping (Bounds, Bounds) -> Bool) -> NodeFilter {
        return { nodes in
            let others = otherFilter(nodes).compactMap(Self.bounds(of:))
            return nodes.filter { node in
                guard let b = Self.bounds(of: node) else { return false }
                return others.contains { predicate(b, $0) }
            }
        }
    }

    // Called with the whole aggregate, so every node is reached once per ancestor: memoised per
    // node, which is what keeps this linear rather than nodes × depth.
    private func deepestMatchingElement(_ filter: @escaping NodeFilter) -> NodeFilter {
        var memo: [ObjectIdentifier: [NearSideNode]] = [:]
        func deepest(_ node: NearSideNode) -> [NearSideNode] {
            if let known = memo[ObjectIdentifier(node)] { return known }
            let below = Self.distinct(node.children.flatMap(deepest))
            let result = !below.isEmpty ? below : (filter([node]).isEmpty ? [] : [node])
            memo[ObjectIdentifier(node)] = result
            return result
        }
        return { nodes in Self.distinct(nodes.flatMap(deepest)) }
    }

    private func index(_ idx: Int) -> NodeFilter {
        return { nodes in
            // compareBy(y, x) with missing bounds last, and a stable sort
            let keyed = nodes.enumerated().map { (i, n) -> (Int, Int, Int, NearSideNode) in
                let b = Self.bounds(of: n)
                return (b?.y ?? Int.max, b?.x ?? Int.max, i, n)
            }
            let sorted = keyed.sorted { ($0.0, $0.1, $0.2) < ($1.0, $1.1, $1.2) }.map { $0.3 }
            let resolved = idx >= 0 ? idx : sorted.count + idx
            return (resolved >= 0 && resolved < sorted.count) ? [sorted[resolved]] : []
        }
    }

    // ---- maestro.UiElement ----

    struct Bounds {
        let x: Int, y: Int, width: Int, height: Int
    }

    // UiElement.toUiElementOrNull
    private static func bounds(of node: NearSideNode) -> Bounds? {
        guard let s = node.attributes["bounds"] else { return nil }
        let parts = s.replacingOccurrences(of: "][", with: ",")
            .trimmingCharacters(in: CharacterSet(charactersIn: "[]"))
            .split(separator: ",")
            .compactMap { Int($0) }
        guard parts.count == 4 else { return nil }
        return Bounds(x: parts[0], y: parts[1], width: parts[2] - parts[0], height: parts[3] - parts[1])
    }

    // UiElement.getVisiblePercentage
    private func visiblePercentage(_ b: Bounds) -> Double {
        if b.width == 0 && b.height == 0 { return 0.0 }
        let overflow = b.x <= 0 && b.y <= 0 && b.x + b.width >= screenWidth && b.y + b.height >= screenHeight
        if overflow { return 1.0 }
        let visibleX = max(0, min(b.x + b.width, screenWidth) - max(b.x, 0))
        let visibleY = max(0, min(b.y + b.height, screenHeight) - max(b.y, 0))
        return Double(visibleX * visibleY) / Double(b.width * b.height)
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
        return NearSideFind(selector: try NearSideSelector.parse(dict["selector"]), limit: limit, screen: screen)
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

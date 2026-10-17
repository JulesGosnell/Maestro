import Foundation

// smithr#1184: the host's element matching, run on the device. A Swift port of maestro-nearside's
// NearSideMatcher (Kotlin), which mirrors ViewHierarchy.from (filterOutOfBounds),
// Orchestra.buildFilter and maestro.Filters function for function. The two must not drift:
// maestro-nearside/src/test/resources/near-side-golden.json holds input trees, selectors and the
// Kotlin matcher's answers, and NearSideGoldenTests runs this port over the same file.

public enum NearSideSelectorError: Error, Equatable {
    case invalid(String)
}

/// maestro-nearside's NearSideSelector. Regexes arrive as the pattern the host compiled.
public final class NearSideSelector {
    // NearSideSelector.SELECTOR_FIELDS: every field this port implements. Anything else is refused.
    public static let fields = [
        "textRegex", "idRegex", "enabled", "selected", "checked", "focused",
        "index", "below", "above", "leftOf", "rightOf",
    ]

    public struct Pattern {
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
                throw NearSideSelectorError.invalid("invalid regex: \(source)")
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

    /// From the JSON wire form. A field this port does not implement is an error, never ignored:
    /// a dropped field would match the wrong element, silently.
    public static func parse(_ any: Any?) throws -> NearSideSelector {
        guard let dict = any as? [String: Any] else {
            throw NearSideSelectorError.invalid("selector must be an object")
        }
        let unknown = Set(dict.keys).subtracting(fields)
        if !unknown.isEmpty {
            throw NearSideSelectorError.invalid("unsupported selector fields: \(unknown.sorted())")
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
                throw NearSideSelectorError.invalid("index must be a number")
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
        guard let s = v as? String else { throw NearSideSelectorError.invalid("\(key) must be a string") }
        return s
    }

    private static func bool(_ dict: [String: Any], _ key: String) throws -> Bool? {
        guard let v = dict[key] else { return nil }
        guard let n = v as? NSNumber, CFGetTypeID(n) == CFBooleanGetTypeID() else {
            throw NearSideSelectorError.invalid("\(key) must be a boolean")
        }
        return n.boolValue
    }
}

/// maestro-nearside's NearSideNode, i.e. maestro.TreeNode. Structural equality over every field is
/// part of the matching semantics: the host's filters dedupe with distinct()/toSet(). A class, so
/// the matcher can memoise per node and the structural hash is computed once.
public final class NearSideNode: Hashable {
    public let attributes: [String: String]
    public let children: [NearSideNode]
    public let clickable: Bool?
    public let enabled: Bool?
    public let focused: Bool?
    public let checked: Bool?
    public let selected: Bool?
    private let structuralHash: Int

    public init(
        attributes: [String: String],
        children: [NearSideNode] = [],
        clickable: Bool? = nil,
        enabled: Bool? = nil,
        focused: Bool? = nil,
        checked: Bool? = nil,
        selected: Bool? = nil
    ) {
        self.attributes = attributes
        self.children = children
        self.clickable = clickable
        self.enabled = enabled
        self.focused = focused
        self.checked = checked
        self.selected = selected
        var hasher = Hasher()
        hasher.combine(attributes)
        children.forEach { hasher.combine($0.structuralHash) }
        hasher.combine(clickable)
        hasher.combine(enabled)
        hasher.combine(focused)
        hasher.combine(checked)
        hasher.combine(selected)
        structuralHash = hasher.finalize()
    }

    // IOSDriver.CHECKABLE_ELEMENTS: checkBox, switch, toggle
    private static let checkableTypes: Set<Int> = [12, 40, 41]

    /// IOSDriver.mapViewHierarchy. `clickable` is never set on iOS.
    public convenience init(_ element: AXElement) {
        let checked = NearSideNode.checkableTypes.contains(element.elementType) && element.value == "1"
        let text: String
        if let title = element.title {
            text = title.isEmpty ? (element.value ?? "") : title
        } else {
            text = ""
        }
        self.init(
            attributes: [
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
            ],
            children: (element.children ?? []).map(NearSideNode.init),
            enabled: element.enabled,
            focused: element.hasFocus,
            checked: checked,
            selected: element.selected
        )
    }

    func withChildren(_ children: [NearSideNode]) -> NearSideNode {
        NearSideNode(attributes: attributes, children: children, clickable: clickable,
                     enabled: enabled, focused: focused, checked: checked, selected: selected)
    }

    // hierarchy.AXFrame.boundsString: Float arithmetic, then Float.toInt()
    static func boundsString(_ frame: AXFrame) -> String {
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

    public func hash(into hasher: inout Hasher) {
        hasher.combine(structuralHash)
    }

    public static func == (lhs: NearSideNode, rhs: NearSideNode) -> Bool {
        lhs === rhs || (lhs.structuralHash == rhs.structuralHash
            && lhs.attributes == rhs.attributes
            && lhs.clickable == rhs.clickable && lhs.enabled == rhs.enabled && lhs.focused == rhs.focused
            && lhs.checked == rhs.checked && lhs.selected == rhs.selected
            && lhs.children == rhs.children)
    }
}

/// maestro-nearside's NearSideMatcher. Kotlin's Set is insertion-ordered, so every set here is an
/// ordered, de-duplicated array.
public struct NearSideMatcher {
    typealias NodeFilter = ([NearSideNode]) -> [NearSideNode]

    let screenWidth: Int
    let screenHeight: Int

    public init(screenWidth: Int, screenHeight: Int) {
        self.screenWidth = screenWidth
        self.screenHeight = screenHeight
    }

    /// The host's `filter(ViewHierarchy.from(driver).aggregate())`, ordered as it orders them.
    public func find(root: NearSideNode, selector: NearSideSelector, limit: Int) -> (matches: [NearSideNode], truncated: Bool) {
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

        let order = selector.index.map(index) ?? clickableFirst()
        return { nodes in order(resultFilter(nodes)) }
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

    // sortedByDescending { it.clickable }: a stable sort, true before false before null
    private func clickableFirst() -> NodeFilter {
        return { nodes in
            func rank(_ c: Bool?) -> Int { c == true ? 0 : (c == false ? 1 : 2) }
            return nodes.enumerated()
                .sorted { (rank($0.element.clickable), $0.offset) < (rank($1.element.clickable), $1.offset) }
                .map { $0.element }
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
            .split(separator: ",", omittingEmptySubsequences: false)
            .map { Int($0) }
        guard parts.count == 4, let x = parts[0], let y = parts[1], let r = parts[2], let b = parts[3] else { return nil }
        return Bounds(x: x, y: y, width: r - x, height: b - y)
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

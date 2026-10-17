import XCTest
@testable import MaestroDriverLib

/// smithr#1184: the Swift NearSideMatcher must answer exactly as the Kotlin one in maestro-nearside.
/// near-side-golden.json is written by maestro-nearside's NearSideGoldenTest from the Kotlin
/// matcher; a semantics change on either side fails the other side's test.
final class NearSideGoldenTests: XCTestCase {

    // this file → MaestroDriverLibTests → Tests → MaestroDriverLib → maestro-ios-xctest-runner → repository root
    private static let goldenFile = URL(fileURLWithPath: #filePath)
        .deletingLastPathComponent()
        .deletingLastPathComponent()
        .deletingLastPathComponent()
        .deletingLastPathComponent()
        .deletingLastPathComponent()
        .appendingPathComponent("maestro-nearside/src/test/resources/near-side-golden.json")

    func testEveryGoldenCase_matchesTheKotlinMatcher() throws {
        let data = try Data(contentsOf: Self.goldenFile)
        let golden = try XCTUnwrap(JSONSerialization.jsonObject(with: data) as? [String: Any])
        XCTAssertEqual(golden["version"] as? Int, 1)
        let cases = try XCTUnwrap(golden["cases"] as? [[String: Any]])
        XCTAssertFalse(cases.isEmpty, "the golden file has no cases")

        for c in cases {
            let name = c["name"] as? String ?? "<unnamed>"
            let matcher = NearSideMatcher(
                screenWidth: try XCTUnwrap(c["screenWidth"] as? Int, name),
                screenHeight: try XCTUnwrap(c["screenHeight"] as? Int, name)
            )
            let result = matcher.find(
                root: try Self.node(c["tree"]),
                selector: try NearSideSelector.parse(c["selector"]),
                limit: try XCTUnwrap(c["limit"] as? Int, name)
            )
            let expected = try XCTUnwrap(c["expected"] as? [Any], name).map(Self.node)

            XCTAssertEqual(result.matches.map(Self.describe), expected.map(Self.describe), name)
            XCTAssertEqual(result.truncated, c["truncated"] as? Bool, "\(name): truncated")
        }
    }

    func testUnknownSelectorField_isRefusedNotIgnored() {
        XCTAssertThrowsError(try NearSideSelector.parse(["textRegex": "a", "containsChild": ["textRegex": "b"]]))
        XCTAssertThrowsError(try NearSideSelector.parse(["below": ["size": 1]]))
        XCTAssertThrowsError(try NearSideSelector.parse(["enabled": "true"]))
    }

    // NODE = {attributes, children, clickable, enabled, focused, checked, selected}; nulls explicit
    private static func node(_ any: Any?) throws -> NearSideNode {
        let dict = try XCTUnwrap(any as? [String: Any])
        func flag(_ key: String) -> Bool? { dict[key] as? Bool }
        return NearSideNode(
            attributes: try XCTUnwrap(dict["attributes"] as? [String: String]),
            children: try (dict["children"] as? [Any] ?? []).map(node),
            clickable: flag("clickable"),
            enabled: flag("enabled"),
            focused: flag("focused"),
            checked: flag("checked"),
            selected: flag("selected")
        )
    }

    // A match compares by attributes and the five flags, never by children.
    private static func describe(_ n: NearSideNode) -> String {
        let attributes = n.attributes.sorted { $0.key < $1.key }.map { "\($0.key)=\($0.value)" }.joined(separator: ",")
        let flags = [n.clickable, n.enabled, n.focused, n.checked, n.selected].map { $0.map(String.init) ?? "null" }
        return "{\(attributes)} \(flags.joined(separator: ","))"
    }
}

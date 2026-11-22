import XCTest

/// smithr 2026-11-22 (run 45038422797): inputText typed one character, then queried the app's
/// main thread for the focused field; under load that query took 6.6 s, iOS's keyboard arbiter
/// disconnected the app, and 25 characters of a password were silently typed into nothing.
/// Now: wait once for keyboard + focus, type everything in one call, look again only afterwards,
/// and a detached keyboard fails by name. No retry.
@MainActor
final class TextInputSequenceTests: XCTestCase {

    private let ready = TextInputState(keyboardAttached: true, focusedFieldValue: "")

    func testTheWholeStringIsOneSendWithNoReadWhileTyping() async throws {
        let screen = Screen([ready, ready])
        try await type("unified-carer-password-1234", on: screen)
        XCTAssertEqual(screen.events, ["read", "send unified-carer-password-1234 @\(TextInputSequence.typingSpeed)", "read"])
    }

    func testItWaitsOnceForKeyboardAndFocusThenTypes() async throws {
        let screen = Screen([
            TextInputState(keyboardAttached: false, focusedFieldValue: nil),
            TextInputState(keyboardAttached: true, focusedFieldValue: nil),
            ready,
            ready,
        ])
        try await type("abc", on: screen)
        XCTAssertEqual(screen.events, ["read", "sleep", "read", "sleep", "read", "send abc @\(TextInputSequence.typingSpeed)", "read"])
    }

    func testNoKeyboardFailsByNameAndTypesNothing() async throws {
        let screen = Screen([TextInputState(keyboardAttached: false, focusedFieldValue: "")])
        let message = try await failure { try await self.type("abc", on: screen, timeout: 0.05) }
        XCTAssertTrue(message.contains("NO KEYBOARD"), message)
        XCTAssertFalse(screen.events.contains { $0.hasPrefix("send") })
    }

    func testNoFocusedFieldFailsByNameAndTypesNothing() async throws {
        let screen = Screen([TextInputState(keyboardAttached: true, focusedFieldValue: nil)])
        let message = try await failure { try await self.type("abc", on: screen, timeout: 0.05) }
        XCTAssertTrue(message.contains("NO FOCUSED FIELD"), message)
        XCTAssertFalse(screen.events.contains { $0.hasPrefix("send") })
    }

    func testAKeyboardThatDetachesWhileTypingFailsByNameAndIsNotRetried() async throws {
        let screen = Screen([ready, TextInputState(keyboardAttached: false, focusedFieldValue: "u")])
        let message = try await failure { try await self.type("unified-carer-password-1234", on: screen) }
        XCTAssertTrue(message.contains("KEYBOARD DETACHED"), message)
        XCTAssertEqual(screen.events.filter { $0.hasPrefix("send") }.count, 1)
    }

    func testAFieldThatLosesFocusWhileTypingFailsByName() async throws {
        let screen = Screen([ready, TextInputState(keyboardAttached: true, focusedFieldValue: nil)])
        let message = try await failure { try await self.type("abc", on: screen) }
        XCTAssertTrue(message.contains("LOST KEYBOARD FOCUS"), message)
    }

    func testEmptyTextDoesNothing() async throws {
        let screen = Screen([])
        try await type("", on: screen)
        XCTAssertEqual(screen.events, [])
    }

    private func type(_ text: String, on screen: Screen, timeout: TimeInterval = TextInputSequence.readyTimeout) async throws {
        try await TextInputSequence.type(
            text,
            timeout: timeout,
            state: { screen.read() },
            send: { keys, speed in screen.events.append("send \(keys) @\(speed)") },
            sleep: { _ in screen.events.append("sleep"); try await Task.sleep(nanoseconds: 10_000_000) })
    }

    private func failure(_ body: () async throws -> Void) async throws -> String {
        do {
            try await body()
        } catch let error as AppError {
            return error.message
        }
        XCTFail("expected an AppError")
        return ""
    }
}

/// A scripted screen: each read returns the next state; the last one repeats.
@MainActor
private final class Screen {
    private var states: [TextInputState]
    var events: [String] = []

    init(_ states: [TextInputState]) { self.states = states }

    func read() -> TextInputState {
        events.append("read")
        return states.count > 1 ? states.removeFirst() : states[0]
    }
}

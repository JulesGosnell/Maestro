import XCTest

/// smithr 2026-11-22 (run 45038422797): inputText typed one character, then queried the app's
/// main thread for the focused field; under load that query took 6.6 s, iOS's keyboard arbiter
/// disconnected the app, and 25 characters of a password were silently typed into nothing.
/// Now: wait once for keyboard + focus, type everything in one call, look again only afterwards,
/// and a detached keyboard fails by name. No retry.
@MainActor
final class TextInputSequenceTests: XCTestCase {

    private let ready = TextInputState(keyboardAttached: true, focused: FocusedField(value: ""))

    func testTheWholeStringIsOneSendWithNoReadWhileTyping() async throws {
        let screen = Screen([ready, ready])
        try await type("unified-carer-password-1234", on: screen)
        XCTAssertEqual(screen.events, ["read", "send unified-carer-password-1234 @\(TextInputSequence.typingSpeed)", "read"])
    }

    func testItWaitsOnceForKeyboardAndFocusThenTypes() async throws {
        let screen = Screen([
            TextInputState(keyboardAttached: false, focused: nil),
            TextInputState(keyboardAttached: true, focused: nil),
            ready,
            ready,
        ])
        try await type("abc", on: screen)
        XCTAssertEqual(screen.events, ["read", "sleep", "read", "sleep", "read", "send abc @\(TextInputSequence.typingSpeed)", "read"])
    }

    func testANineSecondStallBeforeFocusStillTypes() async throws {
        // run 45038422797: the VM stalled 9 s straight. Each read during the stall takes 1.5 s.
        let stalled = TextInputState(keyboardAttached: false, focused: nil)
        let screen = Screen(Array(repeating: stalled, count: 5) + [ready, ready], readCost: 1.5)
        try await type("unified-carer-password-1234", on: screen)
        XCTAssertGreaterThanOrEqual(screen.clock, 9)
        XCTAssertEqual(screen.events.filter { $0.hasPrefix("send") }, ["send unified-carer-password-1234 @\(TextInputSequence.typingSpeed)"])
    }

    func testTheWaitIsBoundedBy60SecondsAndPollsGently() async throws {
        let screen = Screen([TextInputState(keyboardAttached: false, focused: nil)])
        let message = try await failure { try await self.type("abc", on: screen) }
        XCTAssertTrue(message.contains("after 60.0s"), message)
        XCTAssertGreaterThanOrEqual(screen.clock, 60)
        XCTAssertLessThan(screen.clock, 61)
        // a sleep of pollInterval between every two reads, never two reads back to back
        let reads = screen.events.filter { $0 == "read" }.count
        XCTAssertEqual(screen.events.filter { $0 == "sleep" }.count, reads - 1)
        XCTAssertLessThanOrEqual(reads, Int(60 / TextInputSequence.pollInterval) + 1)
    }

    func testTheReadyTimeoutIsOverriddenByTheRunnerEnvironment() async {
        XCTAssertEqual(TextInputSequence.readyTimeout(environment: [:]), 60)
        XCTAssertEqual(TextInputSequence.readyTimeout(environment: ["MAESTRO_INPUT_TEXT_READY_TIMEOUT": "120"]), 120)
        XCTAssertEqual(TextInputSequence.readyTimeout(environment: ["MAESTRO_INPUT_TEXT_READY_TIMEOUT": "nope"]), 60)
        XCTAssertEqual(TextInputSequence.readyTimeout(environment: ["MAESTRO_INPUT_TEXT_READY_TIMEOUT": "0"]), 60)
    }

    func testNoKeyboardFailsByNameAndTypesNothing() async throws {
        let screen = Screen([TextInputState(keyboardAttached: false, focused: FocusedField(value: ""))])
        let message = try await failure { try await self.type("abc", on: screen) }
        XCTAssertTrue(message.contains("NO KEYBOARD"), message)
        XCTAssertFalse(screen.events.contains { $0.hasPrefix("send") })
    }

    func testNoFocusedFieldFailsByNameAndTypesNothing() async throws {
        let screen = Screen([TextInputState(keyboardAttached: true, focused: nil)])
        let message = try await failure { try await self.type("abc", on: screen) }
        XCTAssertTrue(message.contains("NO FOCUSED FIELD"), message)
        XCTAssertFalse(screen.events.contains { $0.hasPrefix("send") })
    }

    func testAKeyboardThatDetachesWhileTypingFailsByNameAndIsNotRetried() async throws {
        let screen = Screen([ready, TextInputState(keyboardAttached: false, focused: FocusedField(value: "u"))])
        let message = try await failure { try await self.type("unified-carer-password-1234", on: screen) }
        XCTAssertTrue(message.contains("KEYBOARD DETACHED"), message)
        XCTAssertEqual(screen.events.filter { $0.hasPrefix("send") }.count, 1)
    }

    func testAFieldThatLosesFocusWhileTypingFailsByName() async throws {
        let screen = Screen([ready, TextInputState(keyboardAttached: true, focused: nil)])
        let message = try await failure { try await self.type("abc", on: screen) }
        XCTAssertTrue(message.contains("LOST KEYBOARD FOCUS"), message)
    }

    func testCharactersDroppedFromAnEmptyFieldFailByName() async throws {
        let screen = Screen([emptyField(), after("c")])
        let message = try await failure { try await self.type("cnified-carer@demo.artha.care", on: screen) }
        XCTAssertTrue(message.contains("CHARACTERS DROPPED"), message)
        XCTAssertTrue(message.contains("typed 29") && message.contains("holds 1"), message)
        XCTAssertEqual(screen.events.filter { $0.hasPrefix("send") }.count, 1)
    }

    func testAllCharactersInAnEmptyFieldPass() async throws {
        try await type("abc", on: Screen([emptyField(), after("abc")]))
    }

    func testAFormattingFieldThatAddsCharactersPasses() async throws {
        try await type("07700900123", on: Screen([emptyField(), after("07700 900 123")]))
    }

    func testASecureFieldIsNotCounted() async throws {
        try await type("hunter22", on: Screen([emptyField(secure: true), after("•", secure: true)]))
    }

    func testAFieldThatWasNotEmptyIsNotCounted() async throws {
        let notEmpty = TextInputState(keyboardAttached: true, focused: FocusedField(value: "abc", empty: false))
        try await type("def", on: Screen([notEmpty, after("x")]))
    }

    func testAnUnreadableValueIsNotCounted() async throws {
        try await type("abc", on: Screen([emptyField(), TextInputState(keyboardAttached: true, focused: FocusedField(value: nil))]))
    }

    func testEmptyTextDoesNothing() async throws {
        let screen = Screen([])
        try await type("", on: screen)
        XCTAssertEqual(screen.events, [])
    }

    private func emptyField(secure: Bool = false) -> TextInputState {
        TextInputState(keyboardAttached: true, focused: FocusedField(value: "Email", empty: true, secure: secure))
    }

    private func after(_ value: String, secure: Bool = false) -> TextInputState {
        TextInputState(keyboardAttached: true, focused: FocusedField(value: value, secure: secure))
    }

    private func type(_ text: String, on screen: Screen, timeout: TimeInterval = TextInputSequence.defaultReadyTimeout) async throws {
        try await TextInputSequence.type(
            text,
            timeout: timeout,
            state: { screen.read() },
            send: { keys, speed in screen.events.append("send \(keys) @\(speed)") },
            sleep: { seconds in screen.events.append("sleep"); screen.clock += seconds },
            now: { Date(timeIntervalSinceReferenceDate: screen.clock) })
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

/// A scripted screen on a simulated clock: each read returns the next state (the last one
/// repeats) and takes `readCost` seconds — a stalled app answers slowly; sleeps advance it too.
@MainActor
private final class Screen {
    private var states: [TextInputState]
    private let readCost: TimeInterval
    var events: [String] = []
    var clock: TimeInterval = 0

    init(_ states: [TextInputState], readCost: TimeInterval = 0) {
        self.states = states
        self.readCost = readCost
    }

    func read() -> TextInputState {
        events.append("read")
        clock += readCost
        return states.count > 1 ? states.removeFirst() : states[0]
    }
}

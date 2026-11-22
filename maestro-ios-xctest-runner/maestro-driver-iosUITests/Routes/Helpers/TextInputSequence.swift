import Foundation

/// What inputText needs to know about the screen: is a keyboard attached, and which field (if
/// any) holds keyboard focus.
struct TextInputState {
    let keyboardAttached: Bool
    /// The focused field's value; nil when no field has keyboard focus.
    let focusedFieldValue: String?

    var ready: Bool { keyboardAttached && focusedFieldValue != nil }
}

/// Types text in ONE synthesized event, with NO query of the app while it is being typed.
///
/// smithr 2026-11-22 (run 45038422797): inputText used to type the first character alone, then
/// poll the focused field until it landed (#4733, after #1119's 500 ms sleep). That poll is an
/// XCTest query on the app's MAIN THREAD. Under host load one poll took 6.6 s; the app could not
/// answer SpringBoard's keyboard arbiter meanwhile, the arbiter disconnected it, and the other 25
/// characters of a password were typed into a keyboard of size 0 — silently. The arbiter's
/// timeout is Apple's.
///
/// So, agreed with artha and norn:
///   1. wait ONCE (bounded) until a keyboard is attached AND a field has focus — else fail by name;
///   2. type the whole string in one call;
///   3. look once more AFTER typing: a keyboard that detached, or a field that lost focus, fails
///      BY NAME. Never retried — a retry would type into a field of unknown content.
@MainActor
struct TextInputSequence {
    static let readyTimeout: TimeInterval = 3
    static let typingSpeed = 30
    private static let pollInterval: TimeInterval = 0.1

    /// `state` reads the screen (only before and after typing, never during); `send` types text
    /// at a typing speed; `sleep` waits between pre-typing reads.
    static func type(
        _ text: String,
        timeout: TimeInterval = readyTimeout,
        state: () throws -> TextInputState,
        send: (String, Int) async throws -> Void,
        sleep: (TimeInterval) async throws -> Void = { try await Task.sleep(nanoseconds: UInt64($0 * 1_000_000_000)) }
    ) async throws {
        guard !text.isEmpty else { return }

        let deadline = Date().addingTimeInterval(timeout)
        var before = try state()
        while !before.ready {
            guard Date() < deadline else {
                throw AppError(message: notReadyMessage(before, timeout: timeout, count: text.count))
            }
            try await sleep(pollInterval)
            before = try state()
        }

        try await send(text, typingSpeed)

        let after = try state()
        if !after.keyboardAttached {
            throw AppError(message: "inputText: KEYBOARD DETACHED while typing \(text.count) characters — some or all of them were not typed (under load iOS's keyboard arbiter disconnects an app that does not answer in time). Not retried.")
        }
        if after.focusedFieldValue == nil {
            throw AppError(message: "inputText: the field LOST KEYBOARD FOCUS while typing \(text.count) characters — some or all of them were not typed. Not retried.")
        }
    }

    private static func notReadyMessage(_ s: TextInputState, timeout: TimeInterval, count: Int) -> String {
        let missing: String
        switch (s.keyboardAttached, s.focusedFieldValue != nil) {
        case (false, false): missing = "NO KEYBOARD attached and NO FOCUSED FIELD"
        case (false, true): missing = "NO KEYBOARD attached"
        default: missing = "NO FOCUSED FIELD"
        }
        return "inputText: \(missing) after \(timeout)s; none of the \(count) characters were typed"
    }
}

import Foundation

/// What inputText needs to know about the screen: is a keyboard attached, and which field (if
/// any) holds keyboard focus.
struct TextInputState {
    let keyboardAttached: Bool
    /// The field holding keyboard focus; nil when there is none.
    let focused: FocusedField?

    var ready: Bool { keyboardAttached && focused != nil }
}

struct FocusedField {
    /// Its value as a string; nil when unreadable. An EMPTY field reports its placeholder here,
    /// which is why `empty` is separate.
    let value: String?
    /// Holds no text (value empty, or equal to the placeholder).
    let empty: Bool
    let secure: Bool

    init(value: String?, empty: Bool = false, secure: Bool = false) {
        self.value = value
        self.empty = empty
        self.secure = secure
    }
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
///      BY NAME; so does a field that was empty, is not secure, and now holds FEWER characters
///      than were typed (dropped keys). Fewer, not different: a formatting field (phone mask)
///      may add characters. Never retried — a retry would type into a field of unknown content.
@MainActor
struct TextInputSequence {
    /// Generous on purpose (artha, under Rojina's rule that a too-short timeout IS the bug): on
    /// run 45038422797 the VM stalled for 9 s straight, and a 3 s wait would have turned that
    /// into a NO KEYBOARD red. Bounded only by this.
    nonisolated static let defaultReadyTimeout: TimeInterval = 60
    /// Overrides `defaultReadyTimeout`, in seconds. It is read from the RUNNER's environment, so
    /// set it in Maestro's environment with the prefix of the launch path that starts the runner:
    /// `TEST_RUNNER_` (xcodebuild test-without-building) or `SIMCTL_CHILD_` (simctl launch) —
    /// the same way PORT and snapshotKeyHonorModalViews reach it.
    nonisolated static let readyTimeoutVariable = "MAESTRO_INPUT_TEXT_READY_TIMEOUT"
    nonisolated static let typingSpeed = 30
    /// Gentle: while the app is stalled every read is work on its main thread, so the wait
    /// sleeps between reads rather than hammering it.
    nonisolated static let pollInterval: TimeInterval = 0.5

    /// The ready timeout an environment asks for; the default when unset or not a positive number.
    nonisolated static func readyTimeout(environment: [String: String]) -> TimeInterval {
        guard let raw = environment[readyTimeoutVariable], let seconds = TimeInterval(raw), seconds > 0 else {
            return defaultReadyTimeout
        }
        return seconds
    }

    /// `state` reads the screen (only before and after typing, never during); `send` types text
    /// at a typing speed; `sleep` waits between pre-typing reads; `now` is the clock.
    static func type(
        _ text: String,
        timeout: TimeInterval = readyTimeout(environment: ProcessInfo.processInfo.environment),
        state: () throws -> TextInputState,
        send: (String, Int) async throws -> Void,
        sleep: (TimeInterval) async throws -> Void = { try await Task.sleep(nanoseconds: UInt64($0 * 1_000_000_000)) },
        now: () -> Date = Date.init
    ) async throws {
        guard !text.isEmpty else { return }

        let deadline = now().addingTimeInterval(timeout)
        var before = try state()
        while !before.ready {
            guard now() < deadline else {
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
        guard let field = after.focused else {
            throw AppError(message: "inputText: the field LOST KEYBOARD FOCUS while typing \(text.count) characters — some or all of them were not typed. Not retried.")
        }
        if let wasEmpty = before.focused?.empty, wasEmpty, !field.secure, let value = field.value,
           value.count < text.count {
            throw AppError(message: "inputText: CHARACTERS DROPPED — typed \(text.count) characters into an empty field, it holds \(value.count). Not retried.")
        }
    }

    private static func notReadyMessage(_ s: TextInputState, timeout: TimeInterval, count: Int) -> String {
        let missing: String
        switch (s.keyboardAttached, s.focused != nil) {
        case (false, false): missing = "NO KEYBOARD attached and NO FOCUSED FIELD"
        case (false, true): missing = "NO KEYBOARD attached"
        default: missing = "NO FOCUSED FIELD"
        }
        return "inputText: \(missing) after \(timeout)s; none of the \(count) characters were typed"
    }
}

import XCTest

/// #4733: the first character makes the keyboard reconfigure, and keys arriving before that ends
/// are dropped. Under load it took longer than the fixed 500 ms the runner used to sleep, and a
/// sign-in typed "cnified-…" with 7 characters missing.
@MainActor
final class TextInputSequenceTests: XCTestCase {

    func testTheRestWaitsUntilTheFirstCharacterHasLanded() async throws {
        let field = SlowKeyboardField(reconfigure: 0.8)
        try await type("cnified-carer@demo.artha.care", into: field)
        XCTAssertEqual(field.keptValue, "cnified-carer@demo.artha.care")
    }

    func testAFirstCharacterThatNeverLandsFailsLoudlyAndSendsNothingMore() async throws {
        let field = SlowKeyboardField(reconfigure: 10)
        do {
            try await type("fnified", into: field, timeout: 0.5)
            XCTFail("typing succeeded although the first character never landed")
        } catch let error as AppError {
            XCTAssertTrue(error.message.contains("did not reach the focused field"), error.message)
        }
        XCTAssertEqual(field.received, ["f"])
    }

    func testNoFocusedFieldFailsBeforeTyping() async throws {
        do {
            try await TextInputSequence.type("x", fieldValue: { nil }, send: { _, _ in XCTFail("sent a key") })
            XCTFail("typing succeeded with no focused field")
        } catch is AppError {}
    }

    private func type(_ text: String, into field: SlowKeyboardField, timeout: TimeInterval = TextInputSequence.firstCharacterTimeout) async throws {
        try await TextInputSequence.type(text, timeout: timeout, fieldValue: { field.value }, send: { keys, _ in field.send(keys) })
    }
}

/// A field as iOS behaves under load: the first key starts a keyboard reconfigure lasting
/// `reconfigure` seconds, keys arriving meanwhile are lost, and the first character appears in
/// the value only when it ends.
@MainActor
private final class SlowKeyboardField {
    private let reconfigure: TimeInterval
    private var typed = ""
    private var busyUntil: Date?
    private(set) var received: [String] = []

    init(reconfigure: TimeInterval) {
        self.reconfigure = reconfigure
    }

    /// What the field ends up holding, once any reconfigure is over.
    var keptValue: String { typed }

    var value: String {
        guard let busyUntil, Date() < busyUntil else { return typed }
        return ""
    }

    func send(_ keys: String) {
        received.append(keys)
        if let busyUntil, Date() < busyUntil { return }
        if typed.isEmpty { busyUntil = Date().addingTimeInterval(reconfigure) }
        typed += keys
    }
}

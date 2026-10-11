import Foundation

/// Types text so that iOS keeps every key (#4733). The first character goes alone because it makes
/// the keyboard reconfigure (shift state, autocorrection), and keys sent before that finishes are
/// dropped. Under load the reconfigure outlasts any fixed pause — a 500 ms sleep lost 7 characters —
/// so the rest waits until the first character has landed in the field.
@MainActor
struct TextInputSequence {
    static let firstCharacterTimeout: TimeInterval = 3
    private static let pollInterval: UInt64 = 50_000_000

    /// `fieldValue` reads the focused field (nil: there is none); `send` types text at a typing speed.
    static func type(
        _ text: String,
        timeout: TimeInterval = firstCharacterTimeout,
        fieldValue: () throws -> String?,
        send: (String, Int) async throws -> Void
    ) async throws {
        let firstCharacter = String(text.prefix(1))
        guard let before = try fieldValue() else {
            throw AppError(message: "No focused text field to type '\(firstCharacter)' into")
        }
        try await send(firstCharacter, 1)

        let deadline = Date().addingTimeInterval(timeout)
        while try fieldValue() == before {
            guard Date() < deadline else {
                throw AppError(message: "The first character '\(firstCharacter)' did not reach the focused field within \(timeout)s; the remaining \(text.count - 1) characters were not sent")
            }
            try await Task.sleep(nanoseconds: pollInterval)
        }

        if text.count > 1 {
            try await send(String(text.dropFirst()), 30)
        }
    }
}

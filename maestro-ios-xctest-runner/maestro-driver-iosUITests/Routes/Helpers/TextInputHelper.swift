import Foundation
import os
import XCTest

@MainActor
struct TextInputHelper {
    private static let logger = Logger(
        subsystem: Bundle.main.bundleIdentifier!,
        category: String(describing: Self.self)
    )
    
    private enum Constants {
        static let typingFrequency = 30
        static let slowInputCharactersCount = 1
    }
    
    static func waitUntilKeyboardIsPresented() async throws {
        let deadline = Date().addingTimeInterval(1.0)
        while Date() < deadline {
            let isKeyboardPresented = try catchingObjCException {
                let app = RunningApp.getForegroundApp() ?? XCUIApplication(bundleIdentifier: RunningApp.springboardBundleId)
                return app.keyboards.firstMatch.exists
            }
            if isKeyboardPresented { return }
            try? await Task.sleep(nanoseconds: 200_000_000)
        }
    }

    static func inputText(_ text: String) async throws {
        try await TextInputSequence.type(text, fieldValue: focusedFieldValue, send: typeKeys)
    }

    /// Erasing keeps a fixed pause after the first delete: a delete in an already empty field
    /// changes nothing, so there is no landing to wait for.
    static func eraseText(_ deleteText: String) async throws {
        try await typeKeys(String(deleteText.prefix(Constants.slowInputCharactersCount)), typingSpeed: 1)

        // wait 500 ms before dispatching next input text request to avoid iOS dropping characters
        try await Task.sleep(nanoseconds: UInt64(1_000_000_000 * 0.5))

        if (deleteText.count > Constants.slowInputCharactersCount) {
            try await typeKeys(String(deleteText.dropFirst(Constants.slowInputCharactersCount)), typingSpeed: Constants.typingFrequency)
        }
    }

    private static func typeKeys(_ text: String, typingSpeed: Int) async throws {
        logger.info("typing: \(text)")
        let eventRecord = try catchingObjCException {
            var eventPath = PointerEventPath.pathForTextInput()
            eventPath.type(text: text, typingSpeed: typingSpeed)
            let eventRecord = EventRecord(orientation: ScreenSizeHelper.currentInterfaceOrientation())
            _ = eventRecord.add(eventPath)
            return eventRecord
        }
        try await RunnerDaemonProxy().synthesize(eventRecord: eventRecord)
    }

    /// The value of the field holding keyboard focus; nil when no field has it.
    private static func focusedFieldValue() throws -> String? {
        try catchingObjCException {
            let app = RunningApp.getForegroundApp() ?? XCUIApplication(bundleIdentifier: RunningApp.springboardBundleId)
            let focused = app.descendants(matching: .any)
                .matching(NSPredicate(format: "hasKeyboardFocus == true"))
                .firstMatch
            guard let snapshot = try? focused.snapshot() else { return nil }
            return snapshot.value as? String ?? ""
        }
    }
}

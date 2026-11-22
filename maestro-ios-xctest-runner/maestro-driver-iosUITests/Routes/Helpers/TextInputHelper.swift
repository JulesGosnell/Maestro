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
        try await TextInputSequence.type(text, state: inputState, send: typeKeys)
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

    /// Whether a keyboard is attached (present with a non-empty frame: after a keyboard-arbiter
    /// disconnect it is left at size 0), and the value of the field holding keyboard focus.
    private static func inputState() throws -> TextInputState {
        try catchingObjCException {
            let app = RunningApp.getForegroundApp() ?? XCUIApplication(bundleIdentifier: RunningApp.springboardBundleId)
            let keyboard = try? app.keyboards.firstMatch.snapshot()
            let keyboardAttached = keyboard.map { !$0.frame.isEmpty } ?? false
            let focused = app.descendants(matching: .any)
                .matching(NSPredicate(format: "hasKeyboardFocus == true"))
                .firstMatch
            let field = (try? focused.snapshot()).map { snapshot -> FocusedField in
                let value = snapshot.value as? String
                let empty = value == "" || (value != nil && value == snapshot.placeholderValue)
                return FocusedField(value: value, empty: empty, secure: snapshot.elementType == .secureTextField)
            }
            return TextInputState(keyboardAttached: keyboardAttached, focused: field)
        }
    }
}

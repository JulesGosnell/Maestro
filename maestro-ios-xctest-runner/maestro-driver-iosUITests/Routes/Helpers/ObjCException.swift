import Foundation

/// Runs `body`, turning an NSException it raises into a thrown NSError (#4697).
/// Wrap every synchronous stretch of XCTest calls in a handler: an `await` ends the stretch,
/// because the code after it runs outside this frame.
func catchingObjCException<T>(_ body: () throws -> T) throws -> T {
    var result: Result<T, Error>!
    try ObjCExceptionCatcher.catchException {
        result = Result { try body() }
    }
    return try result.get()
}

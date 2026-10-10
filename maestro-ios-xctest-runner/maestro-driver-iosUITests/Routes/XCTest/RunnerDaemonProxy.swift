import Foundation

@MainActor
class RunnerDaemonProxy {
    private let proxy: NSObject
    
    init() throws {
        proxy = try catchingObjCException {
            let clazz: AnyClass = NSClassFromString("XCTRunnerDaemonSession")!
            let selector = NSSelectorFromString("sharedSession")
            let imp = clazz.method(for: selector)
            typealias Method = @convention(c) (AnyClass, Selector) -> NSObject
            let method = unsafeBitCast(imp, to: Method.self)
            let session = method(clazz, selector)

            return session
                .perform(NSSelectorFromString("daemonProxy"))
                .takeUnretainedValue() as! NSObject
        }
    }

    func send(string: String, typingFrequency: Int = 10) async throws {
        let selector = NSSelectorFromString("_XCT_sendString:maximumFrequency:completion:")
        let imp = proxy.method(for: selector)
        typealias Method = @convention(c) (NSObject, Selector, NSString, Int, @escaping (Error?) -> ()) -> ()
        let method = unsafeBitCast(imp, to: Method.self)
        return try await withCheckedThrowingContinuation { continuation in
            do {
                try catchingObjCException {
                    method(proxy, selector, string as NSString, typingFrequency, { error in
                        if let error = error {
                            continuation.resume(with: .failure(error))
                        } else {
                            continuation.resume(with: .success(()))
                        }
                    })
                }
            } catch {
                continuation.resume(throwing: error)
            }
        }
    }

    func synthesize(eventRecord: EventRecord) async throws {
        let selector = NSSelectorFromString("_XCT_synthesizeEvent:completion:")
        let imp = proxy.method(for: selector)
        typealias Method = @convention(c) (NSObject, Selector, NSObject, @escaping (Error?) -> ()) -> ()
        let method = unsafeBitCast(imp, to: Method.self)
        try await withCheckedThrowingContinuation { (continuation: CheckedContinuation<Void, Error>) in
            do {
                try catchingObjCException {
                    method(proxy, selector, eventRecord.eventRecord, { error in
                        if let error = error {
                            continuation.resume(with: .failure(error))
                        } else {
                            continuation.resume(with: .success(()))
                        }
                    })
                }
            } catch {
                continuation.resume(throwing: error)
            }
        }
    }
}

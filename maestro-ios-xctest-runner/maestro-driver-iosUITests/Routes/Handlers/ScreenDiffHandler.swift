import Foundation
import XCTest
import CryptoKit
import FlyingFox
import os
import MaestroDriverLib

@MainActor
struct IsScreenStaticHandler: HTTPHandler {
    private let logger = Logger(
        subsystem: Bundle.main.bundleIdentifier!,
        category: String(describing: Self.self)
    )
    
    func handleRequest(_ request: FlyingFox.HTTPRequest) async throws -> FlyingFox.HTTPResponse {
        // artha#5049: ?threshold= is the fraction of pixels allowed to differ; absent, the upstream SHA test
        let threshold = request.query["threshold"].flatMap { Double($0) } ?? 0
        do {
            let isScreenStatic = try catchingObjCException {
                let screenshot1 = XCUIScreen.main.screenshot()
                let screenshot2 = XCUIScreen.main.screenshot()
                if threshold > 0, let frame1 = ScreenFrame.of(screenshot1), let frame2 = ScreenFrame.of(screenshot2) {
                    return frame1.isStatic(comparedWith: frame2, threshold: threshold)
                }
                let hash1 = SHA256.hash(data: screenshot1.pngRepresentation)
                let hash2 = SHA256.hash(data: screenshot2.pngRepresentation)
                return hash1 == hash2
            }
            
            let response = ["isScreenStatic" : isScreenStatic]
            
            let responseData = try JSONSerialization.data(
                withJSONObject: response,
                options: .prettyPrinted
            )
            return HTTPResponse(statusCode: .ok, body: responseData)
        } catch let error {
            return AppError(message: "Detecting screen static request failed. Error \(error.localizedDescription)").httpResponse
        }
    }
}

// smithr#1246 Phase C: the settle's screen-static loop, on the runner.
//
// POST /screenStatic {timeoutMs} -> {isScreenStatic, attempts, frames, elapsedMs}
//
// The same verdict as GET /isScreenStatic (two consecutive screenshots, equal or not), reached
// without its two costs: the PNG encode of every screenshot (compared here as raw pixels — equal
// PNGs are equal pixels), and a fresh pair of screenshots per host round trip (here each new
// screenshot is compared with the previous one, until two are equal or timeoutMs has elapsed).
// At least one comparison is made. Additive: the host calls it only when GET /capabilities
// advertises `screenStatic`, and only under -Dmaestro.fastStatic=on.
enum ScreenStatic {
    static let version = 1
    static let maxTimeoutMs = 30_000
}

/// The screenshot's pixels, as the bytes of its CGImage (format included, so two frames compare
/// equal only if their layout and their pixels are the same).
private struct ScreenFrame: Equatable {
    let width: Int
    let height: Int
    let bytesPerRow: Int
    let bitsPerPixel: Int
    let bitmapInfo: UInt32
    let pixels: Data

    static func capture() -> ScreenFrame? {
        of(XCUIScreen.main.screenshot())
    }

    static func of(_ screenshot: XCUIScreenshot) -> ScreenFrame? {
        guard let cg = screenshot.image.cgImage, let data = cg.dataProvider?.data else { return nil }
        return ScreenFrame(width: cg.width, height: cg.height, bytesPerRow: cg.bytesPerRow,
                           bitsPerPixel: cg.bitsPerPixel, bitmapInfo: cg.bitmapInfo.rawValue, pixels: data as Data)
    }

    /// artha#5049: same layout, and at most `threshold` of the pixels differ (0: every pixel equal)
    func isStatic(comparedWith other: ScreenFrame, threshold: Double) -> Bool {
        guard width == other.width, height == other.height, bytesPerRow == other.bytesPerRow,
              bitsPerPixel == other.bitsPerPixel, bitmapInfo == other.bitmapInfo else { return false }
        return ScreenDiff.isStatic(pixels, other.pixels, width: width, height: height, bytesPerRow: bytesPerRow,
                                   bytesPerPixel: bitsPerPixel / 8, threshold: threshold)
    }
}

@MainActor
struct ScreenStaticHandler: HTTPHandler {
    func handleRequest(_ request: FlyingFox.HTTPRequest) async throws -> FlyingFox.HTTPResponse {
        let start = DispatchTime.now()
        func elapsedMs() -> Int { Int((DispatchTime.now().uptimeNanoseconds - start.uptimeNanoseconds) / 1_000_000) }
        do {
            let data = try await request.bodyData
            let body = (try? JSONSerialization.jsonObject(with: data)) as? [String: Any] ?? [:]
            guard let requested = body["timeoutMs"] as? Int, requested >= 0 else {
                return AppError(type: .precondition, message: "timeoutMs is required").httpResponse
            }
            let timeoutMs = min(requested, ScreenStatic.maxTimeoutMs)
            let threshold = (body["threshold"] as? NSNumber)?.doubleValue ?? 0

            let (isStatic, attempts, frames) = try catchingObjCException { () -> (Bool, Int, Int) in
                var previous = ScreenFrame.capture()
                var attempts = 0
                var frames = 1
                repeat {
                    let current = ScreenFrame.capture()
                    frames += 1
                    if let p = previous, let c = current {
                        attempts += 1
                        if p.isStatic(comparedWith: c, threshold: threshold) { return (true, attempts, frames) }
                    }
                    previous = current
                } while elapsedMs() < timeoutMs
                return (false, attempts, frames)
            }

            let response: [String: Any] = [
                "isScreenStatic": isStatic, "attempts": attempts, "frames": frames, "elapsedMs": elapsedMs(),
            ]
            return HTTPResponse(statusCode: .ok, body: try JSONSerialization.data(withJSONObject: response))
        } catch let error {
            return AppError(message: "screenStatic request failed. Error \(error.localizedDescription)").httpResponse
        }
    }
}

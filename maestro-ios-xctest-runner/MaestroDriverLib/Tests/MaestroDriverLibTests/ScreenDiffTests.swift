import XCTest
@testable import MaestroDriverLib

/// artha#5049: sizes from an iPhone simulator screenshot (1170 x 2532, RGBA). The caret box is
/// what two frames 0.5 s apart actually differed by beside a focused field.
final class ScreenDiffTests: XCTestCase {

    private let width = 1170
    private let height = 2532
    private let bytesPerPixel = 4
    private let threshold = 0.0005

    private var bytesPerRow: Int { width * bytesPerPixel }

    private func frame() -> Data {
        Data(repeating: 0xFF, count: bytesPerRow * height)
    }

    /// `frame` with every pixel in the box changed.
    private func painted(_ frame: Data, x: Int, y: Int, w: Int, h: Int) -> Data {
        var out = frame
        for row in y..<(y + h) {
            for col in x..<(x + w) {
                let p = row * bytesPerRow + col * bytesPerPixel
                out[p] = 0x00
            }
        }
        return out
    }

    private func isStatic(_ a: Data, _ b: Data) -> Bool {
        ScreenDiff.isStatic(a, b, width: width, height: height, bytesPerRow: bytesPerRow,
                            bytesPerPixel: bytesPerPixel, threshold: threshold)
    }

    func testIdenticalFrames_areStatic() {
        XCTAssertTrue(isStatic(frame(), frame()))
    }

    func testCaretSizedDifference_isStatic() {
        // 6 x 57 px = 342 px, 0.0115% of the screen
        let caret = painted(frame(), x: 981, y: 1068, w: 6, h: 57)
        XCTAssertEqual(ScreenDiff.differingFraction(frame(), caret, width: width, height: height,
                                                    bytesPerRow: bytesPerRow, bytesPerPixel: bytesPerPixel)!,
                       342.0 / Double(width * height), accuracy: 1e-12)
        XCTAssertTrue(isStatic(frame(), caret))
    }

    func testTextLineSizedDifference_isNotStatic() {
        // a changed line of text across the field: 900 x 45 px, 1.37% of the screen
        let line = painted(frame(), x: 135, y: 700, w: 900, h: 45)
        XCTAssertFalse(isStatic(frame(), line))
    }

    func testZeroThreshold_isExact() {
        let caret = painted(frame(), x: 981, y: 1068, w: 6, h: 57)
        XCTAssertFalse(ScreenDiff.isStatic(frame(), caret, width: width, height: height,
                                           bytesPerRow: bytesPerRow, bytesPerPixel: bytesPerPixel, threshold: 0))
        XCTAssertTrue(ScreenDiff.isStatic(frame(), frame(), width: width, height: height,
                                          bytesPerRow: bytesPerRow, bytesPerPixel: bytesPerPixel, threshold: 0))
    }

    func testDifferentBufferSizes_areNotStatic() {
        XCTAssertFalse(isStatic(frame(), frame().dropLast(bytesPerRow)))
    }
}

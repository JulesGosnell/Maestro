import Foundation

/// artha#5049: whether two screenshots show a static screen.
///
/// Upstream asks for byte-identical frames. A focused text field's blinking caret changes 344 px
/// (0.0116%) of an iPhone screen between frames, so every settle beside a focused field ran out
/// its 3 s budget. A difference of at most `threshold` (a fraction of the pixels) is static; a
/// `threshold` of 0 keeps the exact comparison.
public enum ScreenDiff {

    /// The fraction of pixels that differ between two frames of the same layout, or nil when the
    /// buffers cannot hold that layout.
    public static func differingFraction(
        _ a: Data, _ b: Data,
        width: Int, height: Int, bytesPerRow: Int, bytesPerPixel: Int
    ) -> Double? {
        let rowBytes = width * bytesPerPixel
        guard width > 0, height > 0, bytesPerPixel > 0, bytesPerRow >= rowBytes,
              a.count == b.count, a.count >= bytesPerRow * (height - 1) + rowBytes else { return nil }
        var differing = 0
        a.withUnsafeBytes { (pa: UnsafeRawBufferPointer) in
            b.withUnsafeBytes { (pb: UnsafeRawBufferPointer) in
                guard let baseA = pa.baseAddress, let baseB = pb.baseAddress else { return }
                for y in 0..<height {
                    let row = y * bytesPerRow
                    if memcmp(baseA + row, baseB + row, rowBytes) == 0 { continue }
                    for x in 0..<width {
                        let p = row + x * bytesPerPixel
                        if memcmp(baseA + p, baseB + p, bytesPerPixel) != 0 { differing += 1 }
                    }
                }
            }
        }
        return Double(differing) / Double(width * height)
    }

    public static func isStatic(
        _ a: Data, _ b: Data,
        width: Int, height: Int, bytesPerRow: Int, bytesPerPixel: Int,
        threshold: Double
    ) -> Bool {
        if threshold <= 0 { return a == b }
        guard let fraction = differingFraction(
            a, b, width: width, height: height, bytesPerRow: bytesPerRow, bytesPerPixel: bytesPerPixel
        ) else { return false }
        return fraction <= threshold
    }
}

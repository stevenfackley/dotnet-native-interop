import SwiftUI

/// Helpers for "visual" features whose result is a rendered image rather than text. The engine ships
/// the image as the compact string `"WxH:base64"` (8-bit grayscale, row-major) over the normal feature
/// path, so no ABI or transport change is needed — every pixel is computed in .NET and this only wraps
/// the raw buffer in a `CGImage` (no image-decoding library).
enum VisualFeature {
    /// True for features whose result should be rendered as an image.
    static func isVisual(_ id: String) -> Bool { id.hasPrefix("viz-") }

    /// Parses `"128x128:<base64>"` into a grayscale SwiftUI `Image`, or nil if malformed.
    static func image(from payload: String) -> Image? {
        guard let colon = payload.firstIndex(of: ":") else { return nil }
        let dimensions = payload[..<colon].split(separator: "x")
        guard dimensions.count == 2,
              let width = Int(dimensions[0]), let height = Int(dimensions[1]),
              let data = Data(base64Encoded: String(payload[payload.index(after: colon)...])),
              data.count == width * height else { return nil }

        guard let provider = CGDataProvider(data: data as CFData),
              let cgImage = CGImage(
                width: width, height: height,
                bitsPerComponent: 8, bitsPerPixel: 8, bytesPerRow: width,
                space: CGColorSpaceCreateDeviceGray(),
                bitmapInfo: CGBitmapInfo(rawValue: 0),
                provider: provider, decode: nil,
                shouldInterpolate: false, intent: .defaultIntent)
        else { return nil }

        return Image(decorative: cgImage, scale: 1)
    }
}

/// Renders a visual feature's `"WxH:base64"` payload as a framed image, with a graceful fallback.
struct FractalImageView: View {
    let payload: String

    var body: some View {
        if let image = VisualFeature.image(from: payload) {
            image
                .interpolation(.none)
                .resizable()
                .scaledToFit()
                .frame(maxWidth: .infinity)
                .frame(maxHeight: 320)
                .background(.black)
                .clipShape(RoundedRectangle(cornerRadius: 12))
        } else {
            ContentUnavailableView("Could not decode image", systemImage: "photo")
        }
    }
}

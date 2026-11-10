import UIKit
import Vision

/// Reads the text in a screenshot on the phone (Apple's Vision text recognition) so the label form
/// can offer it as suggestions. Nothing read here goes into the log unless the tester taps it.
enum ScreenText {
    /// A line that looks like a web address: "example.com", "www.x.org/path", "https://...".
    static func isWebAddress(_ s: String) -> Bool {
        if s.hasPrefix("http://") || s.hasPrefix("https://") { return true }
        guard !s.contains(" "), let dot = s.firstIndex(of: "."), dot != s.startIndex else { return false }
        let tail = s[s.index(after: dot)...].prefix { $0.isLetter }
        return tail.count >= 2
    }

    /// Lines of text, web addresses first, then top of the screen first, without the status bar, duplicates or single characters.
    /// Language correction is off so a suggestion is the text as shown, not a spell-checked version.
    static func lines(in image: UIImage, limit: Int = 12) async -> [String] {
        guard let cg = image.cgImage else { return [] }
        return await withCheckedContinuation { done in
            DispatchQueue.global(qos: .userInitiated).async {
                let request = VNRecognizeTextRequest()
                request.recognitionLevel = .accurate
                request.usesLanguageCorrection = false
                try? VNImageRequestHandler(cgImage: cg, orientation: .up).perform([request])
                // Vision's boxes are normalised with the origin at the bottom left; the top 6% is the status bar.
                let found = (request.results ?? [])
                    .filter { $0.boundingBox.maxY < 0.94 }
                    .sorted { $0.boundingBox.maxY > $1.boundingBox.maxY }
                var all: [String] = []
                for o in found {
                    guard let s = o.topCandidates(1).first?.string.trimmingCharacters(in: .whitespacesAndNewlines),
                          s.count >= 2, !all.contains(s) else { continue }
                    all.append(s)
                }
                // Web addresses first (Safari on iOS 26 shows the address bar at the bottom), then the rest top down.
                let web = all.filter(isWebAddress)
                done.resume(returning: Array((web + all.filter { !isWebAddress($0) }).prefix(limit)))
            }
        }
    }
}

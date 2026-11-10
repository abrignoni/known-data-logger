// swift-tools-version:5.9
import PackageDescription

// The iOS logger's core: entry model, hash chain, store and export. No UI, no third-party code,
// so it builds and tests on the Mac with `swift test` as well as inside the app.
let package = Package(
    name: "KDLCore",
    platforms: [.iOS(.v17), .macOS(.v14)],
    products: [.library(name: "KDLCore", targets: ["KDLCore"])],
    targets: [
        .target(name: "KDLCore", linkerSettings: [.linkedLibrary("sqlite3")]),
        .testTarget(name: "KDLCoreTests", dependencies: ["KDLCore"]),
    ]
)

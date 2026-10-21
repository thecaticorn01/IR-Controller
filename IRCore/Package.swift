// swift-tools-version: 6.0
import PackageDescription

let package = Package(
    name: "IRCore",
    platforms: [.macOS(.v15)],
    products: [
        .library(name: "IRCore", targets: ["IRCore"]),
    ],
    targets: [
        .target(name: "IRCore"),
        .testTarget(name: "IRCoreTests", dependencies: ["IRCore"]),
    ]
)

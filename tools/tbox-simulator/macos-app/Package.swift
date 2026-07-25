// swift-tools-version: 5.9
import PackageDescription

let package = Package(
    name: "MotoHubTBoxSimulatorApp",
    platforms: [.macOS(.v13)],
    products: [
        .executable(name: "MOTO-HUB-TBox-Simulator", targets: ["MotoHubTBoxSimulatorApp"])
    ],
    targets: [
        .executableTarget(name: "MotoHubTBoxSimulatorApp")
    ]
)

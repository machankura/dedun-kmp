import SwiftUI
import ComposeApp

@main
struct iOSApp: App {
    init() {
        // Before the first screen: the profile's Ark wallet reaches Bark through this
        // (docs/bark-wallet.md, Phase 8), and the Kotlin side reads it when it first asks
        // whether this device can have one.
        ArkEngineRegistry.shared.register(engine: BarkEngine())
    }

    var body: some Scene {
        WindowGroup {
            ContentView()
        }
    }
}

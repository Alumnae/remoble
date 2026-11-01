import SwiftUI

@main
struct RemobleApp: App {
    @StateObject private var bridge = RemobleBridge()
    @StateObject private var backgroundAudio = BackgroundAudioKeeper()

    var body: some Scene {
        WindowGroup {
            ContentView()
                .environmentObject(bridge)
                .environmentObject(backgroundAudio)
        }
    }
}

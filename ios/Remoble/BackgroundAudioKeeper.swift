import AVFoundation
import MediaPlayer

enum NoiseSound: String, CaseIterable, Identifiable {
    case white, pink, brown, rain

    var id: String { rawValue }
    var title: String {
        switch self { case .white: return "White"; case .pink: return "Pink"; case .brown: return "Brown"; case .rain: return "Soft Rain" }
    }
    var subtitle: String {
        switch self {
        case .white: return "Focus & privacy"
        case .pink: return "Balanced calm"
        case .brown: return "Deep relaxation"
        case .rain: return "Gentle ambience"
        }
    }
    var icon: String {
        switch self { case .white: return "sparkles"; case .pink: return "waveform"; case .brown: return "wind"; case .rain: return "cloud.rain.fill" }
    }
}

/// A real background audio player. While the user is listening, iOS can keep
/// the process active and the BLE/HTTP bridge can continue doing useful work.
@MainActor
final class BackgroundAudioKeeper: ObservableObject {
    @Published private(set) var isPlaying = false
    @Published private(set) var selectedSound: NoiseSound
    @Published private(set) var volume: Double
    @Published private(set) var sleepMinutes: Int?
    @Published private(set) var status = "Ready"
    @Published private(set) var updatedAt = Date()
    @Published private(set) var autoPlayOnLaunch: Bool

    private let engine = AVAudioEngine()
    private let player = AVAudioPlayerNode()
    private var loopBuffer: AVAudioPCMBuffer?
    private var sleepTask: Task<Void, Never>?
    private let defaults = UserDefaults.standard
    private var applyingRemoteState = false

    init() {
        selectedSound = NoiseSound(rawValue: defaults.string(forKey: "noise.sound") ?? "") ?? .pink
        let savedVolume = defaults.object(forKey: "noise.volume") as? Double
        volume = min(1, max(0.02, savedVolume ?? 0.32))
        autoPlayOnLaunch = defaults.bool(forKey: "noise.autoplay.launch")
        configureRemoteCommands()
    }

    func togglePlayback() { isPlaying ? pause() : play() }

    func setAutoPlayOnLaunch(_ enabled: Bool) {
        autoPlayOnLaunch = enabled
        defaults.set(enabled, forKey: "noise.autoplay.launch")
    }

    func startLaunchAutoplayIfEnabled() {
        guard autoPlayOnLaunch else { return }
        select(.white)
        setVolume(0.02)
        play()
    }

    func play() {
        isPlaying = true
        touchState()
        startAudio()
    }

    func pause() {
        isPlaying = false
        sleepTask?.cancel(); sleepTask = nil; sleepMinutes = nil
        player.stop(); engine.stop(); loopBuffer = nil
        MPNowPlayingInfoCenter.default().nowPlayingInfo = nil
        try? AVAudioSession.sharedInstance().setActive(false, options: .notifyOthersOnDeactivation)
        status = "Paused"
        touchState()
    }

    func select(_ sound: NoiseSound) {
        guard selectedSound != sound else { return }
        selectedSound = sound
        defaults.set(sound.rawValue, forKey: "noise.sound")
        touchState()
        if isPlaying {
            player.stop(); loopBuffer = nil
            startAudio()
        }
    }

    func setVolume(_ value: Double) {
        volume = min(1, max(0, value))
        engine.mainMixerNode.outputVolume = Float(volume)
        defaults.set(volume, forKey: "noise.volume")
        touchState()
    }

    func setSleepTimer(minutes: Int?) {
        sleepTask?.cancel(); sleepTask = nil; sleepMinutes = minutes
        touchState()
        guard let minutes else { return }
        if !isPlaying { play() }
        sleepTask = Task {
            try? await Task<Never, Never>.sleep(nanoseconds: UInt64(minutes) * 60_000_000_000)
            guard !Task.isCancelled else { return }
            pause()
        }
    }

    func resumeIfPlaying() {
        guard isPlaying, !engine.isRunning || !player.isPlaying else { return }
        startAudio()
    }

    func handleInterruption(_ notification: Notification) {
        guard isPlaying,
              let raw = notification.userInfo?[AVAudioSessionInterruptionTypeKey] as? UInt,
              AVAudioSession.InterruptionType(rawValue: raw) == .ended else { return }
        let optionsRaw = notification.userInfo?[AVAudioSessionInterruptionOptionKey] as? UInt ?? 0
        if AVAudioSession.InterruptionOptions(rawValue: optionsRaw).contains(.shouldResume) { startAudio() }
        else { status = "Paused by system" }
    }

    /// Applies a peer snapshot without creating a newer local revision. This
    /// prevents two bidirectionally linked Remoble instances from echoing the
    /// same state back and forth indefinitely.
    func applyRemote(sound: NoiseSound, volume: Double, timer: Int?,
                     playing: Bool, sourceUpdatedAt: Date) {
        applyingRemoteState = true
        select(sound)
        setVolume(volume)
        setSleepTimer(minutes: timer)
        if playing { play() } else { pause() }
        applyingRemoteState = false
        updatedAt = sourceUpdatedAt
    }

    private func startAudio() {
        do {
            let session = AVAudioSession.sharedInstance()
            try session.setCategory(.playback, mode: .default, options: [.mixWithOthers])
            try session.setActive(true)
            let format = AVAudioFormat(standardFormatWithSampleRate: 44_100, channels: 1)!
            if player.engine == nil {
                engine.attach(player)
                engine.connect(player, to: engine.mainMixerNode, format: format)
            }
            player.stop()
            let buffer = try makeBuffer(sound: selectedSound, format: format, seconds: 8)
            loopBuffer = buffer
            player.scheduleBuffer(buffer, at: nil, options: .loops)
            engine.mainMixerNode.outputVolume = Float(volume)
            if !engine.isRunning { engine.prepare(); try engine.start() }
            player.play()
            status = "Playing \(selectedSound.title) Noise"
            MPNowPlayingInfoCenter.default().nowPlayingInfo = [
                MPMediaItemPropertyTitle: "\(selectedSound.title) Noise",
                MPMediaItemPropertyArtist: "Remoble Sounds",
                MPNowPlayingInfoPropertyPlaybackRate: 1.0,
                MPNowPlayingInfoPropertyIsLiveStream: true
            ]
        } catch {
            isPlaying = false
            status = "Audio error: \(error.localizedDescription)"
        }
    }

    private func configureRemoteCommands() {
        let commands = MPRemoteCommandCenter.shared()
        commands.playCommand.isEnabled = true
        commands.pauseCommand.isEnabled = true
        commands.togglePlayPauseCommand.isEnabled = true
        commands.playCommand.addTarget { [weak self] _ in
            Task { @MainActor in self?.play() }
            return .success
        }
        commands.pauseCommand.addTarget { [weak self] _ in
            Task { @MainActor in self?.pause() }
            return .success
        }
        commands.togglePlayPauseCommand.addTarget { [weak self] _ in
            Task { @MainActor in self?.togglePlayback() }
            return .success
        }
    }

    private func touchState() {
        guard !applyingRemoteState else { return }
        updatedAt = Date()
    }

    private func makeBuffer(sound: NoiseSound, format: AVAudioFormat, seconds: Int) throws -> AVAudioPCMBuffer {
        let count = AVAudioFrameCount(Int(format.sampleRate) * seconds)
        guard let buffer = AVAudioPCMBuffer(pcmFormat: format, frameCapacity: count),
              let samples = buffer.floatChannelData?[0] else {
            throw RemobleError.message("Unable to create audio buffer")
        }
        buffer.frameLength = count
        var pinkA: Float = 0, pinkB: Float = 0, pinkC: Float = 0
        var brown: Float = 0, rainEnvelope: Float = 0
        for index in 0..<Int(count) {
            let white = Float.random(in: -1...1)
            let value: Float
            switch sound {
            case .white:
                value = white * 0.38
            case .pink:
                pinkA = 0.99765 * pinkA + white * 0.099046
                pinkB = 0.96300 * pinkB + white * 0.296516
                pinkC = 0.57000 * pinkC + white * 1.052692
                value = (pinkA + pinkB + pinkC + white * 0.1848) * 0.11
            case .brown:
                brown = (brown + white * 0.018) / 1.018
                value = brown * 1.7
            case .rain:
                if Float.random(in: 0...1) > 0.997 { rainEnvelope = Float.random(in: 0.25...0.8) }
                rainEnvelope *= 0.9988
                value = white * (0.10 + rainEnvelope * 0.34)
            }
            samples[index] = min(0.92, max(-0.92, value))
        }
        // Crossfade the loop boundary to avoid a click every eight seconds.
        let fade = min(2_048, Int(count) / 4)
        for offset in 0..<fade {
            let mix = Float(offset) / Float(fade)
            let start = samples[offset], end = samples[Int(count) - fade + offset]
            let blended = start * mix + end * (1 - mix)
            samples[offset] = blended; samples[Int(count) - fade + offset] = blended
        }
        return buffer
    }
}

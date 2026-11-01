import Foundation
import SwiftUI

struct WhiteNoiseView: View {
    @EnvironmentObject private var audio: BackgroundAudioKeeper
    @EnvironmentObject private var bridge: RemobleBridge
    @State private var pulsing = false
    @StateObject private var remote = RemoteNoisePeer()

    var body: some View {
        ZStack {
            LinearGradient(colors: palette, startPoint: .topLeading, endPoint: .bottomTrailing)
                .ignoresSafeArea()
            Circle().fill(Color.white.opacity(0.08)).frame(width: 360, height: 360)
                .blur(radius: 2).offset(x: 170, y: -310)
            Circle().fill(Color.black.opacity(0.14)).frame(width: 300, height: 300)
                .blur(radius: 24).offset(x: -180, y: 360)

            ScrollView(showsIndicators: false) {
                VStack(spacing: 28) {
                    header
                    playerOrb
                    nowPlaying
                    soundSelector
                    controls
                    remoteControl
                    bridgeStatus
                }
                .padding(.horizontal, 22)
                .padding(.top, 18)
                .padding(.bottom, 32)
            }
        }
        .preferredColorScheme(.dark)
        .onAppear {
            withAnimation(.easeInOut(duration: 2.2).repeatForever(autoreverses: true)) { pulsing = true }
            remote.attach(audio)
        }
        .onChange(of: remote.syncEnabled) { enabled in
            if enabled { remote.refresh() }
        }
        .animation(.easeInOut(duration: 0.45), value: audio.selectedSound)
    }

    private var header: some View {
        HStack {
            VStack(alignment: .leading, spacing: 4) {
                Text("REMOBLE SOUNDS").font(.caption.weight(.semibold)).tracking(2.4).opacity(0.72)
                Text(greeting).font(.system(size: 30, weight: .bold, design: .rounded))
            }
            Spacer()
            Image(systemName: audio.isPlaying ? "waveform.circle.fill" : "moon.stars.fill")
                .font(.system(size: 30)).symbolRenderingMode(.hierarchical)
        }
        .foregroundColor(.white)
    }

    private var playerOrb: some View {
        ZStack {
            Circle().stroke(Color.white.opacity(0.10), lineWidth: 1)
                .frame(width: 248, height: 248).scaleEffect(pulsing && audio.isPlaying ? 1.08 : 0.98)
            Circle().stroke(Color.white.opacity(0.16), lineWidth: 1)
                .frame(width: 210, height: 210).scaleEffect(pulsing && audio.isPlaying ? 1.04 : 0.98)
            Circle()
                .fill(LinearGradient(colors: [Color.white.opacity(0.25), Color.white.opacity(0.07)], startPoint: .topLeading, endPoint: .bottomTrailing))
                .overlay(Circle().stroke(Color.white.opacity(0.34), lineWidth: 1))
                .shadow(color: accent.opacity(0.55), radius: 36)
                .frame(width: 174, height: 174)
            VStack(spacing: 15) {
                waveform
                Button(action: togglePlayback) {
                    Image(systemName: audio.isPlaying ? "pause.fill" : "play.fill")
                        .font(.system(size: 28, weight: .semibold))
                        .frame(width: 62, height: 62)
                        .background(Color.white)
                        .foregroundColor(accent)
                        .clipShape(Circle())
                        .shadow(color: Color.black.opacity(0.22), radius: 12, y: 6)
                }
                .accessibilityLabel(audio.isPlaying ? "Pause" : "Play")
            }
        }
        .frame(height: 260)
    }

    private var waveform: some View {
        HStack(spacing: 3) {
            ForEach(0..<17, id: \.self) { index in
                Capsule()
                    .fill(Color.white.opacity(0.9))
                    .frame(width: 3, height: audio.isPlaying ? waveHeight(index) * (pulsing ? 1.0 : 0.72) : 4)
            }
        }
        .frame(height: 28)
    }

    private var nowPlaying: some View {
        VStack(spacing: 7) {
            Text("\(audio.selectedSound.title) Noise")
                .font(.system(size: 27, weight: .bold, design: .rounded))
            Text(audio.isPlaying ? "Playing continuously" : "Tap play to begin")
                .font(.subheadline).foregroundColor(.white.opacity(0.68))
        }
        .foregroundColor(.white)
    }

    private var soundSelector: some View {
        VStack(alignment: .leading, spacing: 14) {
            Text("Choose your atmosphere").font(.headline).foregroundColor(.white)
            ScrollView(.horizontal, showsIndicators: false) {
                HStack(spacing: 12) {
                    ForEach(NoiseSound.allCases) { sound in
                        Button { selectSound(sound) } label: {
                            VStack(alignment: .leading, spacing: 12) {
                                Image(systemName: sound.icon).font(.title2)
                                Spacer(minLength: 4)
                                Text(sound.title).font(.headline)
                                Text(sound.subtitle).font(.caption).opacity(0.68)
                            }
                            .foregroundColor(.white)
                            .padding(16)
                            .frame(width: 142, height: 140, alignment: .leading)
                            .background(sound == audio.selectedSound ? Color.white.opacity(0.22) : Color.white.opacity(0.09))
                            .overlay(RoundedRectangle(cornerRadius: 22).stroke(Color.white.opacity(sound == audio.selectedSound ? 0.55 : 0.12), lineWidth: 1))
                            .clipShape(RoundedRectangle(cornerRadius: 22))
                        }
                    }
                }
            }
        }
    }

    private var controls: some View {
        VStack(spacing: 20) {
            HStack(spacing: 14) {
                Image(systemName: "speaker.fill").opacity(0.65)
                Slider(value: Binding(get: { audio.volume }, set: setVolume), in: 0...1)
                    .accentColor(.white)
                Image(systemName: "speaker.wave.3.fill")
            }
            HStack {
                Label("Sleep timer", systemImage: "timer").font(.subheadline.weight(.medium))
                Spacer()
                Menu {
                    Button("Off") { setTimer(nil) }
                    ForEach([15, 30, 45, 60, 90], id: \.self) { value in
                        Button("\(value) minutes") { setTimer(value) }
                    }
                } label: {
                    HStack(spacing: 6) {
                        Text(audio.sleepMinutes.map { "\($0) min" } ?? "Off")
                        Image(systemName: "chevron.down").font(.caption)
                    }
                    .padding(.horizontal, 13).padding(.vertical, 8)
                    .background(Color.white.opacity(0.12)).clipShape(Capsule())
                }
            }
            HStack {
                Label("Auto-play on launch", systemImage: "play.circle.fill")
                    .font(.subheadline.weight(.medium))
                Spacer()
                Toggle(
                    "Auto-play on launch",
                    isOn: Binding(
                        get: { audio.autoPlayOnLaunch },
                        set: audio.setAutoPlayOnLaunch
                    )
                )
                .labelsHidden()
                .tint(.green)
            }
        }
        .foregroundColor(.white)
        .padding(20)
        .background(.ultraThinMaterial)
        .clipShape(RoundedRectangle(cornerRadius: 24))
        .overlay(RoundedRectangle(cornerRadius: 24).stroke(Color.white.opacity(0.12)))
    }

    private var remoteControl: some View {
        VStack(alignment: .leading, spacing: 14) {
            HStack {
                Label("Remote Remoble", systemImage: "hifispeaker.2.fill")
                    .font(.headline)
                Spacer()
                Toggle("Remote Remoble", isOn: $remote.syncEnabled)
                    .labelsHidden()
                    .tint(.green)
            }
            if remote.syncEnabled {
                TextField("http://192.168.1.20:33777", text: $remote.baseURL)
                    .textInputAutocapitalization(.never)
                    .autocorrectionDisabled()
                    .keyboardType(.URL)
                    .font(.system(.subheadline, design: .monospaced))
                    .padding(10)
                    .background(Color.white.opacity(0.10))
                    .clipShape(RoundedRectangle(cornerRadius: 10))
                SecureField("Remote password (optional)", text: $remote.password)
                    .textContentType(.password)
                    .padding(10)
                    .background(Color.white.opacity(0.10))
                    .clipShape(RoundedRectangle(cornerRadius: 10))
                HStack(spacing: 10) {
                    Button {
                        remote.play(sound: audio.selectedSound)
                    } label: {
                        Label("Play", systemImage: "play.fill")
                    }
                    .buttonStyle(.borderedProminent)
                    Button {
                        remote.stop()
                    } label: {
                        Label("Stop", systemImage: "stop.fill")
                    }
                    .buttonStyle(.bordered)
                    Spacer()
                    Button(action: remote.refresh) {
                        Image(systemName: "arrow.clockwise")
                    }
                    .buttonStyle(.bordered)
                }
                Text(remote.status)
                    .font(.caption)
                    .foregroundStyle(remote.hasError ? Color.red : Color.white.opacity(0.65))
                    .transition(.opacity.combined(with: .move(edge: .top)))
            }
        }
        .foregroundColor(.white)
        .padding(20)
        .background(.ultraThinMaterial)
        .clipShape(RoundedRectangle(cornerRadius: 24))
        .overlay(RoundedRectangle(cornerRadius: 24).stroke(Color.white.opacity(0.12)))
        .animation(.easeInOut(duration: 0.22), value: remote.syncEnabled)
    }

    private var bridgeStatus: some View {
        HStack(spacing: 12) {
            ZStack {
                Circle().fill(bridge.serverRunning ? Color.green.opacity(0.2) : Color.white.opacity(0.1)).frame(width: 42, height: 42)
                Image(systemName: "antenna.radiowaves.left.and.right").foregroundColor(bridge.serverRunning ? .green : .white.opacity(0.6))
            }
            VStack(alignment: .leading, spacing: 3) {
                Text("Remote bridge").font(.subheadline.weight(.semibold))
                Text(bridge.serverRunning ? "Online • \(bridge.connectedReaders.count) BLE connected" : "Offline")
                    .font(.caption).foregroundColor(.white.opacity(0.62))
            }
            Spacer()
            Image(systemName: "gearshape.fill").foregroundColor(.white.opacity(0.5))
        }
        .foregroundColor(.white)
        .padding(16)
        .background(Color.black.opacity(0.14))
        .clipShape(RoundedRectangle(cornerRadius: 20))
    }

    private var palette: [Color] {
        switch audio.selectedSound {
        case .white: return [Color(red: 0.16, green: 0.28, blue: 0.48), Color(red: 0.07, green: 0.11, blue: 0.23)]
        case .pink: return [Color(red: 0.48, green: 0.19, blue: 0.36), Color(red: 0.12, green: 0.08, blue: 0.22)]
        case .brown: return [Color(red: 0.39, green: 0.24, blue: 0.16), Color(red: 0.11, green: 0.08, blue: 0.09)]
        case .rain: return [Color(red: 0.10, green: 0.35, blue: 0.43), Color(red: 0.05, green: 0.12, blue: 0.20)]
        }
    }
    private var accent: Color { palette[0] }

    private func togglePlayback() {
        audio.togglePlayback()
        guard remote.syncEnabled else { return }
        if audio.isPlaying { remote.play(sound: audio.selectedSound) }
        else { remote.stop() }
    }

    private func selectSound(_ sound: NoiseSound) {
        audio.select(sound)
        if remote.syncEnabled && audio.isPlaying { remote.play(sound: sound) }
    }

    private func setVolume(_ value: Double) {
        audio.setVolume(value)
        if remote.syncEnabled { remote.setVolume(value) }
    }

    private func setTimer(_ minutes: Int?) {
        audio.setSleepTimer(minutes: minutes)
        if remote.syncEnabled { remote.setTimer(minutes) }
    }
    private var greeting: String {
        let hour = Calendar.current.component(.hour, from: Date())
        return hour < 12 ? "Good morning" : hour < 18 ? "Good afternoon" : "Good evening"
    }
    private func waveHeight(_ index: Int) -> CGFloat { CGFloat(8 + abs(sin(Double(index) * 0.82)) * 19) }
}

@MainActor
private final class RemoteNoisePeer: ObservableObject {
    @Published var baseURL: String {
        didSet { defaults.set(baseURL, forKey: "remote.audio.url") }
    }
    @Published var password = ""
    @Published var syncEnabled: Bool {
        didSet {
            defaults.set(syncEnabled, forKey: "remote.audio.sync")
            if syncEnabled { startPolling() } else { pollingTask?.cancel(); pollingTask = nil }
        }
    }
    @Published private(set) var status = "Enter another Remoble URL"
    @Published private(set) var hasError = false

    private let defaults = UserDefaults.standard
    private let clientID: String
    private var volumeTask: Task<Void, Never>?
    private var pollingTask: Task<Void, Never>?
    private weak var localAudio: BackgroundAudioKeeper?
    private var lastAppliedRemoteUpdate: Int64 = 0

    init() {
        baseURL = defaults.string(forKey: "remote.audio.url") ?? ""
        syncEnabled = defaults.bool(forKey: "remote.audio.sync")
        if let saved = defaults.string(forKey: "remote.audio.client") {
            clientID = saved
        } else {
            let generated = "remoble-audio-\(UUID().uuidString)"
            defaults.set(generated, forKey: "remote.audio.client")
            clientID = generated
        }
    }

    func attach(_ audio: BackgroundAudioKeeper) {
        localAudio = audio
        if syncEnabled { startPolling() }
    }

    func play(sound: NoiseSound) {
        send(path: "/play", body: ["sound": sound.rawValue])
    }

    func stop() {
        send(path: "/stop", body: [:])
    }

    func setVolume(_ value: Double) {
        volumeTask?.cancel()
        volumeTask = Task { [weak self] in
            try? await Task<Never, Never>.sleep(nanoseconds: 180_000_000)
            guard !Task.isCancelled else { return }
            await self?.perform(path: "/volume", method: "POST", body: ["value": value], applyRemote: false)
        }
    }

    func setTimer(_ minutes: Int?) {
        send(path: "/timer", body: ["minutes": minutes ?? NSNull()])
    }

    func refresh() {
        guard !baseURL.trimmingCharacters(in: .whitespacesAndNewlines).isEmpty else { return }
        Task { await perform(path: "/audio", method: "GET", body: nil, applyRemote: true) }
    }

    private func send(path: String, body: [String: Any]) {
        Task { await perform(path: path, method: "POST", body: body, applyRemote: false) }
    }

    private func startPolling() {
        guard pollingTask == nil else { return }
        pollingTask = Task { [weak self] in
            while !Task.isCancelled {
                guard let self, self.syncEnabled else { return }
                await self.perform(path: "/audio", method: "GET", body: nil, applyRemote: true)
                try? await Task<Never, Never>.sleep(nanoseconds: 2_000_000_000)
            }
        }
    }

    private func perform(path: String, method: String, body: [String: Any]?, applyRemote: Bool) async {
        let entered = baseURL.trimmingCharacters(in: .whitespacesAndNewlines)
        let normalized = entered.contains("://") ? entered : "http://\(entered)"
        guard var components = URLComponents(string: normalized), components.host != nil else {
            hasError = true; status = "Invalid remote URL"; return
        }
        let basePath = components.path.trimmingCharacters(in: CharacterSet(charactersIn: "/"))
        let commandPath = path.trimmingCharacters(in: CharacterSet(charactersIn: "/"))
        components.path = "/" + [basePath, commandPath].filter { !$0.isEmpty }.joined(separator: "/")
        guard let url = components.url else {
            hasError = true; status = "Invalid remote URL"; return
        }
        var request = URLRequest(url: url, timeoutInterval: 8)
        request.httpMethod = method
        request.setValue("application/json", forHTTPHeaderField: "Content-Type")
        request.setValue(clientID, forHTTPHeaderField: "X-Remoble-Client")
        if !password.isEmpty { request.setValue("Bearer \(password)", forHTTPHeaderField: "Authorization") }
        if let body { request.httpBody = try? JSONSerialization.data(withJSONObject: body) }
        do {
            let (data, response) = try await URLSession.shared.data(for: request)
            guard let http = response as? HTTPURLResponse, (200..<300).contains(http.statusCode) else {
                throw RemobleError.message("Remote request rejected")
            }
            let state = (try? JSONSerialization.jsonObject(with: data)) as? [String: Any]
            let playing = state?["playing"] as? Bool ?? false
            let sound = state?["sound"] as? String ?? "unknown"
            let volume = (state?["volume"] as? NSNumber)?.doubleValue
            if applyRemote, let state { applyRemoteState(state) }
            hasError = false
            status = volume.map { "Remote \(playing ? "playing" : "stopped") · \(sound) · \(Int($0 * 100))%" }
                ?? "Remote \(playing ? "playing" : "stopped")"
        } catch {
            hasError = true
            status = error.localizedDescription
        }
    }

    private func applyRemoteState(_ state: [String: Any]) {
        guard syncEnabled, let audio = localAudio,
              let update = (state["updatedAt"] as? NSNumber)?.int64Value,
              update > lastAppliedRemoteUpdate else { return }
        lastAppliedRemoteUpdate = update
        guard let soundName = state["sound"] as? String,
              let sound = NoiseSound(rawValue: soundName),
              let volume = (state["volume"] as? NSNumber)?.doubleValue else { return }
        let timer = (state["timer"] as? NSNumber)?.intValue
        audio.applyRemote(
            sound: sound,
            volume: volume,
            timer: state["timer"] is NSNull ? nil : timer,
            playing: state["playing"] as? Bool == true,
            sourceUpdatedAt: Date(timeIntervalSince1970: Double(update) / 1_000)
        )
    }
}

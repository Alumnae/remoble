import AVFoundation
import SwiftUI
import UIKit

struct ContentView: View {
    @EnvironmentObject private var bridge: RemobleBridge
    @EnvironmentObject private var audio: BackgroundAudioKeeper
    @State private var started = false
    @State private var showAutoplayPrompt = false
    @AppStorage("noise.autoplay.prompted") private var autoplayPrompted = false

    var body: some View {
        TabView {
            WhiteNoiseView()
                .tabItem { Label("Sounds", systemImage: "waveform") }
            BridgeSettingsView()
                .tabItem { Label("Bridge", systemImage: "gearshape.2.fill") }
        }
        .accentColor(.purple)
        .background(KeyboardDismissTapView())
        .toolbar {
            ToolbarItemGroup(placement: .keyboard) {
                Spacer()
                Button("Done") { Self.dismissKeyboard() }
            }
        }
        .alert("Auto-play White Noise?", isPresented: $showAutoplayPrompt) {
            Button("Not now", role: .cancel) {
                autoplayPrompted = true
                audio.setAutoPlayOnLaunch(false)
            }
            Button("Enable") {
                autoplayPrompted = true
                audio.setAutoPlayOnLaunch(true)
                audio.startLaunchAutoplayIfEnabled()
            }
        } message: {
            Text("Play audible White Noise at 2% volume when Remoble opens? You can change this later in Sounds.")
        }
        .onAppear {
            guard !started else { return }
            started = true
            bridge.attachAudio(audio)
            bridge.startServer()
            bridge.restoreAndScan()
            if audio.autoPlayOnLaunch {
                audio.startLaunchAutoplayIfEnabled()
            } else if !autoplayPrompted {
                showAutoplayPrompt = true
            }
        }
        .onReceive(NotificationCenter.default.publisher(for: UIApplication.didBecomeActiveNotification)) { _ in
            audio.resumeIfPlaying()
            bridge.wakeReadersForRemoteAccess()
        }
        .onReceive(NotificationCenter.default.publisher(for: AVAudioSession.interruptionNotification)) {
            audio.handleInterruption($0)
        }
        .onReceive(NotificationCenter.default.publisher(for: AVAudioSession.mediaServicesWereResetNotification)) { _ in
            audio.resumeIfPlaying()
        }
    }
}

private struct BridgeSettingsView: View {
    @EnvironmentObject private var bridge: RemobleBridge
    @EnvironmentObject private var audio: BackgroundAudioKeeper
    @State private var remotePassword = ""
    @State private var httpPort = ""

    var body: some View {
        NavigationView {
            Form {
                Section("Remote bridge") {
                    valueRow("Status", bridge.serverStatus)
                    valueRow("Remote client URL", bridge.serverURL).textSelection(.enabled)
                    VStack(alignment: .leading, spacing: 8) {
                        HStack {
                            Text("Port")
                            Spacer()
                        TextField("HTTP port", text: $httpPort)
                            .keyboardType(.numberPad)
                            .font(.system(.body, design: .monospaced))
                                .multilineTextAlignment(.trailing)
                                .textFieldStyle(.plain)
                                .frame(width: 110)
                        }
                        HStack {
                            Text("1024–65535")
                                .font(.caption)
                                .foregroundStyle(.secondary)
                            Spacer()
                            Button("Apply") {
                                bridge.setHTTPPort(httpPort)
                                httpPort = String(bridge.httpPort)
                            }
                            .buttonStyle(.borderedProminent)
                            .disabled(Int(httpPort).map { !(1024...65535).contains($0) } ?? true)
                        }
                    }
                    Text("Changing the port restarts the HTTP listener. Update the URL in the compatible remote client to match.")
                        .font(.caption).foregroundStyle(.secondary)
                    Button(
                        bridge.serverTransitioning
                            ? (bridge.serverTransitionTarget == true ? "Starting…" : "Stopping…")
                            : (bridge.serverRunning ? "Stop HTTP server" : "Start HTTP server")
                    ) {
                        bridge.toggleServer()
                    }
                    .disabled(bridge.serverTransitioning)
                }

                Section("Remote security") {
                    HStack {
                        Label(
                            bridge.httpPasswordConfigured ? "Password protected" : "No password configured",
                            systemImage: bridge.httpPasswordConfigured ? "lock.shield.fill" : "lock.open.fill"
                        )
                        .foregroundStyle(bridge.httpPasswordConfigured ? .green : .orange)
                        Spacer()
                    }
                    SecureField("Remote password", text: $remotePassword)
                        .textContentType(.newPassword)
                        .autocorrectionDisabled()
                    Button(bridge.httpPasswordConfigured ? "Update password" : "Enable password") {
                        bridge.setHTTPPassword(remotePassword)
                        if bridge.httpPasswordConfigured { remotePassword = "" }
                    }
                    .disabled(remotePassword.trimmingCharacters(in: .whitespacesAndNewlines).isEmpty)
                    if bridge.httpPasswordConfigured {
                        Button("Remove password protection", role: .destructive) {
                            bridge.setHTTPPassword("")
                            remotePassword = ""
                        }
                    }
                    Text("Use the same password in the compatible remote client. Remoble requires Bearer authentication and encrypts card responses and POST bodies with AES-256-CBC.")
                        .font(.caption).foregroundStyle(.secondary)
                }

                Section("Background operation") {
                    valueRow("Audio", audio.status)
                    Text("Play a sound from the Sounds tab to keep audio active while the screen is locked. CoreBluetooth restoration remains enabled independently.")
                        .font(.caption).foregroundStyle(.secondary)
                }

                Section("Channel handling") {
                    Toggle(isOn: Binding(
                        get: { bridge.ensureSingleChannel },
                        set: { bridge.setEnsureSingleChannel($0) }
                    )) {
                        Label("Ensure one logical channel", systemImage: "arrow.triangle.2.circlepath")
                    }
                    .toggleStyle(CheckboxToggleStyle())
                    Text("When enabled, opening a new channel for the same remote client closes its previous channel on that card. Other clients and Bluetooth remain connected.")
                        .font(.caption).foregroundStyle(.secondary)
                }

                Section("BLE signal safety") {
                    HStack {
                        Label("Minimum RSSI", systemImage: "antenna.radiowaves.left.and.right")
                        Spacer()
                        Text("\(bridge.minimumRSSI) dBm")
                            .font(.body.monospacedDigit())
                            .foregroundStyle(.secondary)
                    }
                    Slider(
                        value: Binding(
                            get: { Double(bridge.minimumRSSI) },
                            set: { bridge.setMinimumRSSI(Int($0.rounded())) }
                        ),
                        in: -120 ... -50,
                        step: 1
                    ) {
                        Text("Minimum RSSI")
                    } minimumValueLabel: {
                        Text("-120").font(.caption2)
                    } maximumValueLabel: {
                        Text("-50").font(.caption2)
                    }
                    Text("A new secure-card transaction is refused below this threshold. An operation already in progress is never interrupted because of RSSI.")
                        .font(.caption).foregroundStyle(.secondary)
                    if bridge.minimumRSSI < -90 {
                        Label("Below -90 dBm the BLE link may be highly unstable during a secure-card transfer.",
                              systemImage: "exclamationmark.triangle.fill")
                            .font(.caption)
                            .foregroundStyle(.orange)
                    }
                }

                Section("BLE cards") {
                    Button(bridge.scanning ? "Scanning…" : "Scan for readers") { bridge.startScan() }
                        .disabled(bridge.scanning)
                    if bridge.readers.isEmpty {
                        Text("No compatible BLE card found").foregroundStyle(.secondary)
                    }
                    ForEach(bridge.readers) { reader in
                        BLEReaderCard(reader: reader)
                    }
                    .onDelete { offsets in
                        let targets = offsets.compactMap { index in
                            bridge.readers.indices.contains(index) ? bridge.readers[index] : nil
                        }
                        targets.forEach { bridge.forget($0) }
                    }
                    Text("Tap a reader name to connect. The checkbox controls whether it is remembered and exposed to compatible remote clients. Each card stores its own PIN.")
                        .font(.caption).foregroundStyle(.secondary)
                }

                Section("Diagnostics") {
                    valueRow("Connection", bridge.connectionStatus)
                    valueRow("Connected readers", "\(bridge.connectedReaders.count)")
                    if let error = bridge.lastError { Text(error).foregroundStyle(.red).textSelection(.enabled) }
                    ForEach(bridge.connectedReaders) { reader in
                        Button("Disconnect \(reader.name)", role: .destructive) { bridge.disconnect(reader) }
                    }
                }

                Section("About") {
                    valueRow("Version", appVersion)
                    valueRow("Build", appBuild)
                    valueRow("Compatibility", "iOS 15.6+")
                    Text("Remoble Sounds combines a continuous ambient-noise player with a local, remote-compatible Bluetooth smart-card bridge.")
                        .font(.caption).foregroundStyle(.secondary)
                }
            }
            .navigationTitle("Bridge Settings")
            .onAppear { httpPort = String(bridge.httpPort) }
            .onChange(of: bridge.httpPort) { value in httpPort = String(value) }
        }
        .navigationViewStyle(.stack)
    }

    private func valueRow(_ label: String, _ value: String) -> some View {
        HStack(alignment: .firstTextBaseline) {
            Text(label); Spacer()
            Text(value).foregroundStyle(.secondary).multilineTextAlignment(.trailing)
        }
    }

    private var appVersion: String {
        Bundle.main.object(forInfoDictionaryKey: "CFBundleShortVersionString") as? String ?? "—"
    }

    private var appBuild: String {
        Bundle.main.object(forInfoDictionaryKey: "CFBundleVersion") as? String ?? "—"
    }
}

private extension ContentView {
    static func dismissKeyboard() {
        UIApplication.shared.sendAction(
            #selector(UIResponder.resignFirstResponder), to: nil, from: nil, for: nil)
    }
}

/// Installs one non-blocking window tap recognizer. It dismisses the keyboard
/// only when the user taps a noninteractive area, so text fields, buttons and
/// sliders retain their normal behavior on both tabs.
private struct KeyboardDismissTapView: UIViewRepresentable {
    func makeCoordinator() -> Coordinator { Coordinator() }

    func makeUIView(context: Context) -> UIView {
        let view = UIView(frame: .zero)
        view.isUserInteractionEnabled = false
        context.coordinator.install(whenAvailableFrom: view)
        return view
    }

    func updateUIView(_ view: UIView, context: Context) {
        context.coordinator.install(whenAvailableFrom: view)
    }

    static func dismantleUIView(_ uiView: UIView, coordinator: Coordinator) {
        coordinator.remove()
    }

    final class Coordinator: NSObject, UIGestureRecognizerDelegate {
        private weak var installedWindow: UIWindow?
        private weak var recognizer: UITapGestureRecognizer?

        func install(whenAvailableFrom view: UIView) {
            DispatchQueue.main.async { [weak self, weak view] in
                guard let self, let window = view?.window,
                      self.installedWindow !== window else { return }
                self.remove()
                let recognizer = UITapGestureRecognizer(target: self, action: #selector(self.dismiss))
                recognizer.cancelsTouchesInView = false
                recognizer.delegate = self
                window.addGestureRecognizer(recognizer)
                self.installedWindow = window
                self.recognizer = recognizer
            }
        }

        func remove() {
            if let recognizer { installedWindow?.removeGestureRecognizer(recognizer) }
            recognizer = nil
            installedWindow = nil
        }

        @objc private func dismiss() {
            ContentView.dismissKeyboard()
        }

        func gestureRecognizer(_ gestureRecognizer: UIGestureRecognizer, shouldReceive touch: UITouch) -> Bool {
            var view: UIView? = touch.view
            while let current = view {
                if current is UIControl || current is UITextField || current is UITextView || current is UISlider {
                    return false
                }
                view = current.superview
            }
            return true
        }
    }
}

private struct BLEReaderCard: View {
    @EnvironmentObject private var bridge: RemobleBridge
    let reader: BLEReader

    @State private var showingPINEntry = false
    @State private var pinEntry = ""
    @State private var showingCredentialUpdate = false
    @State private var nameSuffix = ""
    @State private var newPIN = ""
    @State private var credentialUpdateBusy = false

    private var isConnected: Bool { bridge.isConnected(reader) }
    private var isConnecting: Bool { bridge.isConnecting(reader) }
    private var isBusy: Bool { isConnecting || bridge.isEnabling(reader) || bridge.isSettling(reader) || credentialUpdateBusy }
    private var isPINValid: Bool {
        pinEntry.range(of: "^[0-9A-F]{6}$", options: .regularExpression) != nil
    }

    var body: some View {
        VStack(alignment: .leading, spacing: 11) {
            Button(action: connectUsingSavedPIN) {
                HStack(spacing: 10) {
                    Image(systemName: reader.kind == .nble ? "simcard.fill" : "dot.radiowaves.left.and.right")
                        .frame(width: 28)
                        .foregroundColor(.purple)
                    VStack(alignment: .leading, spacing: 2) {
                        Text(reader.name)
                            .font(.body.weight(.semibold))
                            .foregroundStyle(.primary)
                        Text(reader.kind.label)
                            .font(.caption)
                            .foregroundStyle(.secondary)
                        if let rssi = bridge.rssi(for: reader) {
                            Label("\(rssi) dBm · \(signalQuality(rssi))", systemImage: signalIcon(rssi))
                                .font(.caption2.monospacedDigit())
                                .foregroundStyle(signalColor(rssi))
                        }
                    }
                    Spacer()
                    if isConnected {
                        Label("Connected", systemImage: "checkmark.circle.fill")
                            .font(.caption.weight(.semibold))
                            .foregroundStyle(.green)
                    } else if isBusy {
                        ProgressView()
                    } else {
                        Image(systemName: "antenna.radiowaves.left.and.right")
                            .foregroundStyle(.purple)
                    }
                }
                .contentShape(Rectangle())
            }
            .buttonStyle(.plain)
            .disabled(isBusy)
            .accessibilityHint("Connects to this BLE card")

            Divider()

            Toggle(isOn: Binding(
                get: { bridge.isReaderEnabled(reader) },
                set: { enabled in
                    if enabled {
                        Task {
                            await bridge.enableReaderAfterConnection(
                                reader, pin: bridge.savedPin(for: reader))
                        }
                    } else {
                        hidePINEntry()
                        hideCredentialUpdate()
                        bridge.setReaderEnabled(reader, enabled: false)
                    }
                }
            )) {
                Label("Visible to remote clients", systemImage: "network")
                    .font(.subheadline)
            }
            .toggleStyle(CheckboxToggleStyle())
            .disabled(isBusy)

            if reader.kind == .nble {
                Divider()

                HStack {
                    VStack(alignment: .leading, spacing: 2) {
                        Label("BLE card authentication", systemImage: "key.fill")
                            .font(.subheadline.weight(.semibold))
                        Text(bridge.savedPin(for: reader).isEmpty ? "Automatic PIN from device name" : "A PIN is saved for this reader")
                            .font(.caption)
                            .foregroundStyle(.secondary)
                    }
                    Spacer()
                    Button(isConnected ? "Reconnect with PIN" : "Connect with PIN") {
                        if showingPINEntry {
                            hidePINEntry()
                        } else {
                            // An explicit PIN connection is for replacing the
                            // credential; never prefill/reuse the cached value.
                            pinEntry = ""
                            showingPINEntry = true
                        }
                    }
                    .font(.subheadline.weight(.semibold))
                    .disabled(isBusy)
                }

                if showingPINEntry {
                    VStack(alignment: .leading, spacing: 8) {
                        SecureField("6 hexadecimal digits", text: $pinEntry)
                            .keyboardType(.asciiCapable)
                            .textInputAutocapitalization(.characters)
                            .autocorrectionDisabled()
                            .textContentType(.password)
                            .font(.system(.body, design: .monospaced))
                            .onChange(of: pinEntry) { newValue in
                                let cleanValue = sanitizedPIN(newValue)
                                if cleanValue != newValue { pinEntry = cleanValue }
                            }

                        Text("Enter the current 6 hexadecimal characters (0–9, A–F). It replaces the saved PIN only after successful authentication.")
                            .font(.caption)
                            .foregroundStyle(isPINValid || pinEntry.isEmpty ? Color.secondary : Color.red)

                        HStack {
                            Button("Cancel", role: .cancel) { hidePINEntry() }
                            Spacer()
                            Button(isConnected ? "Reconnect" : "Connect") {
                                let submittedPIN = pinEntry
                                hidePINEntry()
                                Task {
                                    await bridge.enableReaderAfterConnection(
                                        reader, pin: submittedPIN, forceReconnect: true)
                                }
                            }
                            .buttonStyle(.borderedProminent)
                            .disabled(!isPINValid || isBusy)
                        }
                    }
                    .padding(12)
                    .background(Color(uiColor: .secondarySystemGroupedBackground))
                    .clipShape(RoundedRectangle(cornerRadius: 10, style: .continuous))
                }

                Divider()

                Button {
                    if showingCredentialUpdate {
                        hideCredentialUpdate()
                    } else {
                        hidePINEntry()
                        nameSuffix = String(reader.name.uppercased().suffix(6))
                        newPIN = ""
                        showingCredentialUpdate = true
                    }
                } label: {
                    Label("Change Bluetooth name / PIN", systemImage: "slider.horizontal.3")
                        .font(.subheadline.weight(.semibold))
                }
                .disabled(isBusy)

                if showingCredentialUpdate {
                    VStack(alignment: .leading, spacing: 9) {
                        TextField("Bluetooth name (6 hex characters)", text: $nameSuffix)
                            .keyboardType(.asciiCapable)
                            .textInputAutocapitalization(.characters)
                            .autocorrectionDisabled()
                            .font(.system(.body, design: .monospaced))
                            .onChange(of: nameSuffix) { value in
                                let clean = sanitizedPIN(value)
                                if clean != value { nameSuffix = clean }
                            }

                        SecureField("New PIN (6 hex characters)", text: $newPIN)
                            .keyboardType(.asciiCapable)
                            .textInputAutocapitalization(.characters)
                            .autocorrectionDisabled()
                            .textContentType(.newPassword)
                            .font(.system(.body, design: .monospaced))
                            .onChange(of: newPIN) { value in
                                let clean = sanitizedPIN(value)
                                if clean != value { newPIN = clean }
                            }

                        Text("The fixed name prefix is preserved. The reader may disappear briefly while it restarts; the new PIN is saved before reconnecting.")
                            .font(.caption)
                            .foregroundStyle(.secondary)

                        HStack {
                            Button("Cancel", role: .cancel) { hideCredentialUpdate() }
                            Spacer()
                            Button("Apply") {
                                let submittedName = nameSuffix
                                let submittedPIN = newPIN
                                credentialUpdateBusy = true
                                Task {
                                    let updated = await bridge.updateNbleCredentials(
                                        reader, nameSuffix: submittedName, pin: submittedPIN)
                                    credentialUpdateBusy = false
                                    if updated { hideCredentialUpdate() }
                                }
                            }
                            .buttonStyle(.borderedProminent)
                            .disabled(!isCredentialUpdateValid || credentialUpdateBusy)
                        }
                    }
                    .padding(12)
                    .background(Color(uiColor: .secondarySystemGroupedBackground))
                    .clipShape(RoundedRectangle(cornerRadius: 10, style: .continuous))
                }
            }
        }
        .padding(.vertical, 5)
    }

    private func connectUsingSavedPIN() {
        hidePINEntry()
        bridge.select(reader)
        Task {
            await bridge.enableReaderAfterConnection(
                reader, pin: bridge.savedPin(for: reader))
        }
    }

    private func hidePINEntry() {
        pinEntry = ""
        showingPINEntry = false
    }

    private var isCredentialUpdateValid: Bool {
        nameSuffix.range(of: "^[0-9A-F]{6}$", options: .regularExpression) != nil
            && newPIN.range(of: "^[0-9A-F]{6}$", options: .regularExpression) != nil
    }

    private func hideCredentialUpdate() {
        nameSuffix = ""
        newPIN = ""
        showingCredentialUpdate = false
    }

    private func sanitizedPIN(_ value: String) -> String {
        String(value.uppercased().filter { "0123456789ABCDEF".contains($0) }.prefix(6))
    }

    private func signalQuality(_ rssi: Int) -> String {
        if rssi >= -55 { return "Excellent" }
        if rssi >= -67 { return "Good" }
        if rssi >= -75 { return "Fair" }
        return "Weak"
    }

    private func signalIcon(_ rssi: Int) -> String {
        rssi >= -67 ? "wifi" : (rssi >= -75 ? "wifi.exclamationmark" : "wifi.slash")
    }

    private func signalColor(_ rssi: Int) -> Color {
        if rssi >= -67 { return .green }
        if rssi >= -75 { return .orange }
        return .red
    }
}

private struct CheckboxToggleStyle: ToggleStyle {
    func makeBody(configuration: Configuration) -> some View {
        Button {
            configuration.isOn.toggle()
        } label: {
            HStack {
                configuration.label
                Spacer(minLength: 12)
                Image(systemName: configuration.isOn ? "checkmark.square.fill" : "square")
                    .font(.title3)
                    .foregroundColor(configuration.isOn ? .purple : .secondary)
            }
            .contentShape(Rectangle())
        }
        .buttonStyle(.plain)
    }
}

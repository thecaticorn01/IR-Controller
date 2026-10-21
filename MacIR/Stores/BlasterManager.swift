import Foundation
import Observation
import Synchronization
import IRCore

/// Connection details reported after a successful handshake.
struct BlasterInfo: Sendable, Equatable {
    var registryID: UInt64
    var model: ElkSmartModel
    var productName: String
    var serialNumber: String?
    var encoding: ElkSmartPayloadEncoding
    var identity: [UInt8]
    var capturedFromDriver: Bool
    var endpoints: String
}

/// Owns the USB connection to the ELK Smart dongle. All blocking USB I/O runs
/// on a private serial queue; this object publishes state for the UI.
@MainActor @Observable
final class BlasterManager {
    static let shared = BlasterManager()

    enum State: Equatable {
        case searching
        case connecting(String)
        case ready(BlasterInfo)
        case failed(String)

        var isReady: Bool {
            if case .ready = self { return true }
            return false
        }
    }

    enum Activity: Equatable {
        case idle
        case sending(String)
        case learning
    }

    struct LogEntry: Identifiable, Equatable {
        let id = UUID()
        let date = Date()
        let message: String
        let isError: Bool
    }

    private(set) var state: State = .searching
    private(set) var activity: Activity = .idle
    private(set) var devices: [USBDeviceInfo] = []
    private(set) var log: [LogEntry] = []
    /// Incremented on every successful transmission; views animate on change.
    private(set) var sendCount = 0
    /// Name of the most recently sent button.
    private(set) var lastSentName: String?

    private let worker = BlasterWorker()
    private var learnToken: UUID?
    private var monitor: USBDeviceMonitor?
    private var connectTask: Task<Void, Never>?

    var info: BlasterInfo? {
        if case .ready(let info) = state { return info }
        return nil
    }

    private init() {}

    /// Starts watching for dongles being plugged in or removed.
    func start() {
        guard monitor == nil else { return }
        let monitor = USBDeviceMonitor(
            vendorID: ElkSmartModel.vendorID,
            productIDs: ElkSmartModel.knownProductIDs.map { NSNumber(value: $0) }
        ) { [weak self] devices in
            MainActor.assumeIsolated {
                self?.devicesChanged(devices)
            }
        }
        self.monitor = monitor
        monitor.start()
    }

    private func devicesChanged(_ newDevices: [USBDeviceInfo]) {
        let previousIDs = devices.map(\.registryID)
        devices = newDevices
        guard let first = newDevices.first else {
            if !previousIDs.isEmpty { record("IR blaster disconnected") }
            connectTask?.cancel()
            Task { await worker.disconnect() }
            state = .searching
            return
        }
        // Stay on a working connection if that device is still present.
        if let info, newDevices.contains(where: { $0.registryID == info.registryID }) { return }
        connect(to: first)
    }

    /// (Re)connects to the first detected dongle.
    func reconnect() {
        if let first = devices.first {
            connect(to: first)
        } else {
            devices = monitor?.currentDevices() ?? []
            if let first = devices.first { connect(to: first) } else { state = .searching }
        }
    }

    private func connect(to device: USBDeviceInfo) {
        connectTask?.cancel()
        let registryID = device.registryID
        let productID = device.productID
        let productName = device.productName
        let serial = device.serialNumber
        state = .connecting(productName)
        connectTask = Task {
            // Give the system a moment to finish enumerating a freshly plugged device.
            try? await Task.sleep(for: .milliseconds(300))
            guard !Task.isCancelled else { return }
            do {
                let result = try await worker.connect(registryID: registryID)
                guard !Task.isCancelled else { return }
                let info = BlasterInfo(
                    registryID: registryID,
                    model: ElkSmartModel.model(forProductID: productID),
                    productName: productName,
                    serialNumber: serial,
                    encoding: result.encoding,
                    identity: result.identity,
                    capturedFromDriver: result.captured,
                    endpoints: result.endpoints
                )
                state = .ready(info)
                record("Connected to \(info.model.name)")
            } catch {
                guard !Task.isCancelled else { return }
                state = .failed(error.localizedDescription)
                record("Connection failed: \(error.localizedDescription)", isError: true)
            }
        }
    }

    // MARK: Sending

    /// Sends a button's signal. Errors are logged and rethrown.
    func send(_ signal: IRSignal, name: String, extraRepeats: Int = 0) async throws {
        guard state.isReady else { throw BlasterError.notConnected }
        // Render on the main actor so RC5/RC6 toggle bits alternate in press order.
        let raw = try signal.rendered(repeats: extraRepeats)
        activity = .sending(name)
        defer { if case .sending = activity { activity = .idle } }
        do {
            try await worker.transmit(raw)
            sendCount += 1
            lastSentName = name
        } catch {
            record("Sending “\(name)” failed: \(error.localizedDescription)", isError: true)
            handleIOError(error)
            throw error
        }
    }

    /// Sends a signal and swallows errors into the log; for fire-and-forget UI buttons.
    func sendLogged(_ button: IRButton, extraRepeats: Int = 0) {
        Task {
            try? await send(button.signal, name: button.name, extraRepeats: extraRepeats)
        }
    }

    // MARK: Learning

    /// Waits for the user to press a button on a physical remote.
    func learn(timeout: TimeInterval, frequency: Int) async throws -> RawSignal {
        guard state.isReady else { throw BlasterError.notConnected }
        let token = UUID()
        learnToken = token
        activity = .learning
        // A newer learn may have started meanwhile; only the latest one resets the state.
        defer { if learnToken == token { activity = .idle } }
        do {
            let signal = try await withTaskCancellationHandler {
                try await worker.learn(timeout: timeout, frequency: frequency)
            } onCancel: { [worker] in
                worker.cancelLearning()
            }
            record("Learned a code with \(signal.timings.count) timings")
            return signal
        } catch let error as ElkSmartError where error == .learnCancelled || error == .learnTimedOut {
            throw error
        } catch {
            record("Learning failed: \(error.localizedDescription)", isError: true)
            handleIOError(error)
            throw error
        }
    }

    func cancelLearning() {
        worker.cancelLearning()
    }

    // MARK: Helpers

    private func handleIOError(_ error: Error) {
        // USB-level failures usually mean the device went away or got wedged; reconnect.
        if !(error is ElkSmartError || error is IRProtocolError || error is BlasterError) {
            reconnect()
        }
    }

    func record(_ message: String, isError: Bool = false) {
        log.insert(LogEntry(message: message, isError: isError), at: 0)
        if log.count > 100 { log.removeLast(log.count - 100) }
    }
}

enum BlasterError: LocalizedError {
    case notConnected

    var errorDescription: String? {
        switch self {
        case .notConnected: "No IR blaster is connected. Plug in your ELK Smart USB-C IR blaster."
        }
    }
}

/// Result of opening the dongle.
struct BlasterConnectResult: Sendable {
    var encoding: ElkSmartPayloadEncoding
    var identity: [UInt8]
    var captured: Bool
    var endpoints: String
}

/// Performs blocking USB work on a dedicated serial queue.
final class BlasterWorker: @unchecked Sendable {
    private let queue = DispatchQueue(label: "com.thecaticorn01.MacIR.blaster", qos: .userInitiated)
    /// Accessed only on `queue`.
    private var driver: ElkSmartDriver?
    private var connection: USBBulkConnection?
    /// Bumped to cancel an in-flight learn; a newer learn also supersedes older ones.
    private let learnGeneration = Mutex(0)

    private func run<T: Sendable>(_ body: @escaping @Sendable (BlasterWorker) throws -> T) async throws -> T {
        try await withCheckedThrowingContinuation { continuation in
            queue.async {
                do {
                    continuation.resume(returning: try body(self))
                } catch {
                    continuation.resume(throwing: error)
                }
            }
        }
    }

    func connect(registryID: UInt64) async throws -> BlasterConnectResult {
        try await run { worker in
            worker.closeOnQueue()
            let connection = try USBBulkConnection.open(registryID: registryID)
            let driver = ElkSmartDriver(transport: USBBulkTransport(connection: connection))
            do {
                let encoding = try driver.identify()
                worker.connection = connection
                worker.driver = driver
                let endpoints = String(format: "OUT 0x%02X · IN 0x%02X", connection.outEndpointAddress, connection.inEndpointAddress)
                return BlasterConnectResult(encoding: encoding, identity: driver.identity,
                                            captured: connection.capturedDevice, endpoints: endpoints)
            } catch {
                connection.close()
                throw error
            }
        }
    }

    func disconnect() async {
        _ = try? await run { worker in worker.closeOnQueue() }
    }

    private func closeOnQueue() {
        driver = nil
        connection?.close()
        connection = nil
    }

    func transmit(_ signal: RawSignal) async throws {
        try await run { worker in
            guard let driver = worker.driver else { throw BlasterError.notConnected }
            try driver.transmit(signal)
        }
    }

    func learn(timeout: TimeInterval, frequency: Int) async throws -> RawSignal {
        let generation = learnGeneration.withLock { value in
            value += 1
            return value
        }
        return try await run { worker in
            guard let driver = worker.driver else { throw BlasterError.notConnected }
            return try driver.learn(timeout: timeout, frequency: frequency) {
                worker.learnGeneration.withLock { $0 } != generation
            }
        }
    }

    func cancelLearning() {
        learnGeneration.withLock { $0 += 1 }
    }
}

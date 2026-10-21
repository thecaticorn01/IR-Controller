import SwiftUI
import IRCore

@main
struct MacIRApp: App {
    @State private var monitorHolder = MonitorHolder()

    var body: some Scene {
        WindowGroup {
            Text("Devices: \(monitorHolder.devices.count)")
                .frame(minWidth: 400, minHeight: 300)
                .task { monitorHolder.start() }
        }
    }
}

@MainActor @Observable
final class MonitorHolder {
    var devices: [USBDeviceInfo] = []
    private var monitor: USBDeviceMonitor?

    func start() {
        let monitor = USBDeviceMonitor(
            vendorID: ElkSmartModel.vendorID,
            productIDs: ElkSmartModel.knownProductIDs.map { NSNumber(value: $0) }
        ) { [weak self] devices in
            MainActor.assumeIsolated { self?.devices = devices }
        }
        monitor.start()
        self.monitor = monitor
        if let first = devices.first, let connection = try? USBBulkConnection.open(registryID: first.registryID) {
            let driver = ElkSmartDriver(transport: USBBulkTransport(connection: connection))
            _ = try? driver.identify()
        }
    }
}

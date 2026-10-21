import Foundation
import IRCore

/// Adapts the Objective-C IOUSBHost connection to IRCore's `BulkTransport`.
final class USBBulkTransport: BulkTransport {
    let connection: USBBulkConnection

    init(connection: USBBulkConnection) {
        self.connection = connection
    }

    func write(_ bytes: [UInt8], timeout: TimeInterval) throws {
        try connection.write(Data(bytes), timeout: timeout)
    }

    func read(maxLength: Int, timeout: TimeInterval) throws -> [UInt8]? {
        let data = try connection.read(maxLength: UInt(maxLength), timeout: timeout)
        return data.isEmpty ? nil : [UInt8](data)
    }

    deinit {
        connection.close()
    }
}

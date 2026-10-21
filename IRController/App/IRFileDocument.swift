import SwiftUI
import UniformTypeIdentifiers
import IRCore

/// A Flipper `.ir` file for `fileExporter`.
struct IRFileDocument: FileDocument {
    static var readableContentTypes: [UTType] { [.flipperIR, .plainText] }

    var text: String

    init(remote: IRRemote) {
        var text = FlipperIRFile.serialize(remote.buttons)
        // Keep provenance as a comment; Flipper ignores lines starting with '#'.
        let header = "# \(remote.name) — exported from IR Controller\n"
        if let range = text.range(of: "Version: 1\n") {
            text.insert(contentsOf: header, at: range.upperBound)
        }
        self.text = text
    }

    init(configuration: ReadConfiguration) throws {
        guard let data = configuration.file.regularFileContents else { throw CocoaError(.fileReadCorruptFile) }
        text = String(decoding: data, as: UTF8.self)
    }

    func fileWrapper(configuration: WriteConfiguration) throws -> FileWrapper {
        FileWrapper(regularFileWithContents: Data(text.utf8))
    }
}

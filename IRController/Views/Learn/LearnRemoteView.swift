import SwiftUI
import IRCore

/// Guided flow for teaching IR Controller a remote that isn't in any database:
/// describe the device, then capture each button from the physical remote.
struct LearnRemoteView: View {
    @Environment(LibraryStore.self) private var library
    @Environment(AppNavigation.self) private var navigation
    @Environment(BlasterManager.self) private var blaster

    struct Slot: Identifiable, Equatable {
        let id = UUID()
        var name: String
        var signal: IRSignal?
        var skipped = false
    }

    private enum Step { case setup, learning }

    @State private var step: Step = .setup
    @State private var name = ""
    @State private var brand = ""
    @State private var model = ""
    @State private var category: DeviceCategory = .tv
    @State private var slots: [Slot] = []
    @State private var currentSlotID: Slot.ID?
    @State private var captured: IRSignal?
    @State private var entering = false
    @State private var entered: IRSignal?
    @State private var newSlotName = ""
    @State private var confirmDiscard = false
    /// Suggested buttons the user has left out of the learning flow.
    @State private var omittedSuggestions: Set<String> = []

    var body: some View {
        Group {
            switch step {
            case .setup: setup
            case .learning: learning
            }
        }
        .navigationTitle("Learn New Remote")
        .confirmationDialog("Discard the buttons you’ve learned?", isPresented: $confirmDiscard) {
            Button("Discard", role: .destructive) { reset() }
        }
    }

    // MARK: Setup

    private var setup: some View {
        ScrollView {
            VStack(spacing: 24) {
                VStack(spacing: 8) {
                    Image(systemName: "dot.radiowaves.left.and.right")
                        .font(.system(size: 48))
                        .foregroundStyle(.tint)
                    Text("Learn a New Remote")
                        .font(.largeTitle.weight(.bold))
                    Text("Can’t find your remote in the databases? Teach IR Controller its buttons one by one using your ELK Smart blaster’s receiver.")
                        .font(.title3)
                        .foregroundStyle(.secondary)
                        .multilineTextAlignment(.center)
                        .frame(maxWidth: 560)
                }

                Form {
                    TextField("Name", text: $name, prompt: Text("e.g. Bedroom Fan"))
                    Picker("Device Type", selection: $category) {
                        ForEach(DeviceCategory.allCases) { category in
                            Label(category.displayName, systemImage: category.symbolName).tag(category)
                        }
                    }
                    TextField("Brand", text: $brand, prompt: Text("Optional"))
                    TextField("Model", text: $model, prompt: Text("Optional"))
                }
                .formStyle(.grouped)
                .scrollDisabled(true)
                .frame(maxWidth: 520)

                VStack(alignment: .leading, spacing: 8) {
                    Text("Suggested buttons")
                        .font(.headline)
                    Text("Click a button to leave it out if your remote doesn’t have it. You can add your own as you go.")
                        .font(.caption)
                        .foregroundStyle(.secondary)
                    FlowLayout(spacing: 6) {
                        ForEach(LearningTemplate.buttonNames(for: category), id: \.self) { name in
                            let included = !omittedSuggestions.contains(name)
                            Toggle(isOn: suggestionBinding(name)) {
                                Label(name, systemImage: included ? "checkmark" : "plus")
                            }
                            .toggleStyle(.button)
                            .buttonStyle(.glass)
                            .help(included ? "Leave out “\(name)”" : "Learn “\(name)”")
                        }
                    }
                }
                .frame(maxWidth: 520, alignment: .leading)
                .onChange(of: category) { omittedSuggestions = [] }

                if !blaster.state.isReady {
                    Label("Connect your IR blaster to capture codes. You can still enter codes manually.", systemImage: "cable.connector.slash")
                        .foregroundStyle(.orange)
                }

                Button("Start Learning", systemImage: "arrow.right.circle.fill") { begin() }
                    .buttonStyle(.glassProminent)
                    .controlSize(.extraLarge)
                    .disabled(name.trimmingCharacters(in: .whitespaces).isEmpty)
                    .keyboardShortcut(.defaultAction)
            }
            .padding(40)
            .frame(maxWidth: .infinity)
        }
    }

    // MARK: Learning

    private var learning: some View {
        HSplitView {
            slotList
                .frame(minWidth: 220, idealWidth: 250, maxWidth: 320)
            captureArea
                .frame(minWidth: 420, maxWidth: .infinity, maxHeight: .infinity)
        }
        .toolbar {
            ToolbarItemGroup(placement: .primaryAction) {
                Button("Start Over", systemImage: "arrow.uturn.backward") {
                    if learnedCount > 0 { confirmDiscard = true } else { reset() }
                }
                Button("Save Remote", systemImage: "checkmark.circle.fill") { finish() }
                    .buttonStyle(.glassProminent)
                    .disabled(learnedCount == 0)
                    .help("Save \(learnedCount) learned buttons as “\(name)”")
            }
        }
    }

    private var learnedCount: Int { slots.filter { $0.signal != nil }.count }

    private var slotList: some View {
        VStack(spacing: 0) {
            List(selection: $currentSlotID) {
                Section("\(name) — \(learnedCount) of \(slots.count) learned") {
                    ForEach(slots) { slot in
                        HStack {
                            Image(systemName: icon(for: slot))
                                .foregroundStyle(slot.signal != nil ? .green : slot.skipped ? .secondary : .accentColor)
                            Text(slot.name)
                            Spacer()
                        }
                        .tag(slot.id)
                        .contextMenu {
                            Button("Remove", systemImage: "trash", role: .destructive) {
                                slots.removeAll { $0.id == slot.id }
                            }
                        }
                    }
                    .onMove { slots.move(fromOffsets: $0, toOffset: $1) }
                }
            }
            .listStyle(.sidebar)
            HStack {
                TextField("Add a button…", text: $newSlotName)
                    .textFieldStyle(.roundedBorder)
                    .onSubmit(addSlot)
                Button("Add", systemImage: "plus") { addSlot() }
                    .labelStyle(.iconOnly)
                    .disabled(newSlotName.trimmingCharacters(in: .whitespaces).isEmpty)
            }
            .padding(10)
        }
        .onChange(of: currentSlotID) {
            captured = nil
            entered = nil
            entering = false
        }
    }

    private func icon(for slot: Slot) -> String {
        if slot.signal != nil { return "checkmark.circle.fill" }
        if slot.skipped { return "forward.circle" }
        if slot.id == currentSlotID { return "record.circle" }
        return "circle"
    }

    @ViewBuilder
    private var captureArea: some View {
        if let index = slots.firstIndex(where: { $0.id == currentSlotID }) {
            let slot = slots[index]
            ScrollView {
                VStack(spacing: 20) {
                    VStack(spacing: 4) {
                        Text("Button \(index + 1) of \(slots.count)")
                            .font(.callout)
                            .foregroundStyle(.secondary)
                        Text(slot.name)
                            .font(.largeTitle.weight(.semibold))
                    }

                    if let existing = slot.signal, captured == nil, !entering {
                        VStack(alignment: .leading, spacing: 8) {
                            Label("Already learned", systemImage: "checkmark.circle.fill")
                                .foregroundStyle(.green)
                            SignalSummaryView(signal: existing)
                        }
                        .padding(16)
                        .glassEffect(.regular, in: .rect(cornerRadius: 20))
                    }

                    if entering {
                        ManualCodeView(signal: $entered)
                            .padding(16)
                            .glassEffect(.regular, in: .rect(cornerRadius: 20))
                    } else {
                        SignalCaptureView(signal: $captured, buttonName: slot.name,
                                          autoStart: slot.signal == nil && blaster.state.isReady,
                                          sessionID: slot.id)
                    }

                    HStack(spacing: 12) {
                        Button(entering ? "Learn from Remote Instead" : "Enter Code Instead",
                               systemImage: entering ? "dot.radiowaves.left.and.right" : "number") {
                            entering.toggle()
                        }
                        .buttonStyle(.glass)
                        Spacer()
                        Button("Skip", systemImage: "forward") {
                            slots[index].skipped = true
                            advance(from: index)
                        }
                        .buttonStyle(.glass)
                        Button("Save & Next", systemImage: "arrow.right") {
                            slots[index].signal = entering ? entered : captured
                            slots[index].skipped = false
                            advance(from: index)
                        }
                        .buttonStyle(.glassProminent)
                        .keyboardShortcut(.defaultAction)
                        .disabled((entering ? entered : captured) == nil)
                    }
                }
                .padding(28)
                .frame(maxWidth: 640)
                .frame(maxWidth: .infinity)
            }
        } else {
            ContentUnavailableView {
                Label(learnedCount > 0 ? "All Done" : "Pick a Button", systemImage: learnedCount > 0 ? "checkmark.seal" : "hand.point.left")
            } description: {
                Text(learnedCount > 0 ? "You learned \(learnedCount) buttons. Save the remote, or pick a button on the left to redo it." : "Choose a button on the left to start learning.")
            } actions: {
                if learnedCount > 0 {
                    Button("Save Remote", systemImage: "checkmark.circle.fill") { finish() }
                        .buttonStyle(.glassProminent)
                }
            }
        }
    }

    // MARK: Actions

    private func suggestionBinding(_ name: String) -> Binding<Bool> {
        Binding {
            !omittedSuggestions.contains(name)
        } set: { included in
            if included { omittedSuggestions.remove(name) } else { omittedSuggestions.insert(name) }
        }
    }

    private func begin() {
        slots = LearningTemplate.buttonNames(for: category)
            .filter { !omittedSuggestions.contains($0) }
            .map { Slot(name: $0) }
        currentSlotID = slots.first?.id
        step = .learning
    }

    private func addSlot() {
        let trimmed = newSlotName.trimmingCharacters(in: .whitespaces)
        guard !trimmed.isEmpty else { return }
        let slot = Slot(name: trimmed)
        slots.append(slot)
        newSlotName = ""
        currentSlotID = slot.id
    }

    private func advance(from index: Int) {
        captured = nil
        entered = nil
        let next = slots[(index + 1)...].first { $0.signal == nil && !$0.skipped }
            ?? slots.first { $0.signal == nil && !$0.skipped }
        currentSlotID = next?.id
    }

    private func finish() {
        let buttons = slots.compactMap { slot in slot.signal.map { IRButton(name: slot.name, signal: $0) } }
        guard !buttons.isEmpty else { return }
        let remote = library.add(IRRemote(
            name: name.trimmingCharacters(in: .whitespaces),
            brand: brand, model: model, category: category, buttons: buttons
        ))
        reset()
        navigation.open(remote)
    }

    private func reset() {
        step = .setup
        slots = []
        omittedSuggestions = []
        currentSlotID = nil
        captured = nil
        entered = nil
        name = ""
        brand = ""
        model = ""
    }
}

/// Simple wrapping layout for chips.
struct FlowLayout: Layout {
    var spacing: CGFloat = 8

    func sizeThatFits(proposal: ProposedViewSize, subviews: Subviews, cache: inout ()) -> CGSize {
        let rows = arrange(width: proposal.width ?? .infinity, subviews: subviews)
        let height = rows.last.map { $0.y + $0.height } ?? 0
        let width = rows.map(\.width).max() ?? 0
        return CGSize(width: proposal.width ?? width, height: height)
    }

    func placeSubviews(in bounds: CGRect, proposal: ProposedViewSize, subviews: Subviews, cache: inout ()) {
        let rows = arrange(width: bounds.width, subviews: subviews)
        for row in rows {
            var x = bounds.minX
            for index in row.indices {
                let size = subviews[index].sizeThatFits(.unspecified)
                subviews[index].place(at: CGPoint(x: x, y: bounds.minY + row.y), proposal: ProposedViewSize(size))
                x += size.width + spacing
            }
        }
    }

    private struct Row {
        var indices: [Int] = []
        var y: CGFloat = 0
        var width: CGFloat = 0
        var height: CGFloat = 0
    }

    private func arrange(width: CGFloat, subviews: Subviews) -> [Row] {
        var rows: [Row] = []
        var current = Row()
        for index in subviews.indices {
            let size = subviews[index].sizeThatFits(.unspecified)
            if !current.indices.isEmpty && current.width + spacing + size.width > width {
                rows.append(current)
                current = Row(y: current.y + current.height + spacing)
            }
            current.width += (current.indices.isEmpty ? 0 : spacing) + size.width
            current.height = max(current.height, size.height)
            current.indices.append(index)
        }
        if !current.indices.isEmpty { rows.append(current) }
        return rows
    }
}

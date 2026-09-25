import SwiftUI

struct RulesView: View {
    @State private var config: PagerConfig = Store.shared.config
    @State private var customInput = ""

    private var defined: [Int] {
        Array(Set(config.statusRules.keys).union(Set(0...5))).sorted()
    }

    var body: some View {
        NavigationStack {
            List {
                Section("Per status: what it does, and when it may notify") {
                    ForEach(defined, id: \.self) { status in
                        RuleRow(
                            status: status,
                            rule: config.statusRules[status] ?? defaultRuleFor(status),
                            onChange: { rule in
                                config.statusRules[status] = rule
                                Store.shared.config = config
                            }
                        )
                    }
                }

                Section("Add a custom status (6–99)") {
                    HStack {
                        TextField("code", text: $customInput)
                            .keyboardType(.numberPad)
                        Button("Add") {
                            guard let code = Int(customInput), (6...99).contains(code) else { return }
                            config.statusRules[code] = defaultRuleFor(code)
                            Store.shared.config = config
                            customInput = ""
                        }
                        .disabled(Int(customInput).map { (6...99).contains($0) } != true)
                    }
                }
            }
            .navigationTitle("Rules")
        }
    }
}

private struct RuleRow: View {
    let status: Int
    @State var rule: StatusRule
    let onChange: (StatusRule) -> Void

    var body: some View {
        VStack(alignment: .leading, spacing: 8) {
            Text("status \(status)").bold()
            Picker("action", selection: Binding(
                get: { rule.action },
                set: { rule.action = $0; onChange(rule) }
            )) {
                Text("silent").tag(NotifyAction.silent)
                Text("sound+vib").tag(NotifyAction.soundVibrate)
                Text("urgent").tag(NotifyAction.urgent)
            }
            Picker("time", selection: Binding(
                get: { rule.timeBound },
                set: { rule.timeBound = $0; onChange(rule) }
            )) {
                Text("always").tag(TimeBound.always)
                Text("active hours").tag(TimeBound.activeHours)
                Text("never").tag(TimeBound.never)
            }
        }
    }
}

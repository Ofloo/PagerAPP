import SwiftUI

func pageStatusName(_ status: Int) -> String {
    switch status {
    case 0: return "Success"
    case 1: return "Warning"
    case 2: return "Failed"
    case 3: return "Critical"
    case 4: return "In Progress"
    case 5: return "Queued"
    case 6: return "Acknowledged"
    case 7: return "Debug"
    default: return "Status \(status)"
    }
}

func pageStatusLabel(_ status: Int, _ prio: String?) -> String {
    if let level = prio?.trimmingCharacters(in: .whitespaces), !level.isEmpty {
        return "\(status) \(level.capitalized)"
    }
    return "\(status) \(pageStatusName(status))"
}

func pageStatusColor(_ status: Int) -> Color {
    switch status {
    case 2, 3: return .red
    case 1: return .orange
    case 6: return .blue
    case 7: return .secondary
    default: return .secondary
    }
}

func pageDate(_ epochSeconds: Double) -> String {
    guard epochSeconds > 0 else { return "—" }
    return Date(timeIntervalSince1970: epochSeconds)
        .formatted(date: .abbreviated, time: .shortened)
}

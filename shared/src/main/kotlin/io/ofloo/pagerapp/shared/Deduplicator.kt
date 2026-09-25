package io.ofloo.pagerapp.shared

/**
 * Tracks which message `id`s have already been delivered. PagerMCP delivery is at-most-once
 * per `id`, so a repeated `id` is a protocol violation and must NOT fire a second notification.
 */
class Deduplicator(seen: Collection<String> = emptyList()) {
    private val seen = LinkedHashSet(seen)

    /** Returns true the first time [id] is seen, false for every repeat. */
    fun isNew(id: String): Boolean = seen.add(id)

    fun snapshot(): Set<String> = seen.toSet()
}

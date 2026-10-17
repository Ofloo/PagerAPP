package io.ofloo.pagerapp.shared

/**
 * Tracks which message `id`s have already been delivered.
 *
 * PagerMCP delivery is **at-least-once**: a page is redelivered until it is acked, so the same
 * `id` may arrive several times (a retry) and must NOT fire a second notification.
 */
class Deduplicator(seen: Collection<String> = emptyList()) {
    private val seen = LinkedHashSet(seen)

    /** Returns true the first time [id] is seen, false for every repeat. */
    fun isNew(id: String): Boolean = seen.add(id)

    fun snapshot(): Set<String> = seen.toSet()
}

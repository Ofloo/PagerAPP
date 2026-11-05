package io.ofloo.pagerapp.shared

/**
 * Signatures were introduced during the night of 1 November 2026. Older queued pages are legacy
 * and remain readable without a signature; newer chat pages must carry one.
 */
object SignaturePolicy {
    /** 2026-11-01T00:00:00Z; server timestamps are Unix seconds in UTC. */
    const val REQUIRED_FROM_EPOCH_SECONDS: Long = 1_793_491_200L

    fun isRequired(createdAt: Double): Boolean =
        createdAt >= REQUIRED_FROM_EPOCH_SECONDS
}

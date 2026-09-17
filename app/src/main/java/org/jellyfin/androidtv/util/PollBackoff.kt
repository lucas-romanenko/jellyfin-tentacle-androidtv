package org.jellyfin.androidtv.util

/**
 * Delay for the next poll, backing off while the server is unreachable.
 *
 * A fixed-interval poll keeps hammering a server that is down: a 3-second loop against a host
 * that takes 6 seconds to time out produced 127 identical failures in an hour of one outage, which
 * is both wasted work and the reason the logs were unreadable afterwards. Doubling per consecutive
 * failure settles to an occasional retry, and the first success returns to the normal interval.
 *
 * @param baseMs the healthy polling interval
 * @param consecutiveFailures failures since the last success (0 while healthy)
 * @param maxMs ceiling for the backed-off interval
 */
fun pollDelay(baseMs: Long, consecutiveFailures: Int, maxMs: Long): Long {
	if (consecutiveFailures <= 0) return baseMs
	// Cap the shift before it can overflow the multiplier.
	val factor = 1L shl consecutiveFailures.coerceAtMost(MAX_BACKOFF_SHIFT)
	return (baseMs * factor).coerceAtMost(maxMs)
}

private const val MAX_BACKOFF_SHIFT = 16

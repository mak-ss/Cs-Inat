// ! Bu araç @Blockades tarafından | @Cs-Inat için yazılmıştır.

package com.Blockades

import android.util.Log
import com.lagradost.cloudstream3.utils.ExtractorLink
import kotlinx.coroutines.*
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger

/**
 * BoundedParallelResolver coordinates multi-source and multi-extractor resolution:
 * - Bounded concurrency via Semaphore to protect low-power Android TV devices (RD-004)
 * - Progressive callback invocation as soon as streams are found
 * - Optional early-exit flag when a high-priority direct CDN stream is acquired
 */
object BoundedParallelResolver {
    const val DEFAULT_MAX_CONCURRENCY = 4
    private const val TAG = "BoundedParallelResolver"

    /**
     * Executes resolution tasks concurrently with bounded concurrency.
     * Each task is given a permit and can invoke [onLinkFound] as soon as an ExtractorLink is resolved.
     * If [earlyExitOnFirstSuccess] is true, cancels remaining tasks once at least one link is found.
     */
    suspend fun <T> resolveProgressive(
        candidates: List<T>,
        maxConcurrency: Int = DEFAULT_MAX_CONCURRENCY,
        earlyExitOnFirstSuccess: Boolean = false,
        provider: String = "Generic",
        resolver: suspend (candidate: T, emitLink: (ExtractorLink) -> Unit) -> Unit,
        onLinkFound: (ExtractorLink) -> Unit
    ): Int = coroutineScope {
        if (candidates.isEmpty()) return@coroutineScope 0

        val semaphore = Semaphore(maxConcurrency.coerceAtLeast(1))
        val linksEmitted = AtomicInteger(0)
        val hasFoundDirectStream = AtomicBoolean(false)
        val seenUrls = java.util.Collections.newSetFromMap(
            java.util.concurrent.ConcurrentHashMap<String, Boolean>()
        )

        val jobs = candidates.map { candidate ->
            launch {
                if (earlyExitOnFirstSuccess && hasFoundDirectStream.get()) {
                    return@launch
                }

                semaphore.withPermit {
                    if (earlyExitOnFirstSuccess && hasFoundDirectStream.get()) {
                        return@withPermit
                    }

                    try {
                        resolver(candidate) { link ->
                            val normUrl = link.url.trim()
                            if (normUrl.isNotBlank() && seenUrls.add(normUrl)) {
                                linksEmitted.incrementAndGet()
                                onLinkFound(link)
                                if (earlyExitOnFirstSuccess) {
                                    hasFoundDirectStream.set(true)
                                }
                            }
                        }
                    } catch (_: CancellationException) {
                        // Coroutine cancelled cleanly
                    } catch (e: Exception) {
                        Log.e(TAG, "[$provider] Extractor resolution failed: ${e.message}", e)
                    }
                }
            }
        }

        jobs.joinAll()
        linksEmitted.get()
    }
}

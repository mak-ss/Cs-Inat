package com.horis.net77

/**
 * Thread-safe in-memory session cookie cache for the t_hash_t token.
 * Cookies are valid for 15 hours.
 */
object Net77Storage {
    @Volatile private var cachedCookie: String? = null
    @Volatile private var cachedTimestamp: Long = 0L

    fun getCookie(): Pair<String?, Long?> {
        return Pair(cachedCookie, cachedTimestamp)
    }

    fun saveCookie(cookie: String) {
        cachedCookie = cookie
        cachedTimestamp = System.currentTimeMillis()
    }

    fun clearCookie() {
        cachedCookie = null
        cachedTimestamp = 0L
    }
}

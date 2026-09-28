package com.Blockades

/**
 * Peacemakerst.com domaini için HdPlayer varyantı.
 * Aynı POST mekanizmasını kullanır, sadece farklı host.
 */
open class PeacemakerExtractor : HdPlayerExtractor() {
    override val name    = "Peacemaker"
    override val mainUrl = "https://peacemakerst.com"
}

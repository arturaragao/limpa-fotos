package io.github.arturaragao.limpafotos

/** Only this app's release series is accepted; Actions run_number is the versionCode. */
object UpdateVersion {
    fun codeFromTag(tag: String): Int? = Regex("v0\\.1\\.([0-9]+)")
        .matchEntire(tag.trim())?.groupValues?.get(1)?.toIntOrNull()?.takeIf { it > 0 }
    fun isNewer(tag: String, current: Int): Boolean = (codeFromTag(tag) ?: 0) > current
}

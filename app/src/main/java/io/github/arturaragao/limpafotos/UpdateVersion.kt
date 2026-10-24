package io.github.arturaragao.limpafotos

/** Tags v0.X.N; N = versionCode (run_number do Actions). Aceita qualquer X para não travar quando o nome muda. */
object UpdateVersion {
    fun codeFromTag(tag: String): Int? = Regex("v0\\.[0-9]{1,3}\\.([0-9]+)")
        .matchEntire(tag.trim())?.groupValues?.get(1)?.toIntOrNull()?.takeIf { it > 0 }
    fun isNewer(tag: String, current: Int): Boolean = (codeFromTag(tag) ?: 0) > current
}

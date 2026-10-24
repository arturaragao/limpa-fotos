package io.github.arturaragao.limpafotos

import java.util.Locale

enum class Category(val title: String) {
    PRINTS("Prints"), STICKERS("GIFs e figurinhas"), BLURRY("Borradas"),
    DUPLICATES("Repetidas"), FOOD("Comida")
}

data class PhotoMeta(
    val id: Long,
    val modified: Long,
    val name: String,
    val path: String,
    val mime: String,
    val width: Int,
    val height: Int,
    val bytes: Long,
    val takenAt: Long,
    val favorite: Boolean,
    val addedAt: Long = 0
)

data class Analysis(val hash: Long, val sharpness: Double, val food: Boolean)

data class Photo(
    val meta: PhotoMeta,
    val analysis: Analysis?,
    val categories: Set<Category>,
    val keeper: Boolean = false
)

object PhotoRules {
    const val BLUR_THRESHOLD = 100.0
    const val DUPLICATE_DISTANCE = 6
    const val HOUR_MS = 3_600_000L

    fun analysisKey(meta: PhotoMeta): String = "${meta.id}:${meta.modified}"

    /** Cache hits never consume the batch. A changed file must be analyzed again. */
    fun nextUnanalyzed(all: List<PhotoMeta>, analyzedKeys: Set<String>, limit: Int): List<PhotoMeta> {
        require(limit >= 0)
        return all.asSequence().filter { analysisKey(it) !in analyzedKeys }
            .sortedWith(compareByDescending<PhotoMeta> { if (it.takenAt > 0) it.takenAt else it.addedAt }
                .thenByDescending { it.id })
            .take(limit).toList()
    }

    fun metadataCategories(meta: PhotoMeta): Set<Category> = buildSet {
        val path = meta.path.lowercase(Locale.ROOT)
        if (path.contains("screenshots") || path.contains("screen recordings") ||
            meta.name.startsWith("Screenshot", ignoreCase = true)) add(Category.PRINTS)
        if (meta.mime.equals("image/gif", true) || meta.mime.equals("image/webp", true) ||
            path.contains("whatsapp stickers") || path.contains("animated gifs") ||
            (meta.width in 1..511 || meta.height in 1..511)) add(Category.STICKERS)
    }

    fun isBlurry(variance: Double): Boolean = variance.isFinite() && variance >= 0 && variance < BLUR_THRESHOLD
    fun hamming(a: Long, b: Long): Int = java.lang.Long.bitCount(a xor b)

    /** Complete-link groups: no transitive chains beyond six bits or one hour.
     * Seven disjoint hash bands find all candidates (pigeonhole principle).
     * Unknown capture times never cause a destructive suggestion.
     */
    fun duplicateGroups(photos: List<Photo>): List<List<Photo>> {
        val groups = mutableListOf<MutableList<Photo>>()
        val bands = HashMap<Pair<Int, Long>, MutableSet<Int>>()
        val groupKeys = mutableListOf<MutableSet<Pair<Int, Long>>>()
        val active = java.util.ArrayDeque<Int>()
        fun keys(hash: Long) = (0..6).map { band ->
            val bits = if (band == 6) 10 else 9
            band to ((hash ushr (band * 9)) and ((1L shl bits) - 1))
        }
        for (photo in photos.filter { it.analysis != null && it.meta.takenAt > 0 }
            .sortedWith(compareBy<Photo> { it.meta.takenAt }.thenBy { it.meta.id })) {
            while (active.isNotEmpty() && photo.meta.takenAt - groups[active.first].first().meta.takenAt > HOUR_MS) {
                val expired = active.removeFirst()
                groupKeys[expired].forEach { key ->
                    bands[key]?.let { indices ->
                        indices.remove(expired)
                        if (indices.isEmpty()) bands.remove(key)
                    }
                }
            }
            val hash = photo.analysis!!.hash
            val candidates = keys(hash).flatMap { bands[it].orEmpty() }.toSortedSet()
            val match = candidates.firstOrNull { index ->
                val group = groups[index]
                photo.meta.takenAt - group.first().meta.takenAt <= HOUR_MS &&
                    group.all { hamming(hash, it.analysis!!.hash) <= DUPLICATE_DISTANCE }
            }
            val index = match ?: groups.size.also {
                groups.add(mutableListOf())
                groupKeys.add(linkedSetOf())
                active.addLast(it)
            }
            groups[index].add(photo)
            // Every group member is indexed; the complete-link check remains authoritative.
            keys(hash).forEach { bands.getOrPut(it) { linkedSetOf() }.add(index); groupKeys[index].add(it) }
        }
        return groups.filter { it.size > 1 }
    }

    fun classify(photos: List<Photo>): List<Photo> {
        val duplicated = mutableSetOf<Long>()
        val keepers = mutableSetOf<Long>()
        for (group in duplicateGroups(photos)) {
            duplicated.addAll(group.map { it.meta.id })
            val best = group.maxWithOrNull(compareBy<Photo> { it.meta.width.toLong() * it.meta.height }
                .thenBy { it.analysis?.sharpness ?: 0.0 }.thenBy { it.meta.bytes }.thenBy { -it.meta.id })!!
            keepers.add(best.meta.id)
        }
        return photos.map { photo ->
            val categories = metadataCategories(photo.meta).toMutableSet()
            photo.analysis?.let {
                if (isBlurry(it.sharpness)) categories.add(Category.BLURRY)
                if (it.food) categories.add(Category.FOOD)
            }
            if (photo.meta.id in duplicated) categories.add(Category.DUPLICATES)
            photo.copy(categories = categories, keeper = photo.meta.id in keepers)
        }
    }

    fun initiallySelected(photo: Photo): Boolean {
        if (photo.meta.path.startsWith("DCIM/Camera/", ignoreCase = true) &&
            photo.categories == setOf(Category.BLURRY)) return false
        return photo.categories.isNotEmpty() && !photo.meta.favorite && !photo.keeper
    }

    /** Decimal MB, as used for storage capacity; locale is explicit for JVM tests. */
    fun formatMb(bytes: Long): String = String.format(Locale.forLanguageTag("pt-BR"), "%.1f MB", bytes.coerceAtLeast(0) / 1_000_000.0)

    fun laplacianVariance(gray: IntArray, width: Int, height: Int): Double {
        require(width >= 0 && height >= 0 && width.toLong() * height == gray.size.toLong())
        if (width < 3 || height < 3) return Double.NaN
        var sum = 0.0
        var sumSquares = 0.0
        val count = (width - 2).toLong() * (height - 2)
        for (y in 1 until height - 1) for (x in 1 until width - 1) {
            val i = y * width + x
            val lap = gray[i - 1] + gray[i + 1] + gray[i - width] + gray[i + width] - 4.0 * gray[i]
            sum += lap
            sumSquares += lap * lap
        }
        return (sumSquares / count - (sum / count) * (sum / count)).coerceAtLeast(0.0)
    }
}

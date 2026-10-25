package io.github.arturaragao.limpafotos

import java.util.Locale
import java.text.Normalizer

enum class Category(val title: String) {
    PRINTS("Prints"), DOCUMENTS("Documentos e papel"), STICKERS("Figurinhas"),
    WHATSAPP("Lixo do WhatsApp"), BLURRY("Muito borradas"), DUPLICATES("Repetidas"),
    GIFS("GIFs"), LEARNED("Pelo seu jeito")
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

/** food/plated retained for callers; food no longer creates a category or suggestion. */
data class Analysis(val hash: Long, val sharpness: Double, val food: Boolean = false,
    val cat: Boolean = false, val plated: Boolean = false,
    val hasFace: Boolean = false, val person: Boolean = false,
    val document: Boolean = false, val whatsappJunk: Boolean = false,
    val labels: Map<Int, Float> = emptyMap(), val faceCount: Int = 0, val largestFace: Float = 0f,
    val textChars: Int = 0, val textLines: Int = 0, val idDocument: Boolean = false)

enum class MediaPresence { MISSING, EXISTS, UNKNOWN }

/** Only a confirmed disappearance advances the deletion count. */
data class GooglePhotosFlow(
    val ids: List<Long>, val index: Int = 0, val deleted: Int = 0, val skipped: Int = 0,
    val awaitingReturn: Boolean = false, val needsDecision: Boolean = false
) {
    val currentId: Long? get() = ids.getOrNull(index)
    val finished: Boolean get() = index >= ids.size
    val progressText: String get() = "${if (finished) ids.size else index + 1} de ${ids.size}"
}

data class Photo(
    val meta: PhotoMeta,
    val analysis: Analysis?,
    val categories: Set<Category>,
    val keeper: Boolean = false
)

object PhotoRules {
    const val BLUR_THRESHOLD = 15.0
    const val DUPLICATE_DISTANCE = 6
    /** Artur (25/10/2026): por enquanto não sugerir fotos parecidas/repetidas. */
    const val SUGGEST_DUPLICATES = false
    /** Pasta para onde o lote vai antes de o Artur apagar no Google Fotos. */
    const val BATCH_FOLDER = "Pictures/Apagar - Limpar Fotos/"
    const val HOUR_MS = 3_600_000L

    fun analysisKey(meta: PhotoMeta): String = "${meta.id}:${meta.modified}"

    /** Cache hits never consume the batch. A changed file must be analyzed again. */
    fun nextUnanalyzed(all: List<PhotoMeta>, analyzedKeys: Set<String>, limit: Int,
        keptIds: Set<Long> = emptySet()): List<PhotoMeta> {
        require(limit >= 0)
        return all.asSequence().filter { it.id !in keptIds && analysisKey(it) !in analyzedKeys }
            .sortedWith(compareByDescending<PhotoMeta> { if (it.takenAt > 0) it.takenAt else it.addedAt }
                .thenByDescending { it.id })
            .take(limit).toList()
    }

    fun metadataCategories(meta: PhotoMeta): Set<Category> = buildSet {
        val path = meta.path.lowercase(Locale.ROOT)
        if (path.contains("screenshots") || meta.name.startsWith("Screenshot", ignoreCase = true) ||
            meta.name.startsWith("Captura de tela", ignoreCase = true)) add(Category.PRINTS)
        val stickerWebp = meta.mime.equals("image/webp", true) && meta.width in 1..512 && meta.width == meta.height
        if (stickerWebp || path.contains("whatsapp stickers")) add(Category.STICKERS)
        if (meta.mime.equals("image/gif", true)) add(Category.GIFS)
    }

    // Só rótulos que indicam gente de fato. Partes do corpo (mão, cabelo, pele, sorriso…) saíram: uma mão
    // segurando um papel ou um print com avatar excluía quase tudo e o app não sugeria nada.
    private val PERSON_LABELS = setOf("person", "people", "selfie", "face", "portrait", "human",
        "baby", "child", "boy", "girl", "man", "woman", "bride", "groom", "crowd")
    const val PERSON_LABEL_MIN = 0.6f
    fun hasPersonLabel(labels: List<Pair<String, Float>>): Boolean = labels.any {
        it.first.lowercase(Locale.ROOT) in PERSON_LABELS && it.second >= PERSON_LABEL_MIN
    }
    fun isScreenLike(meta: PhotoMeta): Boolean = metadataCategories(meta).isNotEmpty()
    /** largestFace = largura do maior rosto / largura da imagem (0 = nenhum rosto). */
    fun faceExcludes(meta: PhotoMeta, largestFace: Float): Boolean =
        !isScreenLike(meta) && largestFace > 0f

    fun isIdDocument(text: String): Boolean {
        val normalized = Normalizer.normalize(text.uppercase(Locale.ROOT), Normalizer.Form.NFD)
            .replace(Regex("\\p{M}+"), "").replace(Regex("[^A-Z0-9]+"), " ").trim()
        val cpfCard = Regex("(?:^| )CPF(?: |$)").containsMatchIn(normalized) &&
            ("MINISTERIO DA FAZENDA" in normalized || "RECEITA FEDERAL" in normalized)
        return cpfCard || ID_TERMS.any { Regex("(?:^| )${Regex.escape(it)}(?: |$)").containsMatchIn(normalized) }
    }
    private val ID_TERMS = listOf("HABILITACAO", "CARTEIRA NACIONAL DE HABILITACAO", "REGISTRO GERAL",
        "CARTEIRA DE IDENTIDADE", "IDENTIDADE", "PASSAPORTE", "PASSPORT", "CARTEIRA DE TRABALHO",
        "TITULO ELEITORAL", "TITULO DE ELEITOR", "CERTIDAO DE NASCIMENTO", "CERTIDAO DE CASAMENTO",
        "CONSELHO REGIONAL DE MEDICINA", "DOCUMENTO DE IDENTIDADE", "CADASTRO DE PESSOAS FISICAS",
        // "CRM" sozinho não: todo laudo/receita tem "CRM-SC" e ele quer poder apagar esses prints.
        "CNH", "RG")

    fun isProtected(photo: Photo): Boolean = photo.analysis == null || photo.meta.favorite ||
        photo.analysis.cat || photo.analysis.idDocument ||
        (!isScreenLike(photo.meta) && excludedByPerson(photo.analysis))

    fun mandatory(meta: PhotoMeta, analysis: Analysis): Boolean = isScreenLike(meta) ||
        analysis.document || analysis.textChars >= 80

    /** Resumo do que a análise encontrou, para nunca ficar "nenhuma sugestão" sem explicação. */
    fun report(analyzed: List<Photo>, classified: List<Photo>, keptIds: Set<Long>): String {
        val people = analyzed.count { !isScreenLike(it.meta) && excludedByPerson(it.analysis) }
        val cat = analyzed.count { !excludedByPerson(it.analysis) && it.analysis?.cat == true }
        val kept = analyzed.count { it.meta.id in keptIds }
        val suggested = classified.filter(::initiallySelected)
        val perCategory = Category.values().mapNotNull { c ->
            suggested.count { c in it.categories }.takeIf { it > 0 }?.let { "${c.title.lowercase(Locale.ROOT)} $it" }
        }
        return buildString {
            append("${analyzed.size} analisadas: ${suggested.size} sugeridas")
            if (perCategory.isNotEmpty()) append(" (${perCategory.joinToString(", ")})")
            append("; $people com pessoas (puladas)")
            if (cat > 0) append("; $cat do Koda")
            if (kept > 0) append("; $kept mantidas por você")
            append(".")
        }
    }

    fun hasDocumentLabel(labels: List<Pair<String, Float>>): Boolean = labels.any {
        it.first.lowercase(Locale.ROOT) in setOf("document", "paper", "receipt") && it.second >= 0.9f
    }
    fun hasJunkLabel(labels: List<Pair<String, Float>>): Boolean = labels.any {
        it.first.lowercase(Locale.ROOT) in setOf("comics", "comic book", "poster", "font") && it.second >= 0.95f
    }
    fun excludedByPerson(analysis: Analysis?): Boolean =
        analysis == null || analysis.hasFace || analysis.person

    fun filterKept(photos: List<Photo>, keptIds: Set<Long>): List<Photo> = photos.filter { it.meta.id !in keptIds }

    fun startGooglePhotos(ids: List<Long>) = GooglePhotosFlow(ids.distinct())
    fun mediaPresence(fullAccess: Boolean, rowFound: Boolean, trashed: Boolean = false): MediaPresence = when {
        rowFound -> if (trashed) MediaPresence.MISSING else MediaPresence.EXISTS
        fullAccess -> MediaPresence.MISSING
        else -> MediaPresence.UNKNOWN
    }
    fun openGooglePhoto(flow: GooglePhotosFlow): GooglePhotosFlow =
        if (flow.finished || flow.awaitingReturn || flow.needsDecision) flow else flow.copy(awaitingReturn = true)
    fun returnedFromGooglePhotos(flow: GooglePhotosFlow, presence: MediaPresence): GooglePhotosFlow {
        if (flow.finished || !flow.awaitingReturn) return flow
        return if (presence == MediaPresence.MISSING)
            flow.copy(index = flow.index + 1, deleted = flow.deleted + 1, awaitingReturn = false)
        else flow.copy(awaitingReturn = false, needsDecision = true)
    }
    fun skipGooglePhoto(flow: GooglePhotosFlow): GooglePhotosFlow =
        if (!flow.needsDecision || flow.finished) flow else flow.copy(index = flow.index + 1,
            skipped = flow.skipped + 1, needsDecision = false)
    fun retryGooglePhoto(flow: GooglePhotosFlow): GooglePhotosFlow = flow.copy(needsDecision = false)

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
        for (photo in photos.filter { !isProtected(it) && it.meta.takenAt > 0 }
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

    fun classify(photos: List<Photo>, keptIds: Set<Long> = emptySet(), learner: Learner = Learner(),
        chosenIds: Set<Long> = emptySet()): List<Photo> {
        val eligible = filterKept(photos, keptIds).filter {
            !it.meta.path.startsWith(BATCH_FOLDER, ignoreCase = true) && it.analysis != null && (isScreenLike(it.meta) || !excludedByPerson(it.analysis))
        }
        val duplicated = mutableSetOf<Long>()
        val keepers = mutableSetOf<Long>()
        for (group in if (SUGGEST_DUPLICATES) duplicateGroups(eligible) else emptyList()) {
            duplicated.addAll(group.map { it.meta.id })
            val best = group.maxWithOrNull(compareBy<Photo> { it.meta.width.toLong() * it.meta.height }
                .thenBy { it.analysis?.sharpness ?: 0.0 }.thenBy { it.meta.bytes }.thenBy { -it.meta.id })!!
            keepers.add(best.meta.id)
        }
        return eligible.map { photo ->
            val categories = metadataCategories(photo.meta).toMutableSet()
            photo.analysis?.let {
                if (isBlurry(it.sharpness)) categories.add(Category.BLURRY)
                if (it.document || it.textChars >= 80) categories.add(Category.DOCUMENTS)
                val path = photo.meta.path.lowercase(Locale.ROOT)
                if (it.whatsappJunk && path.contains("whatsapp images/") && !path.contains("/sent/"))
                    categories.add(Category.WHATSAPP)
            }
            if (photo.meta.id in duplicated) categories.add(Category.DUPLICATES)
            // Explicit marks remain reviewable even before the model has examples of both classes.
            if (!isProtected(photo) && !mandatory(photo.meta, photo.analysis!!) &&
                (photo.meta.id in chosenIds || learner.probability(photo.meta, photo.analysis) >= 0.5))
                categories.add(Category.LEARNED)
            photo.copy(categories = categories, keeper = photo.meta.id in keepers && photo.meta.id !in chosenIds)
        }
    }

    fun initiallySelected(photo: Photo): Boolean {
        if (isProtected(photo)) return false
        if (mandatory(photo.meta, photo.analysis!!)) return true
        if (photo.keeper) return false
        if (Category.LEARNED in photo.categories || Category.WHATSAPP in photo.categories ||
            Category.DUPLICATES in photo.categories) return true
        // Foto da câmera só borrada continua de fora (pode ser a única daquele momento).
        return Category.BLURRY in photo.categories && !photo.meta.path.startsWith("DCIM/Camera/", ignoreCase = true)
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

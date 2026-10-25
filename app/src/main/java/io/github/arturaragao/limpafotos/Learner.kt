package io.github.arturaragao.limpafotos

import java.util.Locale
import kotlin.math.abs
import kotlin.math.exp
import kotlin.math.ln
import kotlin.math.min

data class TrainingExample(val id: Long, val features: DoubleArray, val apagar: Boolean, val quando: Long)

/** Android-free features, bounded history and balanced logistic regression. */
class Learner(
    val weights: DoubleArray = DoubleArray(FEATURE_COUNT),
    val positives: Int = 0,
    val negatives: Int = 0
) {
    val count: Int get() = positives + negatives

    fun predict(x: DoubleArray): Double {
        require(x.size == FEATURE_COUNT)
        var score = 0.0
        for (i in x.indices) score += weights[i] * x[i]
        return 1.0 / (1.0 + exp(-score.coerceIn(-35.0, 35.0)))
    }

    fun probability(meta: PhotoMeta, analysis: Analysis): Double {
        val prior = when {
            PhotoRules.mandatory(meta, analysis) -> 0.95
            meta.path.lowercase(Locale.ROOT).contains("whatsapp") -> 0.5
            else -> 0.15
        }
        if (positives < 3 || negatives < 3) return prior
        val w = count.toDouble() / (count + 20.0)
        return w * predict(features(meta, analysis)) + (1 - w) * prior
    }

    companion object {
        const val LABEL_OFFSET = 16
        const val FEATURE_COUNT = LABEL_OFFSET + 512
        const val MAX_EXAMPLES = 5000

        fun features(meta: PhotoMeta, analysis: Analysis): DoubleArray {
            val path = meta.path.lowercase(Locale.ROOT)
            val categories = PhotoRules.metadataCategories(meta)
            fun flag(value: Boolean) = if (value) 1.0 else 0.0
            return DoubleArray(FEATURE_COUNT).also { x ->
                x[0] = 1.0
                x[1] = flag(Category.PRINTS in categories)
                x[2] = flag(Category.STICKERS in categories)
                x[3] = flag(meta.mime.equals("image/gif", true))
                x[4] = flag("whatsapp" in path)
                x[5] = flag(path.startsWith("dcim/camera"))
                x[6] = flag("download" in path)
                x[7] = ln(meta.width.coerceAtLeast(1).toDouble() / meta.height.coerceAtLeast(1))
                x[8] = ln(meta.width.coerceAtLeast(0).toDouble() * meta.height.coerceAtLeast(0) / 1_000_000 + 1)
                x[9] = ln(meta.bytes.coerceAtLeast(0).toDouble() + 1) / 15
                x[10] = if (analysis.sharpness.isFinite()) ln(analysis.sharpness.coerceAtLeast(0.0) + 1) / 5 else 0.0
                x[11] = ln(analysis.textChars.coerceAtLeast(0).toDouble() + 1) / 7
                x[12] = ln(analysis.textLines.coerceAtLeast(0).toDouble() + 1) / 4
                x[13] = min(analysis.faceCount.coerceAtLeast(0), 3) / 3.0
                x[14] = analysis.largestFace.toDouble().takeIf { it.isFinite() } ?: 0.0
                x[15] = flag(analysis.cat)
                analysis.labels.forEach { (index, confidence) ->
                    if (index >= 0 && confidence.isFinite()) {
                        val slot = LABEL_OFFSET + index % 512
                        x[slot] = maxOf(x[slot], confidence.coerceIn(0f, 1f).toDouble())
                    }
                }
            }
        }

        fun bounded(examples: List<TrainingExample>): List<TrainingExample> = examples
            .sortedBy { it.quando }.associateBy { it.id }.values.sortedBy { it.quando }.takeLast(MAX_EXAMPLES)

        fun train(examples: List<TrainingExample>, checkpoint: () -> Unit = {}): Learner {
            val data = bounded(examples)
            val positive = data.count { it.apagar }
            val negative = data.size - positive
            val model = Learner(positives = positive, negatives = negative)
            if (positive < 3 || negative < 3) return model
            // Sparse rows avoid scanning 512 empty label positions at every epoch.
            val rows = data.map { e -> e.features.indices.filter { e.features[it] != 0.0 }.toIntArray() }
            repeat(300) {
                checkpoint()
                val gradient = DoubleArray(FEATURE_COUNT)
                data.forEachIndexed { row, e ->
                    val indices = rows[row]
                    var score = 0.0
                    for (j in indices) score += model.weights[j] * e.features[j]
                    val p = 1 / (1 + exp(-score.coerceIn(-35.0, 35.0)))
                    val balance = data.size.toDouble() / (2 * if (e.apagar) positive else negative)
                    val error = (p - if (e.apagar) 1.0 else 0.0) * balance / data.size
                    for (j in indices) gradient[j] += error * e.features[j]
                }
                for (j in model.weights.indices) {
                    model.weights[j] -= 0.5 * (gradient[j] + if (j == 0) 0.0 else 1e-3 * model.weights[j])
                }
            }
            return model
        }

        fun teachingBatch(photos: List<Photo>, taught: Set<Long>, model: Learner): List<Photo> {
            val candidates = photos.filter { !PhotoRules.isProtected(it) && it.meta.id !in taught }
            val half = min(30, (candidates.size + 1) / 2)
            val uncertain = candidates.sortedBy { abs(model.probability(it.meta, it.analysis!!) - 0.5) }.take(half)
            val ids = uncertain.map { it.meta.id }.toSet()
            val random = candidates.filter { it.meta.id !in ids }.shuffled().take(30)
            return (uncertain + random).shuffled()
        }
    }
}

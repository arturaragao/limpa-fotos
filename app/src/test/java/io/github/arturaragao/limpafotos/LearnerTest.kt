package io.github.arturaragao.limpafotos

import org.junit.Assert.*
import org.junit.Test
import kotlin.math.ln

class LearnerTest {
    private fun meta(id: Long = 1, path: String = "Pictures/", mime: String = "image/jpeg", favorite: Boolean = false) =
        PhotoMeta(id, 1, "foto.jpg", path, mime, 1200, 900, 2_000_000, 0, favorite)
    private fun analysis(label: Float = 0f) = Analysis(0, 100.0, labels = mapOf(7 to label))
    private fun example(id: Long, apagar: Boolean) = TrainingExample(id,
        Learner.features(meta(id), analysis(if (apagar) 0.95f else 0.05f)), apagar, id)

    @Test fun learnsLabelSevenAndBalancesUnevenClasses() {
        val examples = (1L..20L).map { example(it, true) } + (21L..100L).map { example(it, false) }
        val model = Learner.train(examples)
        assertTrue(model.predict(Learner.features(meta(), analysis(1f))) > 0.8)
        assertTrue(model.predict(Learner.features(meta(), analysis(0f))) < 0.2)
        assertTrue(model.probability(meta(), analysis(1f)) >= 0.5)
        assertTrue(model.probability(meta(), analysis(0f)) < 0.5)
        val photo = Photo(meta(), analysis(1f), emptySet())
        val result = PhotoRules.classify(listOf(photo), learner = model).single()
        assertTrue(Category.LEARNED in result.categories)
        assertTrue(PhotoRules.initiallySelected(result))
    }

    @Test fun usesPriorUntilBothClassesHaveThreeExamples() {
        for (model in listOf(Learner(), Learner.train((1L..20L).map { example(it, true) }))) {
            assertEquals(0.15, model.probability(meta(), analysis()), 0.0)
            assertEquals(0.5, model.probability(meta(path = "WhatsApp/Media/"), analysis()), 0.0)
            assertEquals(0.95, model.probability(meta(path = "Pictures/Screenshots/"), analysis()), 0.0)
            assertEquals(0.95, model.probability(meta(), analysis().copy(textChars = 80)), 0.0)
        }
    }

    @Test fun fixedFeaturesIncludeSparseLabelsAndFiniteSharpness() {
        val features = Learner.features(meta(), analysis(0.9f).copy(sharpness = Double.NaN,
            faceCount = 9, largestFace = 0.25f, textChars = 80, textLines = 4))
        assertEquals(528, features.size)
        assertEquals(1.0, features[0], 0.0)
        assertEquals(0.0, features[10], 0.0)
        assertEquals(ln(81.0) / 7, features[11], 1e-8)
        assertEquals(1.0, features[13], 0.0)
        assertEquals(0.25, features[14], 0.0)
        assertEquals(0.9, features[Learner.LABEL_OFFSET + 7], 1e-6)
        assertEquals(0.0, features[Learner.LABEL_OFFSET + 8], 0.0)
        assertTrue(features.all { it.isFinite() })
    }

    @Test fun fixedProtectionBeatsEvenACertainModelAndUserMark() {
        val weights = DoubleArray(Learner.FEATURE_COUNT).also { it[0] = 35.0 }
        val model = Learner(weights, 100, 100)
        val base = Photo(meta(), analysis(), emptySet())
        for (photo in listOf(base.copy(meta = meta(favorite = true)),
            base.copy(analysis = analysis().copy(cat = true)),
            base.copy(analysis = analysis().copy(idDocument = true, document = true, textChars = 500)),
            base.copy(meta = meta(path = "DCIM/Camera/"), analysis = analysis().copy(hasFace = true, faceCount = 1)))) {
            assertTrue(PhotoRules.isProtected(photo))
            assertFalse(PhotoRules.initiallySelected(photo))
            assertTrue(PhotoRules.classify(listOf(photo), learner = model, chosenIds = setOf(1))
                .none(PhotoRules::initiallySelected))
        }
    }

    @Test fun printsStickersAndGifsIgnoreFacesAndNegativeModel() {
        val weights = DoubleArray(Learner.FEATURE_COUNT).also { it[0] = -35.0 }
        val model = Learner(weights, 100, 100)
        for (m in listOf(meta(path = "Pictures/Screenshots/"), meta(path = "WhatsApp Stickers/"), meta(mime = "image/gif"))) {
            assertFalse(PhotoRules.faceExcludes(m, 0.9f))
            val photo = Photo(m, analysis().copy(faceCount = 1, largestFace = 0.9f, hasFace = true, person = true), emptySet())
            assertTrue(PhotoRules.initiallySelected(PhotoRules.classify(listOf(photo), learner = model).single()))
        }
    }

    @Test fun textDocumentsAreMandatoryButCameraSelfiesNeverAppear() {
        val text = Photo(meta(), analysis().copy(textChars = 80), emptySet())
        val document = PhotoRules.classify(listOf(text)).single()
        assertTrue(Category.DOCUMENTS in document.categories)
        assertTrue(PhotoRules.initiallySelected(document))
        val selfie = text.copy(meta = meta(path = "DCIM/Camera/"), analysis = text.analysis!!.copy(hasFace = true))
        assertTrue(PhotoRules.classify(listOf(selfie)).isEmpty())
    }

    @Test fun historyKeepsFiveThousandNewestUniqueIdsAndLastDecision() {
        val all = (1L..5002L).map { example(it, false) }
        val bounded = Learner.bounded(all)
        assertEquals(5000, bounded.size)
        assertEquals(3L, bounded.first().id)
        assertEquals(5002L, bounded.last().id)
        val updated = Learner.bounded(bounded + example(3, true).copy(quando = 6000))
        assertEquals(5000, updated.size)
        assertTrue(updated.last().apagar)
        assertEquals(3L, updated.last().id)
        assertTrue(updated.last().features.contentEquals(example(3, true).features))
    }

    @Test fun teachingPicksSixtyUnseenEligiblePhotosWithoutDuplicates() {
        val photos = (1L..100L).map { Photo(meta(it), analysis(), emptySet()) }
        val protected = Photo(meta(101, favorite = true), analysis(), emptySet())
        val batch = Learner.teachingBatch(photos + protected, setOf(1, 2, 3), Learner())
        assertEquals(60, batch.size)
        assertEquals(60, batch.map { it.meta.id }.distinct().size)
        assertTrue(batch.none { it.meta.id in setOf(1L, 2L, 3L, 101L) })
        assertEquals(2, Learner.teachingBatch(photos.take(2), emptySet(), Learner()).size)
    }

    @Test fun explicitTeachingMarkAppearsEvenBeforeModelHasEnoughClasses() {
        val photo = Photo(meta(), analysis(), emptySet())
        assertTrue(PhotoRules.classify(listOf(photo), chosenIds = setOf(1)).all(PhotoRules::initiallySelected))
        assertTrue(PhotoRules.classify(listOf(photo), keptIds = setOf(1), chosenIds = setOf(1)).isEmpty())
    }
}

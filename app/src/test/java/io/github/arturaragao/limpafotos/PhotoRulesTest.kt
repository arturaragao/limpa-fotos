package io.github.arturaragao.limpafotos

import org.junit.Assert.*
import org.junit.Test

class PhotoRulesTest {
    private fun meta(id: Long = 1, path: String = "DCIM/Camera/", name: String = "foto.jpg",
        mime: String = "image/jpeg", width: Int = 1200, height: Int = 900, favorite: Boolean = false,
        taken: Long = 1_000_000, bytes: Long = 2_000_000) =
        PhotoMeta(id, 123, name, path, mime, width, height, bytes, taken, favorite)

    private fun photo(id: Long, hash: Long = 0, time: Long = 1_000_000, width: Int = 1200,
        sharpness: Double = 150.0, favorite: Boolean = false) =
        Photo(meta(id, taken = time, width = width, favorite = favorite), Analysis(hash, sharpness, false), emptySet())

    @Test fun nextBatchUsesNewestUncachedPhotosWithoutSpendingLimitOnCacheHits() {
        val all = listOf(meta(1, taken = 100), meta(4, taken = 400), meta(2, taken = 200), meta(3, taken = 300))
        val cached = setOf(PhotoRules.analysisKey(all[1]), PhotoRules.analysisKey(all[3]))
        assertEquals(listOf(2L, 1L), PhotoRules.nextUnanalyzed(all, cached, 2).map { it.id })
    }

    @Test fun batchesContinueFromCacheIncludingWorkSavedBeforeAnError() {
        val all = (1L..7L).map { meta(it, taken = it * 100) }
        val first = PhotoRules.nextUnanalyzed(all, emptySet(), 3)
        assertEquals(listOf(7L, 6L, 5L), first.map { it.id })
        // The second photo failed: only completed analyses were saved before interruption.
        val saved = setOf(PhotoRules.analysisKey(first[0]))
        val retry = PhotoRules.nextUnanalyzed(all, saved, 3)
        assertEquals(listOf(6L, 5L, 4L), retry.map { it.id })
        val next = PhotoRules.nextUnanalyzed(all, saved + retry.map(PhotoRules::analysisKey), 3)
        assertEquals(listOf(3L, 2L, 1L), next.map { it.id })
    }

    @Test fun modifiedPhotosAreEligibleAgainAndNewPhotosComeFirst() {
        val original = meta(1, taken = 100)
        val changed = original.copy(modified = original.modified + 1)
        val added = meta(2, taken = 200)
        val cache = setOf(PhotoRules.analysisKey(original))
        assertEquals(listOf(added, changed), PhotoRules.nextUnanalyzed(listOf(changed, added), cache, 200))
        assertTrue(PhotoRules.nextUnanalyzed(listOf(original), cache, 200).isEmpty())
    }

    @Test fun unknownCaptureTimeUsesDateAddedAndTiesUseId() {
        val all = listOf(meta(1, taken = 0).copy(addedAt = 300), meta(2, taken = 200),
            meta(3, taken = 300), meta(4, taken = 0))
        assertEquals(listOf(3L, 1L, 2L, 4L), PhotoRules.nextUnanalyzed(all, emptySet(), 200).map { it.id })
        assertEquals(0L, all.first().takenAt) // Unknown times stay unknown to duplicate grouping.
    }

    @Test fun batchLimitHandlesEmptyZeroAndExhaustedGallery() {
        assertTrue(PhotoRules.nextUnanalyzed(emptyList(), emptySet(), 200).isEmpty())
        val all = listOf(meta(1), meta(2))
        assertTrue(PhotoRules.nextUnanalyzed(all, emptySet(), 0).isEmpty())
        assertEquals(1, PhotoRules.nextUnanalyzed(all, emptySet(), 1).size)
        assertEquals(2, PhotoRules.nextUnanalyzed(all, emptySet(), 200).size)
        assertTrue(PhotoRules.nextUnanalyzed(all, all.map(PhotoRules::analysisKey).toSet(), 200).isEmpty())
    }

    @Test(expected = IllegalArgumentException::class)
    fun batchLimitRejectsNegativeValues() {
        PhotoRules.nextUnanalyzed(emptyList(), emptySet(), -1)
    }

    @Test fun classificationAcrossBatchesStillProtectsFavoritesAndBestDuplicate() {
        val cached = photo(1, favorite = true)
        val newPhoto = photo(2, width = 1800)
        val results = PhotoRules.classify(listOf(cached, newPhoto))
        assertTrue(results.all { Category.DUPLICATES in it.categories })
        assertTrue(results.last().keeper)
        assertTrue(results.none(PhotoRules::initiallySelected))
    }

    @Test fun hammingIncludesSignBitAndAllBits() {
        assertEquals(0, PhotoRules.hamming(Long.MIN_VALUE, Long.MIN_VALUE))
        assertEquals(1, PhotoRules.hamming(0, Long.MIN_VALUE))
        assertEquals(64, PhotoRules.hamming(0, -1))
        assertEquals(6, PhotoRules.hamming(0, 63))
    }

    @Test fun screenshotNamesAndPathsAreCaseInsensitive() {
        for (m in listOf(meta(name = "Screenshot_2026.jpg"), meta(path = "Pictures/SCREENSHOTS/"),
            meta(path = "Movies/Screen recordings/"))) {
            assertTrue(Category.PRINTS in PhotoRules.metadataCategories(m))
        }
        assertFalse(Category.PRINTS in PhotoRules.metadataCategories(meta()))
    }

    @Test fun gifsWebpFoldersAndSmallSidesAreStickers() {
        for (m in listOf(meta(mime = "image/gif"), meta(mime = "image/webp"),
            meta(path = "WhatsApp/Media/WhatsApp Stickers/"), meta(path = "WhatsApp Animated Gifs/"),
            meta(width = 511), meta(height = 511))) {
            assertTrue(Category.STICKERS in PhotoRules.metadataCategories(m))
        }
        assertFalse(Category.STICKERS in PhotoRules.metadataCategories(meta(width = 512, height = 512)))
        assertFalse(Category.STICKERS in PhotoRules.metadataCategories(meta(width = 0, height = 0)))
    }

    @Test fun blurThresholdAndInvalidScores() {
        assertTrue(PhotoRules.isBlurry(0.0))
        assertTrue(PhotoRules.isBlurry(99.999))
        assertFalse(PhotoRules.isBlurry(100.0))
        assertFalse(PhotoRules.isBlurry(-1.0))
        assertFalse(PhotoRules.isBlurry(Double.NaN))
        assertFalse(PhotoRules.isBlurry(Double.POSITIVE_INFINITY))
    }

    @Test fun laplacianDistinguishesFlatImageFromEdges() {
        assertEquals(0.0, PhotoRules.laplacianVariance(IntArray(25) { 128 }, 5, 5), 0.0)
        val checker = IntArray(25) { if ((it / 5 + it % 5) % 2 == 0) 0 else 255 }
        assertTrue(PhotoRules.laplacianVariance(checker, 5, 5) > PhotoRules.BLUR_THRESHOLD)
        assertTrue(PhotoRules.laplacianVariance(IntArray(4), 2, 2).isNaN())
    }

    @Test fun duplicatesHonorDistanceAndOneHourBoundary() {
        val start = 1_000_000L
        assertEquals(1, PhotoRules.duplicateGroups(listOf(photo(1), photo(2, 63, start + PhotoRules.HOUR_MS))).size)
        assertTrue(PhotoRules.duplicateGroups(listOf(photo(1), photo(2, 127))).isEmpty())
        assertTrue(PhotoRules.duplicateGroups(listOf(photo(1), photo(2, 0, start + PhotoRules.HOUR_MS + 1))).isEmpty())
        assertTrue(PhotoRules.duplicateGroups(listOf(photo(1, time = 0), photo(2, time = 0))).isEmpty())
    }

    @Test fun groupsDoNotChainAcrossAnHourOrAcrossHashDistance() {
        val start = 1_000_000L
        val byTime = PhotoRules.duplicateGroups(listOf(photo(3, time = start + 7_200_000), photo(1), photo(2, time = start + 3_600_000)))
        assertEquals(listOf(1L, 2L), byTime.single().map { it.meta.id })
        // 0 -> 63 -> 4095 would be a dangerous transitive group (0 vs 4095 = 12 bits).
        val byHash = PhotoRules.duplicateGroups(listOf(photo(1), photo(2, 63), photo(3, 4095)))
        assertEquals(listOf(1L, 2L), byHash.single().map { it.meta.id })
    }

    @Test fun hashBandIndexFindsPairsWithDifferencesSpreadAcrossBands() {
        val spread = (0..5).fold(0L) { value, i -> value or (1L shl (9 * i)) }
        assertEquals(6, PhotoRules.hamming(0, spread))
        assertEquals(2, PhotoRules.duplicateGroups(listOf(photo(1), photo(2, spread))).single().size)
    }

    @Test fun largestThenSharpestIsKeptEvenIfItIsAlsoBlurry() {
        val classified = PhotoRules.classify(listOf(photo(1, width = 900, sharpness = 500.0),
            photo(2, width = 1200, sharpness = 20.0), photo(3, width = 1200, sharpness = 30.0)))
        assertEquals(3L, classified.single { it.keeper }.meta.id)
        assertTrue(classified.all { Category.DUPLICATES in it.categories })
        assertFalse(PhotoRules.initiallySelected(classified.last()))
        assertTrue(PhotoRules.initiallySelected(classified.first()))
    }

    @Test fun favoritesAreNeverPremarkedInAnyCategory() {
        val favorite = Photo(meta(favorite = true, mime = "image/gif", name = "Screenshot.gif"),
            Analysis(0, 0.0, true), emptySet())
        val classified = PhotoRules.classify(listOf(favorite)).single()
        assertEquals(setOf(Category.PRINTS, Category.STICKERS, Category.BLURRY, Category.FOOD), classified.categories)
        assertFalse(PhotoRules.initiallySelected(classified))
        assertTrue(PhotoRules.initiallySelected(classified.copy(meta = classified.meta.copy(favorite = false))))
    }

    @Test fun unavailableVisualAnalysisStillAllowsMetadataCategories() {
        val classified = PhotoRules.classify(listOf(Photo(meta(name = "Screenshot.jpg"), null, emptySet()))).single()
        assertEquals(setOf(Category.PRINTS), classified.categories)
        assertFalse(classified.keeper)
    }

    @Test fun cameraBlurOnlyIsNotPremarkedButStaysInCategory() {
        val blurryOnly = Photo(meta(path = "dcim/camera/"), Analysis(0, 20.0, false), emptySet())
        val classified = PhotoRules.classify(listOf(blurryOnly)).single()
        assertEquals(setOf(Category.BLURRY), classified.categories)
        assertFalse(PhotoRules.initiallySelected(classified))
        val elsewhere = classified.copy(meta = classified.meta.copy(path = "Pictures/Camera/"))
        assertTrue(PhotoRules.initiallySelected(elsewhere))
        val extra = classified.copy(categories = setOf(Category.BLURRY, Category.DUPLICATES))
        assertTrue(PhotoRules.initiallySelected(extra))
    }

    @Test fun megabytesAreDecimalPortugueseAndNeverNegative() {
        assertEquals("0,0 MB", PhotoRules.formatMb(0))
        assertEquals("0,0 MB", PhotoRules.formatMb(-10))
        assertEquals("1,0 MB", PhotoRules.formatMb(1_000_000))
        assertEquals("12,3 MB", PhotoRules.formatMb(12_345_678))
    }
}

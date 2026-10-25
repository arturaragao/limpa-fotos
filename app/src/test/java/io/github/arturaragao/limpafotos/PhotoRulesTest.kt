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
        assertTrue(results.none { Category.DUPLICATES in it.categories })
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
            meta(name = "Captura de tela 2026.png"))) {
            assertTrue(Category.PRINTS in PhotoRules.metadataCategories(m))
        }
        assertFalse(Category.PRINTS in PhotoRules.metadataCategories(meta()))
        assertFalse(Category.PRINTS in PhotoRules.metadataCategories(meta(path = "Movies/Screen recordings/")))
    }

    @Test fun onlySmallSquareWebpOrStickerFolderAreStickers() {
        for (m in listOf(meta(mime = "image/webp", width = 512, height = 512), meta(path = "WhatsApp/Media/WhatsApp Stickers/"))) {
            assertTrue(Category.STICKERS in PhotoRules.metadataCategories(m))
        }
        assertFalse(Category.STICKERS in PhotoRules.metadataCategories(meta(width = 512, height = 512)))
        assertFalse(Category.STICKERS in PhotoRules.metadataCategories(meta(width = 0, height = 0)))
        for (m in listOf(meta(mime = "image/gif"), meta(path = "WhatsApp Animated Gifs/"),
            meta(width = 511), meta(height = 511), meta(mime = "image/webp"),
            meta(mime = "image/webp", width = 512, height = 400))) assertFalse(Category.STICKERS in PhotoRules.metadataCategories(m))
    }

    @Test fun blurThresholdAndInvalidScores() {
        assertTrue(PhotoRules.isBlurry(0.0))
        assertTrue(PhotoRules.isBlurry(14.999))
        assertFalse(PhotoRules.isBlurry(15.0))
        assertFalse(PhotoRules.isBlurry(99.999))
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
        assertFalse(PhotoRules.initiallySelected(classified.first())) // Prior alone does not suggest camera photos.
    }

    @Test fun favoritesAreNeverPremarkedInAnyCategory() {
        val favorite = Photo(meta(favorite = true, mime = "image/webp", name = "Screenshot.webp", width = 512, height = 512),
            Analysis(0, 0.0, true), emptySet())
        val classified = PhotoRules.classify(listOf(favorite)).single()
        assertEquals(setOf(Category.PRINTS, Category.STICKERS, Category.BLURRY), classified.categories)
        assertFalse(PhotoRules.initiallySelected(classified))
        assertTrue(PhotoRules.initiallySelected(classified.copy(meta = classified.meta.copy(favorite = false))))
    }

    @Test fun unavailableAnalysisCannotBypassPersonProtectionViaMetadata() {
        val unknown = Photo(meta(name = "Screenshot.jpg"), null, setOf(Category.PRINTS))
        assertTrue(PhotoRules.classify(listOf(unknown)).isEmpty())
        assertFalse(PhotoRules.initiallySelected(unknown))
    }

    @Test fun catPhotosAreNeverSuggested() {
        val koda = PhotoRules.classify(listOf(Photo(meta(name = "Screenshot.jpg"),
            Analysis(0, 20.0, false, cat = true), emptySet()))).single()
        assertFalse(PhotoRules.initiallySelected(koda))
    }

    @Test fun foodAloneIsNeverASuggestion() {
        val food = Photo(meta(), Analysis(0, 500.0, food = true), emptySet())
        assertTrue(PhotoRules.classify(listOf(food)).single().categories.isEmpty())
        assertFalse(PhotoRules.initiallySelected(PhotoRules.classify(listOf(food)).single()))
    }

    @Test fun cameraBlurOnlyIsNotPremarkedButStaysInCategory() {
        val blurryOnly = Photo(meta(path = "dcim/camera/"), Analysis(0, 10.0, false), emptySet())
        val classified = PhotoRules.classify(listOf(blurryOnly)).single()
        assertEquals(setOf(Category.BLURRY), classified.categories)
        assertFalse(PhotoRules.initiallySelected(classified))
        val elsewhere = classified.copy(meta = classified.meta.copy(path = "Pictures/Camera/"))
        assertFalse(PhotoRules.initiallySelected(elsewhere))
        val extra = classified.copy(categories = setOf(Category.BLURRY, Category.DUPLICATES))
        assertFalse(PhotoRules.initiallySelected(extra))
    }

    @Test fun keptIdsAreExcludedFromEveryCategoryEvenAfterModification() {
        val kept = Photo(meta(1, mime = "image/webp", name = "Screenshot.webp"),
            Analysis(0, 1.0, document = true, whatsappJunk = true), Category.entries.toSet())
        val other = photo(2)
        val ids = setOf(1L)
        assertEquals(listOf(other), PhotoRules.filterKept(listOf(kept, other), ids))
        assertTrue(PhotoRules.classify(listOf(kept), ids).isEmpty())
        assertTrue(PhotoRules.classify(listOf(kept.copy(meta = kept.meta.copy(modified = 999))), ids).isEmpty())
        assertTrue(PhotoRules.classify(listOf(kept), emptySet()).single().categories.isNotEmpty())
    }

    @Test fun keptIdsDoNotConsumeBatchAndResetMakesThemEligibleAgain() {
        val all = listOf(meta(1, taken = 300), meta(2, taken = 200), meta(3, taken = 100))
        assertEquals(listOf(2L, 3L), PhotoRules.nextUnanalyzed(all, emptySet(), 2, setOf(1)).map { it.id })
        assertEquals(listOf(1L, 2L), PhotoRules.nextUnanalyzed(all, emptySet(), 2, emptySet()).map { it.id })
        val cached = setOf(PhotoRules.analysisKey(all[1]))
        assertEquals(listOf(3L), PhotoRules.nextUnanalyzed(all, cached, 2, setOf(1)).map { it.id })
    }

    @Test fun keptDuplicateCannotCauseAnotherDeletionSuggestion() {
        val result = PhotoRules.classify(listOf(photo(1), photo(2)), setOf(1))
        assertEquals(listOf(2L), result.map { it.meta.id })
        assertTrue(result.single().categories.isEmpty())
    }

    @Test fun facesAndPersonLabelsExcludeEveryCategoryIncludingDuplicates() {
        for (analysis in listOf(Analysis(0, 1.0, hasFace = true), Analysis(0, 1.0, person = true))) {
            val person = Photo(meta(1, path = "DCIM/Camera/"),
                analysis.copy(document = true, whatsappJunk = true), Category.entries.toSet())
            assertTrue(PhotoRules.classify(listOf(person)).isEmpty())
            assertFalse(PhotoRules.initiallySelected(person))
            assertTrue(PhotoRules.duplicateGroups(listOf(person, photo(2))).isEmpty())
            val result = PhotoRules.classify(listOf(person, photo(2)))
            assertEquals(listOf(2L), result.map { it.meta.id })
            assertFalse(Category.DUPLICATES in result.single().categories)
        }
    }

    @Test fun personLabelsAreCaseInsensitiveAndNeedClearConfidence() {
        for (label in listOf("Selfie", "Person", "FACE", "Portrait", "People", "Baby", "Crowd")) {
            assertTrue(PhotoRules.hasPersonLabel(listOf(label to 0.6f)))
        }
        assertFalse(PhotoRules.hasPersonLabel(listOf("Person" to 0.599f)))
        assertFalse(PhotoRules.hasPersonLabel(listOf("Landscape" to 0.99f)))
        assertFalse(PhotoRules.hasPersonLabel(listOf("Person" to Float.NaN)))
    }

    @Test fun documentsAndWhatsappJunkRequireStrongEvidence() {
        assertTrue(PhotoRules.hasDocumentLabel(listOf("Paper" to 0.9f)))
        assertFalse(PhotoRules.hasDocumentLabel(listOf("Paper" to 0.89f)))
        assertTrue(PhotoRules.hasJunkLabel(listOf("Comics" to 0.95f)))
        assertFalse(PhotoRules.hasJunkLabel(listOf("Comics" to 0.94f)))
        val base = Photo(meta(path = "Android/media/com.whatsapp/WhatsApp/Media/WhatsApp Images/"),
            Analysis(0, 500.0), emptySet())
        assertEquals(setOf(Category.LEARNED), PhotoRules.classify(listOf(base)).single().categories)
        val junk = base.copy(analysis = base.analysis!!.copy(whatsappJunk = true))
        assertEquals(setOf(Category.WHATSAPP, Category.LEARNED), PhotoRules.classify(listOf(junk)).single().categories)
        for (path in listOf("DCIM/Camera/", "WhatsApp/Media/WhatsApp Images/Sent/")) {
            assertFalse(Category.WHATSAPP in PhotoRules.classify(listOf(junk.copy(meta = junk.meta.copy(path = path)))).single().categories)
        }
        val document = base.copy(analysis = base.analysis!!.copy(document = true))
        assertTrue(Category.DOCUMENTS in PhotoRules.classify(listOf(document)).single().categories)
    }

    @Test fun partialAccessDisappearanceCannotBeCountedAsDeletion() {
        val flow = PhotoRules.openGooglePhoto(PhotoRules.startGooglePhotos(listOf(1)))
        val hidden = PhotoRules.mediaPresence(fullAccess = false, rowFound = false)
        val pending = PhotoRules.returnedFromGooglePhotos(flow, hidden)
        assertEquals(MediaPresence.UNKNOWN, hidden)
        assertEquals(0, pending.deleted)
        assertFalse(pending.finished)
        assertTrue(pending.needsDecision)
        assertEquals(MediaPresence.MISSING, PhotoRules.mediaPresence(fullAccess = true, rowFound = false))
        for (full in listOf(true, false)) {
            assertEquals(MediaPresence.EXISTS, PhotoRules.mediaPresence(full, rowFound = true))
            assertEquals(MediaPresence.MISSING, PhotoRules.mediaPresence(full, rowFound = true, trashed = true))
        }
    }

    @Test fun googleFlowDeduplicatesQueueAndCountsOnlyConfirmedDisappearances() {
        val start = PhotoRules.startGooglePhotos(listOf(5, 5, 8))
        assertEquals(listOf(5L, 8L), start.ids)
        assertEquals("1 de 2", start.progressText)
        val opened = PhotoRules.openGooglePhoto(start)
        assertTrue(opened.awaitingReturn)
        assertEquals(opened, PhotoRules.openGooglePhoto(opened))
        val next = PhotoRules.returnedFromGooglePhotos(opened, MediaPresence.MISSING)
        assertEquals(8L, next.currentId)
        assertEquals(1, next.deleted)
        assertEquals("2 de 2", next.progressText)
        assertEquals(next, PhotoRules.returnedFromGooglePhotos(next, MediaPresence.MISSING))
        val done = PhotoRules.returnedFromGooglePhotos(PhotoRules.openGooglePhoto(next), MediaPresence.MISSING)
        assertTrue(done.finished)
        assertNull(done.currentId)
        assertEquals(2, done.deleted)
        assertEquals("2 de 2", done.progressText)
        assertEquals(done, PhotoRules.openGooglePhoto(done))
    }

    @Test fun googlePhotoStillPresentOrUnreadableWaitsForExplicitSkipOrRetry() {
        for (presence in listOf(MediaPresence.EXISTS, MediaPresence.UNKNOWN)) {
            val opened = PhotoRules.openGooglePhoto(PhotoRules.startGooglePhotos(listOf(1, 2)))
            val waiting = PhotoRules.returnedFromGooglePhotos(opened, presence)
            assertTrue(waiting.needsDecision)
            assertFalse(waiting.awaitingReturn)
            assertEquals(1L, waiting.currentId)
            assertEquals(0, waiting.deleted)
            assertEquals(waiting, PhotoRules.openGooglePhoto(waiting))
            val retry = PhotoRules.openGooglePhoto(PhotoRules.retryGooglePhoto(waiting))
            assertEquals(1L, retry.currentId)
            assertTrue(retry.awaitingReturn)
            val skipped = PhotoRules.skipGooglePhoto(waiting)
            assertEquals(2L, skipped.currentId)
            assertEquals(1, skipped.skipped)
            assertEquals(0, skipped.deleted)
            assertFalse(skipped.needsDecision)
            assertEquals(skipped, PhotoRules.skipGooglePhoto(skipped))
        }
    }

    @Test fun googleFlowHandlesEmptyAndMixedCompletionWithoutDoubleCounting() {
        val empty = PhotoRules.startGooglePhotos(emptyList())
        assertTrue(empty.finished)
        assertEquals("0 de 0", empty.progressText)
        val first = PhotoRules.openGooglePhoto(PhotoRules.startGooglePhotos(listOf(1, 2)))
        val skipped = PhotoRules.skipGooglePhoto(PhotoRules.returnedFromGooglePhotos(first, MediaPresence.EXISTS))
        val done = PhotoRules.returnedFromGooglePhotos(PhotoRules.openGooglePhoto(skipped), MediaPresence.MISSING)
        assertTrue(done.finished)
        assertEquals(done.ids.size, done.deleted + done.skipped)
        assertEquals(done, PhotoRules.returnedFromGooglePhotos(done, MediaPresence.MISSING))
    }

    @Test fun megabytesAreDecimalPortugueseAndNeverNegative() {
        assertEquals("0,0 MB", PhotoRules.formatMb(0))
        assertEquals("0,0 MB", PhotoRules.formatMb(-10))
        assertEquals("1,0 MB", PhotoRules.formatMb(1_000_000))
        assertEquals("12,3 MB", PhotoRules.formatMb(12_345_678))
    }

    // Regressão 24/10/2026: 400 fotos, zero sugestões. Mão/pele/cabelo e avatares em prints excluíam quase tudo.
    @Test fun bodyPartLabelsNoLongerHidePhotos() {
        for (label in listOf("Hand", "Skin", "Hair", "Smile", "Arm", "Leg", "Beard"))
            assertFalse(PhotoRules.hasPersonLabel(listOf(label to 0.95f)))
    }

    @Test fun printsIgnoreSmallFacesButCameraPhotosDoNot() {
        val print = meta(path = "Pictures/Screenshots/", name = "Screenshot_1.png")
        val camera = meta()
        assertFalse(PhotoRules.faceExcludes(print, 0f))
        assertFalse(PhotoRules.faceExcludes(print, 0.04f))
        assertFalse(PhotoRules.faceExcludes(print, 0.8f))
        assertFalse(PhotoRules.faceExcludes(camera, 0f))
        assertTrue(PhotoRules.faceExcludes(camera, 0.01f))
    }

    @Test fun idDocumentsAreNormalizedWithoutBlockingBillsWithCpf() {
        for (text in listOf("Carteira Nacional de Habilitação", "REGISTRO GERAL", "Carteira de identidade",
            "Passaporte", "PASSPORT", "Carteira de Trabalho", "Título de Eleitor", "Título eleitoral",
            "Certidão de nascimento", "Certidão de casamento", "Conselho Regional de Medicina",
            "Documento de identidade", "Cadastro de Pessoas Físicas", "CNH", "RG",
            "MINISTÉRIO DA FAZENDA CPF 123.456.789-00")) assertTrue(text, PhotoRules.isIdDocument(text))
        for (text in listOf("Conta de luz ENERGIA CPF 123.456.789-00 vencimento 20/10/2026 total R$ 120,00",
            "Boleto bancário CPF do pagador 123.456.789-00 código de barras vencimento valor 123,45"))
            assertFalse(text, PhotoRules.isIdDocument(text))
    }

    @Test fun reportExplainsWhyNothingWasSuggested() {
        val analyzed = listOf(Photo(meta(1), Analysis(0, Double.NaN, hasFace = true), emptySet()),
            Photo(meta(2, path = "Pictures/Screenshots/", name = "Screenshot_2.png"), Analysis(5, 50.0), emptySet()))
        val text = PhotoRules.report(analyzed, PhotoRules.classify(analyzed), emptySet())
        assertTrue(text, text.startsWith("2 analisadas: 1 sugeridas (prints 1)"))
        assertTrue(text, "1 com pessoas" in text)
    }

    // Laudo/receita printado tem "CRM-SC 12345": é apagável; só a carteira do conselho é protegida.
    @Test fun crmNumberOnPrintedReportIsNotIdDocument() {
        assertFalse(PhotoRules.isIdDocument("Dr. Fulano - CRM-SC 12345 - Laudo de tomografia"))
        assertTrue(PhotoRules.isIdDocument("Conselho Regional de Medicina - carteira de médico"))
    }
}

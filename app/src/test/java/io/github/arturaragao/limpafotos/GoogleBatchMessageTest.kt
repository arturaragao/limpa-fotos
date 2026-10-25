package io.github.arturaragao.limpafotos

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class GoogleBatchMessageTest {
    @Test fun countsSuccessesAndFailuresIncludingEmptyAndFailedBatches() {
        assertEquals(GoogleBatchResult(3, 0), googleBatchResult(3, 3))
        assertEquals(GoogleBatchResult(2, 1), googleBatchResult(3, 2))
        assertEquals(GoogleBatchResult(0, 4), googleBatchResult(4, 0))
        assertEquals(GoogleBatchResult(0, 0), googleBatchResult(0, 0))
    }

    @Test fun successfulBatchShowsCountAndGooglePhotosInstructions() {
        assertEquals(
            "3 fotos foram para a pasta 'Apagar - Limpar Fotos'.\n\n" +
                "No Google Fotos: Biblioteca → Fotos no dispositivo → Apagar - Limpar Fotos → " +
                "toque e segure a 1ª e arraste até a última (ou toque no ✓ de cada data) → " +
                "Lixeira → Mover para a lixeira. Isso apaga do celular e da nuvem.",
            googleBatchMessage(GoogleBatchResult(3, 0))
        )
    }

    @Test fun partialFailureReportsBothCountsAndAllowsRetry() {
        val message = googleBatchMessage(GoogleBatchResult(2, 1))
        assertTrue(message.startsWith("2 fotos foram"))
        assertTrue(message.contains("Falhas ao mover: 1."))
        assertTrue(message.contains("tentar novamente"))
    }

    @Test fun totalFailureDoesNotClaimAnyPhotoWasMoved() {
        val message = googleBatchMessage(GoogleBatchResult(0, 4))
        assertTrue(message.startsWith("0 fotos foram"))
        assertTrue(message.contains("Falhas ao mover: 4."))
        assertFalse(message.contains("4 fotos foram"))
    }
}

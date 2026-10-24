package io.github.arturaragao.limpafotos

import org.junit.Assert.*
import org.junit.Test

class UpdateVersionTest {
    @Test fun readsOnlyOurReleaseSeries() {
        assertEquals(7, UpdateVersion.codeFromTag("v0.1.7"))
        assertEquals(12, UpdateVersion.codeFromTag(" v0.1.12 "))
        for (tag in listOf("latest", "v1.1.7", "v0.1.-1", "v0.1.0", "v0.1.7-beta", "v0.1.9999999999999", "foo.7")) {
            assertNull(tag, UpdateVersion.codeFromTag(tag))
        }
    }
    // Bug 24/10/2026: as tags passaram a v0.3.N e o app (que só lia v0.1.N) parou de se atualizar.
    @Test fun readsTagsAfterTheNameChanged() {
        assertEquals(10, UpdateVersion.codeFromTag("v0.3.10"))
        assertEquals(4, UpdateVersion.codeFromTag("v0.2.4"))
        assertTrue(UpdateVersion.isNewer("v0.3.10", 8))
        assertTrue(UpdateVersion.isNewer("v0.1.11", 10))
    }
    @Test fun onlyUpdatesForHigherVersionCode() {
        assertTrue(UpdateVersion.isNewer("v0.1.8", 7))
        assertFalse(UpdateVersion.isNewer("v0.1.7", 7))
        assertFalse(UpdateVersion.isNewer("v0.1.6", 7))
        assertFalse(UpdateVersion.isNewer("latest", 7))
    }
}

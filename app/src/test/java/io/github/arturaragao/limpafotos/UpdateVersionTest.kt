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
    @Test fun onlyUpdatesForHigherVersionCode() {
        assertTrue(UpdateVersion.isNewer("v0.1.8", 7))
        assertFalse(UpdateVersion.isNewer("v0.1.7", 7))
        assertFalse(UpdateVersion.isNewer("v0.1.6", 7))
        assertFalse(UpdateVersion.isNewer("latest", 7))
    }
}

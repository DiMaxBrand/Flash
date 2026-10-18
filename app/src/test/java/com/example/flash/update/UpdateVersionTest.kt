package com.example.flash.update

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class UpdateVersionTest {

    private fun v(raw: String) = UpdateChecker.parseVersion(raw)!!
    private fun cmp(a: String, b: String) = UpdateChecker.compareSemver(v(a), v(b))

    @Test fun betaNumbersIncrease() {
        assertTrue(cmp("0.12.0-beta.52", "0.12.0-beta.51") > 0)
    }

    @Test fun stableBeatsItsOwnBetas() {
        assertTrue(cmp("0.12.0", "0.12.0-beta.99") > 0)
    }

    @Test fun newerCoreBeatsOlderStable() {
        assertTrue(cmp("0.12.1-beta.1", "0.12.0") > 0)
    }

    @Test fun equalVersionsCompareEqual() {
        assertEquals(0, cmp("0.12.0", "0.12.0"))
        assertEquals(0, cmp("0.12.0-beta.51", "0.12.0-beta.51"))
    }

    @Test fun oldFlashTagsAreIgnored() {
        // Tags from before the updater ("v0.10.3-settings-on-top--Oct-17-f5c5067") must not parse as a release
        assertNull(UpdateChecker.parseVersion("v0.10.3-settings-on-top--Oct-17-f5c5067"))
    }
}

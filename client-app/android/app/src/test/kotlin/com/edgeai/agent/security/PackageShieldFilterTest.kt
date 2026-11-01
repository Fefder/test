package com.edgeai.agent.security

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class PackageShieldFilterTest {
    @Test
    fun unknownPackageFailsClosed() {
        assertEquals(PackageClassification.UNKNOWN, PackageShieldFilter.classify(null).classification)
        assertTrue(PackageShieldFilter.isPackageBlocked(null))
        assertTrue(PackageShieldFilter.validateAction(" ").isFailure)
    }

    @Test
    fun knownSensitivePackagesAreBlocked() {
        assertTrue(PackageShieldFilter.isPackageBlocked("com.ftband.mono"))
        assertTrue(PackageShieldFilter.isPackageBlocked("ua.privatbank.ap24"))
        assertTrue(PackageShieldFilter.isPackageBlocked("ua.gov.diia.app"))
        assertTrue(PackageShieldFilter.isPackageBlocked("com.bitwarden.android"))
    }

    @Test
    fun sensitivePackageSegmentsAreBlocked() {
        assertTrue(PackageShieldFilter.isPackageBlocked("com.example.wallet.client"))
        assertTrue(PackageShieldFilter.isPackageBlocked("org.example.authenticator.mobile"))
    }

    @Test
    fun supportedMessengerIsTrusted() {
        assertTrue(PackageShieldFilter.isPackageTrusted("org.telegram.messenger"))
        assertFalse(PackageShieldFilter.isPackageBlocked("org.telegram.messenger"))
        assertTrue(PackageShieldFilter.validateAction("org.telegram.messenger").isSuccess)
    }

    @Test
    fun ordinaryUnknownApplicationIsUnclassifiedButNotDenied() {
        val assessment = PackageShieldFilter.classify("com.example.notes")
        assertEquals(PackageClassification.UNCLASSIFIED, assessment.classification)
        assertFalse(assessment.blocked)
    }
}

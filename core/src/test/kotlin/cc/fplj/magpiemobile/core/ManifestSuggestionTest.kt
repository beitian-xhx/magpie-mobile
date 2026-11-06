package cc.fplj.magpiemobile.core

import org.junit.jupiter.api.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class ManifestSuggestionTest {

    @Test
    fun correctManifestHasNoProblems() {
        val problems = ManifestSuggestion.check(
            ManifestSuggestion.Actual(
                allowBackup = false,
                hasFgsSpecialUsePermission = true,
                hasSpecialUseSubtypeProperty = true,
                serviceExported = false,
                hasInternetPermission = true,
            ),
        )
        assertEquals(emptyList(), problems)
    }

    @Test
    fun missingBackupFlagIsCaught() {
        val problems = ManifestSuggestion.check(
            ManifestSuggestion.Actual(true, true, true, false, true),
        )
        assertTrue(problems.any { it.contains("allowBackup") })
    }

    @Test
    fun missingSpecialUseDeclarationsCaught() {
        val problems = ManifestSuggestion.check(
            ManifestSuggestion.Actual(false, false, false, false, true),
        )
        assertTrue(problems.any { it.contains("SPECIAL_USE") })
        assertEquals(2, problems.size)
    }

    @Test
    fun exportedServiceCaught() {
        val problems = ManifestSuggestion.check(
            ManifestSuggestion.Actual(false, true, true, true, true),
        )
        assertTrue(problems.any { it.contains("exported") })
    }
}

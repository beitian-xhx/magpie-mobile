package cc.fplj.magpiemobile

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Test

class ProviderEditorPolicyTest {
    @Test fun newCatalogDefaultsToAllWithoutManualIds() {
        val p = ProviderEditorPolicy(true)
        assertEquals(listOf("a", "b"), p.applyCatalog(listOf("a", "b", "a")))
    }
    @Test fun editRefreshPreservesSavedMissingModelsAndDoesNotDirty() {
        val p = ProviderEditorPolicy(false, listOf("old"))
        assertEquals(listOf("old"), p.applyCatalog(listOf("new")))
        assertEquals(listOf("new", "old"), p.visibleIds())
        assertFalse(p.isAvailable("old"))
        assertEquals(null, p.editedModels())
    }
    @Test fun implicitAllCanDisplayCatalogWithoutChangingSavedSemantics() {
        val p = ProviderEditorPolicy(false, emptyList(), false)
        assertEquals(listOf("a"), p.applyCatalog(listOf("a")))
        assertEquals(null, p.editedModels())
        p.chooseNone(); assertEquals(emptyList<String>(), p.editedModels())
    }
    @Test fun disabledProviderIsNotSilentlyEnabledByDiscovery() {
        val p = ProviderEditorPolicy(false, emptyList(), true)
        assertEquals(emptyList<String>(), p.applyCatalog(listOf("a")))
        assertEquals(null, p.editedModels())
    }
    @Test fun touchedSelectionDoesNotGainNewModelsOnRefresh() {
        val p = ProviderEditorPolicy(true)
        p.applyCatalog(listOf("a", "b")); p.toggle("b", false)
        assertEquals(listOf("a"), p.applyCatalog(listOf("a", "b", "c")))
    }
}

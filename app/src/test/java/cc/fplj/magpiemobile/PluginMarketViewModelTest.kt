package cc.fplj.magpiemobile

import android.app.Application
import androidx.test.core.app.ApplicationProvider
import cc.fplj.magpiemobile.core.CuratedPluginCatalog
import cc.fplj.magpiemobile.core.EnginePaths
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import java.io.File

@RunWith(RobolectricTestRunner::class)
class PluginMarketViewModelTest {

    @Test
    fun initiallyLoadsVerifiedCatalogEntries() {
        val app = ApplicationProvider.getApplicationContext<Application>()
        val vm = PluginMarketViewModel(app)

        val state = vm.state.value
        assertEquals(3, state.entries.size)
        assertEquals(0, state.installedIds.size)
    }

    @Test
    fun installFromMarketUpdatesInstalledSet() {
        val app = ApplicationProvider.getApplicationContext<Application>()
        val vm = PluginMarketViewModel(app)

        val tempDir = File(app.filesDir, "test_market_layout")
        tempDir.mkdirs()
        val layout = EnginePaths.Layout(
            nativeLibraryDir = tempDir,
            homeDir = File(tempDir, "home"),
            cacheDir = File(tempDir, "cache"),
            configDir = File(tempDir, "config"),
        )
        vm.initialize(layout)

        val targetEntry = CuratedPluginCatalog.VERIFIED_PLUGINS.first()
        assertFalse(vm.state.value.installedIds.contains(targetEntry.id))

        vm.installFromMarket(targetEntry)
        val state = vm.state.value
        assertTrue(state.installedIds.contains(targetEntry.id))
        assertTrue(state.notice.contains(targetEntry.name))
    }
}

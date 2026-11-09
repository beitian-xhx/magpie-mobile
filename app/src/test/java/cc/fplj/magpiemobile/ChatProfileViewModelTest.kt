package cc.fplj.magpiemobile

import android.app.Application
import androidx.test.core.app.ApplicationProvider
import cc.fplj.magpiemobile.core.ChatProfile
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

@RunWith(RobolectricTestRunner::class)
class ChatProfileViewModelTest {

    @Test
    fun defaultProfileIsInitiallySelected() {
        val app = ApplicationProvider.getApplicationContext<Application>()
        val vm = ChatProfileViewModel(app)

        val state = vm.state.value
        assertEquals("default", state.selectedProfileId)
        assertEquals("通用助手", state.activeProfile.name)
        assertEquals(0.7, state.activeProfile.temperature, 0.001)
    }

    @Test
    fun saveAndSelectCustomProfile() {
        val app = ApplicationProvider.getApplicationContext<Application>()
        val vm = ChatProfileViewModel(app)

        vm.saveProfile(
            id = "creative",
            name = "创意写作",
            systemPrompt = "You are a creative writer.",
            temperature = 1.2,
            topP = 0.9,
            modelAlias = "gpt-creative"
        )

        val state = vm.state.value
        assertEquals("creative", state.selectedProfileId)
        assertEquals("创意写作", state.activeProfile.name)
        assertEquals(1.2, state.activeProfile.temperature, 0.001)
        assertEquals("gpt-creative", state.activeProfile.modelAlias)
        assertTrue(state.notice.contains("创意写作"))
    }

    @Test
    fun deleteCustomProfileRevertsToDefault() {
        val app = ApplicationProvider.getApplicationContext<Application>()
        val vm = ChatProfileViewModel(app)

        vm.saveProfile("temp", "Temp Profile", temperature = 0.5)
        assertEquals("temp", vm.state.value.selectedProfileId)

        vm.deleteProfile("temp")
        assertEquals("default", vm.state.value.selectedProfileId)
    }

    @Test
    fun deleteDefaultProfileSetsError() {
        val app = ApplicationProvider.getApplicationContext<Application>()
        val vm = ChatProfileViewModel(app)

        vm.deleteProfile("default")
        assertTrue(vm.state.value.error.contains("不允许删除"))
    }
}

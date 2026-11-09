package cc.fplj.magpiemobile

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import cc.fplj.magpiemobile.core.ChatProfile
import cc.fplj.magpiemobile.core.ChatProfileStore
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import java.io.File

data class ChatProfileUiState(
    val profiles: List<ChatProfile> = emptyList(),
    val selectedProfileId: String = ChatProfile.DEFAULT.id,
    val notice: String = "",
    val error: String = "",
) {
    val activeProfile: ChatProfile
        get() = profiles.firstOrNull { it.id == selectedProfileId } ?: ChatProfile.DEFAULT
}

/**
 * Manages Chat Profile & Agent Parameter Selection (P3-1/P3-2).
 */
class ChatProfileViewModel(application: Application) : AndroidViewModel(application) {

    private val store = ChatProfileStore(File(application.filesDir, "profiles.json"))
    private val mutable = MutableStateFlow(ChatProfileUiState(profiles = store.list()))
    val state = mutable.asStateFlow()

    fun selectProfile(id: String) {
        if (state.value.profiles.any { it.id == id }) {
            mutable.update { it.copy(selectedProfileId = id, notice = "已切换当前 Profile", error = "") }
        }
    }

    fun saveProfile(
        id: String,
        name: String,
        systemPrompt: String = "",
        temperature: Double = 0.7,
        topP: Double = 1.0,
        maxTokens: Int? = null,
        modelAlias: String = "",
    ) {
        try {
            val profile = ChatProfile(
                id = id.trim(),
                name = name.trim(),
                systemPrompt = systemPrompt.trim(),
                temperature = temperature,
                topP = topP,
                maxTokens = maxTokens,
                modelAlias = modelAlias.trim(),
            )
            store.save(profile)
            mutable.update {
                it.copy(
                    profiles = store.list(),
                    selectedProfileId = profile.id,
                    notice = "已保存 Profile: " + profile.name,
                    error = ""
                )
            }
        } catch (e: Exception) {
            mutable.update { it.copy(error = e.message ?: "保存 Profile 失败", notice = "") }
        }
    }

    fun deleteProfile(id: String) {
        if (store.delete(id)) {
            val updated = store.list()
            mutable.update {
                it.copy(
                    profiles = updated,
                    selectedProfileId = ChatProfile.DEFAULT.id,
                    notice = "已删除 Profile: $id",
                    error = ""
                )
            }
        } else {
            mutable.update { it.copy(error = "默认 Profile 不允许删除", notice = "") }
        }
    }

    fun clearNotice() {
        mutable.update { it.copy(notice = "", error = "") }
    }
}

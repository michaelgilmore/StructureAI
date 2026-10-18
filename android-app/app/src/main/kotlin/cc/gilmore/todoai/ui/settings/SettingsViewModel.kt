package cc.gilmore.todoai.ui.settings

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import cc.gilmore.todoai.data.local.AiModeStore
import cc.gilmore.todoai.data.local.VoiceLaunchPreferenceStore
import cc.gilmore.todoai.model.AiMode
import cc.gilmore.todoai.repository.AuthRepository
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

/**
 * [username] is read once (SettingsScreen only shows while already signed
 * in, per NavGraph's top-level auth gate); [signOut] flips
 * AuthRepository.isSignedIn, which NavGraph observes directly to route
 * back to the login screen — no navigation callback needed from here,
 * same as TodoListViewModel.signOut today.
 *
 * [aiMode]/[setMode] are groundwork for Phase 3 — see AiModeStore's and
 * AiMode's doc comments. SettingsScreen's Home chip stays `enabled = false`
 * today, so [setMode] has no reachable caller yet and [aiMode] will always
 * collect CLOUD; both are wired now so enabling the toggle later is a
 * SettingsScreen-only change (flip `enabled`, point the chip's `onClick`
 * at [setMode]) rather than new plumbing through the ViewModel/store.
 *
 * [autoListenOnLaunch]/[setAutoListenOnLaunch] are a live feature, unlike
 * the two above — see VoiceLaunchPreferenceStore's and PodScreen's doc
 * comments for what this switch actually does.
 */
class SettingsViewModel(
    private val authRepository: AuthRepository,
    private val aiModeStore: AiModeStore,
    private val voiceLaunchPreferenceStore: VoiceLaunchPreferenceStore,
) : ViewModel() {
    val username: String? = authRepository.currentUsername

    val aiMode: StateFlow<AiMode> = aiModeStore.mode
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), AiMode.CLOUD)

    val autoListenOnLaunch: StateFlow<Boolean> = voiceLaunchPreferenceStore.autoListenOnLaunch
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), false)

    fun signOut() {
        authRepository.signOut()
    }

    fun setMode(mode: AiMode) {
        viewModelScope.launch { aiModeStore.setMode(mode) }
    }

    fun setAutoListenOnLaunch(enabled: Boolean) {
        viewModelScope.launch { voiceLaunchPreferenceStore.setAutoListenOnLaunch(enabled) }
    }
}

package ai.deepseek.dsh.android.feature.settings

import ai.deepseek.dsh.android.domain.SyncTuning
import ai.deepseek.dsh.android.domain.repository.BackendRepository
import ai.deepseek.dsh.android.domain.repository.DiagnosticsRepository
import ai.deepseek.dsh.android.domain.repository.SyncRequestPort
import ai.deepseek.dsh.android.domain.repository.toDomainError
import ai.deepseek.dsh.android.model.BackendHealth
import ai.deepseek.dsh.android.model.ServerId
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

/**
 * 设置页只读视图模型：服务器、后台同步开关与本机诊断计数。
 *
 * 探活失败收敛为 [ProbeUiState.Failed] 的 [ai.deepseek.dsh.android.domain.repository.DomainError]，
 * 不抛异常、不删除列表。
 */
class SettingsViewModel(
    private val backendRepository: BackendRepository,
    private val diagnosticsRepository: DiagnosticsRepository,
    private val syncRequestPort: SyncRequestPort,
    private val tuning: SyncTuning,
) : ViewModel() {

    private val _uiState = MutableStateFlow(SettingsUiState())
    val uiState: StateFlow<SettingsUiState> = _uiState.asStateFlow()

    init {
        viewModelScope.launch {
            backendRepository.servers()
                .catch { _uiState.update { it.copy(servers = ServersUiState.Unavailable) } }
                .collect { descriptors ->
                    val probes = currentProbes()
                    val items = descriptors.map {
                        it.toUiItem(tuning, probes[it.serverId] ?: ProbeUiState.Idle)
                    }
                    _uiState.update {
                        it.copy(
                            servers = if (items.isEmpty()) ServersUiState.Empty else ServersUiState.Content(items),
                        )
                    }
                }
        }
        viewModelScope.launch {
            syncRequestPort.isBackgroundSyncEnabled()
                .catch { _uiState.update { it.copy(backgroundSync = BackgroundSyncUiState.Unavailable) } }
                .collect { enabled ->
                    _uiState.update {
                        it.copy(
                            backgroundSync = if (enabled) {
                                BackgroundSyncUiState.Enabled
                            } else {
                                BackgroundSyncUiState.Disabled
                            },
                        )
                    }
                }
        }
        viewModelScope.launch {
            diagnosticsRepository.snapshot()
                .catch { _uiState.update { it.copy(diagnostics = DiagnosticsUiState.Empty) } }
                .collect { snapshot ->
                    _uiState.update { it.copy(diagnostics = snapshot.toDiagnosticsUiState(tuning)) }
                }
        }
    }

    /** 立即探活一台服务器；结果只影响该行，列表本身保持不变。 */
    fun probe(serverId: ServerId) {
        val current = _uiState.value.servers as? ServersUiState.Content ?: return
        val item = current.items.firstOrNull { it.serverId == serverId } ?: return
        if (item.probe == ProbeUiState.Running) return
        updateProbe(serverId, ProbeUiState.Running)
        viewModelScope.launch {
            val result: Result<BackendHealth?> = try {
                backendRepository.probeNow(serverId)
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (failure: Throwable) {
                Result.failure(failure.toDomainError())
            }
            updateProbe(
                serverId,
                result.fold(
                    onSuccess = { ProbeUiState.Success },
                    onFailure = { ProbeUiState.Failed(it.toDomainError()) },
                ),
            )
        }
    }

    private fun currentProbes(): Map<ServerId, ProbeUiState> =
        (_uiState.value.servers as? ServersUiState.Content)
            ?.items
            ?.associate { it.serverId to it.probe }
            .orEmpty()

    private fun updateProbe(serverId: ServerId, probe: ProbeUiState) {
        _uiState.update { state ->
            val servers = state.servers
            if (servers !is ServersUiState.Content) return@update state
            state.copy(
                servers = ServersUiState.Content(
                    servers.items.map { if (it.serverId == serverId) it.copy(probe = probe) else it },
                ),
            )
        }
    }

    /** 由调用方注入仓储与调参；本模块不读 BuildConfig，也不构造传输层。 */
    class Factory(
        private val backendRepository: BackendRepository,
        private val diagnosticsRepository: DiagnosticsRepository,
        private val syncRequestPort: SyncRequestPort,
        private val tuning: SyncTuning,
    ) : ViewModelProvider.Factory {
        override fun <T : ViewModel> create(modelClass: Class<T>): T {
            require(modelClass.isAssignableFrom(SettingsViewModel::class.java)) {
                "Unknown ViewModel class: ${modelClass.name}"
            }
            @Suppress("UNCHECKED_CAST")
            val created = SettingsViewModel(
                backendRepository = backendRepository,
                diagnosticsRepository = diagnosticsRepository,
                syncRequestPort = syncRequestPort,
                tuning = tuning,
            ) as T
            return created
        }
    }
}

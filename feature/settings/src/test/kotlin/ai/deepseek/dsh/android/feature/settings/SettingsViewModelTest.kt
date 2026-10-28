package ai.deepseek.dsh.android.feature.settings

import ai.deepseek.dsh.android.domain.SyncTuning
import ai.deepseek.dsh.android.domain.repository.BackendRepository
import ai.deepseek.dsh.android.domain.repository.DiagnosticsRepository
import ai.deepseek.dsh.android.domain.repository.DomainError
import ai.deepseek.dsh.android.domain.repository.ServerDescriptor
import ai.deepseek.dsh.android.domain.repository.SyncReason
import ai.deepseek.dsh.android.domain.repository.SyncRequestPort
import ai.deepseek.dsh.android.model.AuthState
import ai.deepseek.dsh.android.model.BackendCapabilities
import ai.deepseek.dsh.android.model.BackendHealth
import ai.deepseek.dsh.android.model.DiagnosticsSnapshot
import ai.deepseek.dsh.android.model.ServerId
import ai.deepseek.dsh.android.testing.Fixtures
import app.cash.turbine.test
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.test.TestDispatcher
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TestWatcher
import org.junit.runner.Description

@OptIn(ExperimentalCoroutinesApi::class)
class MainDispatcherRule(
    private val dispatcher: TestDispatcher = UnconfinedTestDispatcher(),
) : TestWatcher() {
    override fun starting(description: Description) {
        Dispatchers.setMain(dispatcher)
    }

    override fun finished(description: Description) {
        Dispatchers.resetMain()
    }
}

@OptIn(ExperimentalCoroutinesApi::class)
class SettingsViewModelTest {

    @get:Rule
    val mainDispatcherRule = MainDispatcherRule()

    @Test
    fun emptyServerListRendersTheRealEmptyState() = runTest {
        val viewModel = viewModel(backend = FakeBackend())
        advanceUntilIdle()
        viewModel.uiState.test {
            assertEquals(ServersUiState.Empty, awaitItem().servers)
        }
    }

    @Test
    fun probeFailureSurfacesTypedErrorAndKeepsTheList() = runTest {
        val backend = FakeBackend(
            descriptors = MutableStateFlow(listOf(descriptor(Fixtures.fullCapabilities()))),
            probeResult = Result.failure<BackendHealth?>(DomainError.Timeout),
        )
        val viewModel = viewModel(backend = backend)
        advanceUntilIdle()

        viewModel.probe(Fixtures.serverId)
        advanceUntilIdle()

        val content = viewModel.uiState.value.servers as ServersUiState.Content
        assertEquals(1, content.items.size)
        assertEquals(ProbeUiState.Failed(DomainError.Timeout), content.items.single().probe)
    }

    @Test
    fun emptyDiagnosticsSnapshotRendersEmptyInsteadOfZeros() = runTest {
        val viewModel = viewModel(
            backend = FakeBackend(),
            diagnostics = FakeDiagnostics(Fixtures.emptyDiagnostics()),
        )
        advanceUntilIdle()

        assertEquals(DiagnosticsUiState.Empty, viewModel.uiState.value.diagnostics)
    }

    @Test
    fun serverWithoutCapabilitiesHidesCapabilityRows() = runTest {
        val noCapabilities = Fixtures.capabilities(
            wsTicket = 0,
            eventPolling = 0,
            eventEpoch = 0,
            operations = 0,
            workspaceFiles = 0,
            resumableUploads = 0,
            fileInbox = 0,
            imagePromptTransport = 0,
        )
        val backend = FakeBackend(descriptors = MutableStateFlow(listOf(descriptor(noCapabilities))))
        val viewModel = viewModel(backend = backend)
        advanceUntilIdle()

        val item = (viewModel.uiState.value.servers as ServersUiState.Content).items.single()
        assertTrue(item.capabilities.isEmpty())
    }

    @Test
    fun capabilitiesMissingFromThePublishedTableAreHidden() = runTest {
        val capabilities = BackendCapabilities(
            wsTicket = 1,
            eventPolling = 1,
            eventEpoch = 1,
            operations = 1,
            workspaceFiles = 1,
            resumableUploads = 1,
            fileInbox = 1,
            imagePromptTransport = 1,
            raw = emptyMap(),
        )
        val backend = FakeBackend(descriptors = MutableStateFlow(listOf(descriptor(capabilities))))
        val viewModel = viewModel(backend = backend)
        advanceUntilIdle()

        val item = (viewModel.uiState.value.servers as ServersUiState.Content).items.single()
        assertTrue(item.capabilities.isEmpty())
    }

    @Test
    fun disabledBackgroundSyncRendersItsOwnState() = runTest {
        val viewModel = viewModel(
            backend = FakeBackend(descriptors = MutableStateFlow(listOf(descriptor(null)))),
            sync = FakeSync(enabled = false),
        )
        advanceUntilIdle()

        assertEquals(BackgroundSyncUiState.Disabled, viewModel.uiState.value.backgroundSync)
    }

    @Test
    fun storedBaseDropsQueryUserInfoAndFragment() {
        assertEquals(
            "https://gateway.test/path",
            sanitizedBaseUrl("https://user:secret@gateway.test/path?token=secret#fragment"),
        )
        assertEquals("wss://gateway.test/ws", sanitizedBaseUrl("wss://gateway.test/ws?ticket=secret"))
        assertEquals("gateway.test/path", sanitizedBaseUrl("user:secret@gateway.test/path?x=1"))
    }

    private fun viewModel(
        backend: BackendRepository,
        diagnostics: DiagnosticsRepository = FakeDiagnostics(Fixtures.emptyDiagnostics()),
        sync: SyncRequestPort = FakeSync(enabled = true),
    ): SettingsViewModel = SettingsViewModel(
        backendRepository = backend,
        diagnosticsRepository = diagnostics,
        syncRequestPort = sync,
        tuning = SyncTuning.DEFAULT,
    )

    private fun descriptor(capabilities: BackendCapabilities?) = ServerDescriptor(
        serverId = Fixtures.serverId,
        label = "Home",
        storedBase = "https://gateway.test/base?token=secret",
        lastKnownAuthState = AuthState.Connected,
        capabilities = capabilities,
    )
}

private class FakeBackend(
    private val descriptors: MutableStateFlow<List<ServerDescriptor>> = MutableStateFlow(emptyList()),
    private val probeResult: Result<BackendHealth?> = Result.success(null),
) : BackendRepository {
    override fun servers(): Flow<List<ServerDescriptor>> = descriptors

    override fun authState(serverId: ServerId): Flow<AuthState> = flowOf(AuthState.Unknown)

    override fun capabilities(serverId: ServerId): Flow<BackendCapabilities?> = flowOf(null)

    override suspend fun probeNow(serverId: ServerId): Result<BackendHealth?> = probeResult
}

private class FakeDiagnostics(
    private val snapshot: DiagnosticsSnapshot,
) : DiagnosticsRepository {
    override fun snapshot(): Flow<DiagnosticsSnapshot> = flowOf(snapshot)
}

private class FakeSync(
    private val enabled: Boolean,
) : SyncRequestPort {
    override suspend fun requestImmediate(serverId: ServerId, reason: SyncReason): Result<Unit> =
        Result.success(Unit)

    override suspend fun setForeground(active: Boolean): Result<Unit> = Result.success(Unit)

    override fun isBackgroundSyncEnabled(): Flow<Boolean> = flowOf(enabled)
}

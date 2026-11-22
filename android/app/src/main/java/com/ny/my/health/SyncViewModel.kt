package com.ny.my.health

import android.app.Application
import android.content.pm.ApplicationInfo
import android.util.Log
import androidx.health.connect.client.HealthConnectClient
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import androidx.work.WorkInfo
import com.ny.my.health.core.BlockReason
import com.ny.my.health.core.DayPhase
import com.ny.my.health.core.CommitStatus
import com.ny.my.health.core.CoverageSummary
import com.ny.my.health.core.ReadAccess
import com.ny.my.health.core.ReadScope
import com.ny.my.health.core.SyncCommit
import com.ny.my.health.core.SyncPlanner
import com.ny.my.health.core.SyncTrigger
import com.ny.my.health.core.AuthorizationDiagnostics
import com.ny.my.health.core.AuthorizationDiagnostics.Failure
import com.ny.my.health.core.DataType
import com.ny.my.health.core.DayWindow
import com.ny.my.health.core.DefaultNormalizer
import com.ny.my.health.core.HealthReader
import com.ny.my.health.core.PrivateDriveUploader
import com.ny.my.health.core.ProfileOutcome
import com.ny.my.health.core.ProfileStage
import com.ny.my.health.core.SyncCoordinator
import com.ny.my.health.core.SyncDiagnostics
import com.ny.my.health.core.ScopedHealthReader
import com.ny.my.health.core.DestinationGeneration
import com.ny.my.health.core.StateStatus
import com.ny.my.health.core.SyncStatus
import com.ny.my.health.core.VersionedCsvExporter
import java.time.Clock
import java.time.Instant
import java.time.LocalDate
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.emitAll
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

internal data class SyncUiState(
    val date: LocalDate = DayWindow.yesterday(Clock.systemUTC()),
    val availability: Int = HealthConnectClient.SDK_UNAVAILABLE,
    val grantedCount: Int = 0,
    val refreshing: Boolean = false,
    val pendingConsent: Boolean = false,
    val syncing: Boolean = false,
    val uploadOptIn: Boolean = false,
    val readyToUpload: Boolean = false,
    val message: String = "권한을 확인하고 비공개 폴더 업로드에 동의해 주세요.",
    val lastAttempt: Instant? = null,
    val lastSuccess: Instant? = null,
    val lastSuccessDate: LocalDate? = null,
    val lastSuccessCoverage: Set<DataType> = emptySet(),
    val uploadedFiles: Int = 0,
    val coverage: Set<DataType> = emptySet(),
    val history: HistorySessionState? = null,
    val transientRemoteStatus: SyncStatus? = null,
    val transientConfirmedFiles: Int? = null,
    val phoneProfiling: PhoneProfilingUiState = PhoneProfilingUiState(),
    val automaticEnabled: Boolean = false,
    val automaticKnown: Boolean = false,
    val automaticAvailable: Boolean = false,
    val automaticBusy: Boolean = false,
    val automaticCoverage: CoverageSummary? = null,
    val automaticWork: Set<WorkInfo.State> = emptySet(),
    val automaticBlockers: Set<BlockReason> = emptySet(),
    val automaticRemoteStale: Boolean = false,
    val automaticMessage: String? = null,
) {
    val busy: Boolean get() = refreshing || pendingConsent || syncing || history?.operation?.let { it != HistoryOperation.IDLE } == true
}

/** 현재 시도의 메모리 표시만 폐기한다. 영속 성공은 저장소 확인 전까지 유지한다. */
internal fun SyncUiState.invalidateCurrentAttempt(): SyncUiState = copy(
    uploadOptIn = false,
    readyToUpload = false,
    pendingConsent = false,
    transientRemoteStatus = null,
    transientConfirmedFiles = null,
    uploadedFiles = 0,
    coverage = emptySet(),
    phoneProfiling = PhoneProfilingUiState(enabled = phoneProfiling.enabled),
    message = "로컬 동의와 현재 시도 표시를 지웠습니다. 저장된 기록을 확인해 주세요.",
)

/** 요청 교체와 저장소 CAS 뒤 취소의 순서를 한 곳에서 유지한다. */
internal class AutomaticSettingsController(
    private val commit: suspend (DestinationGeneration, Boolean) -> SyncCommit,
    private val isCurrent: suspend (DestinationGeneration, Boolean) -> Boolean,
    private val cancel: (DestinationGeneration) -> Unit,
    private val schedule: (DestinationGeneration) -> Unit,
    private val catchUp: (DestinationGeneration) -> Unit,
) {
    @Volatile private var request = 0L
    @Volatile private var mutationJob: Job? = null
    fun invalidate(): Long {
        mutationJob?.cancel()
        return ++request
    }
    fun current(ticket: Long): Boolean = ticket == request
    fun cancelReplaced(previous: DestinationGeneration?, verified: DestinationGeneration?) {
        if (previous != null && verified != null && previous != verified) cancel(previous)
    }
    suspend fun change(ticket: Long, generation: DestinationGeneration, enabled: Boolean): Boolean = coroutineScope {
        if (!current(ticket)) return@coroutineScope false
        val child = async(start = CoroutineStart.LAZY) { changeCurrent(ticket, generation, enabled) }
        mutationJob = child
        try { child.await() } finally { if (mutationJob === child) mutationJob = null }
    }
    private suspend fun changeCurrent(ticket: Long, generation: DestinationGeneration, enabled: Boolean): Boolean {
        if (!current(ticket)) return false
        val result = commit(generation, enabled)
        if (result.status != CommitStatus.COMMITTED) return false
        if (!enabled) cancel(generation)
        val next = result.generation ?: return false
        if (!current(ticket) || !isCurrent(next, enabled) || !current(ticket)) return false
        if (enabled) {
            schedule(next)
            if (!current(ticket) || !isCurrent(next, true) || !current(ticket)) return false
            catchUp(next)
        }
        return true
    }
    suspend fun restore(ticket: Long, generation: DestinationGeneration, enabled: Boolean) {
        if (!enabled || !current(ticket) || !isCurrent(generation, true) || !current(ticket)) return
        schedule(generation)
        if (!current(ticket) || !isCurrent(generation, true) || !current(ticket)) return
        catchUp(generation)
    }
}

internal enum class AutomaticWorkRead { CURRENT, STALE, UNKNOWN }

/** 구독 오류와 저장소 확인 불가를 취소와 구별해 화면에 전달한다. */
internal suspend fun collectAutomaticWork(
    flow: Flow<List<WorkInfo>>, current: () -> Boolean,
    read: suspend () -> AutomaticWorkRead, publish: (Set<WorkInfo.State>) -> Unit,
    unknown: () -> Unit,
) {
    try {
        flow.collect { infos ->
            if (current()) when (read()) {
                AutomaticWorkRead.CURRENT -> if (current()) publish(infos.map { it.state }.toSet())
                AutomaticWorkRead.UNKNOWN -> if (current()) unknown()
                AutomaticWorkRead.STALE -> Unit
            }
        }
    } catch (cancelled: CancellationException) { throw cancelled }
    catch (_: Exception) { if (current()) unknown() }
}

internal fun automaticAccessAllowed(drive: Boolean, access: ReadAccess, categories: Set<DataType>): Boolean =
    drive && access.available && categories.any { type ->
        type in access.granted && (access.scopeByType[type] == ReadScope.SUMMARY ||
            (type == DataType.WEIGHT && access.scopeByType[type] == ReadScope.EXPLICIT_DAY))
    }

internal fun mayRequestBackgroundPermission(foreground: Boolean, state: SyncUiState,
    authorizationPending: Boolean, tokenPresent: Boolean): Boolean =
    foreground && !state.busy && !state.readyToUpload && !authorizationPending && !tokenPresent

internal enum class ManualDestination { DRIVE, LOCAL }
internal data class CommittedDestination(val generation: DestinationGeneration? = null,
    val kind: ManualDestination? = null, val selectionAllowed: Boolean = false)

class SyncViewModel internal constructor(application: Application,
    dependencies: ManualDestinationDependencies?,
    sessionFactory: ((() -> ManualSyncProfiling?, (ManualSyncProfiling) -> Unit,
        (ManualSyncProfiling, ProfileOutcome) -> Unit, () -> Unit) -> ManualDriveSession)?,
) : AndroidViewModel(application) {
    internal class ManualDestinationDependencies(
        val store: SqliteSyncStateStore, val reader: ScopedHealthReader,
        val execution: SyncExecutionSession, val configuration: SafTreeConfiguration, val clock: Clock,
    )
    constructor(application: Application) : this(application, null, null)
    internal constructor(application: Application, sessionFactory: ((() -> ManualSyncProfiling?, (ManualSyncProfiling) -> Unit,
        (ManualSyncProfiling, ProfileOutcome) -> Unit, () -> Unit) -> ManualDriveSession)?) : this(application, null, sessionFactory)

    private val debugEnabled = application.applicationInfo.flags and ApplicationInfo.FLAG_DEBUGGABLE != 0
    private val phoneProfilingSession = PhoneProfilingSession(debugEnabled)
    private val mutableState = MutableStateFlow(SyncUiState(phoneProfiling = phoneProfilingSession.state))
    private val reader = HealthConnectReader(application)
    private val clock = dependencies?.clock ?: Clock.systemUTC()
    private val syncStore = dependencies?.store ?: SqliteSyncStateStore(application)
    private val scopedReader = dependencies?.reader ?: reader
    private val execution = dependencies?.execution ?: SyncExecutionSession.get(application) {
        SyncExecutionSession(syncStore, reader, null, clock, { java.util.UUID.randomUUID().toString() })
    }
    internal val manual = sessionFactory?.invoke(::newBoundProfiling, ::boundProfileStarted, ::boundProfileFinished, ::boundProfileInvalidated)
        ?: ManualDriveSession(syncStore, scopedReader, execution, clock,
            ::newBoundProfiling, ::boundProfileStarted, ::boundProfileFinished, ::boundProfileInvalidated)
    internal val saf = ManualSafSession(syncStore, scopedReader, execution, application.contentResolver,
        dependencies?.configuration ?: SafTreeConfiguration(application.contentResolver), clock)
    private val mutableDestination = MutableStateFlow(ManualDestination.DRIVE)
    internal val selectedDestination = mutableDestination.asStateFlow()
    private var destinationEdited = false
    private val mutableCommittedDestination = MutableStateFlow(CommittedDestination())
    internal val committedDestination = mutableCommittedDestination.asStateFlow()
    private var destinationRead = 0L
    private var lastVerifiedGeneration: DestinationGeneration? = null
    private val automaticReader by lazy { AutomaticHealthReader(application) }
    private val scheduler by lazy { SyncWorkScheduler(application) }
    private var automaticWorkJob: Job? = null
    private val automaticController = AutomaticSettingsController(
        commit = { generation, enabled -> syncStore.setAutomaticCAS(generation, enabled, clock.instant()) },
        isCurrent = { generation, enabled ->
            val read = syncStore.load()
            val current = read.state
            read.status == StateStatus.READY && current?.generation == generation &&
                current?.configuration?.automatic == enabled &&
                current?.configuration?.destinationHandle == "private-drive" &&
                syncStore.readBinding(generation)?.let { it.kind == "DRIVE" && it.accountName != null && it.accountType != null && it.treeUri == null } == true &&
                syncStore.load().let { it.status == StateStatus.READY && it.state?.generation == generation && it.state?.configuration?.automatic == enabled }
        },
        cancel = { scheduler.cancel(it) }, schedule = { scheduler.schedule(it) }, catchUp = { scheduler.enqueueCatchUp(it) },
    )

    private suspend fun refreshAutomatic(restore: Boolean = false) {
        val ticket = automaticController.invalidate()
        automaticWorkJob?.cancel()
        try {
        val read = syncStore.load()
        val snapshot = read.state
        val config = snapshot?.configuration
        val generation = snapshot?.generation
        val drive = mutableCommittedDestination.value.kind == ManualDestination.DRIVE &&
            mutableCommittedDestination.value.generation == generation
        if (!automaticController.current(ticket)) return
        if (read.status != StateStatus.READY || config == null || generation == null) {
            val confirmedOff = read.status == StateStatus.ABSENT ||
                (read.status == StateStatus.READY && snapshot != null && config == null)
            mutableState.value = mutableState.value.copy(
                automaticKnown = confirmedOff,
                automaticEnabled = if (confirmedOff) false else mutableState.value.automaticEnabled,
                automaticAvailable = false, automaticCoverage = null, automaticWork = emptySet(),
                automaticBlockers = emptySet(), automaticRemoteStale = false,
                automaticMessage = if (confirmedOff) null else "자동 설정을 확인할 수 없습니다. 다시 확인하거나 저장소 복구를 진행해 주세요.")
            return
        }
        // 비활성 복원은 Health Connect와 scheduler를 열지 않는다.
        if (!config.automatic || !drive) {
            mutableState.value = mutableState.value.copy(automaticKnown = true, automaticEnabled = config.automatic,
                automaticAvailable = false, automaticCoverage = null, automaticWork = emptySet(),
                automaticBlockers = emptySet(), automaticRemoteStale = false, automaticMessage = if (config.automatic) "자동 Drive 구성을 확인할 수 없습니다." else null)
            return
        }
        val access = automaticReader.access(SyncTrigger.SCHEDULED)
        val scoped = syncStore.loadForScope(generation, LocalDate.now(clock.withZone(DayWindow.zone)), access)
        if (!automaticController.current(ticket)) return
        val verified = syncStore.load()
        if (!automaticController.current(ticket)) return
        if (verified.status != StateStatus.READY || verified.state == null || scoped.status != StateStatus.READY || scoped.state == null) {
            mutableState.value = mutableState.value.copy(automaticKnown = false, automaticAvailable = false,
                automaticCoverage = null, automaticWork = emptySet(),
                automaticMessage = "자동 상태를 확인하지 못했습니다. 다시 확인하거나 저장소 복구를 진행해 주세요.")
            return
        }
        if (verified.state?.generation != generation || verified.state?.configuration?.automatic != true) return
        val allowed = automaticAccessAllowed(drive, access, config.categories)
        val coverage = scoped.state?.takeIf { scoped.status == StateStatus.READY && it.generation == generation }
            ?.let { SyncPlanner.coverage(it, LocalDate.now(clock.withZone(DayWindow.zone))) }
        mutableState.value = mutableState.value.copy(automaticKnown = true, automaticEnabled = true, automaticAvailable = allowed,
            automaticCoverage = coverage, automaticWork = emptySet(),
            automaticBlockers = scoped.state?.days?.values?.mapNotNull { it.blocker }?.toSet().orEmpty(),
            automaticRemoteStale = scoped.state?.days?.values?.any { it.phase == DayPhase.REMOTE_STALE } == true,
            automaticMessage = if (allowed) null else "백그라운드 기능 또는 읽기 권한을 확인해 주세요.")
        automaticWorkJob = viewModelScope.launch {
            collectAutomaticWork(flow { emitAll(scheduler.workInfos(generation)) }, { automaticController.current(ticket) },
                read = {
                    val latest = syncStore.load()
                    when {
                        latest.status != StateStatus.READY || latest.state == null -> AutomaticWorkRead.UNKNOWN
                        latest.state?.generation != generation || latest.state?.configuration?.automatic != true -> AutomaticWorkRead.STALE
                        else -> AutomaticWorkRead.CURRENT
                    }
                },
                publish = { mutableState.value = mutableState.value.copy(automaticWork = it) },
                unknown = { mutableState.value = mutableState.value.copy(automaticKnown = false,
                    automaticAvailable = false, automaticCoverage = null, automaticWork = emptySet(),
                    automaticMessage = "자동 상태를 확인하지 못했습니다. 다시 확인하거나 저장소 복구를 진행해 주세요.") },
            )
        }
        if (restore) automaticController.restore(ticket, generation, true)
        } catch (cancelled: CancellationException) { throw cancelled }
        catch (_: Exception) {
            if (automaticController.current(ticket)) mutableState.value = mutableState.value.copy(
                automaticKnown = false, automaticAvailable = false, automaticCoverage = null, automaticWork = emptySet(),
                automaticBlockers = emptySet(), automaticRemoteStale = false,
                automaticMessage = "자동 상태를 확인하지 못했습니다. 다시 확인해 주세요.")
        }
    }

    internal fun setAutomatic(enabled: Boolean) {
        if (!foreground || !mutableState.value.automaticKnown || mutableState.value.automaticBusy || mutableState.value.automaticEnabled == enabled) return
        val generation = mutableCommittedDestination.value.generation ?: return
        if (enabled && (mutableCommittedDestination.value.kind != ManualDestination.DRIVE ||
                mutableDestination.value != ManualDestination.DRIVE || !manual.state.value.privateFolderConsent)) return
        val ticket = automaticController.invalidate()
        automaticWorkJob?.cancel()
        if (!enabled) { manual.setPrivateFolderConsent(false); saf.setPrivacyConsent(false) }
        mutableState.value = mutableState.value.copy(automaticBusy = true)
        viewModelScope.launch {
            try {
                if (enabled) {
                    val read = syncStore.load()
                    val config = read.state?.configuration
                    val access = automaticReader.access(SyncTrigger.SCHEDULED)
                    if (!foreground || !automaticController.current(ticket) || read.state?.generation != generation ||
                        config == null || config.categories != mutableCategories.value ||
                        !manual.state.value.privateFolderConsent || !automaticAccessAllowed(true, access, config.categories)) {
                        if (automaticController.current(ticket)) mutableState.value = mutableState.value.copy(automaticMessage = "백그라운드 기능과 읽기 권한을 확인해 주세요.")
                        return@launch
                    }
                }
                val changed = automaticController.change(ticket, generation, enabled)
                if (automaticController.current(ticket)) {
                    if (!changed) mutableState.value = mutableState.value.copy(automaticMessage = "설정을 저장하지 못했습니다. 다시 확인해 주세요.")
                    refreshDestinations()
                }
            } catch (cancelled: CancellationException) { throw cancelled }
            catch (_: Exception) {
                if (automaticController.current(ticket)) mutableState.value = mutableState.value.copy(automaticMessage = "자동 설정을 확인하지 못했습니다.")
            } finally { mutableState.value = mutableState.value.copy(automaticBusy = false) }
        }
    }
    internal fun changeDestination(destination: ManualDestination) {
        if (mutableDestination.value == destination) return
        destinationEdited = true
        manual.setPrivateFolderConsent(false)
        saf.setPrivacyConsent(false)
        mutableDestination.value = destination
    }
    private suspend fun refreshCommittedDestination() {
        val request = ++destinationRead
        val previous = lastVerifiedGeneration
        mutableCommittedDestination.value = CommittedDestination()
        val result = try {
            val read = syncStore.load()
            val state = read.state
            if (read.status == StateStatus.ABSENT) CommittedDestination(selectionAllowed = true)
            else if (read.status != StateStatus.READY || state == null) CommittedDestination()
            else if (state.configuration == null) CommittedDestination(state.generation, selectionAllowed = true)
            else {
                val binding = syncStore.readBinding(state.generation)
                val kind = when {
                    binding?.kind == "LOCAL" && binding.treeUri != null && binding.accountName == null && binding.accountType == null -> ManualDestination.LOCAL
                    binding?.kind == "DRIVE" && binding.treeUri == null && binding.accountName != null && binding.accountType != null &&
                        BoundDriveAuthorization.pickerCandidate(true, 1, 1, true, binding.accountName, binding.accountType) != null -> ManualDestination.DRIVE
                    else -> null
                }
                val verified = syncStore.load()
                if (verified.status == StateStatus.READY && verified.state?.generation == state.generation)
                    CommittedDestination(state.generation, kind, kind != null) else CommittedDestination()
            }
        } catch (cancelled: CancellationException) { throw cancelled }
        catch (_: Exception) { CommittedDestination() }
        if (request == destinationRead) {
            mutableCommittedDestination.value = result
            if (result.generation != null) {
                lastVerifiedGeneration = result.generation
                automaticController.cancelReplaced(previous, result.generation)
            }
        }
    }
    private suspend fun refreshDestinations() {
        manual.refresh()
        saf.refresh()
        refreshCommittedDestination()
        if (!destinationEdited) mutableCommittedDestination.value.kind?.let { mutableDestination.value = it }
        refreshAutomatic()
    }
    internal fun selectSafTree(bridge: ManualSafBridge) {
        if (bridge.isBusy()) return
        viewModelScope.launch {
            val ticket = saf.beginTreeSelection() ?: return@launch
            bridge.selectTree(ticket) { uri, flags ->
                viewModelScope.launch { saf.acceptTree(ticket, uri, flags); refreshDestinations() }
            }
        }
    }
    internal fun continueSaf() { viewModelScope.launch { saf.continueExport(); manual.refresh() } }
    internal fun retrySaf() { viewModelScope.launch { saf.retry(); manual.refresh() } }
    internal fun resetSaf(expected: DestinationGeneration) {
        automaticController.invalidate()
        manual.setPrivateFolderConsent(false)
        saf.setPrivacyConsent(false)
        viewModelScope.launch { saf.reset(expected); refreshDestinations() }
    }
    private val mutableCategories = MutableStateFlow(DataType.entries.toSet())
    private var categoriesEdited = false
    internal val manualCategories = mutableCategories.asStateFlow()
    private var manualJob: Job? = null
    private var resumeJob: Job? = null

    internal fun changeManualCategories(categories: Set<DataType>) {
        if (manual.state.value.phase != ManualDrivePhase.RUNNING) {
            categoriesEdited = true
            mutableCategories.value = categories.toSet()
        }
    }

    /** 초기 읽기보다 늦게 도착한 저장 구성은 사용자의 새 선택을 덮어쓰지 않는다. */
    internal fun restoreInitialManualCategories(categories: Set<DataType>) {
        if (!categoriesEdited) mutableCategories.value = categories.toSet()
    }

    internal fun selectManualAccount(bridge: ManualDriveBridge) {
        if (bridge.isBusy()) return
        val categories = mutableCategories.value.toSet()
        viewModelScope.launch {
            val picker = manual.beginAccountSelection() ?: return@launch
            bridge.selectAccount(picker) { ok, extras, name, type ->
                viewModelScope.launch {
                    // 계정 선택을 시작할 때의 범위만 명시적 구성 변경으로 저장한다.
                    val ticket = manual.selectAccount(ok, picker, extras, name, type, categories)
                    if (ticket != null) authorizeManualTicket(bridge, ticket)
                    refreshDestinations()
                }
            }
        }
    }

    internal fun authorizeManual(bridge: ManualDriveBridge) {
        if (bridge.isBusy()) return
        if (mutableCategories.value != manual.state.value.categories) return
        viewModelScope.launch {
            val ticket = manual.beginAuthorization() ?: return@launch
            authorizeManualTicket(bridge, ticket)
        }
    }

    private var dispatchingTicket: BoundDriveAuthorization.Ticket? = null
    internal fun mayDispatchManualAuthorization(ticket: BoundDriveAuthorization.Ticket): Boolean {
        val current = manual.state.value
        return dispatchingTicket === ticket && mutableDestination.value == ManualDestination.DRIVE &&
            current.foreground && current.privateFolderConsent && current.generation == ticket.generation &&
            current.grantedCategories.isNotEmpty() && mutableCategories.value == current.categories
    }

    private fun authorizeManualTicket(bridge: ManualDriveBridge, ticket: BoundDriveAuthorization.Ticket) {
        dispatchingTicket = ticket
        try {
            if (!mayDispatchManualAuthorization(ticket) || bridge.isBusy()) return
            bridge.authorize(ticket) { resolution, token, scopes ->
                if (mutableCategories.value == manual.state.value.categories)
                    manual.acceptAuthorization(ticket, resolution, token, scopes)
            }
        } finally { dispatchingTicket = null }
    }

    internal fun continueManual() {
        if (mutableCategories.value != manual.state.value.categories ||
            manual.state.value.phase != ManualDrivePhase.READY || manualJob?.isActive == true) return
        manualJob = viewModelScope.launch { manual.continueManual() }
    }

    internal fun resetManualConfiguration() {
        automaticController.invalidate()
        manual.setPrivateFolderConsent(false)
        saf.setPrivacyConsent(false)
        categoriesEdited = true
        viewModelScope.launch {
            if (manual.resetConfiguration()) {
                mutableCategories.value = DataType.entries.toSet()
                refreshDestinations()
            }
        }
    }

    internal val state = mutableState.asStateFlow()
    private var foreground = false
    private var syncJob: Job? = null
    private var refreshJob: Job? = null
    private var manualProfiling: ManualSyncProfiling? = null
    // 토큰은 저장 상태, 번들, 기록 또는 로그에 넣지 않는다.
    private var accessToken: String? = null
    internal var authorizationTicket: Long? = null
        private set
    private val resolutionTickets = AuthorizationResolutionTickets()
    private val historySession = HistorySession(SqliteHistoryStore(application), viewModelScope) {
        manual.setPrivateFolderConsent(false)
        phoneProfilingSession.clear()
        finishProfiling(manualProfiling, ProfileOutcome.CANCELLED)
        accessToken = null
        authorizationTicket = null
        resolutionTickets.clear()
        mutableState.value = mutableState.value.invalidateCurrentAttempt()
    }
    private val effects = HistoryEffects(historySession)

    init {
        viewModelScope.launch {
            refreshDestinations()
            if (manual.state.value.configured) restoreInitialManualCategories(manual.state.value.categories)
        }
        viewModelScope.launch {
            historySession.state.collect { history ->
                val attempt = history.read.history?.attempt
                val success = history.read.history?.success
                mutableState.value = mutableState.value.copy(
                    history = history,
                    lastAttempt = attempt?.authorizationRequestedAt,
                    lastSuccess = success?.confirmedAt,
                    lastSuccessDate = success?.date,
                    lastSuccessCoverage = success?.coverage ?: emptySet(),
                    message = if (history.error != null) {
                        "로컬 기록을 확인하거나 저장하지 못했습니다. 이전 성공과 원격 결과는 다를 수 있습니다. 기록을 다시 읽어 확인해 주세요."
                    } else mutableState.value.message,
                )
            }
        }
        viewModelScope.launch { historySession.restore() }
    }

    internal fun resume(onManualRefreshed: () -> Unit = {}) {
        foreground = true
        manual.setForeground(true)
        saf.setForeground(true)
        resumeJob?.cancel()
        resumeJob = viewModelScope.launch {
            refreshDestinations()
            manual.refreshGrants()
            if (foreground && manual.state.value.foreground) { refreshAutomatic(restore = true); onManualRefreshed() }
        }
        refreshPermissions()
    }

    internal fun pause() {
        automaticController.invalidate()
        automaticWorkJob?.cancel()
        foreground = false
        resumeJob?.cancel()
        manual.setForeground(false)
        saf.setForeground(false)
        if (mutableState.value.syncing) {
            syncJob?.cancel()
            accessToken = null
            mutableState.value = mutableState.value.copy(
                readyToUpload = false,
                message = "중단됨: 앱이 전경을 벗어났습니다. 일부 파일은 업로드되었을 수 있습니다. 다시 수동 동기화해 주세요.",
            )
        }
        // 외부 동의 화면의 일시 정지는 읽기나 업로드를 시작하지 않는다.
    }

    internal fun refreshPermissions() {
        viewModelScope.launch { manual.refreshGrants(); saf.refresh() }
        refreshJob?.cancel()
        mutableState.value = mutableState.value.copy(refreshing = true)
        refreshJob = viewModelScope.launch {
            try {
                val availability = reader.availability()
                val granted = if (availability == HealthConnectClient.SDK_AVAILABLE) {
                    reader.grantedPermissions().intersect(HealthConnectReader.permissions).size
                } else 0
                mutableState.value = mutableState.value.copy(
                    availability = availability, grantedCount = granted, refreshing = false,
                    date = DayWindow.yesterday(clock),
                )
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (_: Exception) {
                mutableState.value = mutableState.value.copy(
                    refreshing = false, grantedCount = 0,
                    message = "Health Connect 권한을 확인할 수 없습니다. 다시 확인해 주세요.",
                )
            }
        }
    }

    internal fun optIn(value: Boolean) {
        if (mutableState.value.busy) return
        phoneProfilingSession.clear()
        finishProfiling(manualProfiling, ProfileOutcome.CANCELLED)
        accessToken = null
        mutableState.value = mutableState.value.copy(uploadOptIn = value, readyToUpload = false,
            phoneProfiling = phoneProfilingSession.state)
        if (authorizationTicket != null) viewModelScope.launch { historySession.reload() }
    }

    internal fun beginHealthConsent(): Boolean {
        val state = mutableState.value
        if (!foreground || state.busy || state.readyToUpload || authorizationTicket != null || state.availability != HealthConnectClient.SDK_AVAILABLE) return false
        accessToken = null
        mutableState.value = state.copy(pendingConsent = true, readyToUpload = false)
        return true
    }

    internal fun healthConsentReturned() {
        mutableState.value = mutableState.value.copy(
            pendingConsent = false,
            message = "권한 요청이 끝났습니다. 허용한 항목만 읽습니다. 0/5이면 권한이 필요합니다.",
        )
        refreshPermissions()
    }

    internal fun beginBackgroundPermissionRequest(): Boolean {
        val state = mutableState.value
        if (!mayRequestBackgroundPermission(foreground, state, authorizationTicket != null, accessToken != null)) return false
        mutableState.value = state.copy(pendingConsent = true)
        return true
    }

    internal fun backgroundPermissionUnavailable() {
        mutableState.value = mutableState.value.copy(
            automaticMessage = "백그라운드 읽기 기능을 사용할 수 없습니다. Health Connect 지원 여부를 확인해 주세요.")
    }

    internal fun backgroundPermissionLaunchFailed() {
        mutableState.value = mutableState.value.copy(pendingConsent = false,
            automaticMessage = "백그라운드 읽기 권한 화면을 열지 못했습니다. 다시 시도해 주세요.")
    }

    /** 백그라운드 권한 결과는 별도 확인이며 자동 설정이나 업로드를 시작하지 않는다. */
    internal fun backgroundPermissionReturned() {
        mutableState.value = mutableState.value.copy(pendingConsent = false)
        if (!foreground) return
        refreshPermissions()
        viewModelScope.launch { if (foreground) refreshAutomatic(restore = false) }
    }

    internal fun beginAuthorization(onReady: () -> Unit) {
        val state = mutableState.value
        if (!foreground || !entryGate().allowsAuthorization(authorizationTicket) || !state.uploadOptIn || state.readyToUpload) return
        if (state.availability != HealthConnectClient.SDK_AVAILABLE || state.grantedCount == 0) {
            mutableState.value = state.copy(message = "PERMISSION_REQUIRED: 사용 가능한 Health Connect 읽기 권한이 필요합니다.")
            return
        }
        accessToken = null
        val date = DayWindow.yesterday(clock)
        finishProfiling(manualProfiling, ProfileOutcome.CANCELLED)
        val profiling = ManualSyncProfiling().also { manualProfiling = it }
        phoneProfilingSession.begin(profiling)
        mutableState.value = state.copy(pendingConsent = true, readyToUpload = false,
            phoneProfiling = phoneProfilingSession.state,
            uploadedFiles = 0, coverage = emptySet(), date = date,
            transientRemoteStatus = null, transientConfirmedFiles = null,
            message = "Google Drive 권한 확인 중…")
        viewModelScope.launch {
            var historyOutcome = ProfileOutcome.FAILED
            profiling.collector.start(ProfileStage.AUTHORIZATION_HISTORY)
            try {
                val accepted = effects.authorize(date, clock.instant()) { ticket ->
                    historyOutcome = ProfileOutcome.COMPLETED
                    profiling.collector.finish(ProfileStage.AUTHORIZATION_HISTORY, historyOutcome)
                    profiling.authorizationStarted()
                    authorizationTicket = ticket
                    if (foreground) onReady()
                    else authorizationFailed(ticket, Failure.FOREGROUND_DEPARTURE)
                }
                if (!accepted) {
                    mutableState.value = mutableState.value.copy(pendingConsent = false)
                }
            } catch (cancelled: CancellationException) {
                historyOutcome = ProfileOutcome.CANCELLED
                throw cancelled
            } finally {
                profiling.collector.finish(ProfileStage.AUTHORIZATION_HISTORY, historyOutcome)
                if (historyOutcome != ProfileOutcome.COMPLETED) finishProfiling(profiling, historyOutcome)
            }
        }
    }

    internal fun authorizationReturned(ticket: Long, token: String?, scopes: List<String>) {
        if (ticket != authorizationTicket || !mutableState.value.pendingConsent) return
        resolutionTickets.clear()
        val failure = AuthorizationDiagnostics.resultFailure(token, DRIVE_SCOPE in scopes)
        if (failure != null) {
            authorizationFailed(ticket, failure)
            return
        }
        val profiling = manualProfiling
        profiling?.authorizationReturned(true)
        viewModelScope.launch {
            var accepted = false
            var outcome = ProfileOutcome.FAILED
            try {
                accepted = effects.acceptAuthorization(ticket) { accessToken = token }
                if (ticket != authorizationTicket) return@launch
                if (!accepted) authorizationTicket = null
                mutableState.value = mutableState.value.copy(pendingConsent = false, readyToUpload = accepted,
                    message = if (accepted) "Google 권한 확인됨. 아래 ‘업로드 계속’을 눌러 전경에서 읽기와 업로드를 시작하세요."
                        else "로컬 기록 저장을 확인하지 못했습니다. 기록을 다시 읽어 주세요.")
            } catch (cancelled: CancellationException) {
                outcome = ProfileOutcome.CANCELLED
                throw cancelled
            } finally {
                if (!accepted) finishProfiling(profiling, outcome)
            }
        }
    }

    internal fun healthConsentLaunchFailed() {
        accessToken = null
        mutableState.value = mutableState.value.copy(
            pendingConsent = false, readyToUpload = false,
            message = "HEALTH_CONSENT_LAUNCH_FAILED: Health Connect 권한 화면을 열지 못했습니다. 다시 시도해 주세요.",
        )
    }

    internal fun authorizationFailed(ticket: Long, failure: Failure, statusCode: Int? = null) {
        if (ticket != authorizationTicket || !mutableState.value.pendingConsent) return
        manualProfiling?.authorizationReturned(false)
        finishProfiling(manualProfiling, ProfileOutcome.FAILED)
        resolutionTickets.clear()
        accessToken = null
        mutableState.value = mutableState.value.copy(pendingConsent = false, readyToUpload = false,
            message = AuthorizationDiagnostics.message(failure, statusCode))
        viewModelScope.launch {
            historySession.authorizationFailed(ticket, failure, clock.instant())
            if (authorizationTicket == ticket) authorizationTicket = null
        }
    }

    internal fun continueUpload() {
        val state = mutableState.value
        val token = accessToken ?: return
        val ticket = authorizationTicket ?: return
        if (!foreground || state.busy || !state.readyToUpload || !state.uploadOptIn) return
        val selectedDate = DayWindow.yesterday(clock)
        accessToken = null
        if (historySession.state.value.read.history?.attempt?.date != selectedDate) {
            finishProfiling(manualProfiling, ProfileOutcome.CANCELLED)
            mutableState.value = state.copy(readyToUpload = false,
                phoneProfiling = phoneProfilingSession.state,
                message = "날짜가 바뀌었습니다. Google 권한을 새로 확인하고 업로드 계속을 눌러 주세요.")
            viewModelScope.launch { historySession.reload() }
            return
        }
        mutableState.value = state.copy(syncing = true, readyToUpload = false, date = selectedDate,
            message = "허용된 데이터 읽기 및 업로드 중…")
        val profiling = manualProfiling
        profiling?.executionStarted()
        syncJob = viewModelScope.launch {
            var profileOutcome = ProfileOutcome.FAILED
            try {
                val execution = effects.execute(ticket, selectedDate, clock.instant(), clock::instant, { foreground }, { outcome ->
                    mutableState.value = mutableState.value.copy(
                        uploadedFiles = outcome.result.uploadedFiles, coverage = outcome.coverage,
                        transientRemoteStatus = outcome.result.status,
                        transientConfirmedFiles = outcome.result.uploadedFiles,
                        message = when (outcome.result.status) {
                            SyncStatus.SUCCESS -> "허용된 ${outcome.coverage.size}/5개 항목 범위의 내보내기 업로드 확인됨. 비어 있거나 미허용인 데이터는 포함하지 않습니다."
                            SyncStatus.EMPTY -> "EMPTY: 허용된 범위에서 내보낼 기록이 없습니다. 업로드 성공이 아닙니다."
                            SyncStatus.PERMISSION_REQUIRED -> "PERMISSION_REQUIRED: 읽기 권한이 없습니다."
                            SyncStatus.FAILED -> SyncDiagnostics.failureMessage(outcome.result)
                        },
                    )
                }, profiling?.collector) { date ->
                    var coverage = emptySet<DataType>()
                    val foregroundReader = object : HealthReader {
                        override suspend fun read(date: LocalDate): com.ny.my.health.core.HealthSnapshot {
                            check(foreground)
                            val snapshot = reader.read(date)
                            currentCoroutineContext().ensureActive()
                            check(foreground)
                            coverage = snapshot.grantedTypes
                            return snapshot
                        }
                    }
                    val result = SyncCoordinator(foregroundReader, DefaultNormalizer(), VersionedCsvExporter(),
                        PrivateDriveUploader(GoogleDriveTransport(token), profiling?.collector), clock,
                        profiling?.collector).sync(date)
                    HistoryExecutionResult(result, coverage)
                }
                if (execution != null && execution.result.status in setOf(SyncStatus.SUCCESS, SyncStatus.EMPTY) &&
                    historySession.state.value.error == null) profileOutcome = ProfileOutcome.COMPLETED
            } catch (cancelled: CancellationException) {
                profileOutcome = ProfileOutcome.CANCELLED
                throw cancelled
            } finally {
                finishProfiling(profiling, profileOutcome)
                if (authorizationTicket == ticket) authorizationTicket = null
                mutableState.value = mutableState.value.copy(syncing = false)
            }
        }
        syncJob?.invokeOnCompletion { cause ->
            // 실행 전 취소된 coroutine에도 종료 관찰을 남긴다. 실행된 경우 finally가 먼저 끝난다.
            if (cause is CancellationException) finishProfiling(profiling, ProfileOutcome.CANCELLED)
        }
    }

    internal fun registerAuthorizationResolution(ticket: Long): Boolean {
        if (ticket != authorizationTicket || !mutableState.value.pendingConsent) return false
        return resolutionTickets.register(ticket)
    }

    internal fun consumeAuthorizationResolution(): Long? = resolutionTickets.consume()?.takeIf {
        it == authorizationTicket && mutableState.value.pendingConsent
    }

    private fun entryGate(): HistoryEntryGate {
        val state = mutableState.value
        return HistoryEntryGate(state.pendingConsent, state.refreshing, state.syncing, historySession.state.value)
    }

    internal fun reloadHistory() { viewModelScope.launch { historySession.reload() } }
    internal fun requestDeleteHistory() {
        if (!entryGate().allowsDeletion()) return
        historySession.requestDelete()
    }
    internal fun cancelDeleteHistory() { historySession.cancelDelete() }
    internal fun confirmDeleteHistory() {
        if (!entryGate().allowsDeletion()) return
        viewModelScope.launch { if (entryGate().allowsDeletion()) historySession.confirmDelete() }
    }
    internal fun retryDeleteHistory() {
        if (!entryGate().allowsDeletion()) return
        viewModelScope.launch { if (entryGate().allowsDeletion()) historySession.retryDelete() }
    }
    internal fun keepExistingHistory() { viewModelScope.launch { historySession.keepExistingAfterDeleteFailure() } }

    internal fun forget() { requestDeleteHistory() }

    internal fun copyPhoneProfiling(write: (String) -> Unit) {
        phoneProfilingSession.copy(write)
        mutableState.value = mutableState.value.copy(phoneProfiling = phoneProfilingSession.state)
    }

    private fun newBoundProfiling(): ManualSyncProfiling? = if (debugEnabled) ManualSyncProfiling() else null

    private fun boundProfileStarted(profile: ManualSyncProfiling) {
        phoneProfilingSession.begin(profile)
        mutableState.value = mutableState.value.copy(phoneProfiling = phoneProfilingSession.state)
    }

    private fun boundProfileInvalidated() {
        phoneProfilingSession.clear()
        mutableState.value = mutableState.value.copy(phoneProfiling = phoneProfilingSession.state)
    }

    private fun boundProfileFinished(profile: ManualSyncProfiling, @Suppress("UNUSED_PARAMETER") outcome: ProfileOutcome) {
        profile.publish(debugEnabled) { Log.d("SyncProfiling", it) }
        phoneProfilingSession.complete(profile)
        mutableState.value = mutableState.value.copy(phoneProfiling = phoneProfilingSession.state)
    }

    private fun finishProfiling(profiling: ManualSyncProfiling?, outcome: ProfileOutcome) {
        profiling ?: return
        profiling.finish(outcome)
        profiling.publish(debugEnabled) {
            Log.d("SyncProfiling", it)
        }
        if (manualProfiling === profiling) {
            phoneProfilingSession.complete(profiling)
            mutableState.value = mutableState.value.copy(phoneProfiling = phoneProfilingSession.state)
            manualProfiling = null
        }
    }

    override fun onCleared() {
        manual.setForeground(false)
        saf.setForeground(false)
        if (!mutableState.value.syncing) finishProfiling(manualProfiling, ProfileOutcome.CANCELLED)
        super.onCleared()
    }

    companion object {
        internal const val DRIVE_SCOPE = "https://www.googleapis.com/auth/drive.file"
    }
}

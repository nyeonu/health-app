package com.ny.my.health

import com.ny.my.health.core.*
import java.time.Clock
import java.time.LocalDate
import java.time.ZoneId
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

internal enum class ManualDrivePhase { UNCONFIGURED, AUTHORIZATION_REQUIRED, READY, RUNNING, BLOCKED }

/** 공개 표시는 계정·ticket·credential 없이 현재 V1 메타데이터만 전달한다. */
internal data class ManualDriveState(
    val phase: ManualDrivePhase = ManualDrivePhase.UNCONFIGURED,
    val generation: DestinationGeneration? = null,
    val configured: Boolean = false,
    val privateFolderConsent: Boolean = false,
    val foreground: Boolean = false,
    val grantedCategories: Set<DataType> = emptySet(),
    val categories: Set<DataType> = emptySet(),
    val enrolledDay: LocalDate? = null,
    val coverage: CoverageSummary? = null,
    val outcome: BatchStatus? = null,
)

/** JVM 대역은 저장·실행 경계만 교체한다. credential은 실행 transport 생성 이외의 경계로 전달하지 않는다. */
internal interface ManualDrivePorts {
    val store: SyncStateStore
    val reader: ScopedHealthReader
    suspend fun binding(generation: DestinationGeneration): BoundDriveAuthorization.AccountSelection?
    suspend fun configure(expected: DestinationGeneration?, configuration: SyncConfiguration,
        account: BoundDriveAuthorization.AccountSelection): SyncCommit
    suspend fun reopen(generation: DestinationGeneration): SyncCommit
    suspend fun runPrepared(generation: DestinationGeneration, prepare: suspend () -> DestinationSync): BatchOutcome
    fun transport(token: String): DriveTransport
    fun destination(transport: DriveTransport, generation: DestinationGeneration): StorageDestination
    fun profiledDestination(transport: DriveTransport, generation: DestinationGeneration,
        profiling: SyncProfiling?): StorageDestination = destination(transport, generation)
}

/** 외부 동의 복귀와 실행을 분리하고 전경의 명시 계속만 gate owner에게 전달한다. */
internal class ManualDriveSession(
    private val ports: ManualDrivePorts,
    private val clock: Clock,
    private val newProfiling: () -> ManualSyncProfiling? = { null },
    private val onProfileStarted: (ManualSyncProfiling) -> Unit = {},
    private val onProfileFinished: (ManualSyncProfiling, ProfileOutcome) -> Unit = { _, _ -> },
    private val onProfileInvalidated: () -> Unit = {},
) {
    constructor(sqliteStore: SqliteSyncStateStore, healthReader: ScopedHealthReader,
        execution: SyncExecutionSession, clock: Clock = Clock.systemUTC(),
        newProfiling: () -> ManualSyncProfiling? = { null },
        onProfileStarted: (ManualSyncProfiling) -> Unit = {},
        onProfileFinished: (ManualSyncProfiling, ProfileOutcome) -> Unit = { _, _ -> },
        onProfileInvalidated: () -> Unit = {},
    ) : this(object : ManualDrivePorts {
        override val store: SyncStateStore = sqliteStore
        override val reader: ScopedHealthReader = healthReader
        override suspend fun binding(generation: DestinationGeneration): BoundDriveAuthorization.AccountSelection? {
            val binding = sqliteStore.readBinding(generation) ?: return null
            if (binding.kind != "DRIVE" || binding.accountName == null || binding.accountType == null) return null
            return BoundDriveAuthorization.pickerCandidate(true, 1, 1, true, binding.accountName, binding.accountType)
        }
        override suspend fun configure(expected: DestinationGeneration?, configuration: SyncConfiguration,
            account: BoundDriveAuthorization.AccountSelection) = sqliteStore.configure(expected, configuration,
                PrivateDestinationBinding("DRIVE", account.name, account.type))
        override suspend fun reopen(generation: DestinationGeneration) = sqliteStore.reopenManualBlockers(generation)
        override suspend fun runPrepared(generation: DestinationGeneration, prepare: suspend () -> DestinationSync) =
            execution.runPrepared(SyncTrigger.MANUAL, generation, prepare)
        override fun transport(token: String): DriveTransport = GoogleDriveTransport(token)
        override fun destination(transport: DriveTransport, generation: DestinationGeneration): StorageDestination =
            DriveApiDestination(PrivateDriveUploader(transport, clock = clock), generation, sqliteStore, clock)
        override fun profiledDestination(transport: DriveTransport, generation: DestinationGeneration,
            profiling: SyncProfiling?): StorageDestination =
            DriveApiDestination(PrivateDriveUploader(transport, profiling = profiling, clock = clock), generation, sqliteStore, clock)
    }, clock, newProfiling, onProfileStarted, onProfileFinished, onProfileInvalidated)

    private val mutableState = MutableStateFlow(ManualDriveState())
    val state: StateFlow<ManualDriveState> = mutableState.asStateFlow()
    private var sequence = 0L
    private var epoch = 0L
    private var metadataSequence = 0L
    private var picker: Long? = null
    private var ticket: BoundDriveAuthorization.Ticket? = null
    private var authorization: BoundDriveAuthorization? = null
    private var ownerRunning = false
    private var ownerJob: Job? = null
    private var externalRequest: Long? = null
    private var profile: ManualSyncProfiling? = null

    fun setForeground(foreground: Boolean) {
        if (state.value.foreground != foreground) epoch++
        mutableState.value = state.value.copy(foreground = foreground)
        if (!foreground) {
            val retained = externalRequest
            externalRequest = null
            ownerJob?.cancel()
            authorization = null
            if (ticket?.id != retained) {
                if (!ownerRunning) finishProfile(ProfileOutcome.CANCELLED)
                ticket = null
            }
            if (picker != retained) picker = null
            if (!ownerRunning && state.value.configured)
                mutableState.value = state.value.copy(phase = ManualDrivePhase.AUTHORIZATION_REQUIRED)
        }
    }

    fun setPrivateFolderConsent(consent: Boolean) {
        if (state.value.privateFolderConsent != consent) epoch++
        mutableState.value = state.value.copy(privateFolderConsent = consent)
        if (!consent) {
            observe(onProfileInvalidated)
            ownerJob?.cancel()
            picker = null
            clearAuthorization()
        }
    }

    /** 실행 credential 없이 현재 요청의 외부 화면 이동 한 번만 허용한다. */
    fun expectExternalResult(requestId: Long): Boolean {
        if (!state.value.foreground || ownerRunning || authorization != null ||
            (ticket?.id != requestId && picker != requestId)) return false
        externalRequest = requestId
        return true
    }

    /** 시작·복원은 메타데이터만 읽으며 읽기/인가/업로드를 시작하지 않는다. */
    suspend fun refresh() {
        if (ownerRunning) return
        val metadataRequest = ++metadataSequence
        requestValue(additionalCurrent = { metadataSequence == metadataRequest }) { current ->
            val read = ports.store.load()
            requireRequest(current)
            check(read.status == StateStatus.ABSENT || read.status == StateStatus.READY)
            val metadataAccess = ports.reader.access(SyncTrigger.MANUAL)
            requireRequest(current)
            if (read.status == StateStatus.ABSENT) {
                clearAuthorization()
                mutableState.value = state.value.copy(configured = false, generation = null,
                    categories = emptySet(), enrolledDay = null, coverage = null,
                    grantedCategories = grantedCategories(metadataAccess), phase = ManualDrivePhase.UNCONFIGURED)
            } else {
                check(read.status == StateStatus.READY)
                val loaded = checkNotNull(read.state)
                val scoped = ports.store.loadForScope(loaded.generation, today(), metadataAccess)
                requireRequest(current)
                check(scoped.status == StateStatus.READY)
                val latest = checkNotNull(scoped.state)
                check(latest.generation == loaded.generation)
                if (latest.generation != state.value.generation) { epoch++; clearAuthorization() }
                val granted = grantedCategories(metadataAccess)
                if (granted.isEmpty()) clearAuthorization()
                val config = latest.configuration
                mutableState.value = state.value.copy(generation = latest.generation, configured = config != null,
                    categories = config?.categories.orEmpty(), enrolledDay = config?.enrolledDay,
                    grantedCategories = granted, coverage = latest.coverage,
                    phase = when {
                        config == null -> ManualDrivePhase.UNCONFIGURED
                        authorization != null -> ManualDrivePhase.READY
                        else -> ManualDrivePhase.AUTHORIZATION_REQUIRED
                    })
            }
        }
    }

    /** 권한 화면 복귀 때 같은 실제 범위의 메타데이터를 다시 투영한다. */
    suspend fun refreshGrants() = refresh()

    suspend fun beginAccountSelection(): Long? {
        if (ownerRunning) return null
        return requestValue { current ->
            check(permitted(current))
            requireRequest(current)
            clearAuthorization()
            (++sequence).also { picker = it }
        }
    }

    suspend fun selectAccount(resultOk: Boolean, returnedTicket: Long?, extrasPresent: Boolean,
        name: String?, type: String?, categories: Set<DataType>): BoundDriveAuthorization.Ticket? {
        if (ownerRunning || picker == null || picker != returnedTicket) return null
        return requestValue { current ->
            val selected = BoundDriveAuthorization.pickerCandidate(resultOk, picker, returnedTicket, extrasPresent, name, type)
            checkNotNull(selected)
            check(permitted(current) && categories.isNotEmpty() && categories.any { it in state.value.grantedCategories })
            requireRequest(current)
            val expected = state.value.generation
            val read = ports.store.load()
            requireRequest(current)
            check(read.status == StateStatus.ABSENT || read.status == StateStatus.READY)
            check(read.state?.generation == expected)
            val existing = read.state?.configuration
            val sameDestination = expected != null && existing?.categories == categories && ports.binding(expected) == selected
            requireRequest(current)
            val generation = if (sameDestination) checkNotNull(expected) else {
                val commit = ports.configure(expected, SyncConfiguration("private-drive", yesterday(), false, categories), selected)
                requireRequest(current)
                check(commit.status == CommitStatus.COMMITTED)
                checkNotNull(commit.generation)
            }
            val metadataAccess = ports.reader.access(SyncTrigger.MANUAL)
            requireRequest(current)
            val refreshed = ports.store.loadForScope(generation, today(), metadataAccess)
            requireRequest(current)
            check(refreshed.status == StateStatus.READY)
            val latest = checkNotNull(refreshed.state)
            check(latest.generation == generation)
            val config = checkNotNull(latest.configuration)
            epoch++
            picker = null
            clearAuthorization()
            mutableState.value = state.value.copy(generation = generation, configured = true,
                categories = config.categories, enrolledDay = config.enrolledDay, coverage = latest.coverage,
                grantedCategories = grantedCategories(metadataAccess),
                phase = ManualDrivePhase.AUTHORIZATION_REQUIRED)
            BoundDriveAuthorization.Ticket(++sequence, generation, selected).also { ticket = it; startProfile() }
        }
    }

    /** 같은 목적지의 재인가에는 configure/invalidate를 호출하지 않는다. */
    suspend fun beginAuthorization(): BoundDriveAuthorization.Ticket? {
        if (ownerRunning) return null
        return requestValue { current ->
            check(permitted(current))
            requireRequest(current)
            val generation = checkNotNull(state.value.generation)
            check(state.value.configured)
            val selected = ports.binding(generation)
            requireRequest(current)
            checkNotNull(selected)
            clearAuthorization()
            BoundDriveAuthorization.Ticket(++sequence, generation, selected).also { ticket = it; startProfile() }
        }
    }

    fun acceptAuthorization(returned: BoundDriveAuthorization.Ticket, hasResolution: Boolean,
        token: String?, grantedScopes: Collection<String>): Boolean {
        if (ownerRunning || returned != ticket || returned.generation != state.value.generation ||
            !state.value.foreground || !state.value.privateFolderConsent ||
            state.value.grantedCategories.isEmpty()) return false
        val accepted = BoundDriveAuthorization.acceptResult(returned, ticket, state.value.generation,
            hasResolution, token, grantedScopes)
        externalRequest = null
        authorization = accepted
        observe { profile?.authorizationReturned(accepted != null) }
        if (accepted == null) finishProfile(ProfileOutcome.FAILED)
        mutableState.value = state.value.copy(phase = if (accepted != null) ManualDrivePhase.READY
            else ManualDrivePhase.AUTHORIZATION_REQUIRED)
        return accepted != null
    }

    suspend fun continueManual(): BatchOutcome? = safelyValue {
        val generation = checkNotNull(state.value.generation)
        var ownsPreparation = false
        var outcome = ProfileOutcome.FAILED
        try {
            ports.runPrepared(generation) {
                ownsPreparation = true
                ownerRunning = true
                epoch++
                ownerJob = currentCoroutineContext()[Job]
                observe { profile?.executionStarted() }
                mutableState.value = state.value.copy(phase = ManualDrivePhase.RUNNING, outcome = null)
                val currentTicket = checkNotNull(ticket)
                val credential = checkNotNull(authorization)
                val capturedEpoch = epoch
                check(currentTicket == credential.ticket && currentTicket.generation == generation)
                checkCurrent(currentTicket, capturedEpoch)
                credential.withVerifiedTransport({ isCurrent(currentTicket, capturedEpoch) }, ports::transport) { transport ->
                    checkCurrent(currentTicket, capturedEpoch)
                    check(ports.reopen(generation).status == CommitStatus.COMMITTED)
                    checkCurrent(currentTicket, capturedEpoch)
                    DestinationSync(ports.store, ports.reader,
                        ports.profiledDestination(transport, generation, profile?.collector), clock, profile?.collector)
                }
            }.also { result ->
                outcome = if (result.status == BatchStatus.COMPLETED) ProfileOutcome.COMPLETED else ProfileOutcome.FAILED
                if (!ownsPreparation && !ownerRunning && result.status != BatchStatus.MERGED) finishProfile(outcome)
                mutableState.value = state.value.copy(outcome = result.status, coverage = result.coverage ?: state.value.coverage)
            }
        } catch (cancelled: CancellationException) {
            outcome = ProfileOutcome.CANCELLED
            if (!ownsPreparation && !ownerRunning) finishProfile(outcome)
            throw cancelled
        } catch (failure: Throwable) {
            if (!ownsPreparation && !ownerRunning) finishProfile(ProfileOutcome.FAILED)
            throw failure
        } finally {
            if (ownsPreparation) {
                finishProfile(outcome)
                ownerRunning = false
                ownerJob = null
                clearAuthorization()
            }
        }
    }

    /** V1 구성·coverage만 초기화한다. foundation 이력에는 접근하지 않는다. */
    suspend fun resetConfiguration(): Boolean = safelyValue {
        observe(onProfileInvalidated)
        val expected = state.value.generation
        epoch++
        ownerJob?.cancel()
        picker = null
        clearAuthorization()
        val commit = ports.store.reset(expected)
        check(commit.status == CommitStatus.COMMITTED)
        mutableState.value = ManualDriveState(generation = commit.generation, foreground = state.value.foreground)
        true
    } ?: false

    private suspend fun checkCurrent(expected: BoundDriveAuthorization.Ticket, capturedEpoch: Long) {
        check(isCurrent(expected, capturedEpoch))
        val read = ports.store.load()
        check(read.status == StateStatus.READY && read.state?.generation == expected.generation)
        val config = checkNotNull(read.state?.configuration)
        check(!config.automatic && config.categories == state.value.categories)
        check(ports.binding(expected.generation) == expected.account)
        check(permitted() && config.categories.any { it in state.value.grantedCategories })
        check(isCurrent(expected, capturedEpoch))
    }

    private fun isCurrent(expected: BoundDriveAuthorization.Ticket, capturedEpoch: Long) =
        epoch == capturedEpoch && ticket == expected && state.value.generation == expected.generation &&
            state.value.foreground && state.value.privateFolderConsent

    private suspend fun access(current: () -> Boolean = { true }): Set<DataType> {
        val access = ports.reader.access(SyncTrigger.MANUAL)
        requireRequest(current)
        val granted = grantedCategories(access)
        mutableState.value = state.value.copy(grantedCategories = granted)
        if (granted.isEmpty()) clearAuthorization()
        return granted
    }
    private fun grantedCategories(access: ReadAccess): Set<DataType> =
        if (access.available) access.granted.filterTo(mutableSetOf()) {
            access.scopeByType[it] == ReadScope.EXPLICIT_DAY
        } else emptySet()

    private suspend fun permitted(current: () -> Boolean = { true }) =
        state.value.foreground && state.value.privateFolderConsent && access(current).isNotEmpty()

    private class SupersededRequest : RuntimeException()
    private fun requireRequest(current: () -> Boolean) {
        if (!current()) throw SupersededRequest()
    }
    private suspend fun <T> requestValue(additionalCurrent: () -> Boolean = { true },
        action: suspend (() -> Boolean) -> T): T? {
        val requestSequence = sequence
        val requestEpoch = epoch
        val generation = state.value.generation
        val current = { additionalCurrent() && !ownerRunning && sequence == requestSequence && epoch == requestEpoch &&
            state.value.generation == generation }
        return safelyValue(current) { action(current) }
    }
    private fun today() = LocalDate.now(clock.withZone(ZoneId.of("Asia/Seoul")))
    private fun yesterday() = today().minusDays(1)
    private fun startProfile() {
        profile = try { newProfiling() } catch (_: Throwable) { null }
        profile?.let { current ->
            observe { current.authorizationStarted() }
            observe { onProfileStarted(current) }
        }
    }

    private fun finishProfile(outcome: ProfileOutcome) {
        val current = profile ?: return
        profile = null
        observe { current.finish(outcome) }
        observe { onProfileFinished(current, outcome) }
    }

    private inline fun observe(action: () -> Unit) {
        try { action() } catch (_: Throwable) {
            // 진단 관찰 실패는 업무 결과와 실제 취소를 바꾸지 않는다.
        }
    }

    private fun clearAuthorization() {
        if (!ownerRunning) finishProfile(ProfileOutcome.CANCELLED)
        authorization = null
        ticket = null
        externalRequest = null
        if (state.value.configured && !ownerRunning)
            mutableState.value = state.value.copy(phase = ManualDrivePhase.AUTHORIZATION_REQUIRED)
    }
    private suspend fun safely(action: suspend () -> Unit) { safelyValue { action() } }
    private suspend fun <T> safelyValue(current: () -> Boolean = { true }, action: suspend () -> T): T? = try {
        action()
    } catch (cancelled: CancellationException) {
        throw cancelled
    } catch (_: SupersededRequest) {
        null
    } catch (_: Exception) {
        if (current()) {
            clearAuthorization()
            picker = null
            mutableState.value = state.value.copy(phase = ManualDrivePhase.BLOCKED)
        }
        null
    }
}

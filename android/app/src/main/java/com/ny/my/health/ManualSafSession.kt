package com.ny.my.health

import android.content.ContentResolver
import android.net.Uri
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

/** JVM에서는 SDK·proof를 만들지 않고 저장·선택·실행 경계만 대체한다. */
internal interface ManualSafPorts {
    val store: SyncStateStore
    val reader: ScopedHealthReader
    suspend fun binding(generation: DestinationGeneration): String?
    suspend fun validateSelection(treeUri: String, returnedFlags: Int, privacyConsent: Boolean)
    suspend fun configure(expected: DestinationGeneration?, configuration: SyncConfiguration, treeUri: String): SyncCommit
    suspend fun reopen(generation: DestinationGeneration): SyncCommit
    suspend fun runPrepared(generation: DestinationGeneration, prepare: suspend () -> DestinationSync): BatchOutcome
    suspend fun destination(treeUri: String, generation: DestinationGeneration, privacyConsent: Boolean): StorageDestination
}

/** 표시 복원·폴더 선택과 실제 내보내기를 분리한다. URI는 공개 상태에 담지 않는다. */
internal class ManualSafSession(private val ports: ManualSafPorts, private val clock: Clock) {
    constructor(sqliteStore: SqliteSyncStateStore, healthReader: ScopedHealthReader,
        execution: SyncExecutionSession, resolver: ContentResolver,
        configuration: SafTreeConfiguration = SafTreeConfiguration(resolver),
        clock: Clock = Clock.systemUTC(),
    ) : this(object : ManualSafPorts {
        override val store: SyncStateStore = sqliteStore
        override val reader: ScopedHealthReader = healthReader
        override suspend fun binding(generation: DestinationGeneration): String? {
            val binding = sqliteStore.readBinding(generation) ?: return null
            return binding.treeUri.takeIf { binding.kind == "LOCAL" && binding.accountName == null && binding.accountType == null }
        }
        override suspend fun validateSelection(treeUri: String, returnedFlags: Int, privacyConsent: Boolean) {
            configuration.select(Uri.parse(treeUri), returnedFlags, privacyConsent)
        }
        override suspend fun configure(expected: DestinationGeneration?, configuration: SyncConfiguration, treeUri: String) =
            sqliteStore.configure(expected, configuration, PrivateDestinationBinding("LOCAL", treeUri = treeUri))
        override suspend fun reopen(generation: DestinationGeneration) = sqliteStore.reopenManualBlockers(generation)
        override suspend fun runPrepared(generation: DestinationGeneration, prepare: suspend () -> DestinationSync) =
            execution.runPrepared(SyncTrigger.MANUAL, generation, prepare)
        override suspend fun destination(treeUri: String, generation: DestinationGeneration, privacyConsent: Boolean): StorageDestination {
            val proof = configuration.restore(treeUri, privacyConsent)
            return SafDestination(resolver, proof, generation, sqliteStore::loadKnownTargets, clock)
        }
    }, clock)

    private data class Picker(val id: Long, val generation: DestinationGeneration?,
        val categories: Set<DataType>, val input: Long, val mayPause: Boolean = true)
    private val mutableState = MutableStateFlow(ManualSafState())
    val state: StateFlow<ManualSafState> = mutableState.asStateFlow()
    private var epoch = 0L
    private var input = 0L
    private var sequence = 0L
    private var metadataSequence = 0L
    private var categoriesEdited = false
    private var metadataBinding: String? = null
    private var picker: Picker? = null
    private var selectionJob: Job? = null
    private var ownerRunning = false
    private var ownerJob: Job? = null

    fun setForeground(foreground: Boolean) {
        if (state.value.foreground == foreground) return
        epoch++
        mutableState.value = state.value.copy(foreground = foreground)
        if (!foreground) {
            selectionJob?.cancel()
            ownerJob?.cancel()
            picker = picker?.takeIf { it.mayPause }?.copy(mayPause = false)
        }
        projectPhase()
    }

    fun setPrivacyConsent(consent: Boolean) {
        if (state.value.privacyConsent == consent) return
        invalidateInput()
        mutableState.value = state.value.copy(privacyConsent = consent, outcome = null, phase = ManualSafPhase.UNCONFIGURED)
        projectPhase()
    }

    fun setCategories(categories: Set<DataType>) {
        categoriesEdited = true
        val copied = categories.toSet()
        if (state.value.intendedCategories == copied) return
        invalidateInput()
        mutableState.value = state.value.copy(intendedCategories = copied, outcome = null, phase = ManualSafPhase.UNCONFIGURED)
        projectPhase()
    }

    /** 메타데이터 접근만 허용하며 capability probe와 건강 기록 읽기는 하지 않는다. */
    suspend fun refresh() {
        if (ownerRunning) return
        val request = ++metadataSequence
        requestValue({ metadataSequence == request }) { current ->
            val read = ports.store.load()
            requireCurrent(current)
            check(read.status in setOf(StateStatus.ABSENT, StateStatus.READY))
            val access = ports.reader.access(SyncTrigger.MANUAL)
            requireCurrent(current)
            val loaded = read.state
            val binding = loaded?.takeIf { it.configuration != null }?.let { ports.binding(it.generation) }
            val local = binding != null
            requireCurrent(current)
            val latest = if (loaded != null) {
                val scoped = ports.store.loadForScope(loaded.generation, today(), access)
                requireCurrent(current)
                check(scoped.status == StateStatus.READY && scoped.state?.generation == loaded.generation)
                checkNotNull(scoped.state)
            } else null
            val config = latest?.configuration.takeIf { local }
            if (state.value.generation != latest?.generation) {
                epoch++
                input++
                picker = null
            }
            val previous = state.value
            val newCategories = config?.categories?.toSet().orEmpty()
            val newGrants = granted(access)
            val sameScope = previous.generation == latest?.generation && previous.configured == (config != null) &&
                previous.categories == newCategories && metadataBinding == binding &&
                previous.categories.intersect(previous.grantedCategories) == newCategories.intersect(newGrants)
            metadataBinding = binding
            mutableState.value = previous.copy(generation = latest?.generation,
                phase = if (sameScope) previous.phase else ManualSafPhase.UNCONFIGURED,
                configured = config != null, categories = newCategories,
                intendedCategories = if (categoriesEdited) state.value.intendedCategories else config?.categories?.toSet().orEmpty(),
                grantedCategories = newGrants, enrolledDay = config?.enrolledDay,
                coverage = latest?.coverage.takeIf { local }, outcome = if (sameScope) previous.outcome else null)
            if (config?.automatic == true) mutableState.value = state.value.copy(phase = ManualSafPhase.BLOCKED)
            else projectPhase()
        }
    }

    suspend fun beginTreeSelection(): Long? {
        if (ownerRunning) return null
        mutableState.value = state.value.copy(outcome = null, phase = ManualSafPhase.UNCONFIGURED)
        projectPhase()
        return requestValue { current ->
            check(state.value.foreground && state.value.privacyConsent && state.value.intendedCategories.isNotEmpty())
            freshAccess(current)
            check(state.value.intendedCategories.any { it in state.value.grantedCategories })
            requireCurrent(current)
            (++sequence).also { picker = Picker(it, state.value.generation, state.value.intendedCategories.toSet(), input) }
        }
    }

    suspend fun acceptTree(requestId: Long, uri: Uri?, returnedFlags: Int) =
        acceptTreeValue(requestId, uri?.toString(), returnedFlags)

    /** SDK 문자열 변환만 실제 facade에 남겨 JVM 수명 검증을 분리한다. */
    internal suspend fun acceptTreeValue(requestId: Long, treeUri: String?, returnedFlags: Int): Boolean {
        val selected = picker ?: return false
        if (selected.id != requestId || ownerRunning) return false
        picker = null
        if (treeUri == null) { projectPhase(); return false }
        val job = currentCoroutineContext()[Job]
        selectionJob = job
        return try {
            requestValue {
                current ->
                check(selected.generation == state.value.generation && selected.categories == state.value.intendedCategories && selected.input == input)
                check(state.value.foreground && state.value.privacyConsent)
                freshAccess(current)
                check(selected.categories.any { it in state.value.grantedCategories })
                val before = ports.store.load()
                requireCurrent(current)
                check(before.status in setOf(StateStatus.ABSENT, StateStatus.READY) && before.state?.generation == selected.generation)
                ports.validateSelection(treeUri, returnedFlags, true)
                requireCurrent(current)
                freshAccess(current)
                check(selected.categories.any { it in state.value.grantedCategories })
                val read = ports.store.load()
                requireCurrent(current)
                check(read.status in setOf(StateStatus.ABSENT, StateStatus.READY) && read.state?.generation == selected.generation)
                val existing = read.state?.configuration
                val oldTree = selected.generation?.let { ports.binding(it) }
                requireCurrent(current)
                val same = existing != null && existing.categories == selected.categories && oldTree == treeUri && !existing.automatic
                val generation = if (same) checkNotNull(selected.generation) else {
                    val commit = ports.configure(selected.generation,
                        SyncConfiguration("local-tree", today().minusDays(1), false, selected.categories), treeUri)
                    requireCurrent(current)
                    check(commit.status == CommitStatus.COMMITTED)
                    checkNotNull(commit.generation)
                }
                val access = ports.reader.access(SyncTrigger.MANUAL)
                requireCurrent(current)
                val scoped = ports.store.loadForScope(generation, today(), access)
                requireCurrent(current)
                check(scoped.status == StateStatus.READY && scoped.state?.generation == generation)
                val latest = checkNotNull(scoped.state)
                val configured = checkNotNull(latest.configuration)
                check(configured.categories == selected.categories)
                epoch++
                metadataBinding = treeUri
                mutableState.value = state.value.copy(generation = generation, configured = true,
                    phase = ManualSafPhase.UNCONFIGURED,
                    categories = selected.categories, grantedCategories = granted(access),
                    enrolledDay = configured.enrolledDay, coverage = latest.coverage, outcome = null)
                projectPhase()
                true
            } ?: false
        } finally { if (selectionJob === job) selectionJob = null }
    }

    suspend fun continueExport(): BatchOutcome? {
        val generation = state.value.generation ?: return null
        // 중복 caller는 기존 공용 gate에서만 merge하며 owner 상태를 끝내지 않는다.
        if (ownerRunning) return safelyValue({ false }) {
            ports.runPrepared(generation) { error("중복 실행 준비는 허용되지 않습니다") }
        }
        mutableState.value = state.value.copy(outcome = null)
        var owns = false
        var ownerCurrent: (() -> Boolean)? = null
        val beforeEpoch = epoch
        val beforeSequence = sequence
        val current = { !ownerRunning && epoch == beforeEpoch && sequence == beforeSequence && state.value.generation == generation }
        return try {
            safelyValue({ (ownerCurrent ?: current).invoke() }) {
                val categories = state.value.intendedCategories.toSet()
                val tree = checkStored(generation, categories, null, current)
                val result = ports.runPrepared(generation) {
                    requireCurrent(current)
                    owns = true
                    ownerRunning = true
                    ownerJob = currentCoroutineContext()[Job]
                    picker = null
                    epoch++
                    val runEpoch = epoch
                    val runInput = input
                    val runningCurrent = { epoch == runEpoch && input == runInput && state.value.generation == generation &&
                        state.value.foreground && state.value.privacyConsent && state.value.intendedCategories == categories }
                    ownerCurrent = runningCurrent
                    mutableState.value = state.value.copy(phase = ManualSafPhase.RUNNING, outcome = null)
                    checkStored(generation, categories, tree, runningCurrent)
                    val destination = ports.destination(tree, generation, true)
                    checkStored(generation, categories, tree, runningCurrent)
                    check(ports.reopen(generation).status == CommitStatus.COMMITTED)
                    checkStored(generation, categories, tree, runningCurrent)
                    DestinationSync(ports.store, ports.reader, destination, clock)
                }
                if ((ownerCurrent ?: current).invoke()) mutableState.value = state.value.copy(
                    outcome = result.status, coverage = result.coverage ?: state.value.coverage,
                    phase = if (result.status == BatchStatus.BLOCKED) ManualSafPhase.BLOCKED else state.value.phase)
                result
            }
        } finally {
            if (owns) {
                ownerRunning = false
                ownerJob = null
                if (state.value.phase != ManualSafPhase.BLOCKED) projectPhase()
            }
        }
    }

    suspend fun retry(): BatchOutcome? = continueExport()

    suspend fun reset(expected: DestinationGeneration): Boolean {
        if (state.value.generation != expected) return false
        invalidateInput()
        val captured = epoch
        return safelyValue({ epoch == captured && state.value.generation == expected }) {
            val commit = ports.store.reset(expected)
            if (epoch != captured || state.value.generation != expected) throw SupersededRequest()
            check(commit.status == CommitStatus.COMMITTED)
            mutableState.value = ManualSafState(generation = commit.generation, foreground = state.value.foreground)
            categoriesEdited = false
            metadataBinding = null
            true
        } ?: false
    }

    private suspend fun checkStored(generation: DestinationGeneration, categories: Set<DataType>, tree: String?,
        current: () -> Boolean): String {
        requireCurrent(current)
        check(state.value.foreground && state.value.privacyConsent && state.value.configured && categories.isNotEmpty() &&
            state.value.intendedCategories == categories && state.value.categories == categories)
        val read = ports.store.load()
        requireCurrent(current)
        check(read.status == StateStatus.READY && read.state?.generation == generation)
        val config = checkNotNull(read.state?.configuration)
        check(config.destinationHandle == "local-tree" && !config.automatic && config.categories == categories)
        val binding = checkNotNull(ports.binding(generation))
        requireCurrent(current)
        check(tree == null || binding == tree)
        freshAccess(current)
        check(categories.any { it in state.value.grantedCategories })
        requireCurrent(current)
        return binding
    }

    private suspend fun freshAccess(current: () -> Boolean) {
        val access = ports.reader.access(SyncTrigger.MANUAL)
        requireCurrent(current)
        val previous = state.value
        val newGrants = granted(access)
        val scopeChanged = previous.categories.intersect(previous.grantedCategories) != previous.categories.intersect(newGrants)
        mutableState.value = previous.copy(grantedCategories = newGrants,
            outcome = if (scopeChanged) null else previous.outcome)
    }
    private fun granted(access: ReadAccess) = if (!access.available) emptySet() else
        access.granted.filterTo(mutableSetOf()) { access.scopeByType[it] == ReadScope.EXPLICIT_DAY }
    private fun projectPhase() {
        if (ownerRunning) return
        val s = state.value
        mutableState.value = s.copy(phase = when {
            s.phase == ManualSafPhase.BLOCKED -> ManualSafPhase.BLOCKED
            !s.configured -> ManualSafPhase.UNCONFIGURED
            s.foreground && s.privacyConsent && s.intendedCategories == s.categories &&
                s.intendedCategories.any { it in s.grantedCategories } -> ManualSafPhase.READY
            else -> ManualSafPhase.SELECTION_REQUIRED
        })
    }
    private fun invalidateInput() {
        epoch++
        input++
        picker = null
        selectionJob?.cancel()
        ownerJob?.cancel()
    }
    private class SupersededRequest : RuntimeException()
    private fun requireCurrent(current: () -> Boolean) { if (!current()) throw SupersededRequest() }
    private suspend fun <T> requestValue(extra: () -> Boolean = { true }, action: suspend (() -> Boolean) -> T): T? {
        val capturedEpoch = epoch
        val capturedSequence = sequence
        val generation = state.value.generation
        val current = { extra() && !ownerRunning && epoch == capturedEpoch && sequence == capturedSequence &&
            state.value.generation == generation }
        return safelyValue(current) { action(current) }
    }
    private suspend fun <T> safelyValue(current: () -> Boolean, action: suspend () -> T): T? = try { action() }
        catch (cancel: CancellationException) { throw cancel }
        catch (_: SupersededRequest) { null }
        catch (_: Exception) {
            if (current()) mutableState.value = state.value.copy(phase = ManualSafPhase.BLOCKED)
            null
        }
    private fun today() = LocalDate.now(clock.withZone(ZoneId.of("Asia/Seoul")))
}

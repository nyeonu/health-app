package com.ny.my.health.core

import java.security.MessageDigest
import java.time.Clock
import java.time.LocalDate
import java.time.ZoneId
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext

/** 승인된 시도의 날짜와 범위를 고정하고 파일별 영속 확인만 집계한다. */
class DestinationSync(private val store: SyncStateStore, private val reader: ScopedHealthReader,
    private val destination: StorageDestination, private val clock: Clock) {
    private class Halt(val status: BatchStatus) : RuntimeException()
    private class DestinationFailed(val reason: BlockReason) : RuntimeException()

    suspend fun run(trigger: SyncTrigger, generation: DestinationGeneration, attemptId: String): BatchOutcome {
        var confirmed = 0
        var activeDay: DayState? = null
        var activeAttempt: AttemptState? = null
        fun mutation(day: DayState? = null, confirmation: FileConfirmation? = null,
            finish: java.time.Instant? = null) = SyncMutation(attemptId, null, day, confirmation, null, finish)
        suspend fun commit(value: SyncMutation) {
            when (store.transact(generation, value).status) {
                CommitStatus.COMMITTED -> Unit
                CommitStatus.STALE -> throw Halt(BatchStatus.STALE)
                else -> throw Halt(BatchStatus.BLOCKED)
            }
        }
        suspend fun current(): SyncState {
            val loaded = store.load()
            if (loaded.status != StateStatus.READY) throw Halt(BatchStatus.BLOCKED)
            val state = loaded.state ?: throw Halt(BatchStatus.BLOCKED)
            val attempt = state.attempt
            if (state.generation != generation || attempt?.id != attemptId || attempt.finishedAt != null ||
                (activeAttempt != null && (attempt.scopeKey != activeAttempt?.scopeKey ||
                    attempt.requestedScopes != activeAttempt?.requestedScopes || attempt.dates != activeAttempt?.dates))) {
                throw Halt(BatchStatus.STALE)
            }
            return state
        }
        suspend fun acknowledge(confirmation: FileConfirmation, intended: Map<FileKey, PlannedFile>) {
            currentCoroutineContext().ensureActive()
            current()
            if (confirmation.key !in intended) throw Halt(BatchStatus.STALE)
            val row = store.loadDay(generation, confirmation.key.date, confirmation.key.scopeKey)
                ?: throw Halt(BatchStatus.STALE)
            if (row.revision?.digest != confirmation.key.revision ||
                row.datasets[confirmation.key.dataset]?.revision?.digest != confirmation.key.revision) throw Halt(BatchStatus.STALE)
            val already = row.datasets.getValue(confirmation.key.dataset).phase == FilePhase.CONFIRMED
            commit(mutation(confirmation = confirmation))
            if (!already) confirmed++
            activeDay = store.loadDay(generation, row.date, row.scopeKey) ?: throw Halt(BatchStatus.STALE)
        }
        fun destinationFailure(error: Exception) = DestinationFailed(
            (error as? DestinationException)?.reason ?: BlockReason.DESTINATION_BLOCKED)
        suspend fun journalDestinationFailure(reason: BlockReason) {
            val active = activeDay ?: throw Halt(BatchStatus.BLOCKED)
            current()
            val latest = store.loadDay(generation, active.date, active.scopeKey) ?: throw Halt(BatchStatus.STALE)
            if (latest.revision != active.revision) throw Halt(BatchStatus.STALE)
            val files = latest.datasets.mapValues { (_, file) ->
                if (file.phase == FilePhase.RUNNING) file.copy(phase = FilePhase.INDETERMINATE) else file
            }
            val phase = if (reason != BlockReason.DESTINATION_TRANSIENT_FAILURE) DayPhase.BLOCKED
                else if (files.values.any { it.phase == FilePhase.INDETERMINATE }) DayPhase.INDETERMINATE
                else DayPhase.PENDING
            val failed = latest.copy(datasets = files, phase = phase, blocker = reason)
            commit(mutation(day = failed))
            activeDay = null
        }
        suspend fun journalInterrupted() {
            val day = activeDay ?: return
            val latest = store.loadDay(generation, day.date, day.scopeKey) ?: return
            if (latest.revision != day.revision) return
            val files = latest.datasets.mapValues { (_, file) ->
                if (file.phase == FilePhase.RUNNING) file.copy(phase = FilePhase.INDETERMINATE) else file
            }
            store.transact(generation, mutation(day = latest.copy(datasets = files,
                phase = if (files.values.any { it.phase == FilePhase.INDETERMINATE }) DayPhase.INDETERMINATE else latest.phase)))
        }
        try {
            val initial = current()
            val attempt = requireNotNull(initial.attempt)
            activeAttempt = attempt
            if (attempt.trigger != trigger || attempt.startedAt == null) return BatchOutcome(BatchStatus.STALE, 0, null)
            val access = reader.access()
            val config = initial.configuration ?: throw Halt(BatchStatus.STALE)
            if (SyncPlanner.scopeKey(config, access) != attempt.scopeKey) throw Halt(BatchStatus.STALE)
            val today = LocalDate.now(clock.withZone(ZoneId.of("Asia/Seoul")))
            val selected = store.loadForScope(generation, today, access)
            if (selected.status != StateStatus.READY) throw Halt(BatchStatus.BLOCKED)
            val state = selected.state ?: throw Halt(BatchStatus.BLOCKED)
            if (state.generation != generation || state.scopeKey != attempt.scopeKey ||
                state.requestedScopes != attempt.requestedScopes || SyncPlanner.plan(state, today, access).scopeKey != attempt.scopeKey) {
                throw Halt(BatchStatus.STALE)
            }
            // 자정이 지나 계획이 바뀌어도 세션이 영속 승인한 날짜만 실행한다.
            for (date in attempt.dates) {
                currentCoroutineContext().ensureActive()
                current()
                val previous = store.loadDay(generation, date, attempt.scopeKey) ?: DayState(date,
                    attempt.scopeKey, attempt.requestedScopes, null, DayPhase.PENDING, emptyMap(), null, emptyMap(), null)
                activeDay = previous
                // 승인 뒤 대기하거나 날짜별 처리 중 자정이 지나도 현재 읽기 경계를 지킨다.
                val currentToday = LocalDate.now(clock.withZone(ZoneId.of("Asia/Seoul")))
                if (date < config.enrolledDay || date >= currentToday) throw Halt(BatchStatus.STALE)
                if (date < currentToday.minusDays(30)) {
                    commit(mutation(day = previous.copy(phase = DayPhase.BLOCKED, checkedAt = null,
                        blocker = BlockReason.HISTORY_ACCESS_REQUIRED)))
                    activeDay = null
                    continue
                }
                val snapshot = try { reader.readScoped(date, attempt.requestedScopes) }
                catch (cancel: CancellationException) { throw cancel }
                catch (_: Exception) {
                    commit(mutation(day = previous.copy(phase = DayPhase.BLOCKED, checkedAt = null,
                        blocker = BlockReason.SOURCE_READ_FAILED)))
                    continue
                }
                val revision = SyncRevision.identify(snapshot, previous, clock.instant())
                if (snapshot.blockedTypes.isNotEmpty()) {
                    commit(mutation(day = previous.copy(phase = DayPhase.BLOCKED, scopes = revision.scopes,
                        checkedAt = null, blocker = BlockReason.SOURCE_ACCESS_REQUIRED)))
                    continue
                }
                fun prior(dataset: Dataset): FileConfirmation? {
                    val old = previous.datasets[dataset] ?: return null
                    return if (old.phase == FilePhase.CONFIRMED) FileConfirmation(
                        FileKey(generation, date, attempt.scopeKey, dataset, old.revision.digest),
                        requireNotNull(old.targetHandle), requireNotNull(old.confirmedAt)) else old.priorConfirmation
                }
                val files = revision.files.mapValues { (dataset, _) ->
                    val old = previous.datasets[dataset]
                    // 과거 확인은 이력이며 현재 원격 대상의 안전성은 매번 다시 검증한다.
                    DatasetState(dataset, revision.key,
                        if (old?.revision == revision.key && old.phase == FilePhase.CONFIRMED)
                            FilePhase.INDETERMINATE else FilePhase.PENDING,
                        true, old?.targetHandle, clock.instant(), null, prior(dataset))
                }.toMutableMap()
                for (dataset in revision.remoteStale) {
                    val old = previous.datasets.getValue(dataset)
                    files[dataset] = old.copy(revision = revision.key, phase = FilePhase.REMOTE_STALE,
                        populated = false, confirmedAt = null, priorConfirmation = prior(dataset))
                }
                var day = DayState(date, attempt.scopeKey, attempt.requestedScopes, revision.key, DayPhase.PENDING,
                    revision.scopes, clock.instant(), files, null)
                commit(mutation(day = day))
                activeDay = day
                val planned = revision.files.map { (dataset, file) ->
                    PlannedFile(
                        FileKey(generation, date, attempt.scopeKey, dataset, revision.key.digest), file,
                        MessageDigest.getInstance("SHA-256").digest(file.content.toByteArray(Charsets.UTF_8))
                            .joinToString("") { "%02x".format(it.toInt() and 255) }, files[dataset]?.targetHandle)
                }
                try {
                    if (planned.isNotEmpty()) {
                        current()
                        val intended = planned.associateBy { it.key }
                        val reconciled = try { destination.reconcile(planned) }
                        catch (cancel: CancellationException) { throw cancel }
                        catch (error: Exception) { throw destinationFailure(error) }
                        val returned = reconciled.confirmed.map { it.key } + reconciled.pending.map { it.key } + reconciled.blocked
                        if (returned.size != returned.distinct().size || returned.toSet() != intended.keys ||
                            reconciled.pending.any { intended[it.key] != it }) throw Halt(BatchStatus.BLOCKED)
                        if (reconciled.confirmed.isNotEmpty()) {
                            // 원격에서 검증됐지만 로컬 확인 커밋 전인 중간 상태다.
                            val adopted = reconciled.confirmed.map { it.key.dataset }.toSet()
                            day = day.copy(datasets = day.datasets.mapValues { (dataset, file) ->
                                if (dataset in adopted) file.copy(phase = FilePhase.INDETERMINATE) else file
                            })
                            commit(mutation(day = day)); activeDay = day
                        }
                        for (confirmation in reconciled.confirmed) acknowledge(confirmation, intended)
                        day = store.loadDay(generation, date, attempt.scopeKey) ?: throw Halt(BatchStatus.STALE)
                        if (reconciled.blocked.isNotEmpty()) {
                            day = day.copy(datasets = day.datasets.mapValues { (dataset, file) ->
                                if (FileKey(generation, date, attempt.scopeKey, dataset, revision.key.digest) in reconciled.blocked)
                                    file.copy(phase = FilePhase.BLOCKED) else file
                            }, blocker = BlockReason.DESTINATION_BLOCKED)
                            commit(mutation(day = day)); activeDay = day
                        }
                        if (reconciled.pending.isNotEmpty()) {
                            val pendingKeys = reconciled.pending.map { it.key.dataset }.toSet()
                            day = day.copy(phase = DayPhase.RUNNING, datasets = day.datasets.mapValues { (dataset, file) ->
                                if (dataset in pendingKeys) file.copy(phase = FilePhase.RUNNING, startedAt = clock.instant()) else file
                            })
                            // 저장소는 이 커밋에서 같은 원격 대상을 쓰는 다른 범위를 보수적으로 무효화한다.
                            commit(mutation(day = day)); activeDay = day
                            currentCoroutineContext().ensureActive()
                            current()
                            var callbackFailure: Exception? = null
                            try {
                                destination.writePending(reconciled.pending) {
                                    try { acknowledge(it, reconciled.pending.associateBy { f -> f.key }) }
                                    catch (error: Exception) { callbackFailure = error; throw error }
                                }
                            } catch (cancel: CancellationException) { throw cancel }
                            catch (error: Exception) {
                                // 콜백의 저장소·세대 오류는 목적지 transient로 분류하지 않는다.
                                callbackFailure?.let { throw it }
                                throw destinationFailure(error)
                            }
                            callbackFailure?.let { throw it }
                        }
                    }
                } catch (failure: DestinationFailed) {
                    journalDestinationFailure(failure.reason)
                    continue
                }
                day = store.loadDay(generation, date, attempt.scopeKey) ?: throw Halt(BatchStatus.STALE)
                val phase = when {
                    day.datasets.values.any { it.phase == FilePhase.REMOTE_STALE } -> DayPhase.REMOTE_STALE
                    day.blocker != null -> DayPhase.BLOCKED
                    day.datasets.isEmpty() -> DayPhase.EMPTY
                    day.datasets.values.all { it.phase == FilePhase.CONFIRMED } -> DayPhase.CONFIRMED
                    else -> DayPhase.INDETERMINATE
                }
                day = day.copy(phase = phase, datasets = day.datasets.mapValues { (_, file) ->
                    if (file.phase == FilePhase.RUNNING) file.copy(phase = FilePhase.INDETERMINATE) else file
                })
                commit(mutation(day = day)); activeDay = null
            }
            commit(mutation(finish = clock.instant()))
            val endingToday = LocalDate.now(clock.withZone(ZoneId.of("Asia/Seoul")))
            val final = store.loadForScope(generation, endingToday, access)
            if (final.status != StateStatus.READY || final.state?.generation != generation) throw Halt(BatchStatus.BLOCKED)
            val rows = attempt.dates.map { store.loadDay(generation, it, attempt.scopeKey) }
            val status = when {
                rows.any { it == null } -> BatchStatus.PENDING
                rows.any { it?.phase in setOf(DayPhase.BLOCKED, DayPhase.REMOTE_STALE) } -> BatchStatus.BLOCKED
                rows.any { it?.blocker == BlockReason.DESTINATION_TRANSIENT_FAILURE } -> BatchStatus.RETRYABLE
                rows.all { it?.isUploaded() == true || (it?.phase == DayPhase.EMPTY && it.isChecked()) } -> BatchStatus.COMPLETED
                else -> BatchStatus.PENDING
            }
            return BatchOutcome(status, confirmed, final.state?.coverage)
        } catch (cancel: CancellationException) {
            withContext(NonCancellable) {
                try { journalInterrupted() }
                catch (journalCancel: CancellationException) { throw journalCancel }
                catch (_: Exception) { }
            }
            throw cancel
        } catch (halt: Halt) {
            return BatchOutcome(halt.status, confirmed, null)
        } catch (_: Exception) {
            try { journalInterrupted() } catch (cancel: CancellationException) { throw cancel } catch (_: Exception) { }
            return BatchOutcome(BatchStatus.PENDING, confirmed, null)
        }
    }
}

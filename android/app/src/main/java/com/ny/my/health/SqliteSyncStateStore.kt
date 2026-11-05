package com.ny.my.health

import android.content.ContentValues
import android.content.Context
import android.database.Cursor
import android.database.sqlite.SQLiteDatabase
import android.database.sqlite.SQLiteDatabaseCorruptException
import android.system.Os
import android.system.OsConstants
import java.io.FileOutputStream
import com.ny.my.health.core.*
import java.io.File
import java.nio.file.Files
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.time.temporal.ChronoUnit
import java.util.UUID
import kotlin.coroutines.CoroutineContext
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONObject
import org.json.JSONException

internal data class PrivateDestinationBinding(val kind: String, val accountName: String? = null,
    val accountType: String? = null, val treeUri: String? = null)

/** 단일 프로세스에서 경로 잠금과 실제 SQLite 트랜잭션으로 active 세대를 보호한다. */
internal class SqliteSyncStateStore internal constructor(
    internal val database: SyncStateDatabase,
    private val today: () -> LocalDate = { LocalDate.now(ZoneId.of("Asia/Seoul")) },
    private val beforeCommit: () -> Unit = {},
) : SyncStateStore {
    constructor(context: Context) : this(SyncStateDatabase(context))

    override suspend fun load(): SyncStateRead = withContext(Dispatchers.IO) { read(null, today(), null) }
    override suspend fun loadForScope(expected: DestinationGeneration, todayInSeoul: LocalDate,
        access: ReadAccess): SyncStateRead = withContext(Dispatchers.IO) { read(expected, todayInSeoul, access) }

    private fun read(expected: DestinationGeneration?, date: LocalDate, access: ReadAccess?): SyncStateRead =
        synchronized(database.pathLock) {
            try {
                // 모든 지원 writer와 같은 경로 잠금을 유지하므로 여러 SELECT도 같은 읽기 snapshot이다.
                val db = database.openForRead() ?: return@synchronized SyncStateRead(StateStatus.ABSENT, null)
                db.use {
                    val state = snapshot(db,date,access)
                    if (expected != null && state.generation != expected) throw StaleSyncStateException()
                    SyncStateRead(StateStatus.READY, state)
                }
            } catch (error: StaleSyncStateException) { throw error }
            catch (error: Exception) { SyncStateRead(classify(error), null) }
        }

    override suspend fun loadDay(expected: DestinationGeneration, date: LocalDate, scopeKey: ReadScopeKey): DayState? = withContext(Dispatchers.IO) {
        synchronized(database.pathLock) {
            val db = database.openForRead() ?: throw IllegalStateException("동기화 DB가 없습니다")
            db.use {
                val meta = metadata(db)
                if (meta.generation != expected) throw StaleSyncStateException()
                readDay(db, meta, date, scopeKey, today())
            }
        }

    }

    suspend fun configure(expected: DestinationGeneration?, configuration: SyncConfiguration,
        binding: PrivateDestinationBinding): SyncCommit {
        if (!configuration.isValid() || configuration.enrolledDay != today().minusDays(1)) return SyncCommit(CommitStatus.INVALID, expected)
        val payload = try { encodeBinding(binding) } catch (_: Exception) { return SyncCommit(CommitStatus.INVALID, expected) }
        return write(expected,allowAbsent = true) { db,admitted ->
            val old = admitted?.metadata
            if (old?.generation != expected) return@write SyncCommit(CommitStatus.STALE, old?.generation)
            val generation = fresh()
            insertGeneration(db, generation, configuration.copy(automatic = false))
            if (old == null) db.insertOrThrow("config", null, values("id" to 1, "format_version" to 1,
                "active_generation" to generation.value, "automatic" to 0, "binding_kind" to binding.kind,
                "binding_payload" to payload, "reset_epoch" to 0))
            else check(db.update("config", values("active_generation" to generation.value, "automatic" to 0,
                "binding_kind" to binding.kind, "binding_payload" to payload), "id=1 AND active_generation=?",
                arrayOf(expected!!.value)) == 1)
            SyncCommit(CommitStatus.COMMITTED, generation)
        }
    }

    suspend fun readBinding(expected: DestinationGeneration): PrivateDestinationBinding? = withContext(Dispatchers.IO) { synchronized(database.pathLock) {
        val db = database.openForRead() ?: throw IllegalStateException("동기화 DB가 없습니다")
        db.use {
            if (metadata(db).generation != expected) throw StaleSyncStateException()
            db.rawQuery("SELECT binding_kind,binding_payload FROM config WHERE id=1", null).use { row ->
                check(row.moveToFirst())
                if (row.isNull(0) && row.isNull(1)) null else decodeBinding(row.text(0), row.text(1))
            }
        }
    }

    }

    suspend fun invalidate(expected: DestinationGeneration): SyncCommit = write(expected) { db,admitted ->
        val old = checkNotNull(admitted).metadata
        if (old.generation != expected) return@write SyncCommit(CommitStatus.STALE, old.generation)
        val generation = fresh()
        insertGeneration(db, generation, old.configuration?.copy(automatic = false))
        check(db.update("config", values("active_generation" to generation.value, "automatic" to 0),
            "id=1 AND active_generation=?", arrayOf(expected.value)) == 1)
        SyncCommit(CommitStatus.COMMITTED, generation)
    }

    override suspend fun transact(expected: DestinationGeneration?, mutation: SyncMutation): SyncCommit = write(expected) { db,admitted ->
        val admission = checkNotNull(admitted)
        val meta = admission.metadata
        if (meta.generation != expected) return@write SyncCommit(CommitStatus.STALE, meta.generation)
        val now = today()
        val scopes = mutation.start?.requestedScopes ?: admission.attempt?.requestedScopes
        val access = scopes?.let { ReadAccess(true, it.keys, it) }
        val state = snapshot(db,now,access,admission)
        val date = mutation.day?.date ?: mutation.confirmation?.key?.date
        val key = mutation.day?.scopeKey ?: mutation.confirmation?.key?.scopeKey
        val current = if (date != null && key != null) readDay(db, meta, date, key, now) else null
        val status = state.validateMutation(expected, mutation, current, now)
        if (status != CommitStatus.COMMITTED) return@write SyncCommit(status, meta.generation)
        val start = mutation.start
        val dayMutation = mutation.day
        val confirmationMutation = mutation.confirmation
        val pendingTriggers = mutation.pendingTriggers
        when {
            start != null -> saveAttempt(db, meta.generation, start)
            dayMutation != null -> {
                saveDay(db, meta.generation, dayMutation)
                invalidateOtherScopes(db, meta, dayMutation, now)
            }
            confirmationMutation != null -> {
                val confirmation = confirmationMutation
                val day = checkNotNull(current)
                val file = day.datasets.getValue(confirmation.key.dataset).copy(phase = FilePhase.CONFIRMED,
                    targetHandle = confirmation.targetHandle, confirmedAt = confirmation.confirmedAt)
                val files = day.datasets + (file.dataset to file)
                val next = day.copy(datasets = files,
                    phase = if (day.blocker == null && day.phase != DayPhase.REMOTE_STALE &&
                        files.values.all { it.phase == FilePhase.CONFIRMED } && day.isChecked()) DayPhase.CONFIRMED else day.phase)
                require(next.isValid(meta.generation,checkNotNull(meta.configuration).enrolledDay,now))
                saveDay(db, meta.generation, next)
            }
            pendingTriggers != null -> db.update("attempt", values("pending_triggers_json" to
                SyncStateCodec.encodeTriggers(pendingTriggers)), "generation=? AND id=?", arrayOf(meta.generation.value, mutation.attemptId))
            mutation.finishAt != null -> db.update("attempt", values("finished_at" to mutation.finishAt.toString()),
                "generation=? AND id=?", arrayOf(meta.generation.value, mutation.attemptId))
        }
        SyncCommit(CommitStatus.COMMITTED, meta.generation)
    }

    override suspend fun reset(expected: DestinationGeneration?): SyncCommit {
        if (expected == null) return recoverReset()
        return write(expected) { db,admitted ->
            val meta = checkNotNull(admitted).metadata
            if (meta.generation != expected) return@write SyncCommit(CommitStatus.STALE, meta.generation)
            if (meta.resetEpoch == Long.MAX_VALUE) return@write SyncCommit(CommitStatus.INVALID, meta.generation)
            val generation = fresh()
            insertGeneration(db, generation, null)
            db.execSQL("UPDATE config SET active_generation=?,automatic=0,binding_kind=NULL,binding_payload=NULL,reset_epoch=reset_epoch+1 WHERE id=1 AND active_generation=?", arrayOf(generation.value, expected.value))
            db.delete("dataset", null, null)
            db.delete("day", null, null)
            db.delete("attempt", null, null)
            db.delete("generation", "id<>?", arrayOf(generation.value))
            SyncCommit(CommitStatus.COMMITTED, generation)
        }
    }

    private suspend fun write(expected: DestinationGeneration?,allowAbsent: Boolean = false,
        block: (SQLiteDatabase,Admission?) -> SyncCommit): SyncCommit {
        val coroutine = currentCoroutineContext()
        return withContext(Dispatchers.IO) { synchronized(database.pathLock) {
            try {
                // 읽기 경계가 marker/symlink를 검사하며 ABSENT에서는 파일을 만들지 않는다.
                val checkedAt = today()
                val ready = database.openForRead().use { db -> db?.let { validateActive(it,checkedAt) } }
                if (ready == null) {
                    if (!allowAbsent) return@synchronized SyncCommit(CommitStatus.UNAVAILABLE,null)
                    if (expected != null) return@synchronized SyncCommit(CommitStatus.STALE,null)
                    return@synchronized initialize(coroutine,block)
                }
                val result = database.openForWrite().use { db ->
                    database.transaction(db,{ capacity(db); coroutine.ensureActive(); beforeCommit(); coroutine.ensureActive() }) {
                        val meta = metadata(db)
                        if (meta.generation != expected) SyncCommit(CommitStatus.STALE,meta.generation)
                        else {
                            require(meta == ready.metadata)
                            // 같은 path lock의 read-only 무결성 판정은 이 호출 안에서만 재사용한다.
                            block(db,Admission(meta,readAttempt(db,meta,checkedAt)))
                        }
                    }
                }
                database.openForRead().use { db -> checkNotNull(db); metadata(db) }
                result
            } catch (error: CancellationException) { throw error }
            catch (_: Exception) { SyncCommit(CommitStatus.UNAVAILABLE,null) }
        } }
    }

    /** 최초 DB는 닫힌 READY 파일을 입증한 뒤에만 active 경로로 게시한다. */
    private fun initialize(coroutine: CoroutineContext,block: (SQLiteDatabase,Admission?) -> SyncCommit): SyncCommit {
        val candidate = File(database.databaseFile.parentFile,"${database.databaseFile.name}.init-${UUID.randomUUID()}")
        check(!present(candidate))
        val staged = SyncStateDatabase(candidate,database::observe,SyncStatePhase.INITIAL_SCHEMA_READY)
        val result = staged.openForWrite().use { db ->
            staged.transaction(db,{ capacity(db); coroutine.ensureActive(); beforeCommit(); coroutine.ensureActive() }) {
                block(db,null)
            }
        }
        check(result.status == CommitStatus.COMMITTED)
        staged.openForRead().use { db -> checkNotNull(db); proveInitial(db,checkNotNull(result.generation)) }
        syncFile(candidate); syncDirectory(checkNotNull(candidate.parentFile))
        database.observe(SyncStatePhase.INITIAL_BEFORE_PUBLISH)
        coroutine.ensureActive()
        check(!present(database.databaseFile) && !present(database.resetDirectory))
        check(candidate.renameTo(database.databaseFile))
        syncDirectory(checkNotNull(database.databaseFile.parentFile))
        database.observe(SyncStatePhase.INITIAL_PUBLISHED)
        database.openForRead().use { db -> checkNotNull(db); proveInitial(db,checkNotNull(result.generation)) }
        return result
    }

    private fun proveInitial(db: SQLiteDatabase,generation: DestinationGeneration) {
        val admitted = validateActive(db,today())
        val config = checkNotNull(admitted.metadata.configuration)
        require(admitted.metadata.generation == generation && admitted.metadata.resetEpoch == 0L && !config.automatic)
        require(admitted.attempt == null)
        proveCounts(db)
        val state = snapshot(db,today(),null,admitted)
        require(state.isValid() && state.coverage.lastUploadAt == null && state.coverage.checkedThrough == null &&
            state.coverage.uploadedThrough == null && state.days.isEmpty())
    }

    private suspend fun recoverReset(): SyncCommit {
        val coroutine = currentCoroutineContext()
        return withContext(Dispatchers.IO) { synchronized(database.pathLock) {
            try {
                val marker = database.resetDirectory
                if (Files.isSymbolicLink(marker.toPath()) || Files.isSymbolicLink(database.databaseFile.toPath()) ||
                    (database.databaseFile.exists() && !database.databaseFile.isFile)) return@synchronized SyncCommit(CommitStatus.UNAVAILABLE, null)
                if (!present(marker)) {
                    val status = read(null,today(),null).status
                    if (status == StateStatus.READY || status == StateStatus.ABSENT) return@synchronized SyncCommit(CommitStatus.INVALID,null)
                    if (status != StateStatus.CORRUPT && status != StateStatus.UNSUPPORTED) return@synchronized SyncCommit(CommitStatus.UNAVAILABLE,null)
                    prepareReset(marker,coroutine)
                }
                check(marker.isDirectory && marker.listFiles()!!.all { it.name in setOf("owner", "stage", "old", "installed", "old-journal", "old-wal", "old-shm") && it.isFile && !Files.isSymbolicLink(it.toPath()) })
                val owner = readOwner(File(marker,"owner"))
                val stage = File(marker, "stage")
                val old = File(marker, "old")
                val installed = File(marker, "installed")
                val preserved = File(database.databaseFile.parentFile, "${database.databaseFile.name}.old-$owner")
                val completed = File(database.databaseFile.parentFile, "${database.databaseFile.name}.reset.completed-$owner")
                check(!Files.exists(completed.toPath(),java.nio.file.LinkOption.NOFOLLOW_LINKS))
                if (Files.exists(preserved.toPath(),java.nio.file.LinkOption.NOFOLLOW_LINKS)) {
                    check(preserved.isFile && !Files.isSymbolicLink(preserved.toPath()) && installed.exists() && !stage.exists() && !old.exists())
                    check(readOwner(installed) == owner)
                }
                if (!old.exists() && !installed.exists()) {
                    check(stage.isFile && !Files.isSymbolicLink(stage.toPath()))
                    SyncStateDatabase(stage).openForRead().use { checkNotNull(it); proveEmpty(it) }
                    coroutine.ensureActive(); beforeCommit(); coroutine.ensureActive()
                    check(database.databaseFile.isFile && !Files.isSymbolicLink(database.databaseFile.toPath()))
                    check(database.databaseFile.renameTo(old))
                    syncDirectory(marker); syncDirectory(marker.parentFile!!)
                }
                if (stage.exists()) {
                    SyncStateDatabase(stage).openForRead().use { checkNotNull(it); proveEmpty(it) }
                    for (suffix in listOf("-journal", "-wal", "-shm")) {
                        val sidecar = File(database.databaseFile.path + suffix)
                        val retired = File(marker, "old$suffix")
                        if (Files.exists(sidecar.toPath(), java.nio.file.LinkOption.NOFOLLOW_LINKS)) {
                            check(sidecar.isFile && !Files.isSymbolicLink(sidecar.toPath()) && !retired.exists())
                            check(sidecar.renameTo(retired))
                        }
                    }
                    check(!database.databaseFile.exists())
                    check(stage.renameTo(database.databaseFile))
                    syncDirectory(marker); syncDirectory(marker.parentFile!!)
                }
                val meta = database.openForRead(allowMarker = true).use { checkNotNull(it); proveEmpty(it) }
                if (!installed.exists()) durableText(installed, owner)
                check(readOwner(installed) == owner)
                syncDirectory(marker)
                // 원본은 명시 복구 후에도 별도 보존한다.
                if (old.exists()) { check(!preserved.exists()); check(old.renameTo(preserved)) }
                check(preserved.isFile && !Files.isSymbolicLink(preserved.toPath()))
                for (suffix in listOf("-journal", "-wal", "-shm")) {
                    val retired = File(marker, "old$suffix")
                    if (retired.exists()) {
                        val saved = File(preserved.path + suffix)
                        check(!Files.exists(saved.toPath(),java.nio.file.LinkOption.NOFOLLOW_LINKS) && retired.renameTo(saved))
                    }
                }
                syncDirectory(marker); syncDirectory(marker.parentFile!!)
                check(!stage.exists())
                check(marker.listFiles()!!.map { it.name }.toSet() == setOf("owner","installed"))
                for (suffix in listOf("-journal","-wal","-shm")) {
                    check(!Files.exists(File(database.databaseFile.path + suffix).toPath(),java.nio.file.LinkOption.NOFOLLOW_LINKS))
                }
                database.openForRead(allowMarker = true).use { checkNotNull(it); proveEmpty(it) }
                // owner와 완료 증명을 함께 원자적으로 퇴역하여 admission marker에 빈 틈을 만들지 않는다.
                check(!Files.exists(completed.toPath(),java.nio.file.LinkOption.NOFOLLOW_LINKS))
                check(marker.renameTo(completed)); syncDirectory(completed.parentFile!!)
                SyncCommit(CommitStatus.COMMITTED, meta.generation)
            } catch (error: CancellationException) { throw error }
            catch (_: Exception) { SyncCommit(CommitStatus.UNAVAILABLE, null) }
        } }
    }

    /** 미완성 owner/schema는 비활성 준비물로 남고 다음 명시 reset은 새 준비물을 쓴다. */
    private fun prepareReset(marker: File,coroutine: CoroutineContext) {
        val owner = UUID.randomUUID().toString()
        val prepared = File(marker.parentFile,"${database.databaseFile.name}.reset.prepare-$owner")
        check(!present(prepared) && prepared.mkdir())
        syncDirectory(checkNotNull(prepared.parentFile))
        database.observe(SyncStatePhase.RESET_OWNER_BEFORE)
        durableText(File(prepared,"owner"),owner) { database.observe(SyncStatePhase.RESET_OWNER_WRITING) }
        syncDirectory(prepared)
        database.observe(SyncStatePhase.RESET_OWNER_WRITTEN)
        val stage = File(prepared,"stage")
        val staged = SyncStateDatabase(stage,database::observe,SyncStatePhase.RESET_STAGE_SCHEMA_READY)
        staged.openForWrite().use { db -> staged.transaction(db) {
            val generation = fresh(); insertGeneration(db,generation,null)
            db.insertOrThrow("config",null,values("id" to 1,"format_version" to 1,"active_generation" to generation.value,
                "automatic" to 0,"reset_epoch" to 0))
        } }
        staged.openForRead().use { checkNotNull(it); proveEmpty(it) }
        check(readOwner(File(prepared,"owner")) == owner)
        check(prepared.listFiles()!!.map { it.name }.toSet() == setOf("owner","stage"))
        syncFile(stage); syncDirectory(prepared); syncDirectory(checkNotNull(prepared.parentFile))
        database.observe(SyncStatePhase.RESET_PREPARED)
        coroutine.ensureActive()
        check(!present(marker))
        check(prepared.renameTo(marker))
        syncDirectory(checkNotNull(marker.parentFile))
        database.observe(SyncStatePhase.RESET_MARKER_PUBLISHED)
    }

    private data class Metadata(val generation: DestinationGeneration, val configuration: SyncConfiguration?, val resetEpoch: Long)
    private fun metadata(db: SQLiteDatabase): Metadata = db.rawQuery(
        "SELECT c.id,c.format_version,c.active_generation,c.automatic,c.reset_epoch,g.destination_handle,g.enrolled_day,g.categories_json,c.binding_kind IS NULL,c.binding_payload IS NULL FROM config c JOIN generation g ON g.id=c.active_generation", null).use { row ->
        require(row.moveToFirst() && row.integer(0) == 1L)
        if (row.integer(1) != 1L) throw UnsupportedSyncSchemaException()
        val generation = DestinationGeneration(SyncStateCodec.decodeUuid(row.text(2)))
        val automatic = row.integer(3)
        val epoch = row.integer(4)
        require(automatic in 0L..1L && epoch >= 0)
        val categories = SyncStateCodec.decodeCategories(row.text(7), 1)
        require(row.integer(8) == row.integer(9))
        val destination = row.nullable(5)
        val config = if (destination == null) {
            require(row.nullable(6) == null && categories.isEmpty() && automatic == 0L && row.integer(8) == 1L)
            null
        } else SyncConfiguration(SyncStateCodec.decodeHandle(destination),SyncStateCodec.decodeDate(row.text(6)),
            automatic == 1L,categories).also { require(it.isValid() && row.integer(8) == 0L) }
        require(!row.moveToNext())
        Metadata(generation,config,epoch)
    }

    private fun proveEmpty(db: SQLiteDatabase): Metadata {
        val meta = metadata(db)
        require(meta.configuration == null && meta.resetEpoch == 0L)
        proveCounts(db)
        return meta
    }

    private fun proveCounts(db: SQLiteDatabase) {
        for ((table,count) in mapOf("config" to 1L,"generation" to 1L,"day" to 0L,"dataset" to 0L,"attempt" to 0L)) {
            db.rawQuery("SELECT count(*) FROM $table",null).use { require(it.moveToFirst() && it.getLong(0) == count) }
        }
    }

    private data class Admission(val metadata: Metadata,val attempt: AttemptState?)

    /** private binding은 adapter에서 정규성만 확인하고 core snapshot에는 전달하지 않는다. */
    private fun validateActive(db: SQLiteDatabase,date: LocalDate,meta: Metadata = metadata(db)): Admission {
        db.rawQuery("SELECT date,scope_key FROM day WHERE generation=?",arrayOf(meta.generation.value)).use { rows ->
            while (rows.moveToNext()) readDay(db,meta,SyncStateCodec.decodeDate(rows.text(0)),
                ReadScopeKey(SyncStateCodec.decodeDigest(rows.text(1))),date)
        }
        val attempt = readAttempt(db,meta,date)
        db.rawQuery("SELECT binding_kind,binding_payload FROM config WHERE id=1",null).use { binding ->
            require(binding.moveToFirst())
            if (!binding.isNull(0)) decodeBinding(binding.text(0),binding.text(1))
        }
        return Admission(meta,attempt)
    }

    private fun snapshot(db: SQLiteDatabase,date: LocalDate,access: ReadAccess?,
        admitted: Admission = validateActive(db,date)): SyncState {
        val meta = admitted.metadata
        val config = meta.configuration
        val attempt = admitted.attempt
        if (config == null) {
            require(attempt == null)
            return SyncState(1, meta.generation, null, null, null, emptyMap(), emptyMap(), emptyList(), emptyCoverage())
        }
        val scopes = if (access?.available == true) config.categories.mapNotNull { type ->
            access.scopeByType[type]?.takeIf { type in access.granted && it != ReadScope.BLOCKED }?.let { type to it }
        }.toMap() else emptyMap()
        val key = if (scopes.isEmpty()) null else SyncPlanner.scopeKey(config, ReadAccess(true, scopes.keys, scopes))
        if (key != null && attempt != null && attempt.finishedAt == null &&
            (attempt.scopeKey != key || attempt.requestedScopes != scopes)) throw BusySyncProfileException()
        val selected = key?.let { scan(db, meta, date, it) }
        val fullScopes = DataType.entries.associateWith { ReadScope.EXPLICIT_DAY }
        val fullKey = SyncPlanner.scopeKey(config.copy(categories = fullScopes.keys), ReadAccess(true, fullScopes.keys, fullScopes))!!
        val full = if (key == fullKey) selected!! else scan(db, meta, date, fullKey)
        var last: Instant? = null
        db.rawQuery("SELECT confirmed_at,prior_confirmation_json FROM dataset WHERE generation=?", arrayOf(meta.generation.value)).use { rows ->
            while (rows.moveToNext()) {
                val currentTime = if (rows.isNull(0)) null else SyncStateCodec.decodeInstant(rows.text(0))
                val priorTime = if (rows.isNull(1)) null else SyncStateCodec.decodeConfirmation(rows.text(1),1).confirmedAt
                for (time in listOfNotNull(currentTime,priorTime)) if (last == null || time > checkNotNull(last)) last = time
            }
        }
        val coverage = CoverageSummary(full.checked, full.uploaded, key, selected?.checked, selected?.uploaded,
            config.categories - scopes.keys, selected?.pending, selected?.blocked, last,
            selected?.recentChecked.orEmpty(), selected?.recentUploaded.orEmpty())
        return SyncState(1, meta.generation, config, attempt, key, scopes, selected?.days.orEmpty(), selected?.oldest.orEmpty(), coverage).also { require(it.isValid()) }
    }

    private data class Scan(val checked: LocalDate?, val uploaded: LocalDate?, val pending: Long, val blocked: Long,
        val days: Map<LocalDate, DayState>, val oldest: List<LocalDate>, val recentChecked: Set<LocalDate>, val recentUploaded: Set<LocalDate>)
    private fun scan(db: SQLiteDatabase, meta: Metadata, today: LocalDate, key: ReadScopeKey): Scan {
        val enrolled = checkNotNull(meta.configuration).enrolledDay
        val floor = today.minusDays(30)
        val total = ChronoUnit.DAYS.between(enrolled, today).coerceAtLeast(0)
        var completed = 0L
        var blocked = ChronoUnit.DAYS.between(enrolled, minOf(today, maxOf(enrolled, floor))).coerceAtLeast(0)
        var checkedNext = enrolled
        var uploadedNext = enrolled
        var gap = maxOf(enrolled, floor)
        val oldest = mutableListOf<LocalDate>()
        val days = linkedMapOf<LocalDate, DayState>()
        val checkedRecent = mutableSetOf<LocalDate>(); val uploadedRecent = mutableSetOf<LocalDate>()
        fun missingUntil(end: LocalDate) {
            while (gap < end && gap < today && oldest.size < 7) { oldest += gap; gap = gap.plusDays(1) }
            if (gap < end) gap = end
        }
        db.rawQuery("SELECT date FROM day WHERE generation=? AND scope_key=? ORDER BY date", arrayOf(meta.generation.value, key.value)).use { rows ->
            while (rows.moveToNext()) {
                val day = checkNotNull(readDay(db, meta, SyncStateCodec.decodeDate(rows.text(0)), key, today))
                val done = day.isUploaded() || (day.phase == DayPhase.EMPTY && day.isChecked())
                if (done) completed++
                if (day.date == checkedNext && day.isChecked()) checkedNext = checkedNext.plusDays(1)
                if (day.date == uploadedNext && day.isUploaded()) uploadedNext = uploadedNext.plusDays(1)
                val permanent = permanent(day)
                if (day.date < floor && done) blocked--
                if (day.date >= floor && !done && permanent) blocked++
                if (day.date >= floor) {
                    missingUntil(day.date)
                    if (!done && !permanent && oldest.size < 7) { oldest += day.date; days[day.date] = day }
                    gap = day.date.plusDays(1)
                }
                if (day.date >= today.minusDays(2)) {
                    days[day.date] = day
                    if (day.isChecked()) checkedRecent += day.date
                    if (day.isUploaded()) uploadedRecent += day.date
                }
            }
        }
        missingUntil(today)
        return Scan(checkedNext.minusDays(1).takeIf { checkedNext > enrolled }, uploadedNext.minusDays(1).takeIf { uploadedNext > enrolled },
            total - completed, blocked, days, oldest, checkedRecent, uploadedRecent)
    }
    private fun permanent(day: DayState) = day.phase == DayPhase.REMOTE_STALE || day.blocker in setOf(
        BlockReason.DESTINATION_BLOCKED, BlockReason.RETRY_EXHAUSTED, BlockReason.STORE_UNAVAILABLE,
        BlockReason.DESTINATION_RECONCILIATION_REQUIRED, BlockReason.ACCOUNT_AUTHORIZATION_BLOCKED)

    private fun readAttempt(db: SQLiteDatabase, meta: Metadata, today: LocalDate): AttemptState? = db.rawQuery(
        "SELECT * FROM attempt WHERE generation=?", arrayOf(meta.generation.value)).use { row ->
        if (!row.moveToFirst()) return null
        val value = AttemptState(SyncStateCodec.decodeUuid(row.s("id")), ReadScopeKey(SyncStateCodec.decodeDigest(row.s("scope_key"))),
            SyncStateCodec.decodeScopes(row.s("requested_scopes_json"),1), enumValueOf(row.s("trigger")),
            SyncStateCodec.decodeInstant(row.s("scheduled_at")), row.time("started_at"), row.time("finished_at"),
            SyncStateCodec.decodeDates(row.s("dates_json"),1), SyncStateCodec.decodeDigest(row.s("retry_budget_key")),
            row.integer(row.getColumnIndexOrThrow("retries_used")).also { require(it in 0L..3L) }.toInt(), SyncStateCodec.decodeTriggers(row.s("pending_triggers_json"),1))
        require(meta.configuration != null && value.isValid(meta.configuration.enrolledDay,today) && meta.configuration.categories.containsAll(value.requestedScopes.keys))
        require(!row.moveToNext()); value
    }
    private fun readDay(db: SQLiteDatabase, meta: Metadata, date: LocalDate, key: ReadScopeKey, today: LocalDate): DayState? = db.rawQuery(
        "SELECT * FROM day WHERE generation=? AND date=? AND scope_key=?", arrayOf(meta.generation.value,date.toString(),key.value)).use { row ->
        if (!row.moveToFirst()) return null
        val files = linkedMapOf<Dataset, DatasetState>()
        db.rawQuery("SELECT * FROM dataset WHERE generation=? AND date=? AND scope_key=?", arrayOf(meta.generation.value,date.toString(),key.value)).use { datasets ->
            while (datasets.moveToNext()) {
                val dataset = enumValueOf<Dataset>(datasets.s("dataset"))
                val populated = datasets.integer(datasets.getColumnIndexOrThrow("populated")); require(populated in 0L..1L)
                files[dataset] = DatasetState(dataset, RevisionKey(SyncStateCodec.decodeDigest(datasets.s("revision")), SyncStateCodec.decodeInstant(datasets.s("generated_at"))),
                    enumValueOf(datasets.s("phase")), populated == 1L, datasets.nullable("target_handle")?.let(SyncStateCodec::decodeHandle), datasets.time("started_at"), datasets.time("confirmed_at"),
                    datasets.nullable("prior_confirmation_json")?.let { SyncStateCodec.decodeConfirmation(it,1) })
            }
        }
        val revision = row.nullable("revision")?.let { RevisionKey(SyncStateCodec.decodeDigest(it), SyncStateCodec.decodeInstant(row.s("generated_at"))) }
        require((revision == null) == row.isNull(row.getColumnIndexOrThrow("generated_at")))
        val value = DayState(SyncStateCodec.decodeDate(row.s("date")), ReadScopeKey(SyncStateCodec.decodeDigest(row.s("scope_key"))),
            SyncStateCodec.decodeScopes(row.s("requested_scopes_json"),1), revision, enumValueOf(row.s("phase")),
            SyncStateCodec.decodeScopes(row.s("scopes_json"),1), row.time("checked_at"), files, row.nullable("blocker")?.let { enumValueOf<BlockReason>(it) })
        require(meta.configuration != null && meta.configuration.categories.containsAll(value.requestedScopes.keys) && value.isValid(meta.generation,meta.configuration.enrolledDay,today))
        value
    }

    private fun saveAttempt(db: SQLiteDatabase, generation: DestinationGeneration, value: AttemptState) {
        db.delete("attempt", "generation=?", arrayOf(generation.value))
        db.insertOrThrow("attempt", null, values("id" to value.id,"generation" to generation.value,"scope_key" to value.scopeKey.value,
            "requested_scopes_json" to SyncStateCodec.encodeScopes(value.requestedScopes),"trigger" to value.trigger.name,
            "scheduled_at" to value.scheduledAt.toString(),"started_at" to value.startedAt?.toString(),"finished_at" to value.finishedAt?.toString(),
            "dates_json" to SyncStateCodec.encodeDates(value.dates),"retry_budget_key" to value.retryBudgetKey,"retries_used" to value.retriesUsed,
            "pending_triggers_json" to SyncStateCodec.encodeTriggers(value.pendingTriggers)))
    }
    private fun saveDay(db: SQLiteDatabase, generation: DestinationGeneration, day: DayState) {
        val where = "generation=? AND date=? AND scope_key=?"
        val args = arrayOf(generation.value, day.date.toString(), day.scopeKey.value)
        val fields = values("generation" to generation.value,"date" to day.date.toString(),"scope_key" to day.scopeKey.value,
            "requested_scopes_json" to SyncStateCodec.encodeScopes(day.requestedScopes),"revision" to day.revision?.digest,"generated_at" to day.revision?.generatedAt?.toString(),
            "phase" to day.phase.name,"scopes_json" to SyncStateCodec.encodeScopes(day.scopes),"checked_at" to day.checkedAt?.toString(),"blocker" to day.blocker?.name)
        if (db.update("day",fields,where,args) == 0) db.insertOrThrow("day",null,fields)
        db.delete("dataset",where,args)
        day.datasets.values.forEach { file -> db.insertOrThrow("dataset",null,values("generation" to generation.value,"date" to day.date.toString(),
            "scope_key" to day.scopeKey.value,"dataset" to file.dataset.name,"revision" to file.revision.digest,"generated_at" to file.revision.generatedAt.toString(),
            "phase" to file.phase.name,"populated" to if(file.populated) 1 else 0,"target_handle" to file.targetHandle,"started_at" to file.startedAt?.toString(),
            "confirmed_at" to file.confirmedAt?.toString(),"prior_confirmation_json" to file.priorConfirmation?.let(SyncStateCodec::encodeConfirmation))) }
    }
    private fun invalidateOtherScopes(db: SQLiteDatabase, meta: Metadata, day: DayState, today: LocalDate) {
        val running = day.datasets.values.filter { it.phase == FilePhase.RUNNING }.map { it.dataset }.toSet()
        if (running.isEmpty()) return
        val keys = mutableListOf<ReadScopeKey>()
        db.rawQuery("SELECT scope_key FROM day WHERE generation=? AND date=? AND scope_key<>?", arrayOf(meta.generation.value,day.date.toString(),day.scopeKey.value)).use { rows ->
            while(rows.moveToNext()) keys += ReadScopeKey(rows.text(0))
        }
        keys.forEach { key ->
            val previous = checkNotNull(readDay(db,meta,day.date,key,today))
            var changed = false
            val files = previous.datasets.mapValues { (dataset,file) ->
                if (dataset in running && file.phase == FilePhase.CONFIRMED) {
                    changed = true
                    file.copy(phase = FilePhase.INDETERMINATE, confirmedAt = null, priorConfirmation = FileConfirmation(
                        FileKey(meta.generation,day.date,key,dataset,file.revision.digest),checkNotNull(file.targetHandle),checkNotNull(file.confirmedAt)))
                } else file
            }
            if (changed) {
                val next = previous.copy(phase = if (previous.phase in setOf(DayPhase.BLOCKED,DayPhase.REMOTE_STALE))
                    previous.phase else DayPhase.INDETERMINATE,datasets = files)
                require(next.isValid(meta.generation,checkNotNull(meta.configuration).enrolledDay,today))
                saveDay(db,meta.generation,next)
            }
        }
    }
    private fun insertGeneration(db: SQLiteDatabase, generation: DestinationGeneration, configuration: SyncConfiguration?) {
        db.insertOrThrow("generation",null,values("id" to generation.value,"destination_handle" to configuration?.destinationHandle,
            "enrolled_day" to configuration?.enrolledDay?.toString(),"categories_json" to SyncStateCodec.encodeCategories(configuration?.categories.orEmpty())))
    }
    private fun capacity(db: SQLiteDatabase) {
        mapOf("generation" to 10000L,"attempt" to 10000L,"day" to 100000L,"dataset" to 400000L).forEach { (table,max) ->
            db.rawQuery("SELECT count(*) FROM $table",null).use { require(it.moveToFirst() && it.getLong(0) <= max) }
        }
    }
    private fun encodeBinding(value: PrivateDestinationBinding): String {
        fun bounded(text: String?, max: Int) = text != null && text.toByteArray(Charsets.UTF_8).size in 1..max && Charsets.UTF_8.newEncoder().canEncode(text) && text.none(Char::isISOControl)
        require(when(value.kind) {
            "DRIVE" -> bounded(value.accountName,1024) && bounded(value.accountType,1024) && value.treeUri == null
            "LOCAL" -> value.accountName == null && value.accountType == null && bounded(value.treeUri,4096)
            "SYNTHETIC" -> value.accountName == null && value.accountType == null && value.treeUri == null
            else -> false
        })
        return "{\"kind\":${JSONObject.quote(value.kind)},\"accountName\":${value.accountName?.let(JSONObject::quote) ?: "null"},\"accountType\":${value.accountType?.let(JSONObject::quote) ?: "null"},\"treeUri\":${value.treeUri?.let(JSONObject::quote) ?: "null"}}".also { require(it.toByteArray().size <= 8192) }
    }
    private fun decodeBinding(kind: String, payload: String): PrivateDestinationBinding {
        require(payload.toByteArray().size <= 8192)
        val json = JSONObject(payload)
        require(json.keys().asSequence().toSet() == setOf("kind","accountName","accountType","treeUri"))
        fun field(key: String): String? = if (json.isNull(key)) null else json.get(key).also { require(it is String) } as String
        return PrivateDestinationBinding(checkNotNull(field("kind")),field("accountName"),field("accountType"),field("treeUri")).also {
            require(it.kind == kind && encodeBinding(it) == payload)
        }
    }
    private fun readOwner(file: File): String {
        check(file.isFile && !Files.isSymbolicLink(file.toPath()) && file.length() == 36L)
        return file.readText(Charsets.UTF_8).also { check(it.toByteArray(Charsets.UTF_8).size == 36 && UUID.fromString(it).toString() == it) }
    }
    private fun durableText(file: File, value: String, beforeWrite: () -> Unit = {}) = FileOutputStream(file).use {
        beforeWrite(); it.write(value.toByteArray(Charsets.UTF_8)); it.fd.sync()
    }
    private fun syncFile(file: File) = FileOutputStream(file,true).use { it.fd.sync() }
    private fun syncDirectory(file: File) {
        val descriptor = Os.open(file.path,OsConstants.O_RDONLY,0)
        try { check(OsConstants.S_ISDIR(Os.fstat(descriptor).st_mode)); Os.fsync(descriptor) } finally { Os.close(descriptor) }
    }
    private fun emptyCoverage() = CoverageSummary(null,null,null,null,null,emptySet(),0,0,null,emptySet(),emptySet())
    private fun fresh() = DestinationGeneration(UUID.randomUUID().toString())
    private fun present(file: File) = Files.exists(file.toPath(),java.nio.file.LinkOption.NOFOLLOW_LINKS)
    private fun classify(error: Exception) = when(error) {
        is UnsupportedSyncSchemaException, is UnsupportedOperationException -> StateStatus.UNSUPPORTED
        is SQLiteDatabaseCorruptException, is IllegalArgumentException, is JSONException -> StateStatus.CORRUPT
        else -> StateStatus.UNAVAILABLE
    }
    private fun values(vararg pairs: Pair<String,Any?>) = ContentValues().apply { pairs.forEach { (key,value) ->
        when(value) { null -> putNull(key); is String -> put(key,value); is Int -> put(key,value); is Long -> put(key,value); else -> error("지원하지 않는 SQL 값") }
    } }
    private fun Cursor.integer(index: Int): Long { require(getType(index) == Cursor.FIELD_TYPE_INTEGER); return getLong(index) }
    private fun Cursor.text(index: Int): String { require(getType(index) == Cursor.FIELD_TYPE_STRING); return getString(index) }
    private fun Cursor.nullable(index: Int): String? = if (getType(index) == Cursor.FIELD_TYPE_NULL) null else text(index)
    private fun Cursor.s(name: String) = text(getColumnIndexOrThrow(name))
    private fun Cursor.nullable(name: String): String? = nullable(getColumnIndexOrThrow(name))
    private fun Cursor.time(name: String) = nullable(name)?.let(SyncStateCodec::decodeInstant)
}
internal class StaleSyncStateException : IllegalStateException("동기화 세대가 변경되었습니다")

private class BusySyncProfileException : IllegalStateException("실행 중인 읽기 범위를 먼저 복구해 주세요")

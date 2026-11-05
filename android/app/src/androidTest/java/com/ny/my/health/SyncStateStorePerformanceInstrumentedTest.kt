package com.ny.my.health

import android.content.ContentValues
import android.database.sqlite.SQLiteDatabase
import android.os.Process
import android.os.SystemClock
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.platform.io.PlatformTestStorageRegistry
import com.ny.my.health.core.*
import java.io.File
import java.time.Instant
import java.time.LocalDate
import java.util.UUID
import kotlinx.coroutines.runBlocking
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith

/** 합성 메타데이터의 첫 재열기와 반복 재열기를 측정한다. 시간 임계값은 두지 않는다. */
@RunWith(AndroidJUnit4::class)
class SyncStateStorePerformanceInstrumentedTest {
    private enum class Profile { FULL_EXPLICIT, PARTIAL_SUMMARY }
    private enum class Operation { LOAD, SCOPED_LOAD, CONFIRMATION }

    @Test
    fun accumulatedMetadataMeasurementsReportLoadScopedQueryAndConfirmationCostsWithoutWeakeningIntegrity() = runBlocking {
        val samples = JSONArray()
        for (n in listOf(30, 365, 3650)) for (profile in Profile.entries) {
            val fixture = Fixture(n, profile)
            try {
                fixture.seed()
                repeat(4) { index ->
                    val read = measure(samples, fixture, Operation.LOAD, index) { fixture.store().load() }
                    fixture.assertRead(read, scoped = false)
                }
                repeat(4) { index ->
                    val read = measure(samples, fixture, Operation.SCOPED_LOAD, index) { fixture.selected() }
                    fixture.assertRead(read, scoped = true)
                }
                val attempt = fixture.start()
                repeat(4) { index ->
                    // 동일 리비전의 prior journal 준비ㆍ검증은 측정에서 제외한다.
                    val confirmation = fixture.prepareConfirmation(attempt)
                    val result = measure(samples, fixture, Operation.CONFIRMATION, index) {
                        fixture.store().transact(fixture.generation,
                            SyncMutation(attempt.id, null, null, confirmation, null, null))
                    }
                    assertEquals(CommitStatus.COMMITTED, result.status)
                    assertEquals(fixture.generation, result.generation)
                    val reopened = fixture.store().loadDay(fixture.generation, confirmation.key.date, fixture.key)!!
                    assertTrue(reopened.isUploaded())
                    assertEquals(confirmation.targetHandle, reopened.datasets.getValue(Dataset.DAILY).targetHandle)
                    assertEquals(confirmation.confirmedAt, reopened.datasets.getValue(Dataset.DAILY).confirmedAt)
                    assertEquals(confirmation, reopened.datasets.getValue(Dataset.DAILY).priorConfirmation)
                    fixture.assertRead(fixture.selected(), scoped = true)
                }
                assertEquals(CommitStatus.COMMITTED, fixture.store().transact(fixture.generation,
                    SyncMutation(attempt.id, null, null, null, null, fixture.time.plusSeconds(2))).status)
                fixture.assertRead(fixture.store().load(), scoped = false)
                fixture.assertCountsAndIntegrity()
            } finally {
                fixture.close()
            }
        }
        val json = samples.toString()
        val decoded = JSONArray(json)
        assertEquals(72, decoded.length())
        val fields = setOf("profile", "N", "operation", "sampleIndex", "elapsedNs", "processCpuMs")
        val identities = mutableSetOf<String>()
        repeat(decoded.length()) { index ->
            val sample = decoded.getJSONObject(index)
            assertEquals(fields, sample.keys().asSequence().toSet())
            assertTrue(sample.getLong("elapsedNs") >= 0)
            assertTrue(sample.getLong("processCpuMs") >= 0)
            assertTrue(identities.add("${sample.getString("profile")}/${sample.getInt("N")}/${sample.getString("operation")}/${sample.getInt("sampleIndex")}"))
        }
        PlatformTestStorageRegistry.getInstance()
            .openOutputFile("v1-synthetic-store-performance/metrics.json").use { output ->
                output.write(json.toByteArray(Charsets.UTF_8))
            }
    }

    private suspend fun <T> measure(samples: JSONArray, fixture: Fixture, operation: Operation,
        sampleIndex: Int, block: suspend () -> T): T {
        // 프로세스 전체 CPU 실제 차이이며 스레드별 CPUㆍ할당량ㆍ배터리를 추정하지 않는다.
        val cpuStart = Process.getElapsedCpuTime()
        val elapsedStart = SystemClock.elapsedRealtimeNanos()
        val result = block()
        val elapsedNs = SystemClock.elapsedRealtimeNanos() - elapsedStart
        val processCpuMs = Process.getElapsedCpuTime() - cpuStart
        samples.put(JSONObject().put("profile", fixture.profile.name).put("N", fixture.n)
            .put("operation", operation.name).put("sampleIndex", sampleIndex)
            .put("elapsedNs", elapsedNs).put("processCpuMs", processCpuMs))
        return result
    }

    private class Fixture(val n: Int, val profile: Profile) {
        private val context = InstrumentationRegistry.getInstrumentation().targetContext
        private val database = SyncStateDatabase(File(context.noBackupFilesDir,
            "sync-state-performance-${UUID.randomUUID()}.db"))
        private val measurementDay = LocalDate.of(2026, 11, 5)
        private val enrolled = measurementDay.minusDays(n.toLong())
        private var today = enrolled.plusDays(1)
        val time: Instant = Instant.parse("2026-11-05T00:00:00Z")
        private val revision = RevisionKey("a".repeat(64), time)
        private val categories = DataType.entries.toSet()
        private val scopes = when (profile) {
            Profile.FULL_EXPLICIT -> categories.associateWith { ReadScope.EXPLICIT_DAY }
            Profile.PARTIAL_SUMMARY -> mapOf(DataType.STEPS to ReadScope.SUMMARY)
        }
        private val configuration = SyncConfiguration("synthetic-destination", enrolled, false, categories)
        private val access = ReadAccess(true, scopes.keys, scopes)
        val key = SyncPlanner.scopeKey(configuration, access)!!
        private var configuredGeneration: DestinationGeneration? = null
        val generation: DestinationGeneration get() = checkNotNull(configuredGeneration)

        fun store() = SqliteSyncStateStore(database, { today })
        suspend fun selected() = store().loadForScope(generation, today, access)

        suspend fun seed() {
            assertEquals(StateStatus.ABSENT, store().load().status)
            val configured = store().configure(null, configuration, PrivateDestinationBinding("SYNTHETIC"))
            assertEquals(CommitStatus.COMMITTED, configured.status)
            configuredGeneration = configured.generation!!
            today = measurementDay
            // 정상 writer의 schemaㆍFKㆍcanonical leaf를 유지한 실제 단일 SQLite 트랜잭션이다.
            database.openForWrite().use { db -> database.transaction(db) {
                repeat(n) { offset ->
                    val date = enrolled.plusDays(offset.toLong())
                    val confirmation = confirmation(date)
                    val file = DatasetState(Dataset.DAILY, revision, FilePhase.CONFIRMED, true,
                        confirmation.targetHandle, time, confirmation.confirmedAt, null)
                    val day = DayState(date, key, scopes, revision, DayPhase.CONFIRMED, scopes,
                        time, mapOf(Dataset.DAILY to file), null)
                    assertTrue(day.isValid(generation, enrolled, today))
                    db.insertOrThrow("day", null, values("generation" to generation.value,
                        "date" to date.toString(), "scope_key" to key.value,
                        "requested_scopes_json" to SyncStateCodec.encodeScopes(scopes),
                        "revision" to revision.digest, "generated_at" to time.toString(),
                        "phase" to day.phase.name, "scopes_json" to SyncStateCodec.encodeScopes(scopes),
                        "checked_at" to time.toString(), "blocker" to null))
                    db.insertOrThrow("dataset", null, values("generation" to generation.value,
                        "date" to date.toString(), "scope_key" to key.value, "dataset" to Dataset.DAILY.name,
                        "revision" to revision.digest, "generated_at" to time.toString(),
                        "phase" to file.phase.name, "populated" to "1", "target_handle" to file.targetHandle,
                        "started_at" to time.toString(), "confirmed_at" to confirmation.confirmedAt.toString(),
                        "prior_confirmation_json" to null))
                }
            } }
            assertCountsAndIntegrity()
        }

        suspend fun start(): AttemptState {
            val attempt = AttemptState(UUID.randomUUID().toString(), key, scopes, SyncTrigger.MANUAL,
                time, time, null, listOf(today.minusDays(1)), "b".repeat(64), 0, emptySet())
            assertEquals(CommitStatus.COMMITTED, store().transact(generation,
                SyncMutation(attempt.id, attempt, null, null, null, null)).status)
            return attempt
        }

        suspend fun prepareConfirmation(attempt: AttemptState): FileConfirmation {
            val date = today.minusDays(1)
            val confirmed = store().loadDay(generation, date, key)!!
            assertTrue(confirmed.isUploaded())
            val confirmation = confirmation(date)
            val file = confirmed.datasets.getValue(Dataset.DAILY)
            val pending = confirmed.copy(phase = DayPhase.INDETERMINATE, datasets = mapOf(Dataset.DAILY to
                file.copy(phase = FilePhase.INDETERMINATE, confirmedAt = null, priorConfirmation = confirmation)))
            assertEquals(CommitStatus.COMMITTED, store().transact(generation,
                SyncMutation(attempt.id, null, pending, null, null, null)).status)
            val reopened = store().loadDay(generation, date, key)!!
            assertEquals(pending, reopened)
            assertFalse(reopened.isUploaded())
            assertEquals(revision, reopened.revision)
            assertEquals(confirmation, reopened.datasets.getValue(Dataset.DAILY).priorConfirmation)
            return confirmation
        }

        fun assertRead(read: SyncStateRead, scoped: Boolean) {
            assertEquals(StateStatus.READY, read.status)
            val state = read.state!!
            assertTrue(state.isValid())
            assertEquals(generation, state.generation)
            assertEquals(configuration, state.configuration)
            val yesterday = today.minusDays(1)
            val fullThrough = if (profile == Profile.FULL_EXPLICIT) yesterday else null
            assertEquals(fullThrough, state.coverage.checkedThrough)
            assertEquals(fullThrough, state.coverage.uploadedThrough)
            assertEquals(time.plusSeconds(1), state.coverage.lastUploadAt)
            assertEquals(if (scoped) key else null, state.scopeKey)
            assertEquals(if (scoped) scopes else emptyMap<DataType, ReadScope>(), state.requestedScopes)
            assertEquals(if (scoped) categories - scopes.keys else categories, state.coverage.excludedTypes)
            if (scoped) {
                assertEquals(yesterday, state.coverage.scopeCheckedThrough)
                assertEquals(yesterday, state.coverage.scopeUploadedThrough)
                assertEquals(0L, state.coverage.scopePendingCount)
                assertEquals(0L, state.coverage.scopeBlockedCount)
                val recent = setOf(today.minusDays(2), yesterday)
                assertEquals(recent, state.days.keys)
                assertEquals(recent, state.coverage.recentCheckedDays)
                assertEquals(recent, state.coverage.recentUploadedDays)
                assertTrue(state.days.values.all { it.isUploaded() })
            } else {
                assertNull(state.coverage.scopeCheckedThrough)
                assertNull(state.coverage.scopeUploadedThrough)
                assertNull(state.coverage.scopePendingCount)
                assertNull(state.coverage.scopeBlockedCount)
                assertTrue(state.days.isEmpty())
                assertTrue(state.coverage.recentCheckedDays.isEmpty())
                assertTrue(state.coverage.recentUploadedDays.isEmpty())
            }
            assertTrue(state.oldestPendingDays.isEmpty())
        }

        fun assertCountsAndIntegrity() {
            database.openForRead()!!.use { db ->
                for (table in listOf("day", "dataset")) db.rawQuery("SELECT count(*) FROM $table", null).use {
                    assertTrue(it.moveToFirst())
                    assertEquals(n.toLong(), it.getLong(0))
                }
                db.rawQuery("PRAGMA foreign_key_check", null).use { assertFalse(it.moveToFirst()) }
                db.rawQuery("PRAGMA quick_check", null).use {
                    assertTrue(it.moveToFirst())
                    assertEquals("ok", it.getString(0))
                }
            }
        }

        private fun confirmation(date: LocalDate) = FileConfirmation(
            FileKey(generation, date, key, Dataset.DAILY, revision.digest), "synthetic-target", time.plusSeconds(1))

        private fun values(vararg entries: Pair<String, String?>) = ContentValues().apply {
            entries.forEach { (key, value) -> if (value == null) putNull(key) else put(key, value) }
        }

        fun close() { SQLiteDatabase.deleteDatabase(database.databaseFile) }
    }
}

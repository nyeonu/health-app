package com.ny.my.health.core

import java.io.ByteArrayOutputStream
import java.io.DataOutputStream
import java.time.Instant
import java.time.LocalDate
import java.security.MessageDigest
import org.junit.Assert.*
import org.junit.Test

class SyncRevisionTest {
    private val f = SyncFixture(LocalDate.of(2026, 11, 5), 20)
    private val date = f.today.minusDays(1)
    private val time = f.time
    private fun snapshot(steps: Long? = 12, samples: List<HeartRateSample> = emptyList(),
        scopes: Map<DataType, ReadScope> = f.scopes): ScopedSnapshot = ScopedSnapshot(
        HealthSnapshot(date, steps, emptyList(), samples, emptyList(), emptyList(),
            scopes.filterValues { it != ReadScope.BLOCKED }.keys), scopes,
        scopes.filterValues { it == ReadScope.BLOCKED }.keys)
    private fun previous(revision: DayRevision): DayState = DayState(date, revision.scopeKey, f.scopes,
        revision.key, DayPhase.PENDING, revision.scopes, time, emptyMap(), null)
    private fun rejected(block: () -> Unit) {
        try { block(); fail("거부해야 한다") } catch (_: IllegalArgumentException) { }
    }

    @Test
    fun `Source reread reuses canonical revision despite generated time and advances changed snapshots`() {
        val vectorDate = LocalDate.of(2026, 11, 1)
        val vectorScopes = mapOf(DataType.STEPS to ReadScope.EXPLICIT_DAY)
        val vectorSnapshot = HealthSnapshot(vectorDate, null, emptyList(), emptyList(), emptyList(), emptyList(), vectorScopes.keys)
        val vector = SyncRevision.identify(ScopedSnapshot(vectorSnapshot, vectorScopes, emptySet()), null, time)
        assertEquals("1b5224b5616fb920c2d5d8915f8286076f2622ea1934de16f67b99b24f929a62", vector.scopeKey.value)
        assertEquals("8a568fc7d9d65599f3add30b9c90a5ef26e7f70d58c91a41a71937c7b04be58d", vector.key.digest)

        // 독립적인 계약 순서와 기존 CSV 형식을 명시해 enum 선언 순서 사용을 검출한다.
        fun framedHash(components: List<String>): String {
            val buffer = ByteArrayOutputStream()
            DataOutputStream(buffer).use { output ->
                components.forEach { component ->
                    val bytes = component.toByteArray(Charsets.UTF_8)
                    output.writeInt(bytes.size)
                    output.write(bytes)
                }
            }
            return MessageDigest.getInstance("SHA-256").digest(buffer.toByteArray())
                .joinToString("") { "%02x".format(it.toInt() and 255) }
        }
        val allScopes = DataType.entries.reversed().associateWith { ReadScope.EXPLICIT_DAY }
        val actual = HealthSnapshot(vectorDate, 1, emptyList(),
            listOf(HeartRateSample("hr", "synthetic", Instant.parse("2026-11-01T01:00:00Z"), 70)),
            listOf(SleepSession("sleep", "synthetic", Instant.parse("2026-11-01T01:00:00Z"), Instant.parse("2026-11-01T02:00:00Z"))),
            listOf(ExerciseSession("workout", "synthetic", Instant.parse("2026-11-01T01:00:00Z"), Instant.parse("2026-11-01T02:00:00Z"), 1)),
            allScopes.keys)
        val typeOrder = listOf("EXERCISE", "HEART_RATE", "SLEEP", "STEPS", "WEIGHT")
        val expectedScope = framedHash(listOf("sync-scope/1") + typeOrder.flatMap { listOf(it, "EXPLICIT_DAY") })
        val canonicalFiles = listOf(
            Triple("DAILY", "daily/2026-11-01.csv",
                "schema_version,date,timezone,steps,weight_kg,resting_hr_bpm,sleep_min,exercise_min,generated_at\r\n" +
                    "0.1,2026-11-01,Asia/Seoul,1,,,,,1970-01-01T00:00:00Z\r\n"),
            Triple("HEART_RATE", "heart-rate/2026-11-01.csv",
                "schema_version,record_id,source_package,sample_time,bpm\r\n0.1,hr,synthetic,2026-11-01T01:00:00Z,70\r\n"),
            Triple("SLEEP", "sleep/2026-11-01.csv",
                "schema_version,record_id,source_package,start_time,end_time,duration_min\r\n" +
                    "0.1,sleep,synthetic,2026-11-01T01:00:00Z,2026-11-01T02:00:00Z,60.0\r\n"),
            Triple("WORKOUTS", "workouts/2026-11-01.csv",
                "schema_version,record_id,source_package,start_time,end_time,exercise_type,duration_min\r\n" +
                    "0.1,workout,synthetic,2026-11-01T01:00:00Z,2026-11-01T02:00:00Z,1,60.0\r\n"),
        )
        val expectedComponents = listOf("sync-revision/1", "0.1", "2026-11-01", expectedScope) +
            typeOrder.flatMap { listOf(it, "EXPLICIT_DAY") } + canonicalFiles.flatMap { listOf(it.first, it.second, it.third) }
        val populated = SyncRevision.identify(ScopedSnapshot(actual, allScopes, emptySet()), null, time)
        assertEquals(setOf(Dataset.DAILY, Dataset.HEART_RATE, Dataset.SLEEP, Dataset.WORKOUTS), populated.files.keys)
        assertEquals(expectedScope, populated.scopeKey.value)
        assertEquals(framedHash(expectedComponents), populated.key.digest)

        val scopes = f.scopes + (DataType.HEART_RATE to ReadScope.SUMMARY)
        val sample = HeartRateSample("합성", "합성", DayWindow.start(date).plusSeconds(3600), 70)
        val first = SyncRevision.identify(snapshot(samples = listOf(sample, sample.copy(bpm = 72)), scopes = scopes), null, time)
        val prior = previous(first).copy(requestedScopes = scopes)
        val repeat = SyncRevision.identify(snapshot(samples = listOf(sample.copy(bpm = 72), sample), scopes = scopes), prior, time.plusSeconds(90))
        assertEquals(first.key, repeat.key)
        assertEquals(first.files, repeat.files)
        val changed = SyncRevision.identify(snapshot(13, listOf(sample), scopes), prior, time.plusSeconds(90))
        assertNotEquals(first.key.digest, changed.key.digest)
        assertEquals(time.plusSeconds(90), changed.key.generatedAt)
        val byteDigest = MessageDigest.getInstance("SHA-256").digest(first.files.getValue(Dataset.DAILY).content.toByteArray())
            .joinToString("") { "%02x".format(it.toInt() and 255) }
        assertNotEquals(first.key.digest, byteDigest)
        val narrow = SyncRevision.identify(snapshot(), null, time)
        assertNotEquals(first.scopeKey, narrow.scopeKey)
        assertEquals(first.key, prior.revision)
        rejected { SyncRevision.identify(snapshot(), prior, time) }
        val summary = SyncRevision.identify(snapshot(scopes = mapOf(DataType.STEPS to ReadScope.EXPLICIT_DAY)), null, time)
        assertNotEquals(narrow.key.digest, summary.key.digest)
    }

    @Test
    fun `Disappearing populated datasets retain remote stale and prevent full current without deletion`() {
        val first = SyncRevision.identify(snapshot(), null, time)
        val confirmed = FileConfirmation(FileKey(f.state().generation, date, first.scopeKey, Dataset.DAILY, first.key.digest), "합성-target", time)
        val old = previous(first).copy(phase = DayPhase.CONFIRMED, datasets = mapOf(Dataset.DAILY to
            DatasetState(Dataset.DAILY, first.key, FilePhase.CONFIRMED, true, confirmed.targetHandle, time, time, null)))
        val empty = SyncRevision.identify(snapshot(null), old, time.plusSeconds(10))
        assertTrue(empty.files.isEmpty())
        assertEquals(setOf(Dataset.DAILY), empty.remoteStale)
        val stale = old.copy(revision = empty.key, phase = DayPhase.REMOTE_STALE,
            datasets = mapOf(Dataset.DAILY to old.datasets.getValue(Dataset.DAILY).copy(revision = empty.key,
                phase = FilePhase.REMOTE_STALE, populated = false, confirmedAt = null, priorConfirmation = confirmed)))
        assertTrue(stale.isChecked())
        assertFalse(stale.isUploaded())
        assertEquals(confirmed.targetHandle, stale.datasets.getValue(Dataset.DAILY).targetHandle)
        val blocked = SyncRevision.identify(snapshot(null, scopes = mapOf(DataType.STEPS to ReadScope.BLOCKED)), old, time.plusSeconds(10))
        assertTrue(blocked.remoteStale.isEmpty())
        assertEquals(old.datasets.getValue(Dataset.DAILY).targetHandle, confirmed.targetHandle)
        rejected { SyncRevision.identify(snapshot(null, scopes = mapOf(DataType.STEPS to ReadScope.BLOCKED)), null, time) }
        rejected { SyncRevision.identify(snapshot().copy(blockedTypes = setOf(DataType.STEPS)), old, time) }
    }
}

package com.ny.my.health.core

import java.time.Instant
import java.time.LocalDate
import kotlinx.serialization.json.*
import org.junit.Assert.*
import org.junit.Test

class SyncHistoryTest {
    private val date = LocalDate.of(2026, 1, 2)
    private val requested = Instant.parse("2026-01-03T00:00:00Z")
    private val started = requested.plusSeconds(10)
    private val finished = started.plusSeconds(20)
    private val types = setOf(DataType.STEPS, DataType.SLEEP)
    private val prior = HistorySuccess(date.minusDays(1), requested.minusSeconds(60), setOf(DataType.WEIGHT), 1)
    private val running = HistoryAttempt(date, requested, started, null, HistoryPhase.RUNNING, types, null, null)
    private val history = SyncHistory(1, running, prior)

    @Test
    fun `History codec rejects forbidden fields and oversized or malformed metadata`() {
        val encoded = SyncHistoryCodec.encode(history)
        val root = Json.parseToJsonElement(encoded).jsonObject
        assertEquals(setOf("version", "attempt", "success"), root.keys)
        assertEquals(setOf("date", "authorizationRequestedAt", "startedAt", "finishedAt", "phase", "coverage", "confirmedFiles", "failureCode"), root.getValue("attempt").jsonObject.keys)
        assertEquals(setOf("date", "confirmedAt", "coverage", "confirmedFiles"), root.getValue("success").jsonObject.keys)
        for (forbidden in listOf("token", "session", "generation", "accountId", "fileId", "recordId", "rawMeasurement", "csvBody")) {
            assertCorrupt(JsonObject(root + (forbidden to JsonPrimitive("합성 값"))).toString())
            assertCorrupt(attemptPayload(forbidden, JsonPrimitive("합성 값")))
            val success = root.getValue("success").jsonObject
            assertCorrupt(JsonObject(root + ("success" to JsonObject(success + (forbidden to JsonPrimitive("합성 값"))))).toString())
        }
        for (invalid in listOf("", "null", "[]", "{", encoded.dropLast(1), encoded + "x")) assertCorrupt(invalid)
        assertCorrupt(encoded + " ".repeat(SyncHistoryCodec.MAX_BYTES))
        assertCorrupt(attemptPayload("failureCode", JsonPrimitive("합성 비허용 오류")))
        assertThrows(IllegalArgumentException::class.java) {
            SyncHistoryCodec.encode(history.copy(attempt = running.copy(failureCode = "합성 비허용 오류")))
        }
        val codes = listOf("READ_FAILED", "NORMALIZATION_FAILED", "EXPORT_FAILED", "UPLOAD_FAILED", "SYNC_FAILED") +
            AuthorizationDiagnostics.Failure.entries.map { it.name }
        for (code in codes) {
            val allowed = history.complete(running.copy(phase = HistoryPhase.FAILED, finishedAt = finished, confirmedFiles = 0, failureCode = code))
            assertEquals(allowed, SyncHistoryCodec.decode(SyncHistoryCodec.encode(allowed)).history)
        }
    }

    @Test
    fun `Later non-success outcomes retain independently committed prior success`() {
        for (phase in listOf(HistoryPhase.FAILED, HistoryPhase.EMPTY, HistoryPhase.PERMISSION_REQUIRED, HistoryPhase.INTERRUPTED,
            HistoryPhase.AUTHORIZATION_FAILED, HistoryPhase.AUTHORIZATION_ABANDONED, HistoryPhase.UPLOAD_NOT_STARTED)) {
            val execution = phase !in setOf(HistoryPhase.AUTHORIZATION_FAILED, HistoryPhase.AUTHORIZATION_ABANDONED, HistoryPhase.UPLOAD_NOT_STARTED)
            val terminal = running.copy(phase = phase, startedAt = if (execution) started else null,
                finishedAt = if (phase in setOf(HistoryPhase.AUTHORIZATION_ABANDONED, HistoryPhase.UPLOAD_NOT_STARTED)) null else finished,
                confirmedFiles = if (execution) 0 else null)
            val completed = history.complete(terminal)
            assertEquals(prior, completed.success)
            assertEquals(terminal, completed.attempt)
            assertEquals(completed, SyncHistoryCodec.decode(SyncHistoryCodec.encode(completed)).history)
        }
    }

    @Test
    fun `Recovery distinguishes abandoned authorization from awaiting-upload interruption`() {
        for ((pending, expected) in listOf(HistoryPhase.AUTHORIZING to HistoryPhase.AUTHORIZATION_ABANDONED,
            HistoryPhase.AWAITING_UPLOAD to HistoryPhase.UPLOAD_NOT_STARTED)) {
            val result = history.copy(attempt = running.copy(phase = pending, startedAt = null)).recover()
            assertEquals(running.copy(phase = expected, startedAt = null), result.attempt)
            assertEquals(prior, result.success)
            assertEquals(result, result.recover())
        }
    }

    @Test
    fun `Running recovery never invents terminal time or confirmed upload count`() {
        val result = history.recover()
        assertEquals(running.copy(phase = HistoryPhase.INTERRUPTED), result.attempt)
        assertNull(result.attempt!!.finishedAt)
        assertNull(result.attempt.confirmedFiles)
        assertEquals(prior, result.success)
        assertEquals(result, result.recover())
    }

    @Test
    fun `Terminal restoration preserves actual outcome and prior independent success`() {
        for (phase in listOf(HistoryPhase.SUCCESS, HistoryPhase.EMPTY, HistoryPhase.PERMISSION_REQUIRED, HistoryPhase.FAILED, HistoryPhase.INTERRUPTED)) {
            val terminal = running.copy(phase = phase, finishedAt = finished, confirmedFiles = 2)
            val completed = history.complete(terminal)
            val restored = SyncHistoryCodec.decode(SyncHistoryCodec.encode(completed))
            assertEquals(HistoryReadStatus.READY, restored.status)
            assertEquals(completed, restored.history!!.recover())
            assertEquals(terminal, restored.history.attempt)
            assertEquals(if (phase == HistoryPhase.SUCCESS) HistorySuccess(date, finished, types, 2) else prior, restored.history.success)
        }
    }

    @Test
    fun `Parser accepts exactly the UTF-8 cap and rejects the next byte`() {
        val encoded = SyncHistoryCodec.encode(history)
        val boundary = encoded + " ".repeat(SyncHistoryCodec.MAX_BYTES - encoded.toByteArray(Charsets.UTF_8).size)
        assertEquals(SyncHistoryCodec.MAX_BYTES, boundary.toByteArray(Charsets.UTF_8).size)
        assertEquals(HistoryRead(HistoryReadStatus.READY, history), SyncHistoryCodec.decode(boundary))
        assertCorrupt(boundary + " ")
    }

    @Test
    fun `Unknown versions are unsupported while invalid enums and duplicate categories are corrupt`() {
        val root = Json.parseToJsonElement(SyncHistoryCodec.encode(history)).jsonObject
        assertEquals(HistoryRead(HistoryReadStatus.UNSUPPORTED, null), SyncHistoryCodec.decode(JsonObject(root + ("version" to JsonPrimitive(2))).toString()))
        assertCorrupt(JsonObject(root + ("version" to JsonPrimitive("1"))).toString())
        assertCorrupt(attemptPayload("phase", JsonPrimitive("UNKNOWN")))
        assertCorrupt(attemptPayload("coverage", JsonArray(listOf(JsonPrimitive("UNKNOWN")))))
        assertCorrupt(attemptPayload("coverage", JsonArray(listOf(JsonPrimitive("STEPS"), JsonPrimitive("STEPS")))))
        assertCorrupt(attemptPayload("phase", JsonPrimitive(0)))
        assertCorrupt(attemptPayload("phase", JsonPrimitive("SUCCESS")))
        assertCorrupt(attemptPayload("phase", JsonPrimitive("AUTHORIZING")))
        assertCorrupt(attemptPayload("startedAt", JsonNull))
    }

    @Test
    fun `Negative counts and invalid dates or times are corrupt without default coercion`() {
        assertCorrupt(attemptPayload("confirmedFiles", JsonPrimitive(-1)))
        val root = Json.parseToJsonElement(SyncHistoryCodec.encode(history)).jsonObject
        val success = root.getValue("success").jsonObject
        assertCorrupt(JsonObject(root + ("success" to JsonObject(success + ("confirmedFiles" to JsonPrimitive(-1))))).toString())
        for (value in listOf(JsonPrimitive("0"), JsonPrimitive(0.5), JsonPrimitive(2147483648L))) {
            assertCorrupt(attemptPayload("confirmedFiles", value))
        }
        assertCorrupt(attemptPayload("date", JsonPrimitive("2026-02-30")))
        assertCorrupt(attemptPayload("authorizationRequestedAt", JsonPrimitive("2026-01-03T00:00:00")))
        assertCorrupt(attemptPayload("startedAt", JsonPrimitive("not-an-instant")))
        assertCorrupt(attemptPayload("finishedAt", JsonPrimitive("2026-01-03T25:00:00Z")))
    }

    private fun attemptPayload(key: String, value: JsonElement): String {
        val root = Json.parseToJsonElement(SyncHistoryCodec.encode(history)).jsonObject
        val attempt = root.getValue("attempt").jsonObject
        return JsonObject(root + ("attempt" to JsonObject(attempt + (key to value)))).toString()
    }

    private fun assertCorrupt(payload: String) {
        assertEquals(HistoryRead(HistoryReadStatus.CORRUPT, null), SyncHistoryCodec.decode(payload))
    }
}

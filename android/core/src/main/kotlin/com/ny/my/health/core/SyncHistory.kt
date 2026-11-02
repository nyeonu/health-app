package com.ny.my.health.core

import java.time.Instant
import java.time.LocalDate
import kotlinx.serialization.json.*

/** 마지막 시도와 독립적으로 확정된 마지막 성공만 보관한다. */
data class SyncHistory(val version: Int, val attempt: HistoryAttempt?, val success: HistorySuccess?)

data class HistoryAttempt(
    val date: LocalDate,
    val authorizationRequestedAt: Instant,
    val startedAt: Instant?,
    val finishedAt: Instant?,
    val phase: HistoryPhase,
    val coverage: Set<DataType>,
    val confirmedFiles: Int?,
    val failureCode: String?,
)

data class HistorySuccess(
    val date: LocalDate,
    val confirmedAt: Instant,
    val coverage: Set<DataType>,
    val confirmedFiles: Int,
)

enum class HistoryPhase {
    AUTHORIZING, AWAITING_UPLOAD, RUNNING, SUCCESS, EMPTY, PERMISSION_REQUIRED,
    FAILED, AUTHORIZATION_FAILED, AUTHORIZATION_ABANDONED, UPLOAD_NOT_STARTED, INTERRUPTED,
}

enum class HistoryReadStatus { READY, ABSENT, UNSUPPORTED, CORRUPT, UNAVAILABLE }

data class HistoryRead(val status: HistoryReadStatus, val history: SyncHistory?)

interface HistoryStore {
    suspend fun load(): HistoryRead
    suspend fun replace(history: SyncHistory)
    suspend fun reset()
}

/** 복구는 실행을 재개하지 않으며 종료 시각이나 원격 확인 수를 추정하지 않는다. */
fun SyncHistory.recover(): SyncHistory {
    SyncHistoryCodec.encode(this)
    val current = attempt ?: return this
    val recovered = when (current.phase) {
        HistoryPhase.AUTHORIZING -> HistoryPhase.AUTHORIZATION_ABANDONED
        HistoryPhase.AWAITING_UPLOAD -> HistoryPhase.UPLOAD_NOT_STARTED
        HistoryPhase.RUNNING -> HistoryPhase.INTERRUPTED
        else -> return this
    }
    return copy(attempt = current.copy(phase = recovered, finishedAt = null, confirmedFiles = null))
}

/** 호출자가 관찰한 종결 결과를 적용한다. 성공 이외에는 기존 성공을 유지한다. */
fun SyncHistory.complete(attempt: HistoryAttempt): SyncHistory {
    require(attempt.phase !in setOf(HistoryPhase.AUTHORIZING, HistoryPhase.AWAITING_UPLOAD, HistoryPhase.RUNNING)) {
        "종결 결과가 필요합니다"
    }
    val nextSuccess = if (attempt.phase == HistoryPhase.SUCCESS) {
        HistorySuccess(attempt.date, requireNotNull(attempt.finishedAt), attempt.coverage, requireNotNull(attempt.confirmedFiles))
    } else success
    return copy(attempt = attempt, success = nextSuccess).also { SyncHistoryCodec.encode(it) }
}

/** 앱 전용 메타데이터의 유일한 JSON 경계. 오류에 입력 값을 포함하지 않는다. */
object SyncHistoryCodec {
    const val VERSION = 1
    const val MAX_BYTES = 16 * 1024

    private val errors = setOf("READ_FAILED", "NORMALIZATION_FAILED", "EXPORT_FAILED", "UPLOAD_FAILED", "SYNC_FAILED") +
        AuthorizationDiagnostics.Failure.entries.map { it.name }
    private val rootFields = setOf("version", "attempt", "success")
    private val attemptFields = setOf("date", "authorizationRequestedAt", "startedAt", "finishedAt", "phase", "coverage", "confirmedFiles", "failureCode")
    private val successFields = setOf("date", "confirmedAt", "coverage", "confirmedFiles")

    fun encode(history: SyncHistory): String {
        require(valid(history)) { "유효하지 않은 기록 메타데이터입니다" }
        val payload = buildJsonObject {
            put("version", history.version)
            put("attempt", history.attempt?.let { attempt ->
                buildJsonObject {
                    put("date", attempt.date.toString())
                    put("authorizationRequestedAt", attempt.authorizationRequestedAt.toString())
                    put("startedAt", attempt.startedAt?.let { JsonPrimitive(it.toString()) } ?: JsonNull)
                    put("finishedAt", attempt.finishedAt?.let { JsonPrimitive(it.toString()) } ?: JsonNull)
                    put("phase", attempt.phase.name)
                    put("coverage", coverageJson(attempt.coverage))
                    put("confirmedFiles", attempt.confirmedFiles?.let { JsonPrimitive(it) } ?: JsonNull)
                    put("failureCode", attempt.failureCode?.let { JsonPrimitive(it) } ?: JsonNull)
                }
            } ?: JsonNull)
            put("success", history.success?.let { success ->
                buildJsonObject {
                    put("date", success.date.toString())
                    put("confirmedAt", success.confirmedAt.toString())
                    put("coverage", coverageJson(success.coverage))
                    put("confirmedFiles", success.confirmedFiles)
                }
            } ?: JsonNull)
        }.toString()
        require(payload.toByteArray(Charsets.UTF_8).size <= MAX_BYTES) { "기록 메타데이터 크기를 초과했습니다" }
        return payload
    }

    fun decode(payload: String): HistoryRead {
        if (payload.toByteArray(Charsets.UTF_8).size > MAX_BYTES) return HistoryRead(HistoryReadStatus.CORRUPT, null)
        return try {
            val root = Json.parseToJsonElement(payload).jsonObject
            val version = integer(root.getValue("version"))
            if (version != VERSION) return HistoryRead(HistoryReadStatus.UNSUPPORTED, null)
            require(root.keys == rootFields)
            val attempt = root.getValue("attempt").takeUnless { it == JsonNull }?.jsonObject?.let { obj ->
                require(obj.keys == attemptFields)
                HistoryAttempt(
                    LocalDate.parse(string(obj.getValue("date"))),
                    Instant.parse(string(obj.getValue("authorizationRequestedAt"))),
                    obj.getValue("startedAt").takeUnless { it == JsonNull }?.let { Instant.parse(string(it)) },
                    obj.getValue("finishedAt").takeUnless { it == JsonNull }?.let { Instant.parse(string(it)) },
                    HistoryPhase.valueOf(string(obj.getValue("phase"))),
                    coverage(obj.getValue("coverage")),
                    obj.getValue("confirmedFiles").takeUnless { it == JsonNull }?.let { integer(it) },
                    obj.getValue("failureCode").takeUnless { it == JsonNull }?.let { string(it) },
                )
            }
            val success = root.getValue("success").takeUnless { it == JsonNull }?.jsonObject?.let { obj ->
                require(obj.keys == successFields)
                HistorySuccess(
                    LocalDate.parse(string(obj.getValue("date"))),
                    Instant.parse(string(obj.getValue("confirmedAt"))),
                    coverage(obj.getValue("coverage")),
                    integer(obj.getValue("confirmedFiles")),
                )
            }
            val history = SyncHistory(version, attempt, success)
            require(valid(history))
            HistoryRead(HistoryReadStatus.READY, history)
        } catch (_: IllegalArgumentException) {
            HistoryRead(HistoryReadStatus.CORRUPT, null)
        } catch (_: IllegalStateException) {
            HistoryRead(HistoryReadStatus.CORRUPT, null)
        } catch (_: NoSuchElementException) {
            HistoryRead(HistoryReadStatus.CORRUPT, null)
        } catch (_: java.time.format.DateTimeParseException) {
            HistoryRead(HistoryReadStatus.CORRUPT, null)
        }
    }

    private fun coverageJson(types: Set<DataType>) = JsonArray(types.sortedBy { it.ordinal }.map { JsonPrimitive(it.name) })

    private fun coverage(element: JsonElement): Set<DataType> {
        val types = element.jsonArray.map { DataType.valueOf(string(it)) }
        require(types.size == types.toSet().size)
        return types.toSet()
    }

    private fun string(element: JsonElement): String {
        val primitive = element.jsonPrimitive
        require(primitive.isString)
        return primitive.content
    }

    private fun integer(element: JsonElement): Int {
        val primitive = element.jsonPrimitive
        require(!primitive.isString && primitive.content.matches(Regex("-?(0|[1-9][0-9]*)")))
        return requireNotNull(primitive.intOrNull)
    }

    private fun valid(history: SyncHistory): Boolean {
        if (history.version != VERSION || (history.success?.confirmedFiles ?: 0) < 0) return false
        val attempt = history.attempt ?: return true
        if ((attempt.confirmedFiles ?: 0) < 0 || (attempt.failureCode != null && attempt.failureCode !in errors)) return false
        return when (attempt.phase) {
            HistoryPhase.AUTHORIZING, HistoryPhase.AWAITING_UPLOAD,
            HistoryPhase.AUTHORIZATION_ABANDONED, HistoryPhase.UPLOAD_NOT_STARTED ->
                attempt.startedAt == null && attempt.finishedAt == null && attempt.confirmedFiles == null
            HistoryPhase.RUNNING -> attempt.startedAt != null && attempt.finishedAt == null && attempt.confirmedFiles == null
            HistoryPhase.INTERRUPTED -> attempt.startedAt != null && (attempt.finishedAt != null || attempt.confirmedFiles == null)
            HistoryPhase.SUCCESS -> attempt.startedAt != null && attempt.finishedAt != null && attempt.confirmedFiles != null &&
                attempt.failureCode == null && history.success == HistorySuccess(attempt.date, attempt.finishedAt, attempt.coverage, attempt.confirmedFiles)
            else -> true
        }
    }
}

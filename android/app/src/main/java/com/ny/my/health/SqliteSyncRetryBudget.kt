package com.ny.my.health

import android.content.ContentValues
import android.database.Cursor
import android.database.sqlite.SQLiteDatabase
import com.ny.my.health.core.*
import java.time.LocalDate
import org.json.JSONObject

/** 같은 SQLite 연결에서만 사용하는 제한된 재시도 ledger 접근이다. */
internal object SqliteSyncRetryBudget {
    private fun Cursor.text(name: String): String {
        val i = getColumnIndexOrThrow(name)
        require(getType(i) == Cursor.FIELD_TYPE_STRING)
        return getString(i)
    }
    private fun Cursor.optional(name: String): String? =
        if (isNull(getColumnIndexOrThrow(name))) null else text(name)
    private fun Cursor.number(name: String): Long {
        val i = getColumnIndexOrThrow(name)
        require(getType(i) == Cursor.FIELD_TYPE_INTEGER)
        return getLong(i)
    }
    private fun Cursor.optionalNumber(name: String): Long? =
        if (isNull(getColumnIndexOrThrow(name))) null else number(name)

    private fun revisions(value: Map<LocalDate, String?>): String =
        value.entries.joinToString(",", "{", "}") { (date, revision) ->
            JSONObject.quote(date.toString()) + ":" + (revision?.let(JSONObject::quote) ?: "null")
        }

    fun decode(row: Cursor): RetryBudget {
        val scopes = row.text("requested_scopes_json")
        val datesJson = row.text("dates_json")
        val observations = row.text("observed_revisions_json")
        require(listOf(scopes, datesJson, observations).sumOf { it.toByteArray(Charsets.UTF_8).size } <= 16384)
        val dates = SyncStateCodec.decodeDates(datesJson, 1)
        val json = JSONObject(observations)
        require(json.keys().asSequence().toSet() == dates.map { it.toString() }.toSet())
        val revisions = dates.associateWith { date ->
            if (json.isNull(date.toString())) null
            else (json.get(date.toString()) as? String)?.let(SyncStateCodec::decodeDigest)
                ?: throw IllegalArgumentException("잘못된 재시도 상태")
        }
        require(revisions(revisions) == observations)
        val count = row.number("retries_used")
        require(count in 0L..3L)
        return RetryBudget.validated(SyncStateCodec.decodeUuid(row.text("lineage_id")),
            DestinationGeneration(SyncStateCodec.decodeUuid(row.text("generation"))),
            ReadScopeKey(SyncStateCodec.decodeDigest(row.text("scope_key"))),
            SyncStateCodec.decodeScopes(scopes, 1), dates, revisions, count.toInt(),
            row.optional("retry_not_before")?.let(SyncStateCodec::decodeInstant), row.optionalNumber("retry_delay_millis"),
            row.number("claim_version"), row.optional("owner_attempt_id")?.let(SyncStateCodec::decodeUuid),
            enumValueOf(row.text("status")), SyncStateCodec.decodeInstant(row.text("created_at")),
            SyncStateCodec.decodeInstant(row.text("updated_at")))
    }

    fun find(db: SQLiteDatabase, generation: DestinationGeneration, scope: ReadScopeKey): RetryBudget? =
        db.rawQuery("SELECT * FROM retry_budget WHERE generation=? AND scope_key=? AND status<>?",
            arrayOf(generation.value, scope.value, RetryBudgetStatus.COMPLETED.name)).use { rows ->
            if (!rows.moveToFirst()) null else decode(rows).also { require(!rows.moveToNext()) }
        }

    fun findLineage(db: SQLiteDatabase, lineageId: String): RetryBudget? =
        db.rawQuery("SELECT * FROM retry_budget WHERE lineage_id=?", arrayOf(lineageId)).use { rows ->
            if (!rows.moveToFirst()) null else decode(rows).also { require(!rows.moveToNext()) }
        }

    fun values(budget: RetryBudget): ContentValues = ContentValues().apply {
        put("lineage_id", budget.lineageId)
        put("generation", budget.generation.value)
        put("scope_key", budget.scopeKey.value)
        val scopes = SyncStateCodec.encodeScopes(budget.requestedScopes)
        val dates = SyncStateCodec.encodeDates(budget.dates)
        val observations = revisions(budget.observedRevisions)
        require(listOf(scopes, dates, observations).sumOf { it.toByteArray(Charsets.UTF_8).size } <= 16384)
        put("requested_scopes_json", scopes)
        put("dates_json", dates)
        put("observed_revisions_json", observations)
        put("retries_used", budget.retriesUsed)
        put("retry_not_before", budget.retryNotBefore?.toString())
        if (budget.retryDelayMillis == null) putNull("retry_delay_millis") else put("retry_delay_millis", budget.retryDelayMillis)
        put("claim_version", budget.claimVersion)
        put("owner_attempt_id", budget.ownerAttemptId)
        put("status", budget.status.name)
        put("created_at", budget.createdAt.toString())
        put("updated_at", budget.updatedAt.toString())
    }
}

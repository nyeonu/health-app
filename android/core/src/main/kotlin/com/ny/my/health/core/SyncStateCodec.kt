package com.ny.my.health.core

import java.time.Instant
import java.time.LocalDate
import kotlinx.serialization.json.*

/** SQL의 JSON 말단만 정규 형식으로 변환한다. */
object SyncStateCodec {
    private const val LIMIT = 16 * 1024
    private fun write(value: JsonElement): String = value.toString().also {
        require(it.toByteArray(Charsets.UTF_8).size <= LIMIT)
    }
    private fun <T> read(text: String, version: Int, decode: (JsonElement) -> T, encode: (T) -> String): T {
        if (version != 1) throw UnsupportedOperationException("지원하지 않는 형식")
        require(text.toByteArray(Charsets.UTF_8).size <= LIMIT)
        try {
            val element = Json.parseToJsonElement(text)
            fun depth(node: JsonElement, level: Int) {
                require(level <= 2)
                when (node) {
                    is JsonObject -> node.values.forEach { depth(it, level + 1) }
                    is JsonArray -> node.forEach { depth(it, level + 1) }
                    else -> Unit
                }
            }
            depth(element, 0)
            return decode(element).also { require(encode(it) == text) }
        } catch (error: Exception) {
            throw IllegalArgumentException("잘못된 상태 말단", error)
        }
    }
    private fun string(value: JsonElement): String {
        require(value is JsonPrimitive && value.isString)
        return value.content
    }
    private inline fun <reified T : Enum<T>> enums(value: JsonElement, max: Int): Set<T> {
        require(value is JsonArray && value.size <= max)
        return value.map { enumValueOf<T>(string(it)) }.also { require(it.size == it.distinct().size) }.toSet()
    }
    private fun names(value: Collection<Enum<*>>, max: Int): String {
        require(value.size <= max)
        return write(JsonArray(value.map { it.name }.sorted().map(::JsonPrimitive)))
    }
    fun encodeCategories(value: Set<DataType>): String = names(value, 5)
    fun decodeCategories(json: String, formatVersion: Int): Set<DataType> =
        read(json, formatVersion, { enums<DataType>(it, 5) }, ::encodeCategories)
    fun encodeTriggers(value: Set<SyncTrigger>): String = names(value, 3)
    fun decodeTriggers(json: String, formatVersion: Int): Set<SyncTrigger> =
        read(json, formatVersion, { enums<SyncTrigger>(it, 3) }, ::encodeTriggers)
    fun encodeScopes(value: Map<DataType, ReadScope>): String {
        require(value.size <= 5)
        return write(JsonObject(value.entries.sortedBy { it.key.name }.associate { it.key.name to JsonPrimitive(it.value.name) }))
    }
    fun decodeScopes(json: String, formatVersion: Int): Map<DataType, ReadScope> = read(json, formatVersion, {
        require(it is JsonObject && it.size <= 5)
        it.map { (key, value) -> enumValueOf<DataType>(key) to enumValueOf<ReadScope>(string(value)) }.toMap()
    }, ::encodeScopes)
    fun encodeDates(value: List<LocalDate>): String {
        require(value.size <= 7 && value == value.distinct().sorted() && value.all(::validDate))
        return write(JsonArray(value.map { JsonPrimitive(it.toString()) }))
    }
    fun decodeDates(json: String, formatVersion: Int): List<LocalDate> = read(json, formatVersion, {
        require(it is JsonArray && it.size <= 7)
        it.map { date -> LocalDate.parse(string(date)) }
    }, ::encodeDates)
    fun encodeConfirmation(value: FileConfirmation): String {
        require(value.isValid())
        val key = value.key
        return write(JsonObject(linkedMapOf(
            "generation" to JsonPrimitive(key.generation.value), "date" to JsonPrimitive(key.date.toString()),
            "scopeKey" to JsonPrimitive(key.scopeKey.value), "dataset" to JsonPrimitive(key.dataset.name),
            "revision" to JsonPrimitive(key.revision), "targetHandle" to JsonPrimitive(value.targetHandle),
            "confirmedAt" to JsonPrimitive(value.confirmedAt.toString()))))
    }
    fun decodeConfirmation(json: String, formatVersion: Int): FileConfirmation = read(json, formatVersion, {
        require(it is JsonObject && it.keys == setOf("generation", "date", "scopeKey", "dataset", "revision", "targetHandle", "confirmedAt"))
        fun field(name: String) = string(it.getValue(name))
        FileConfirmation(FileKey(DestinationGeneration(field("generation")), LocalDate.parse(field("date")),
            ReadScopeKey(field("scopeKey")), enumValueOf<Dataset>(field("dataset")), field("revision")),
            field("targetHandle"), Instant.parse(field("confirmedAt")))
    }, ::encodeConfirmation)
}

internal fun validDate(value: LocalDate): Boolean = value.year in 1970..9999
internal fun validInstant(value: Instant): Boolean = value >= Instant.parse("1970-01-01T00:00:00Z") && value < Instant.parse("+10000-01-01T00:00:00Z")
internal fun validHex(value: String): Boolean = value.matches(Regex("[0-9a-f]{64}"))
internal fun validUuid(value: String): Boolean = value.matches(Regex("[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}"))
internal fun validHandle(value: String): Boolean = value.toByteArray(Charsets.UTF_8).size in 1..4096 &&
    value.none { it.isWhitespace() || it.isISOControl() } && Charsets.UTF_8.newEncoder().canEncode(value)
internal fun scopeFingerprint(scopes: Map<DataType, ReadScope>): ReadScopeKey? = SyncPlanner.scopeKey(
    SyncConfiguration("검증", LocalDate.of(1970, 1, 1), false, scopes.keys), ReadAccess(true, scopes.keys, scopes))

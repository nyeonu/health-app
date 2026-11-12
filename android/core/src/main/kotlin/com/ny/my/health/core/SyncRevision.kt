package com.ny.my.health.core

import java.nio.ByteBuffer
import java.security.MessageDigest
import java.time.Instant

/** 정규 CSV 투영을 이용해 읽기 시각과 독립적인 의미 리비전을 만든다. */
object SyncRevision {
    fun identify(snapshot: ScopedSnapshot, previous: DayState?, observedAt: Instant): DayRevision {
        require(validInstant(observedAt) && validDate(snapshot.snapshot.date))
        val scopes = snapshot.scopeByType
        require(scopes.isNotEmpty() && scopes.size <= 5)
        require(snapshot.blockedTypes == scopes.filterValues { it == ReadScope.BLOCKED }.keys)
        val positive = scopes.filterValues { it != ReadScope.BLOCKED }
        require(snapshot.snapshot.grantedTypes == positive.keys)
        val requested = previous?.requestedScopes ?: positive
        require(requested.isNotEmpty() && requested.values.none { it == ReadScope.BLOCKED })
        val scopeKey = requireNotNull(scopeFingerprint(requested))
        if (previous == null) require(snapshot.blockedTypes.isEmpty()) else {
            require(previous.date == snapshot.snapshot.date && previous.scopeKey == scopeKey)
            require(scopes.keys == requested.keys)
            require(scopes.all { (type, scope) -> type in requested && (scope == ReadScope.BLOCKED || requested[type] == scope) })
        }
        fun export(at: Instant): Map<Dataset, CsvFile> = VersionedCsvExporter()
            .export(DefaultNormalizer().normalize(snapshot.snapshot, at)).associateBy { file ->
                when (file.relativePath.substringBefore('/')) {
                    "daily" -> Dataset.DAILY
                    "workouts" -> Dataset.WORKOUTS
                    "heart-rate" -> Dataset.HEART_RATE
                    "sleep" -> Dataset.SLEEP
                    else -> error("알 수 없는 데이터셋")
                }
            }
        val digest = MessageDigest.getInstance("SHA-256")
        fun add(value: String) {
            val bytes = value.toByteArray(Charsets.UTF_8)
            digest.update(ByteBuffer.allocate(4).putInt(bytes.size).array())
            digest.update(bytes)
        }
        add("sync-revision/1")
        add("0.1")
        add(snapshot.snapshot.date.toString())
        add(scopeKey.value)
        scopes.entries.sortedBy { it.key.name }.forEach { (type, scope) ->
            add(type.name); add(scope.name)
        }
        export(Instant.EPOCH).entries.sortedBy { it.key.name }.forEach { (dataset, file) ->
            add(dataset.name); add(file.relativePath); add(file.content)
        }
        val hash = digest.digest().joinToString("") { "%02x".format(it.toInt() and 255) }
        val key = previous?.revision?.takeIf { it.digest == hash } ?: RevisionKey(hash, observedAt)
        val files = export(key.generatedAt)
        val missing = if (scopes == requested) previous?.datasets.orEmpty().filter { (dataset, state) ->
            dataset !in files && (state.populated || state.phase == FilePhase.REMOTE_STALE)
        }.keys else emptySet()
        return DayRevision(key, scopeKey, scopes, files, missing)
    }
}

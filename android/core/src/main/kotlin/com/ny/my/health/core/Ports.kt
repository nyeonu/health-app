package com.ny.my.health.core

import java.time.Instant
import java.time.LocalDate
import kotlinx.coroutines.ensureActive

interface HealthReader {
    suspend fun read(date: LocalDate): HealthSnapshot
}

interface Normalizer {
    fun normalize(snapshot: HealthSnapshot, generatedAt: Instant): NormalizedDay
}

interface CsvExporter {
    fun export(day: NormalizedDay): List<CsvFile>
}

interface DriveUploader {
    suspend fun upsert(file: CsvFile): String

    suspend fun upsertBatch(files: List<CsvFile>, onConfirmed: suspend (String) -> Unit) {
        for (file in files) {
            kotlinx.coroutines.currentCoroutineContext().ensureActive()
            val id = upsert(file)
            check(id.isNotBlank()) { "Unconfirmed Drive upload" }
            onConfirmed(id)
        }
    }
}

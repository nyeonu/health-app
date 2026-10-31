package dev.nyeonu.healthapp.core

import java.time.Instant
import java.time.LocalDate

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
}

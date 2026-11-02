package com.ny.my.health

import android.database.sqlite.SQLiteDatabase
import android.system.Os
import android.system.OsConstants
import java.io.File
import java.io.FileOutputStream
import java.nio.file.Files

/** Explicit reset only. A pending directory is an error on reads, never a recovery command. */
internal class HistoryResetRecovery(private val database: HistoryDatabase) {
    private val target = database.databaseFile
    private val directory = database.resetDirectory
    private val parent = checkNotNull(target.parentFile)
    private val suffixes = listOf("", "-journal", "-wal", "-shm")

    // Caller holds the shared path lock and has closed all connections to target.
    fun reset(
        beforeInstall: () -> Unit,
        writeEmpty: (SQLiteDatabase) -> Unit,
        validateEmpty: (SQLiteDatabase) -> Unit,
    ) {
        suffixes.map { File(target.path + it) }
            .filter { it.exists() || Files.isSymbolicLink(it.toPath()) }.forEach(::requireRegular)
        if (!directory.exists()) check(directory.mkdir())
        check(directory.isDirectory && !Files.isSymbolicLink(directory.toPath()))
        syncDirectory(parent) // The marker is durable before any original path can change.

        val candidate = File(directory, "empty.db")
        suffixes.map { File(candidate.path + it) }.filter { it.exists() }.forEach(::removeFile)
        val fresh = HistoryDatabase(candidate)
        fresh.transaction(block = writeEmpty)
        checkNotNull(fresh.openForRead()).use { connection ->
            connection.rawQuery("PRAGMA integrity_check", null).use { rows ->
                check(rows.moveToFirst() && rows.getString(0) == "ok" && !rows.moveToNext())
            }
            validateEmpty(connection)
        }
        syncFile(candidate)
        syncDirectory(directory)

        val prepared = File(directory, "prepared")
        if (!prepared.exists()) {
            // No original is retired until the entire SQLite family has a durable snapshot.
            for ((index, suffix) in suffixes.withIndex()) {
                val original = File(target.path + suffix)
                val snapshot = File(directory, "old-$index")
                if (snapshot.exists()) removeFile(snapshot)
                if (original.exists()) {
                    requireRegular(original)
                    original.inputStream().use { input ->
                        FileOutputStream(snapshot).use { output ->
                            input.copyTo(output)
                            output.fd.sync()
                        }
                    }
                }
            }
            syncDirectory(directory)
            FileOutputStream(prepared).use { it.fd.sync() }
            syncDirectory(directory)
        } else requireRegular(prepared)

        beforeInstall() // Existing cancellation/failure seam; original files are still intact.
        for (suffix in suffixes.drop(1)) {
            val sidecar = File(target.path + suffix)
            if (sidecar.exists()) removeFile(sidecar)
        }
        syncDirectory(parent)
        Os.rename(candidate.path, target.path) // Atomic same-filesystem installation.
        syncDirectory(parent)
        // Only explicit reset bypasses the marker to validate the installed file.
        database.openInstalledAfterReset().use(validateEmpty)

        // Never acknowledge while forgotten history remains in recovery snapshots.
        val children = checkNotNull(directory.listFiles())
        children.forEach(::removeFile)
        syncDirectory(directory)
        check(directory.delete())
        syncDirectory(parent)
    }

    private fun requireRegular(file: File) {
        check(file.isFile && !Files.isSymbolicLink(file.toPath())) { "기록 DB 파일을 사용할 수 없습니다" }
    }

    private fun removeFile(file: File) {
        requireRegular(file)
        check(file.delete())
    }

    private fun syncFile(file: File) = FileOutputStream(file, true).use { it.fd.sync() }

    private fun syncDirectory(file: File) {
        val descriptor = Os.open(file.path, OsConstants.O_RDONLY, 0)
        try {
            check(OsConstants.S_ISDIR(Os.fstat(descriptor).st_mode))
            Os.fsync(descriptor)
        } finally {
            Os.close(descriptor)
        }
    }
}

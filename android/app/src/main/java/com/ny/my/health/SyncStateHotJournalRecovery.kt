package com.ny.my.health

import android.database.sqlite.SQLiteDatabase
import android.database.sqlite.SQLiteException
import android.system.ErrnoException
import android.system.Os
import android.system.OsConstants
import android.system.StructStat
import java.io.File
import java.io.FileDescriptor
import java.security.MessageDigest
import java.util.UUID

/** 같은 앱의 private parent와 단일 프로세스 pathLock 아래에서만 복구한다. raw는 SQLite로 열지 않는다. */
internal class SyncStateHotJournalRecovery(private val database: SyncStateDatabase) {
    fun recover(validateContent: (SQLiteDatabase) -> Unit, checkCancelled: () -> Unit) {
        check(Thread.holdsLock(database.pathLock))
        val source = database.databaseFile
        checkCancelled()
        requireRollbackPair(source)
        val before = pair(source,checkCancelled)
        val raw = preserve(source,before,checkCancelled)
        database.observe(SyncStatePhase.HOT_RAW_SAVED)
        checkCancelled()
        validateRaw(source,raw,checkCancelled)
        val owner = UUID.randomUUID().toString()
        val work = File(source.parentFile,"${source.name}.hot-work-$owner")
        makeDirectory(work)
        writeNew(File(work,"owner"),owner.toByteArray())
        var failure: Throwable? = null
        try {
            val candidate = File(work,"state.db")
            copy(File(raw,"raw/state.db"),candidate,checkCancelled)
            copy(File(raw,"raw/state.db-journal"),File(work,"state.db-journal"),checkCancelled)
            checkCancelled()
            // raw 복제 자체는 격리 수단이 아니다. 모든 native open 앞에서 외부 참조를 거부한다.
            SyncStateDatabase(candidate).openExistingForRollback().use { db ->
                checkCancelled()
                validateContent(db)
                checkCancelled()
            }
            database.observe(SyncStatePhase.HOT_CANARY_VALIDATED)
            checkCancelled()
            require(pair(source,checkCancelled) == before)
            validateRaw(source,raw,checkCancelled)
            validateWork(work,owner)
            database.observe(SyncStatePhase.HOT_BEFORE_ORIGINAL)
            checkCancelled()
            validateRaw(source,raw,checkCancelled)
            validateWork(work,owner)
            requireRollbackPair(source)
            require(pair(source,checkCancelled) == before)
            checkCancelled()
            // Java path open에 NOFOLLOW 보장은 없다. 위의 즉시 재검사와 private parent/잠금이 전제다.
            database.openExistingForRollback().use { db ->
                validateContent(db)
            }
            database.observe(SyncStatePhase.HOT_ORIGINAL_ROLLED_BACK)
            checkCancelled()
        } catch (error: Throwable) {
            failure = error
            throw error
        } finally {
            try { cleanupWork(work,owner) } catch (cleanupError: Throwable) {
                val original = failure
                if (original == null) throw cleanupError
                // 정리 경로ㆍ원시 IO 예외를 덧붙이지 않으며 최초 취소/실패 객체를 보존한다.
                original.addSuppressed(IllegalStateException("동기화 복구 작업 정리 실패"))
            }
        }
    }

    private fun preserve(source: File, expected: PairProof, cancel: () -> Unit): File {
        val complete = File(source.parentFile,"${source.name}.hot-journal-${fingerprint(expected)}")
        if (exists(complete)) {
            validateRaw(source,complete,cancel)
            require(rawPair(complete,cancel).content() == expected.content())
            return complete
        }
        val owner = UUID.randomUUID().toString()
        val prepare = File(source.parentFile,"${source.name}.hot-journal.prepare-$owner")
        makeDirectory(prepare)
        val raw = File(prepare,"raw")
        makeDirectory(raw)
        copy(source,File(raw,"state.db"),cancel)
        copy(File(source.path + "-journal"),File(raw,"state.db-journal"),cancel)
        require(rawPair(prepare,cancel).content() == expected.content())
        require(pair(source,cancel) == expected)
        writeNew(File(prepare,"manifest"),manifest(owner,expected))
        syncDirectory(raw); syncDirectory(prepare)
        // 완료 경로의 충돌은 덮어쓰거나 삭제하지 않는다.
        require(!exists(complete))
        Os.rename(prepare.path,complete.path)
        syncDirectory(checkNotNull(source.parentFile))
        validateRaw(source,complete,cancel)
        return complete
    }

    private data class Proof(val device: Long, val inode: Long, val size: Long, val hash: String)
    private data class PairProof(val db: Proof, val journal: Proof) {
        fun content() = listOf(db.size.toString(),db.hash,journal.size.toString(),journal.hash)
    }

    companion object {
        private val magic = byteArrayOf(0xd9.toByte(),0xd5.toByte(),5,0xf9.toByte(),0x20,0xa1.toByte(),0x63,0xd7.toByte())
        private val uuid = Regex("[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}")
        private val hashPattern = Regex("[0-9a-f]{64}")
        private const val protocol = "sync-hot-journal/1"

        fun guardSidecarPaths(databaseFile: File) {
            try {
                privateParent(databaseFile)
                val companions = listOf(File(databaseFile.path + "-journal"),
                    File(databaseFile.path + "-wal"),File(databaseFile.path + "-shm"))
                if (status(databaseFile) == null && companions.any { status(it) != null }) {
                    throw SQLiteException("원본 없는 동기화 DB 보조 파일을 보존합니다")
                }
                for (file in listOf(databaseFile) + companions) {
                    val stat = status(file) ?: continue
                    regular(stat)
                    withRead(file) { fd ->
                        require(identity(Os.fstat(fd)) == identity(stat))
                        if (file.name.endsWith("-journal")) {
                            // 빈 정상 journal은 허용하되 짧은 비어 있지 않은 footer는 추측하지 않는다.
                            if (stat.st_size > 0) {
                                require(stat.st_size >= 8)
                                require(!exact(fd,stat.st_size - 8,8).contentEquals(magic))
                            }
                        }
                        require(identity(Os.fstat(fd)) == identity(stat))
                    }
                }
            } catch (_: IllegalArgumentException) {
                throw SQLiteException("동기화 DB 경로를 사용할 수 없습니다")
            }
        }

        internal fun requireRollbackPair(file: File) {
            guardSidecarPaths(file)
            require(!exists(File(file.path + "-wal")) && !exists(File(file.path + "-shm")))
            require(!exists(File(file.parentFile,"${file.name}.reset")))
            withRead(file) { db ->
                val size = Os.fstat(db).st_size
                val header = exact(db,0,100)
                require(header.copyOfRange(0,16).contentEquals("SQLite format 3\u0000".toByteArray(Charsets.US_ASCII)))
                require(header[18].toInt() == 1 && header[19].toInt() == 1)
                val encoded = ((header[16].toInt() and 255) shl 8) or (header[17].toInt() and 255)
                val page = if (encoded == 1) 65536L else encoded.toLong()
                require(power(page,512,65536) && size >= page && size % page == 0L)
                withRead(File(file.path + "-journal")) { journal ->
                    val journalSize = Os.fstat(journal).st_size
                    val head = exact(journal,0,28)
                    require(head.copyOfRange(0,8).contentEquals(magic))
                    val records = unsigned(head,8)
                    val pages = unsigned(head,16)
                    val sector = unsigned(head,20)
                    require(power(sector,512,65536) && unsigned(head,24) == page && pages > 0)
                    require(records > 0 && journalSize >= sector + page + 8)
                    if (records != 0xffffffffL) require(records <= (journalSize - sector) / (page + 8))
                    require(unsigned(exact(journal,sector,4),0) in 1..pages)
                }
            }
        }

        private fun power(value: Long, min: Long, max: Long) = value in min..max && value and (value - 1) == 0L
        private fun unsigned(bytes: ByteArray, start: Int): Long {
            var value = 0L
            for (i in start until start + 4) value = (value shl 8) or (bytes[i].toLong() and 255)
            return value
        }
        private fun privateParent(file: File) {
            // 정상 Context alias와 ./는 같은 private parent로 판정한다. 마지막 leaf는 따라가지 않는다.
            val parent = checkNotNull(file.absoluteFile.parentFile).canonicalFile
            // AOSP ContextImpl의 no_backup은 0771이다. group-write는 같은 앱의 primary GID만 허용한다.
            val stat = Os.lstat(parent.path)
            require(OsConstants.S_ISDIR(stat.st_mode) && stat.st_uid == Os.getuid() &&
                stat.st_mode and 0x2 == 0 && (stat.st_mode and 0x10 == 0 || stat.st_gid == Os.getgid()))
        }
        private fun status(file: File): StructStat? = try { Os.lstat(file.path) } catch (error: ErrnoException) {
            if (error.errno == OsConstants.ENOENT) null else throw error
        }
        private fun exists(file: File) = status(file) != null
        internal fun regular(stat: StructStat) {
            require(OsConstants.S_ISREG(stat.st_mode) && stat.st_nlink == 1L && stat.st_uid == Os.getuid())
        }
        private fun identity(stat: StructStat) = listOf(stat.st_dev,stat.st_ino,stat.st_size,stat.st_nlink)
        private fun <T> withRead(file: File, block: (FileDescriptor) -> T): T {
            privateParent(file)
            val fd = Os.open(file.path,OsConstants.O_RDONLY or OsConstants.O_NOFOLLOW or OsConstants.O_CLOEXEC,0)
            try { regular(Os.fstat(fd)); return block(fd) } finally { Os.close(fd) }
        }
        private fun exact(fd: FileDescriptor, offset: Long, length: Int): ByteArray {
            val result = ByteArray(length)
            var position = 0
            while (position < length) {
                val count = Os.pread(fd,result,position,length - position,offset + position)
                require(count > 0)
                position += count
            }
            return result
        }
        private fun inspect(file: File, cancel: () -> Unit, output: FileDescriptor? = null): Proof = withRead(file) { fd ->
            cancel()
            val before = Os.fstat(fd)
            val digest = MessageDigest.getInstance("SHA-256")
            val buffer = ByteArray(65536)
            var total = 0L
            while (true) {
                cancel()
                val count = Os.read(fd,buffer,0,buffer.size)
                if (count == 0) break
                require(count > 0)
                digest.update(buffer,0,count)
                if (output != null) writeFully(output,buffer,count)
                total += count
            }
            require(total == before.st_size && identity(before) == identity(Os.fstat(fd)))
            require(identity(before) == identity(Os.lstat(file.path)))
            Proof(before.st_dev,before.st_ino,total,hex(digest.digest()))
        }
        private fun pair(file: File, cancel: () -> Unit): PairProof {
            cancel(); requireRollbackPair(file)
            val proof = PairProof(inspect(file,cancel),inspect(File(file.path + "-journal"),cancel))
            requireRollbackPair(file)
            return proof
        }
        private fun rawPair(directory: File, cancel: () -> Unit) = PairProof(
            inspect(File(directory,"raw/state.db"),cancel),inspect(File(directory,"raw/state.db-journal"),cancel))
        private fun fingerprint(pair: PairProof) = hex(MessageDigest.getInstance("SHA-256").digest(pair.content().joinToString("\n").toByteArray()))
        private fun hex(bytes: ByteArray) = bytes.joinToString("") { "%02x".format(it.toInt() and 255) }
        private fun manifest(owner: String, pair: PairProof): ByteArray {
            require(uuid.matches(owner) && UUID.fromString(owner).toString() == owner)
            return ("protocol=$protocol\nowner=$owner\ndb_sha256=${pair.db.hash}\njournal_sha256=${pair.journal.hash}\n" +
                "db_size=${pair.db.size}\njournal_size=${pair.journal.size}\n").toByteArray(Charsets.US_ASCII).also { require(it.size <= 512) }
        }
        private fun directory(file: File) {
            privateParent(file)
            val stat = Os.lstat(file.path)
            require(OsConstants.S_ISDIR(stat.st_mode) && stat.st_uid == Os.getuid() &&
                stat.st_mode and 0x2 == 0 && (stat.st_mode and 0x10 == 0 || stat.st_gid == Os.getgid()))
        }
        private fun entries(file: File, allowed: Set<String>) {
            directory(file)
            require(checkNotNull(file.list()).toSet() == allowed)
        }
        private fun validateRaw(source: File, directory: File, cancel: () -> Unit): List<File> {
            entries(directory,setOf("manifest","raw"))
            val raw = File(directory,"raw")
            entries(raw,setOf("state.db","state.db-journal"))
            val bytes = withRead(File(directory,"manifest")) { fd ->
                val size = Os.fstat(fd).st_size
                require(size in 1..512)
                exact(fd,0,size.toInt()).also { require(Os.fstat(fd).st_size == size) }
            }
            val fields = String(bytes,Charsets.US_ASCII).split('\n')
            require(fields.size == 7 && fields[6].isEmpty())
            require(fields[0] == "protocol=$protocol")
            val owner = fields[1].removePrefix("owner=")
            val proof = rawPair(directory,cancel)
            require(hashPattern.matches(proof.db.hash) && hashPattern.matches(proof.journal.hash))
            require(bytes.contentEquals(manifest(owner,proof)))
            require(directory.name == "${source.name}.hot-journal-${fingerprint(proof)}")
            return listOf(File(raw,"state.db"),File(raw,"state.db-journal"),raw,File(directory,"manifest"),directory)
        }
        /** 전용 합성 host도 동일한 owner/layout/hash 검증을 끝낸 뒤에만 삭제 목록을 받는다. */
        internal fun ownedRawFiles(source: File, directory: File): List<File> = validateRaw(source,directory,{})
        private fun makeDirectory(file: File) { require(!exists(file)); Os.mkdir(file.path,448); directory(file) }
        private fun writeFully(fd: FileDescriptor, bytes: ByteArray, size: Int) {
            var offset = 0
            while (offset < size) {
                val count = Os.write(fd,bytes,offset,size - offset)
                require(count > 0); offset += count
            }
        }
        private fun <T> newOutput(file: File, block: (FileDescriptor) -> T): T {
            privateParent(file)
            val fd = Os.open(file.path,OsConstants.O_WRONLY or OsConstants.O_CREAT or OsConstants.O_EXCL or
                OsConstants.O_NOFOLLOW or OsConstants.O_CLOEXEC,384)
            try { regular(Os.fstat(fd)); val result = block(fd); Os.fsync(fd); return result } finally { Os.close(fd) }
        }
        private fun writeNew(file: File, bytes: ByteArray) = newOutput(file) { writeFully(it,bytes,bytes.size) }
        private fun copy(source: File, target: File, cancel: () -> Unit) = newOutput(target) { inspect(source,cancel,it) }
        private fun syncDirectory(file: File) {
            directory(file)
            val fd = Os.open(file.path,OsConstants.O_RDONLY or OsConstants.O_NOFOLLOW or OsConstants.O_CLOEXEC,0)
            try { require(OsConstants.S_ISDIR(Os.fstat(fd).st_mode)); Os.fsync(fd) } finally { Os.close(fd) }
        }
        private fun validateWork(work: File, owner: String): List<File> {
            // 알 수 없는 entry가 하나라도 있으면 owner를 포함해 어떤 파일도 먼저 지우지 않는다.
            directory(work)
            require(work.name.endsWith(".hot-work-$owner") && uuid.matches(owner))
            val children = checkNotNull(work.listFiles()).toList()
            require(children.map { it.name }.toSet().let { names -> "owner" in names &&
                names.all { it in setOf("owner","state.db","state.db-journal") } })
            children.forEach { regular(Os.lstat(it.path)); withRead(it) {} }
            withRead(File(work,"owner")) { fd ->
                require(Os.fstat(fd).st_size == 36L && String(exact(fd,0,36),Charsets.US_ASCII) == owner)
            }
            return children
        }
        private fun cleanupWork(work: File, owner: String) {
            val children = validateWork(work,owner)
            children.forEach { require(it.delete()) }
            require(work.delete())
        }
    }
}

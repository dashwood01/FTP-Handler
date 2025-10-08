package com.dashwood.ftphandler.ftp_service

import org.apache.commons.net.ftp.FTP
import org.apache.commons.net.ftp.FTPClient
import org.apache.commons.net.ftp.FTPSClient
import org.apache.commons.net.ftp.FTPFile
import java.io.*
import java.net.SocketException
import java.nio.charset.StandardCharsets
import java.time.Duration
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicInteger
import android.os.Handler
import android.os.Looper
import com.dashwood.ftphandler.listener.OnFTPJobListener
import com.dashwood.ftphandler.model.FileModel
import com.dashwood.ftphandler.models.DownloadItem
import com.dashwood.ftphandler.models.UploadItem
import javax.net.ssl.SSLContext

/**
 * FTPService — Listener-based FTP/FTPS helper for Android with multi + resumable transfers.
 */
class FTPService(
    private val host: String,
    private val port: Int = 21,
    private val username: String,
    private val password: String,
    private val useFtps: Boolean = false,
    private val connectTimeoutMs: Int = 15_000,
    private val dataTimeoutMs: Long = 30_000L,
    private val bufferSize: Int = 1024 * 1024
) {
    enum class Action { CONNECT, DISCONNECT, UPLOAD, DOWNLOAD, LIST, DELETE, MKDIR, RMDIR, RENAME }
    enum class Type(val label: String) { DIR("Directory"), FILE("File"), LINK("Symbolic Link") }

    sealed class FtpError(open val cause: Throwable? = null) {
        data class AuthFailed(override val cause: Throwable? = null) : FtpError(cause)
        data class NotConnected(override val cause: Throwable? = null) : FtpError(cause)
        data class PathNotFound(val path: String, override val cause: Throwable? = null) :
            FtpError(cause)

        data class IO(override val cause: Throwable? = null) : FtpError(cause)
        data class Timeout(override val cause: Throwable? = null) : FtpError(cause)
        data class Protocol(override val cause: Throwable? = null) : FtpError(cause)
        data class Unknown(override val cause: Throwable? = null) : FtpError(cause)
    }

    private val main = Handler(Looper.getMainLooper())
    private val io = Executors.newSingleThreadExecutor()

    private var client: FTPClient? = null
    private var listener: OnFTPJobListener? = null

    /** Tracks per-file progress across threads (key = absolute destination path). */
    private val progressMap = java.util.concurrent.ConcurrentHashMap<String, FileModel>()

    /** ✅ Tracks active upload batch signatures to ignore duplicate calls */
    private val activeBatchSignatures = java.util.concurrent.ConcurrentHashMap.newKeySet<String>()

    fun setListener(l: OnFTPJobListener?) = apply { listener = l }

    private fun postProgressSnapshot() {
        val snapshot = progressMap.values.toList()
        post { listener?.onProgress(snapshot) }
    }

    // ---------------- Connection ----------------

    fun connect() {
        run(Action.CONNECT) {
            val ftp = newClient()
            client = ftp
            connectAndLogin(ftp)
            applySafeDefaults(ftp)
            post { listener?.onConnected() }
            post { listener?.onSuccess(Action.CONNECT, "Connected to $host:$port") }
        }
    }

    fun disconnect() {
        run(Action.DISCONNECT) {
            client?.let { safeLogoutDisconnect(it) }
            client = null
            post { listener?.onDisconnected() }
            post { listener?.onSuccess(Action.DISCONNECT, "Disconnected") }
        }
    }

    fun isConnected(): Boolean = client?.isAvailable == true && client?.isConnected == true

    // ---------------- Admin ops ----------------

    fun list(remoteDir: String) {
        run(Action.LIST) {
            val ftp = requireConnected(Action.LIST) ?: return@run
            val files: Array<FTPFile> = try {
                ftp.listFiles(remoteDir)
            } catch (e: FileNotFoundException) {
                error(Action.LIST, FtpError.PathNotFound(remoteDir, e)); return@run
            } catch (e: SocketException) {
                error(Action.LIST, FtpError.Timeout(e)); return@run
            } catch (e: IOException) {
                error(Action.LIST, FtpError.IO(e)); return@run
            }

            val list = files.map {
                val t = when {
                    it.isDirectory -> Type.DIR
                    it.isFile -> Type.FILE
                    else -> Type.LINK
                }
                FileModel(type = t, name = it.name, size = it.size)
            }
            post { listener?.onSuccess(Action.LIST, list) }
        }
    }

    fun mkdir(remoteDir: String) {
        run(Action.MKDIR) {
            val ftp = requireConnected(Action.MKDIR) ?: return@run
            val ok = try {
                ftp.makeDirectory(remoteDir)
            } catch (e: IOException) {
                error(Action.MKDIR, FtpError.IO(e)); return@run
            }
            if (!ok) error(Action.MKDIR, FtpError.Protocol())
            else post { listener?.onSuccess(Action.MKDIR, "Created: $remoteDir") }
        }
    }

    fun rmdir(remoteDir: String) {
        run(Action.RMDIR) {
            val ftp = requireConnected(Action.RMDIR) ?: return@run
            val ok = try {
                ftp.removeDirectory(remoteDir)
            } catch (e: IOException) {
                error(Action.RMDIR, FtpError.IO(e)); return@run
            }
            if (!ok) error(Action.RMDIR, FtpError.Protocol())
            else post { listener?.onSuccess(Action.RMDIR, "Removed: $remoteDir") }
        }
    }

    fun delete(remotePath: String) {
        run(Action.DELETE) {
            val ftp = requireConnected(Action.DELETE) ?: return@run
            val ok = try {
                ftp.deleteFile(remotePath)
            } catch (e: IOException) {
                error(Action.DELETE, FtpError.IO(e)); return@run
            }
            if (!ok) error(Action.DELETE, FtpError.PathNotFound(remotePath))
            else post { listener?.onSuccess(Action.DELETE, "Deleted: $remotePath") }
        }
    }

    fun rename(from: String, to: String) {
        run(Action.RENAME) {
            val ftp = requireConnected(Action.RENAME) ?: return@run
            val ok = try {
                ftp.rename(from, to)
            } catch (e: IOException) {
                error(Action.RENAME, FtpError.IO(e)); return@run
            }
            if (!ok) error(Action.RENAME, FtpError.Protocol())
            else post { listener?.onSuccess(Action.RENAME, "Renamed: $from -> $to") }
        }
    }

    // ---------------- Single-file (progress via List<FileModel>) ----------------

    fun upload(localFile: File, remotePath: String) {
        run(Action.UPLOAD) {
            val ftp = requireConnected(Action.UPLOAD) ?: return@run
            if (!localFile.exists() || !localFile.isFile) {
                error(Action.UPLOAD, FtpError.PathNotFound(localFile.absolutePath)); return@run
            }

            var inStream: BufferedInputStream? = null
            try {
                val total = localFile.length().coerceAtLeast(1L)
                var transferred = 0L
                inStream = BufferedInputStream(FileInputStream(localFile), bufferSize)

                val progressStream = object : InputStream() {
                    override fun read(): Int {
                        val b = inStream!!.read()
                        if (b >= 0) {
                            transferred++
                            post {
                                listener?.onProgress(
                                    listOf(
                                        FileModel(
                                            Type.FILE,
                                            File(remotePath).name,
                                            total,
                                            Action.UPLOAD,
                                            transferred,
                                            total
                                        )
                                    )
                                )
                            }
                        }
                        return b
                    }
                }

                val ok = ftp.storeFile(remotePath, progressStream)
                if (!ok) {
                    error(Action.UPLOAD, FtpError.Protocol()); return@run
                }

                post {
                    listener?.onProgress(
                        listOf(
                            FileModel(
                                Type.FILE,
                                File(remotePath).name,
                                total,
                                Action.UPLOAD,
                                total,
                                total
                            )
                        )
                    )
                }
                post { listener?.onSuccess(Action.UPLOAD, "Uploaded: $remotePath") }
            } catch (e: SocketException) {
                error(Action.UPLOAD, FtpError.Timeout(e))
            } catch (e: FileNotFoundException) {
                error(Action.UPLOAD, FtpError.PathNotFound(localFile.absolutePath, e))
            } catch (e: IOException) {
                error(Action.UPLOAD, FtpError.IO(e))
            } finally {
                try {
                    inStream?.close()
                } catch (_: Throwable) {
                }
            }
        }
    }

    fun download(remotePath: String, destFile: File) {
        run(Action.DOWNLOAD) {
            val ftp = requireConnected(Action.DOWNLOAD) ?: return@run
            val size = runCatching {
                ftp.listFiles(remotePath)?.firstOrNull()?.size ?: -1L
            }.getOrDefault(-1L)

            var out: BufferedOutputStream? = null
            var inStream: InputStream? = null
            try {
                out = BufferedOutputStream(FileOutputStream(destFile, false), bufferSize)
                val tmp = ByteArray(bufferSize)
                var transferred = 0L

                inStream = ftp.retrieveFileStream(remotePath)
                    ?: throw FileNotFoundException("Remote not found or cannot open stream: $remotePath")

                while (true) {
                    val n = inStream.read(tmp); if (n <= 0) break
                    out.write(tmp, 0, n); transferred += n

                    val total = if (size > 0) size else -1L
                    post {
                        listener?.onProgress(
                            listOf(
                                FileModel(
                                    Type.FILE,
                                    destFile.name,
                                    total,
                                    Action.DOWNLOAD,
                                    transferred,
                                    total
                                )
                            )
                        )
                    }
                }
                out.flush()

                if (!ftp.completePendingCommand()) {
                    error(Action.DOWNLOAD, FtpError.Protocol()); return@run
                }

                if (size > 0) {
                    post {
                        listener?.onProgress(
                            listOf(
                                FileModel(
                                    Type.FILE,
                                    destFile.name,
                                    size,
                                    Action.DOWNLOAD,
                                    size,
                                    size
                                )
                            )
                        )
                    }
                }
                post {
                    listener?.onSuccess(
                        Action.DOWNLOAD,
                        "Downloaded: ${destFile.absolutePath}"
                    )
                }
            } catch (e: SocketException) {
                error(Action.DOWNLOAD, FtpError.Timeout(e))
            } catch (e: FileNotFoundException) {
                error(Action.DOWNLOAD, FtpError.PathNotFound(remotePath, e))
            } catch (e: IOException) {
                error(Action.DOWNLOAD, FtpError.IO(e))
            } finally {
                try {
                    inStream?.close()
                } catch (_: Throwable) {
                }
                try {
                    out?.close()
                } catch (_: Throwable) {
                }
            }
        }
    }

    // ---------------- Single-file Resumable (still emits List<FileModel>) ----------------

    fun downloadResumable(
        remotePath: String,
        destFile: File,
        maxRetries: Int = 3,
        backoffMs: Long = 1500L
    ) {
        run(Action.DOWNLOAD) {
            val part = File(destFile.absolutePath + ".part")
            var attempt = 0

            while (true) {
                attempt++
                var ftp: FTPClient? = null
                var ins: InputStream? = null
                var raf: RandomAccessFile? = null
                try {
                    ftp = newClient(); connectAndLogin(ftp); applySafeDefaults(ftp)

                    val remoteSize = runCatching {
                        ftp.listFiles(remotePath)?.firstOrNull()?.size ?: -1L
                    }.getOrDefault(-1L)
                    val resumeAt = (if (part.exists()) part.length() else 0L).coerceAtLeast(0L)

                    if (resumeAt > 0L) ftp.setRestartOffset(resumeAt)
                    raf = RandomAccessFile(part, "rw").apply { seek(resumeAt) }

                    ins = ftp.retrieveFileStream(remotePath)
                        ?: throw FileNotFoundException("Cannot open stream: $remotePath")

                    val buf = ByteArray(bufferSize)
                    var transferred = resumeAt
                    val total = if (remoteSize > 0) remoteSize else -1L

                    // initial tick
                    post {
                        listener?.onProgress(
                            listOf(
                                FileModel(
                                    Type.FILE,
                                    destFile.name,
                                    total,
                                    Action.DOWNLOAD,
                                    transferred,
                                    total
                                )
                            )
                        )
                    }

                    while (true) {
                        val n = ins.read(buf); if (n <= 0) break
                        raf.write(buf, 0, n); transferred += n
                        post {
                            listener?.onProgress(
                                listOf(
                                    FileModel(
                                        Type.FILE,
                                        destFile.name,
                                        total,
                                        Action.DOWNLOAD,
                                        transferred,
                                        total
                                    )
                                )
                            )
                        }
                    }
                    raf.fd.sync()
                    if (!ftp.completePendingCommand()) throw IOException("completePendingCommand failed (reply=${ftp.replyCode})")

                    if (!part.renameTo(destFile)) {
                        part.copyTo(destFile, overwrite = true); part.delete()
                    }
                    if (remoteSize > 0) post {
                        listener?.onProgress(
                            listOf(
                                FileModel(
                                    Type.FILE,
                                    destFile.name,
                                    remoteSize,
                                    Action.DOWNLOAD,
                                    remoteSize,
                                    remoteSize
                                )
                            )
                        )
                    }
                    post {
                        listener?.onSuccess(
                            Action.DOWNLOAD,
                            "Downloaded: ${destFile.absolutePath}"
                        )
                    }
                    return@run
                } catch (e: FileNotFoundException) {
                    error(Action.DOWNLOAD, FtpError.PathNotFound(remotePath, e)); return@run
                } catch (e: SocketException) {
                    if (attempt >= maxRetries) {
                        error(Action.DOWNLOAD, FtpError.Timeout(e)); return@run
                    }
                    Thread.sleep(backoffMs * attempt)
                } catch (e: IOException) {
                    if (attempt >= maxRetries) {
                        error(Action.DOWNLOAD, FtpError.IO(e)); return@run
                    }
                    Thread.sleep(backoffMs * attempt)
                } catch (e: Throwable) {
                    error(Action.DOWNLOAD, FtpError.Unknown(e)); return@run
                } finally {
                    try {
                        ins?.close()
                    } catch (_: Throwable) {
                    }
                    try {
                        raf?.close()
                    } catch (_: Throwable) {
                    }
                    if (ftp != null) safeLogoutDisconnect(ftp)
                }
            }
        }
    }

    fun uploadResumable(
        localFile: File,
        remotePath: String,
        maxRetries: Int = 3,
        backoffMs: Long = 1500L
    ) {
        run(Action.UPLOAD) {
            if (!localFile.exists() || !localFile.isFile) {
                error(Action.UPLOAD, FtpError.PathNotFound(localFile.absolutePath)); return@run
            }

            var attempt = 0
            while (true) {
                attempt++
                var ftp: FTPClient? = null
                var fis: InputStream? = null
                var out: OutputStream? = null
                try {
                    ftp = newClient(); connectAndLogin(ftp); applySafeDefaults(ftp)

                    val localSize = localFile.length().coerceAtLeast(0L)
                    val remoteSize = runCatching {
                        ftp.listFiles(remotePath)?.firstOrNull()?.size ?: 0L
                    }.getOrDefault(0L)

                    if (remoteSize > localSize) {
                        error(
                            Action.UPLOAD,
                            FtpError.Protocol(IOException("Remote larger than local"))
                        ); return@run
                    }
                    if (remoteSize == localSize) {
                        post {
                            listener?.onProgress(
                                listOf(
                                    FileModel(
                                        Type.FILE,
                                        File(remotePath).name,
                                        localSize,
                                        Action.UPLOAD,
                                        localSize,
                                        localSize
                                    )
                                )
                            )
                        }
                        post { listener?.onSuccess(Action.UPLOAD, "Already uploaded: $remotePath") }
                        return@run
                    }

                    fis = BufferedInputStream(FileInputStream(localFile), bufferSize)
                    skipFully(fis, remoteSize)
                    if (remoteSize > 0L) ftp.setRestartOffset(remoteSize)

                    out = ftp.storeFileStream(remotePath)
                        ?: throw IOException("storeFileStream failed (reply=${ftp.replyCode})")

                    val buf = ByteArray(bufferSize)
                    var transferred = remoteSize
                    val total = localSize

                    // initial tick
                    post {
                        listener?.onProgress(
                            listOf(
                                FileModel(
                                    Type.FILE,
                                    File(remotePath).name,
                                    total,
                                    Action.UPLOAD,
                                    transferred,
                                    total
                                )
                            )
                        )
                    }

                    while (true) {
                        val n = fis.read(buf); if (n <= 0) break
                        out.write(buf, 0, n); transferred += n
                        post {
                            listener?.onProgress(
                                listOf(
                                    FileModel(
                                        Type.FILE,
                                        File(remotePath).name,
                                        total,
                                        Action.UPLOAD,
                                        transferred,
                                        total
                                    )
                                )
                            )
                        }
                    }
                    out.flush()
                    if (!ftp.completePendingCommand()) throw IOException("completePendingCommand failed (reply=${ftp.replyCode})")

                    post {
                        listener?.onProgress(
                            listOf(
                                FileModel(
                                    Type.FILE,
                                    File(remotePath).name,
                                    total,
                                    Action.UPLOAD,
                                    total,
                                    total
                                )
                            )
                        )
                    }
                    post { listener?.onSuccess(Action.UPLOAD, "Uploaded: $remotePath") }
                    return@run
                } catch (e: FileNotFoundException) {
                    error(
                        Action.UPLOAD,
                        FtpError.PathNotFound(localFile.absolutePath, e)
                    ); return@run
                } catch (e: SocketException) {
                    if (attempt >= maxRetries) {
                        error(Action.UPLOAD, FtpError.Timeout(e)); return@run
                    }
                    Thread.sleep(backoffMs * attempt)
                } catch (e: IOException) {
                    if (attempt >= maxRetries) {
                        error(Action.UPLOAD, FtpError.IO(e)); return@run
                    }
                    Thread.sleep(backoffMs * attempt)
                } catch (e: Throwable) {
                    error(Action.UPLOAD, FtpError.Unknown(e)); return@run
                } finally {
                    try {
                        out?.close()
                    } catch (_: Throwable) {
                    }
                    try {
                        fis?.close()
                    } catch (_: Throwable) {
                    }
                    if (ftp != null) safeLogoutDisconnect(ftp)
                }
            }
        }
    }

    // ---------------- Multi (resumable + per-file snapshots) ----------------

    /** Build a strong signature for a batch to prevent accidental duplicates. */
    private fun List<UploadItem>.signature(): String =
        joinToString(separator = "|") { u ->
            val len = runCatching { u.local.length() }.getOrDefault(-1L)
            val lm = runCatching { u.local.lastModified() }.getOrDefault(-1L)
            "${u.remotePath}#${len}@${lm}"
        }

    fun uploadMany(
        items: List<UploadItem>,
        parallelism: Int = 3,
        maxRetries: Int = 3,
        backoffMs: Long = 1500L
    ) {
        if (items.isEmpty()) {
            post { listener?.onSuccess(Action.UPLOAD, "Nothing to upload (0 files).") }
            return
        }

        // ✅ De-dupe: ignore if an identical batch is already running
        val sig = items.signature()
        if (!activeBatchSignatures.add(sig)) {
            post {
                listener?.onSuccess(
                    Action.UPLOAD,
                    "Ignored duplicate batch (already running)."
                )
            }
            return
        }

        val pool = Executors.newFixedThreadPool(parallelism.coerceAtLeast(1))
        val done = AtomicInteger(0)

        items.forEach { item ->
            pool.submit {
                val name = File(item.remotePath).name
                val key = "upload:$name:${item.remotePath.hashCode()}"
                var attempt = 0

                while (true) {
                    attempt++
                    var ftp: FTPClient? = null
                    var fis: InputStream? = null
                    var out: OutputStream? = null

                    try {

                        ftp = newClient(); connectAndLogin(ftp); applySafeDefaults(ftp)

                        val localSize = item.local.length().coerceAtLeast(0L)
                        if (localSize <= 0L) throw FileNotFoundException("Empty/missing: ${item.local.absolutePath}")

                        val remoteSize = runCatching {
                            ftp.listFiles(item.remotePath)?.firstOrNull()?.size ?: 0L
                        }.getOrDefault(0L)
                        if (remoteSize > localSize) throw IOException("Remote larger than local")

                        var transferred = remoteSize
                        progressMap[key] = FileModel(
                            Type.FILE,
                            name,
                            localSize,
                            Action.UPLOAD,
                            transferred,
                            localSize
                        )
                        postProgressSnapshot()

                        if (remoteSize == localSize) {
                            post {
                                listener?.onSuccess(
                                    Action.UPLOAD,
                                    "Already uploaded: ${item.remotePath}"
                                )
                            }
                            break
                        }

                        fis = BufferedInputStream(FileInputStream(item.local), bufferSize)
                        skipFully(fis, remoteSize)
                        if (remoteSize > 0L) ftp.setRestartOffset(remoteSize)

                        out = ftp.storeFileStream(item.remotePath)
                            ?: throw IOException("storeFileStream failed (reply=${ftp.replyCode})")

                        val buf = ByteArray(bufferSize)
                        while (true) {
                            val n = fis.read(buf); if (n <= 0) break
                            out.write(buf, 0, n); transferred += n
                            progressMap[key] = FileModel(
                                Type.FILE,
                                name,
                                localSize,
                                Action.UPLOAD,
                                transferred,
                                localSize
                            )
                            postProgressSnapshot()
                        }
                        out.flush()
                        if (!ftp.completePendingCommand()) throw IOException("completePendingCommand failed (reply=${ftp.replyCode})")

                        progressMap[key] = FileModel(
                            Type.FILE,
                            name,
                            localSize,
                            Action.UPLOAD,
                            localSize,
                            localSize
                        )
                        postProgressSnapshot()
                        post { listener?.onSuccess(Action.UPLOAD, "Uploaded: ${item.remotePath}") }
                        break
                    } catch (e: FileNotFoundException) {
                        post {
                            listener?.onError(
                                Action.UPLOAD,
                                FtpError.PathNotFound(item.local.absolutePath, e)
                            )
                        }
                        break
                    } catch (e: SocketException) {
                        if (attempt >= maxRetries) {
                            post { listener?.onError(Action.UPLOAD, FtpError.Timeout(e)) }; break
                        }
                        Thread.sleep(backoffMs * attempt)
                    } catch (e: IOException) {
                        if (attempt >= maxRetries) {
                            post { listener?.onError(Action.UPLOAD, FtpError.IO(e)) }; break
                        }
                        Thread.sleep(backoffMs * attempt)
                    } catch (e: Throwable) {
                        post { listener?.onError(Action.UPLOAD, FtpError.Unknown(e)) }
                        break
                    } finally {
                        try {
                            out?.close()
                        } catch (_: Throwable) {
                        }
                        try {
                            fis?.close()
                        } catch (_: Throwable) {
                        }
                        if (ftp != null) safeLogoutDisconnect(ftp)
                    }
                }

                progressMap.remove(key)
                postProgressSnapshot()
                val c = done.incrementAndGet()
                if (c == items.size) {
                    // ✅ Remove signature on true completion
                    activeBatchSignatures.remove(sig)
                    post {
                        listener?.onSuccess(
                            Action.UPLOAD,
                            "Batch upload finished: $c/${items.size} files."
                        )
                    }
                }
            }
        }

        pool.shutdown()
    }

    /**
     * The difference between UploadSequential and uploadMany is that it establishes only one FTP connection, uploading the files one by one. After each file finishes uploading, the next file is uploaded.
     */
    fun uploadManySequential(
        items: List<UploadItem>,
        maxRetriesPerFile: Int = 3,
        backoffMs: Long = 1500L
    ) {
        if (items.isEmpty()) {
            post { listener?.onSuccess(Action.UPLOAD, "Nothing to upload (0 files).") }
            return
        }

        run(Action.UPLOAD) {
            var ftp = client
            try {
                if (ftp == null || !ftp.isConnected) {
                    ftp = newClient()
                    connectAndLogin(ftp!!)
                    applySafeDefaults(ftp!!)
                    client = ftp
                    post { listener?.onConnected() }
                }

                for (item in items) {
                    var attempt = 0
                    while (true) {
                        attempt++
                        try {
                            if (ftp == null || !ftp.isConnected) {
                                ftp = newClient()
                                connectAndLogin(ftp)
                                applySafeDefaults(ftp)
                                client = ftp
                            }

                            val localSize = item.local.length().coerceAtLeast(0L)
                            if (localSize <= 0L) throw FileNotFoundException("Empty/missing: ${item.local.absolutePath}")

                            val remoteSize = runCatching {
                                ftp.listFiles(item.remotePath)?.firstOrNull()?.size ?: 0L
                            }
                                .getOrDefault(0L)
                            if (remoteSize > localSize) throw IOException("Remote larger than local")

                            var transferred = remoteSize
                            val name = File(item.remotePath).name
                            progressMap[item.remotePath] = FileModel(
                                Type.FILE,
                                name,
                                localSize,
                                Action.UPLOAD,
                                transferred,
                                localSize
                            )
                            postProgressSnapshot()

                            if (remoteSize == localSize) {
                                post {
                                    listener?.onSuccess(
                                        Action.UPLOAD,
                                        "Already uploaded: ${item.remotePath}"
                                    )
                                }
                                break
                            }

                            BufferedInputStream(
                                FileInputStream(item.local),
                                bufferSize
                            ).use { fis ->
                                // رزومه
                                skipFully(fis, remoteSize)
                                if (remoteSize > 0L) ftp.setRestartOffset(remoteSize)

                                val out = ftp.storeFileStream(item.remotePath)
                                    ?: throw IOException("storeFileStream failed (reply=${ftp.replyCode})")

                                out.use { o ->
                                    val buf = ByteArray(bufferSize)
                                    while (true) {
                                        val n = fis.read(buf); if (n <= 0) break
                                        o.write(buf, 0, n); transferred += n
                                        progressMap[item.remotePath] = FileModel(
                                            Type.FILE,
                                            name,
                                            localSize,
                                            Action.UPLOAD,
                                            transferred,
                                            localSize
                                        )
                                        postProgressSnapshot()
                                    }
                                }

                                if (!ftp.completePendingCommand()) throw IOException("completePendingCommand failed (reply=${ftp!!.replyCode})")
                            }

                            progressMap[item.remotePath] = FileModel(
                                Type.FILE,
                                name,
                                localSize,
                                Action.UPLOAD,
                                localSize,
                                localSize
                            )
                            postProgressSnapshot()
                            post {
                                listener?.onSuccess(
                                    Action.UPLOAD,
                                    "Uploaded: ${item.remotePath}"
                                )
                            }
                            break
                        } catch (e: SocketException) {
                            if (attempt >= maxRetriesPerFile) {
                                post {
                                    listener?.onError(
                                        Action.UPLOAD,
                                        FtpError.Timeout(e)
                                    )
                                }; break
                            }
                            Thread.sleep(backoffMs * attempt)
                            // اتصال خراب شد؟ پاک و از نو بساز
                            try {
                                if (ftp != null) safeLogoutDisconnect(ftp)
                            } catch (_: Throwable) {
                            }
                            ftp = null
                        } catch (e: FileNotFoundException) {
                            post {
                                listener?.onError(
                                    Action.UPLOAD,
                                    FtpError.PathNotFound(item.local.absolutePath, e)
                                )
                            }
                            break
                        } catch (e: IOException) {
                            if (attempt >= maxRetriesPerFile) {
                                post { listener?.onError(Action.UPLOAD, FtpError.IO(e)) }; break
                            }
                            Thread.sleep(backoffMs * attempt)
                            try {
                                if (ftp != null) safeLogoutDisconnect(ftp)
                            } catch (_: Throwable) {
                            }
                            ftp = null
                        } catch (e: Throwable) {
                            post { listener?.onError(Action.UPLOAD, FtpError.Unknown(e)) }
                            break
                        } finally {

                        }
                    }
                    progressMap.remove(item.remotePath)
                    postProgressSnapshot()
                }

                post {
                    listener?.onSuccess(
                        Action.UPLOAD,
                        "Batch upload finished: ${items.size}/${items.size} files (sequential)."
                    )
                }
            } finally {

            }
        }
    }

    fun downloadMany(
        items: List<DownloadItem>,
        parallelism: Int = 3,
        maxRetries: Int = 3,
        backoffMs: Long = 1500L
    ) {
        if (items.isEmpty()) {
            post { listener?.onSuccess(Action.DOWNLOAD, "Nothing to download (0 files).") }
            return
        }
        val pool = Executors.newFixedThreadPool(parallelism.coerceAtLeast(1))
        val done = AtomicInteger(0)

        items.forEach { item ->
            pool.submit {
                val key = item.dest.absolutePath
                val name = item.dest.name
                val part = File("$key.part")
                var attempt = 0

                while (true) {
                    attempt++
                    var ftp: FTPClient? = null
                    var ins: InputStream? = null
                    var raf: RandomAccessFile? = null

                    try {
                        ftp = newClient(); connectAndLogin(ftp); applySafeDefaults(ftp)

                        val remoteSize = runCatching {
                            ftp.listFiles(item.remotePath)?.firstOrNull()?.size ?: -1L
                        }.getOrDefault(-1L)
                        val resumeAt = (if (part.exists()) part.length() else 0L).coerceAtLeast(0L)

                        var transferred = resumeAt
                        val total = if (remoteSize > 0) remoteSize else -1L
                        progressMap[key] =
                            FileModel(Type.FILE, name, total, Action.DOWNLOAD, transferred, total)
                        postProgressSnapshot()

                        if (remoteSize > 0 && resumeAt >= remoteSize) {
                            if (part.exists()) part.renameTo(item.dest)
                            progressMap[key] = FileModel(
                                Type.FILE,
                                name,
                                remoteSize,
                                Action.DOWNLOAD,
                                remoteSize,
                                remoteSize
                            )
                            postProgressSnapshot()
                            post {
                                listener?.onSuccess(
                                    Action.DOWNLOAD,
                                    "Already downloaded: ${item.dest.absolutePath}"
                                )
                            }
                            break
                        }

                        raf = RandomAccessFile(part, "rw").apply { seek(resumeAt) }
                        if (resumeAt > 0L) ftp.setRestartOffset(resumeAt)

                        ins = ftp.retrieveFileStream(item.remotePath)
                            ?: throw FileNotFoundException("Cannot open stream: ${item.remotePath}")

                        val buf = ByteArray(bufferSize)
                        while (true) {
                            val n = ins.read(buf); if (n <= 0) break
                            raf.write(buf, 0, n); transferred += n
                            progressMap[key] = FileModel(
                                Type.FILE,
                                name,
                                total,
                                Action.DOWNLOAD,
                                transferred,
                                total
                            )
                            postProgressSnapshot()
                        }
                        raf.fd.sync()
                        if (!ftp.completePendingCommand()) throw IOException("completePendingCommand failed (reply=${ftp.replyCode})")

                        if (!part.renameTo(item.dest)) {
                            part.copyTo(item.dest, overwrite = true); part.delete()
                        }

                        if (remoteSize > 0) {
                            progressMap[key] = FileModel(
                                Type.FILE,
                                name,
                                remoteSize,
                                Action.DOWNLOAD,
                                remoteSize,
                                remoteSize
                            )
                        }
                        postProgressSnapshot()
                        post {
                            listener?.onSuccess(
                                Action.DOWNLOAD,
                                "Downloaded: ${item.dest.absolutePath}"
                            )
                        }
                        break
                    } catch (e: FileNotFoundException) {
                        post {
                            listener?.onError(
                                Action.DOWNLOAD,
                                FtpError.PathNotFound(item.remotePath, e)
                            )
                        }
                        break
                    } catch (e: SocketException) {
                        if (attempt >= maxRetries) {
                            post { listener?.onError(Action.DOWNLOAD, FtpError.Timeout(e)) }; break
                        }
                        Thread.sleep(backoffMs * attempt)
                    } catch (e: IOException) {
                        if (attempt >= maxRetries) {
                            post { listener?.onError(Action.DOWNLOAD, FtpError.IO(e)) }; break
                        }
                        Thread.sleep(backoffMs * attempt)
                    } catch (e: Throwable) {
                        post { listener?.onError(Action.DOWNLOAD, FtpError.Unknown(e)) }
                        break
                    } finally {
                        try {
                            ins?.close()
                        } catch (_: Throwable) {
                        }
                        try {
                            raf?.close()
                        } catch (_: Throwable) {
                        }
                        if (ftp != null) safeLogoutDisconnect(ftp)
                    }
                }

                progressMap.remove(key)
                postProgressSnapshot()
                val c = done.incrementAndGet()
                if (c == items.size) {
                    post {
                        listener?.onSuccess(
                            Action.DOWNLOAD,
                            "Batch download finished: $c/${items.size} files."
                        )
                    }
                }
            }
        }

        pool.shutdown()
    }

    // ---------------- Internals ----------------

    private fun newClient(): FTPClient {
        return if (useFtps) {
            val ctx = SSLContext.getInstance("TLS").apply { init(null, null, null) }
            FTPSClient(false, ctx)
        } else {
            FTPClient()
        }.apply {
            connectTimeout = connectTimeoutMs
            defaultTimeout = connectTimeoutMs
            dataTimeout = Duration.ofMillis(dataTimeoutMs)
        }
    }

    private fun connectAndLogin(ftp: FTPClient) {
        ftp.connect(host, port)
        val reply = ftp.replyCode
        if (!org.apache.commons.net.ftp.FTPReply.isPositiveCompletion(reply)) {
            safeLogoutDisconnect(ftp); throw IOException("FTP server refused connection. Reply=$reply")
        }
        if (!ftp.login(username, password)) {
            safeLogoutDisconnect(ftp); throw IOException("Authentication failed")
        }
    }

    private fun applySafeDefaults(ftp: FTPClient) {
        ftp.controlEncoding = StandardCharsets.UTF_8.name()
        ftp.setFileType(FTP.BINARY_FILE_TYPE)
        ftp.enterLocalPassiveMode()
        ftp.bufferSize = bufferSize
        ftp.setControlKeepAliveTimeout(15) // seconds
        if (ftp is FTPSClient) {
            ftp.execPBSZ(0)
            ftp.execPROT("P")
        }
    }

    private fun requireConnected(action: Action): FTPClient? {
        val c = client
        return if (c == null || !c.isConnected) {
            error(action, FtpError.NotConnected()); null
        } else c
    }

    private fun run(action: Action, block: () -> Unit) {
        io.execute {
            try {
                block()
            } catch (e: SocketException) {
                error(action, FtpError.Timeout(e))
            } catch (e: org.apache.commons.net.MalformedServerReplyException) {
                error(action, FtpError.Protocol(e))
            } catch (e: IOException) {
                error(action, FtpError.IO(e))
            } catch (e: Throwable) {
                error(action, FtpError.Unknown(e))
            }
        }
    }

    private fun error(action: Action, err: FtpError) = post { listener?.onError(action, err) }
    private fun post(r: () -> Unit) = main.post(r)

    private fun safeLogoutDisconnect(ftp: FTPClient) {
        try {
            if (ftp.isAvailable) ftp.logout()
        } catch (_: Throwable) {
        }
        try {
            if (ftp.isConnected) ftp.disconnect()
        } catch (_: Throwable) {
        }
    }

    /** Skip exactly [count] bytes from an InputStream (for resumable uploads). */
    private fun skipFully(ins: InputStream, count: Long) {
        var remaining = count
        val buf = ByteArray(64 * 1024)
        while (remaining > 0) {
            val toRead = if (remaining > buf.size) buf.size else remaining.toInt()
            val n = ins.read(buf, 0, toRead)
            if (n <= 0) break
            remaining -= n
        }
    }
}

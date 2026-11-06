package com.geno1024.netdis.update

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL
import java.security.MessageDigest

sealed interface UpdateResult {
    data object UpToDate : UpdateResult
    data class Available(val info: CanaryInfo) : UpdateResult
    data class Failed(val message: String) : UpdateResult
}

sealed interface DownloadResult {
    data class Success(val file: File) : DownloadResult
    data class Failed(val message: String) : DownloadResult
}

object Updater {

    private const val CONNECT_TIMEOUT_MS = 15_000
    private const val READ_TIMEOUT_MS = 30_000

    /**
     * Fetches the canary manifest, trying GitHub directly first and then the
     * given mirrors.
     *
     * Direct is preferred because the manifest carries the expected sha256 and
     * it is tiny. If a mirror serves the manifest it also chooses the digest,
     * so the digest check then guards against corruption rather than a hostile
     * mirror. What actually prevents a substituted APK is Android's signature
     * check: an update signed with another key is refused, so the worst case is
     * a failed update, never a compromised install.
     */
    suspend fun check(currentRunNumber: Int, mirrors: List<Mirror>): UpdateResult =
        withContext(Dispatchers.IO) {
            val candidates = buildList {
                add(Mirrors.DIRECT)
                addAll(mirrors)
            }.distinctBy { it.prefix }
            var failure: String? = null
            for (mirror in candidates) {
                when (val text = fetchText(mirror.wrap(CanaryManifest.DIRECT_URL))) {
                    is Fetch.Failed -> failure = text.message
                    is Fetch.Ok -> {
                        val info = CanaryManifest.parse(text.body)
                            ?: return@withContext UpdateResult.Failed("无法解析更新信息")
                        return@withContext if (info.runNumber <= currentRunNumber) {
                            UpdateResult.UpToDate
                        } else {
                            UpdateResult.Available(info)
                        }
                    }
                }
            }
            UpdateResult.Failed(failure ?: "检查更新失败")
        }

    private sealed interface Fetch {
        data class Ok(val body: String) : Fetch
        data class Failed(val message: String) : Fetch
    }

    /**
     * GitHub redirects asset downloads to another host and that leg drops often
     * enough to be worth retrying before giving up.
     */
    private fun fetchText(url: String, attempts: Int = 2): Fetch {
        var last = "网络错误"
        repeat(attempts) {
            try {
                val connection = open(url)
                if (connection.responseCode !in 200..299) {
                    last = "HTTP ${connection.responseCode}"
                    return@repeat
                }
                return Fetch.Ok(connection.inputStream.bufferedReader().use { it.readText() })
            } catch (e: IOException) {
                last = e.message ?: "网络错误"
            }
        }
        return Fetch.Failed(last)
    }

    /**
     * Downloads the APK through [mirror], verifying the sha256 that was fetched
     * directly from GitHub in [check]. A file is only returned when the digest
     * matches; the caller may then retry with the next mirror.
     */
    suspend fun download(
        info: CanaryInfo,
        mirror: Mirror,
        cacheDir: File,
        onProgress: (Float) -> Unit = {},
    ): DownloadResult = withContext(Dispatchers.IO) {
        val target = File(cacheDir, "netdis-${info.runNumber}.apk")
        val url = mirror.wrap(CanaryManifest.apkUrl(info.assetName))
        try {
            val connection = open(url)
            if (connection.responseCode !in 200..299) {
                return@withContext DownloadResult.Failed("HTTP ${connection.responseCode}")
            }
            val expected = info.sizeBytes
            val total = contentLength(connection, expected)
            var received = 0L
            connection.inputStream.use { input ->
                target.outputStream().use { output ->
                    val buffer = ByteArray(64 * 1024)
                    while (true) {
                        val read = input.read(buffer)
                        if (read == -1) break
                        output.write(buffer, 0, read)
                        received += read
                        onProgress(if (total > 0) received.toFloat() / total else -1f)
                    }
                }
            }
            if (expected > 0 && received != expected) {
                target.delete()
                return@withContext DownloadResult.Failed("文件不完整（$received/$expected 字节）")
            }
            val actual = sha256(target)
            if (actual != info.sha256) {
                target.delete()
                return@withContext DownloadResult.Failed("校验失败，镜像可能已被篡改")
            }
            DownloadResult.Success(target)
        } catch (e: IOException) {
            target.delete()
            DownloadResult.Failed(e.message ?: "下载失败")
        }
    }

    private fun open(url: String): HttpURLConnection =
        (URL(url).openConnection() as HttpURLConnection).apply {
            connectTimeout = CONNECT_TIMEOUT_MS
            readTimeout = READ_TIMEOUT_MS
            instanceFollowRedirects = true
            setRequestProperty("Accept", "*/*")
        }

    private fun contentLength(connection: HttpURLConnection, fallback: Long): Long {
        val declared = connection.getHeaderField("Content-Length")?.toLongOrNull() ?: -1L
        return if (declared > 0) declared else fallback
    }

    private fun sha256(file: File): String {
        val digest = MessageDigest.getInstance("SHA-256")
        file.inputStream().use { input ->
            val buffer = ByteArray(64 * 1024)
            while (true) {
                val read = input.read(buffer)
                if (read == -1) break
                digest.update(buffer, 0, read)
            }
        }
        return digest.digest().joinToString("") { byte -> "%02x".format(byte) }
    }
}

package top.iwesley.lyn.music.platform

import android.content.Context
import com.hierynomus.msdtyp.AccessMask
import com.hierynomus.mssmb2.SMB2CreateDisposition
import com.hierynomus.mssmb2.SMB2ShareAccess
import com.hierynomus.smbj.SMBClient
import com.hierynomus.smbj.auth.AuthenticationContext
import com.hierynomus.smbj.share.DiskShare
import java.io.File
import java.io.InputStream
import java.security.SecureRandom
import java.security.cert.X509Certificate
import javax.net.ssl.HostnameVerifier
import javax.net.ssl.SSLContext
import javax.net.ssl.TrustManager
import javax.net.ssl.X509TrustManager
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import kotlin.time.Clock
import top.iwesley.lyn.music.core.model.DEFAULT_SAMBA_PORT
import top.iwesley.lyn.music.core.model.DiagnosticLogger
import top.iwesley.lyn.music.core.model.ImportSourceType
import top.iwesley.lyn.music.core.model.LxMusicLocatorRuntime
import top.iwesley.lyn.music.core.model.NavidromeAudioQuality
import top.iwesley.lyn.music.core.model.OfflineDownloadGateway
import top.iwesley.lyn.music.core.model.OfflineDownloadProgress
import top.iwesley.lyn.music.core.model.OfflineDownloadResult
import top.iwesley.lyn.music.core.model.OfflineDownloadStatus
import top.iwesley.lyn.music.core.model.SecureCredentialStore
import top.iwesley.lyn.music.core.model.Track
import top.iwesley.lyn.music.core.model.buildBasicAuthorizationHeader
import top.iwesley.lyn.music.core.model.buildWebDavTrackUrl
import top.iwesley.lyn.music.core.model.info
import top.iwesley.lyn.music.core.model.parseEmbySongLocator
import top.iwesley.lyn.music.core.model.parseLxMusicSongLocator
import top.iwesley.lyn.music.core.model.parseSubsonicCompatibleSongLocator
import top.iwesley.lyn.music.core.model.parseSambaLocator
import top.iwesley.lyn.music.core.model.parseWebDavLocator
import top.iwesley.lyn.music.data.db.LynMusicDatabase
import top.iwesley.lyn.music.domain.RemoteSourceResolvedUrl
import top.iwesley.lyn.music.domain.RemoteSourceAddressSelector
import top.iwesley.lyn.music.domain.isRemoteSourceAddressFallbackAllowed
import top.iwesley.lyn.music.domain.resolveEmbyDownloadUrlCandidates
import top.iwesley.lyn.music.domain.resolveNavidromeDownloadUrlCandidates
import top.iwesley.lyn.music.domain.resolveNavidromeStreamUrlCandidates

fun createAndroidOfflineDownloadGateway(
    context: Context,
    database: LynMusicDatabase,
    secureCredentialStore: SecureCredentialStore,
    logger: DiagnosticLogger,
    addressSelector: RemoteSourceAddressSelector = RemoteSourceAddressSelector(),
): OfflineDownloadGateway = AndroidOfflineDownloadGateway(
    context = context.applicationContext,
    database = database,
    secureCredentialStore = secureCredentialStore,
    logger = logger,
    addressSelector = addressSelector,
)

private class AndroidOfflineDownloadGateway(
    context: Context,
    private val database: LynMusicDatabase,
    private val secureCredentialStore: SecureCredentialStore,
    private val logger: DiagnosticLogger,
    private val addressSelector: RemoteSourceAddressSelector,
) : OfflineDownloadGateway {
    private val rootDirectory = File(context.filesDir, "offline")
    private val defaultClient = createHttpClientBuilder().build()
    private val insecureClient by lazy {
        createHttpClientBuilder()
            .sslSocketFactory(trustAllSslContext.socketFactory, trustAllManager)
            .hostnameVerifier(trustAllHostnameVerifier)
            .build()
    }

    override suspend fun download(
        track: Track,
        quality: NavidromeAudioQuality,
        onProgress: suspend (OfflineDownloadProgress) -> Unit,
    ): OfflineDownloadResult = withContext(Dispatchers.IO) {
        rootDirectory.mkdirs()
        val finalFile = File(rootDirectory, offlineFileName(track, quality))
        val partFile = File(rootDirectory, "${finalFile.name}.part")
        partFile.delete()
        val subsonicCompatible = parseSubsonicCompatibleSongLocator(track.mediaLocator)
        try {
            val totalBytes = when {
                subsonicCompatible != null -> {
                    val requestUrls = if (quality == NavidromeAudioQuality.Original) {
                        resolveNavidromeDownloadUrlCandidates(database, secureCredentialStore, track.mediaLocator, addressSelector)
                    } else {
                        resolveNavidromeStreamUrlCandidates(database, secureCredentialStore, track.mediaLocator, quality, addressSelector)
                    } ?: error("Subsonic-compatible 来源不可用。")
                    downloadHttpFileWithAddressFallback(
                        requestUrls = requestUrls,
                        authorizationHeader = null,
                        allowInsecureTls = false,
                        target = partFile,
                        requestLogSource = subsonicCompatible.sourceType.offlineDownloadLogSourceName(),
                        onProgress = onProgress,
                    )
                }

                parseEmbySongLocator(track.mediaLocator) != null -> {
                    val requestUrls = resolveEmbyDownloadUrlCandidates(database, secureCredentialStore, track.mediaLocator, addressSelector)
                        ?: error("Emby 来源不可用。")
                    downloadHttpFileWithAddressFallback(
                        requestUrls = requestUrls,
                        authorizationHeader = null,
                        allowInsecureTls = false,
                        target = partFile,
                        onProgress = onProgress,
                    )
                }

                parseLxMusicSongLocator(track.mediaLocator) != null -> {
                    val requestUrl = LxMusicLocatorRuntime.resolveStreamUrl(track, quality)
                        ?: error("LX 音源未返回可下载地址。")
                    downloadHttpFile(
                        requestUrl = requestUrl,
                        authorizationHeader = null,
                        allowInsecureTls = false,
                        target = partFile,
                        requestLogSource = "LX Music",
                        onProgress = onProgress,
                    )
                }

                parseWebDavLocator(track.mediaLocator) != null -> downloadWebDav(track, partFile, onProgress)
                parseSambaLocator(track.mediaLocator) != null -> downloadSamba(track, partFile, onProgress)
                else -> error("本地音乐不需要离线下载。")
            }
            require(partFile.length() > 0L) { "下载文件为空。" }
            if (finalFile.exists()) {
                finalFile.delete()
            }
            check(partFile.renameTo(finalFile)) { "离线文件写入失败。" }
            logger.info(OFFLINE_LOG_TAG) {
                "download-complete track=${track.id} path=${finalFile.absolutePath} size=${finalFile.length()}"
            }
            OfflineDownloadResult(
                localMediaLocator = finalFile.absolutePath,
                sizeBytes = finalFile.length(),
                totalBytes = totalBytes ?: finalFile.length(),
            )
        } catch (throwable: Throwable) {
            partFile.delete()
            throw throwable
        }
    }

    override suspend fun delete(localMediaLocator: String): Result<Unit> = withContext(Dispatchers.IO) {
        runCatching {
            File(localMediaLocator).takeIf { it.exists() }?.delete()
            Unit
        }
    }

    override suspend fun exists(localMediaLocator: String): Boolean = withContext(Dispatchers.IO) {
        File(localMediaLocator).isFile
    }

    override suspend fun clearAll(): Result<Unit> = withContext(Dispatchers.IO) {
        runCatching {
            clearDirectory(rootDirectory)
            rootDirectory.mkdirs()
            Unit
        }
    }

    override suspend fun sizeBytes(): Long = withContext(Dispatchers.IO) {
        directorySizeBytes(rootDirectory)
    }

    override suspend fun availableSpaceBytes(): Long? = withContext(Dispatchers.IO) {
        rootDirectory.mkdirs()
        rootDirectory.usableSpace.takeIf { it >= 0L }
    }

    override suspend fun cleanupPartialFiles(): Result<Unit> = withContext(Dispatchers.IO) {
        runCatching {
            rootDirectory.listFiles().orEmpty()
                .filter { it.isFile && it.name.endsWith(".part") }
                .forEach { it.delete() }
        }
    }

    private suspend fun downloadHttpFileWithAddressFallback(
        requestUrls: List<RemoteSourceResolvedUrl>,
        authorizationHeader: String?,
        allowInsecureTls: Boolean,
        target: File,
        requestLogSource: String? = null,
        onProgress: suspend (OfflineDownloadProgress) -> Unit,
    ): Long? {
        var lastFailure: Throwable? = null
        requestUrls.forEachIndexed { index, requestUrl ->
            try {
                target.delete()
                val totalBytes = downloadHttpFile(
                    requestUrl = requestUrl.value,
                    authorizationHeader = authorizationHeader,
                    allowInsecureTls = allowInsecureTls,
                    target = target,
                    requestLogSource = requestLogSource,
                    onProgress = onProgress,
                )
                if (requestUrl.sourceId.isNotBlank()) {
                    addressSelector.markSuccess(requestUrl.sourceId, requestUrl.kind)
                }
                return totalBytes
            } catch (throwable: Throwable) {
                if (throwable is CancellationException) throw throwable
                target.delete()
                lastFailure = throwable
                val hasFallback = index < requestUrls.lastIndex
                if (!hasFallback || !isOfflineDownloadAddressFallbackAllowed(throwable)) {
                    throw throwable
                }
            }
        }
        throw lastFailure ?: IllegalStateException("远程来源缺少可用下载地址。")
    }

    private suspend fun downloadWebDav(
        track: Track,
        target: File,
        onProgress: suspend (OfflineDownloadProgress) -> Unit,
    ): Long? {
        val webDav = parseWebDavLocator(track.mediaLocator) ?: return null
        val source = database.importSourceDao().getById(webDav.first)?.takeIf { it.enabled }
            ?: error("WebDAV 来源不可用。")
        val password = source.credentialKey?.let { secureCredentialStore.get(it) }.orEmpty()
        return downloadHttpFile(
            requestUrl = buildWebDavTrackUrl(source.rootReference, webDav.second),
            authorizationHeader = buildBasicAuthorizationHeader(source.username.orEmpty(), password),
            allowInsecureTls = source.allowInsecureTls,
            target = target,
            onProgress = onProgress,
        )
    }

    private suspend fun downloadSamba(
        track: Track,
        target: File,
        onProgress: suspend (OfflineDownloadProgress) -> Unit,
    ): Long? {
        val samba = parseSambaLocator(track.mediaLocator) ?: return null
        val source = database.importSourceDao().getById(samba.first)?.takeIf { it.enabled }
            ?: error("Samba 来源不可用。")
        val spec = resolveSambaSourceSpec(source, samba.second)
        val password = spec.credentialKey?.let { secureCredentialStore.get(it) }.orEmpty()
        var downloadedBytes = 0L
        SMBClient().use { client ->
            client.connect(spec.server, spec.port.takeIf { it > 0 } ?: DEFAULT_SAMBA_PORT).use { connection ->
                val session = connection.authenticate(
                    AuthenticationContext(spec.username, password.toCharArray(), ""),
                )
                val share = session.connectShare(spec.shareName) as DiskShare
                share.openFile(
                    spec.remotePath,
                    setOf(AccessMask.GENERIC_READ),
                    null,
                    SMB2ShareAccess.ALL,
                    SMB2CreateDisposition.FILE_OPEN,
                    null,
                ).use { smbFile ->
                    target.outputStream().use { output ->
                        val buffer = ByteArray(DEFAULT_BUFFER_SIZE)
                        var offset = 0L
                        while (true) {
                            currentCoroutineContext().ensureActive()
                            val read = smbFile.read(buffer, offset)
                            if (read <= 0) break
                            output.write(buffer, 0, read)
                            offset += read
                            downloadedBytes += read
                            onProgress(OfflineDownloadProgress(downloadedBytes = downloadedBytes))
                        }
                    }
                }
            }
        }
        return null
    }

    private suspend fun downloadHttpFile(
        requestUrl: String,
        authorizationHeader: String?,
        allowInsecureTls: Boolean,
        target: File,
        requestLogSource: String? = null,
        onProgress: suspend (OfflineDownloadProgress) -> Unit,
    ): Long? {
        val request = Request.Builder()
            .url(requestUrl)
            .get()
            .apply {
                authorizationHeader?.let { header("Authorization", it) }
                requestLogSource?.let {
                    tag(OfflineDownloadRequestLog::class.java, OfflineDownloadRequestLog(it))
                }
            }
            .build()
        val response = (if (allowInsecureTls) insecureClient else defaultClient).newCall(request).execute()
        response.use { activeResponse ->
            if (!activeResponse.isSuccessful) {
                error("下载失败，HTTP ${activeResponse.code}。")
            }
            val totalBytes = activeResponse.body.contentLength().takeIf { it > 0L }
            activeResponse.body.byteStream().use { input ->
                writeStream(
                    input = input,
                    target = target,
                    totalBytes = totalBytes,
                    responseLogSource = requestLogSource,
                    responseContentType = activeResponse.body.contentType()?.toString(),
                    onProgress = onProgress,
                )
            }
            return totalBytes
        }
    }

    private suspend fun writeStream(
        input: InputStream,
        target: File,
        totalBytes: Long?,
        responseLogSource: String? = null,
        responseContentType: String? = null,
        onProgress: suspend (OfflineDownloadProgress) -> Unit,
    ) {
        target.outputStream().use { output ->
            val buffer = ByteArray(DEFAULT_BUFFER_SIZE)
            var downloadedBytes = 0L
            val sniffPrefix = input.readOfflineDownloadSniffPrefix()
            responseLogSource?.let { source ->
                logger.info(OFFLINE_LOG_TAG) {
                    buildOfflineDownloadResponseSniffLog(
                        source = source,
                        contentType = responseContentType,
                        bytes = sniffPrefix.bytes,
                        length = sniffPrefix.length,
                    )
                }
                sniffPrefix.subsonicResponseFailureMessage(
                    source = source,
                    contentType = responseContentType,
                )?.let { message -> throw OfflineDownloadAddressFallbackException(message) }
            }
            if (sniffPrefix.length > 0) {
                output.write(sniffPrefix.bytes, 0, sniffPrefix.length)
                downloadedBytes += sniffPrefix.length
                onProgress(OfflineDownloadProgress(downloadedBytes = downloadedBytes, totalBytes = totalBytes))
            }
            while (true) {
                currentCoroutineContext().ensureActive()
                val read = input.read(buffer)
                if (read <= 0) break
                output.write(buffer, 0, read)
                downloadedBytes += read
                onProgress(OfflineDownloadProgress(downloadedBytes = downloadedBytes, totalBytes = totalBytes))
            }
        }
    }

    private fun createHttpClientBuilder(): OkHttpClient.Builder {
        return OkHttpClient.Builder()
            .addNetworkInterceptor { chain ->
                val request = chain.request()
                request.tag(OfflineDownloadRequestLog::class.java)?.let { log ->
                    logger.info(OFFLINE_LOG_TAG) {
                        buildOfflineDownloadRequestHeadersLog(
                            source = log.source,
                            headers = request.headers.formatOfflineDownloadHeaderBlock(),
                        )
                    }
                }
                chain.proceed(request)
            }
    }
}

private data class OfflineDownloadRequestLog(
    val source: String,
)

private class OfflineDownloadAddressFallbackException(
    message: String,
) : IllegalStateException(message)

internal suspend fun resolveAndroidOfflinePlaybackTarget(
    database: LynMusicDatabase,
    track: Track,
): AndroidOfflinePlaybackTarget? {
    val row = database.offlineDownloadDao().getByTrackId(track.id) ?: return null
    val path = row.localMediaLocator?.takeIf { it.isNotBlank() } ?: return null
    val file = File(path)
    if (file.isFile) {
        return AndroidOfflinePlaybackTarget(
            file = file,
            quality = NavidromeAudioQuality.entries.firstOrNull { it.name == row.quality }
                ?: NavidromeAudioQuality.Original,
        )
    }
    database.offlineDownloadDao().upsert(
        row.copy(
            localMediaLocator = null,
            status = OfflineDownloadStatus.Failed.name,
            updatedAt = Clock.System.now().toEpochMilliseconds(),
            errorMessage = "离线文件不存在。",
        ),
    )
    return null
}

internal data class AndroidOfflinePlaybackTarget(
    val file: File,
    val quality: NavidromeAudioQuality,
)

private fun offlineFileName(track: Track, quality: NavidromeAudioQuality): String {
    val extension = when {
        parseSubsonicCompatibleSongLocator(track.mediaLocator) != null && quality != NavidromeAudioQuality.Original -> "mp3"
        else -> track.relativePath.substringAfterLast('.', "").lowercase().ifBlank { "audio" }
    }
    val key = "${track.id}-${quality.name}".hashCode().toUInt().toString(16)
    return "$key.$extension"
}

private fun directorySizeBytes(root: File): Long {
    if (!root.exists()) return 0L
    if (root.isFile) return root.length()
    return root.listFiles().orEmpty().sumOf(::directorySizeBytes)
}

private fun clearDirectory(root: File) {
    if (!root.exists()) return
    root.listFiles().orEmpty().forEach { file ->
        if (file.isDirectory) clearDirectory(file)
        file.delete()
    }
}

private val trustAllSslContext: SSLContext by lazy {
    SSLContext.getInstance("TLS").apply {
        init(null, arrayOf<TrustManager>(trustAllManager), SecureRandom())
    }
}

private val trustAllManager = object : X509TrustManager {
    override fun checkClientTrusted(chain: Array<out X509Certificate>?, authType: String?) = Unit
    override fun checkServerTrusted(chain: Array<out X509Certificate>?, authType: String?) = Unit
    override fun getAcceptedIssuers(): Array<X509Certificate> = emptyArray()
}

private val trustAllHostnameVerifier = HostnameVerifier { _, _ -> true }
private const val OFFLINE_LOG_TAG = "OfflineDownload"

private fun ImportSourceType.offlineDownloadLogSourceName(): String {
    return when (this) {
        ImportSourceType.NAVIDROME -> "Navidrome"
        ImportSourceType.SUBSONIC -> "Subsonic"
        ImportSourceType.LX_MUSIC -> "LX Music"
        else -> name
    }
}

private fun buildOfflineDownloadRequestHeadersLog(source: String, headers: String): String {
    return buildString {
        append("download-request-headers source=")
        append(source)
        append('\n')
        append("headers:\n")
        append(headers)
    }
}

private data class OfflineDownloadSniffPrefix(
    val bytes: ByteArray,
    val length: Int,
) {
    fun subsonicResponseFailureMessage(
        source: String,
        contentType: String?,
    ): String? {
        val preview = bytes.offlineDownloadTextPreview(length)
        val response = preview.sniffSubsonicResponse()
        if (response.looksLikeResponse) {
            return response.failureMessage(source)
        }
        if (contentType.isOfflineDownloadXmlContentType()) {
            return "$source 下载失败：服务器返回 XML 响应。"
        }
        return null
    }
}

private fun isOfflineDownloadAddressFallbackAllowed(throwable: Throwable): Boolean {
    return throwable is OfflineDownloadAddressFallbackException ||
        isRemoteSourceAddressFallbackAllowed(throwable)
}

private suspend fun InputStream.readOfflineDownloadSniffPrefix(): OfflineDownloadSniffPrefix {
    val bytes = ByteArray(OFFLINE_DOWNLOAD_SNIFF_PREVIEW_BYTES)
    var totalRead = 0
    while (totalRead < bytes.size) {
        currentCoroutineContext().ensureActive()
        val read = read(bytes, totalRead, bytes.size - totalRead)
        if (read <= 0) break
        totalRead += read
    }
    return OfflineDownloadSniffPrefix(bytes = bytes, length = totalRead)
}

private fun buildOfflineDownloadResponseSniffLog(
    source: String,
    contentType: String?,
    bytes: ByteArray,
    length: Int,
): String {
    val rawPreview = bytes.offlineDownloadTextPreview(length)
    val subsonicResponse = rawPreview.sniffSubsonicResponse()
    return buildString {
        append("download-response-sniff source=")
        append(source)
        append(" contentType=")
        append(contentType?.takeIf { it.isNotBlank() } ?: "<none>")
        append(" bytes=")
        append(length)
        append(" looksLikeSubsonicResponse=")
        append(subsonicResponse.looksLikeResponse)
        subsonicResponse.status?.let {
            append(" status=")
            append(it)
        }
        subsonicResponse.errorCode?.let {
            append(" errorCode=")
            append(it)
        }
        subsonicResponse.errorMessage?.let {
            append(" errorMessage=\"")
            append(it)
            append('"')
        }
        append('\n')
        append("hexPrefix: ")
        append(bytes.offlineDownloadHexPrefix(length))
        append('\n')
        append("preview:\n")
        append(rawPreview.sanitizeOfflineDownloadPreview().ifBlank { "<empty>" })
    }
}

private fun okhttp3.Headers.formatOfflineDownloadHeaderBlock(): String {
    if (size == 0) return "<empty>"
    return buildString {
        for (index in 0 until size) {
            if (index > 0) append('\n')
            val name = name(index)
            append(name)
            append(": ")
            append(redactOfflineDownloadHeader(name, value(index)))
        }
    }
}

private fun redactOfflineDownloadHeader(name: String, value: String): String {
    return if (name.equals("Authorization", ignoreCase = true) ||
        name.equals("Cookie", ignoreCase = true) ||
        name.equals("X-Emby-Authorization", ignoreCase = true)
    ) {
        "<redacted>"
    } else {
        value
    }
}

private data class OfflineDownloadSubsonicResponseSniff(
    val looksLikeResponse: Boolean,
    val status: String? = null,
    val errorCode: String? = null,
    val errorMessage: String? = null,
) {
    fun failureMessage(source: String): String {
        val detail = errorMessage
            ?: status?.let { "status=$it" }
            ?: "服务器返回 Subsonic XML 响应。"
        return buildString {
            append(source)
            append(" 下载失败：")
            append(detail)
            errorCode?.let {
                append(" (code=")
                append(it)
                append(')')
            }
        }
    }
}

private fun String.sniffSubsonicResponse(): OfflineDownloadSubsonicResponseSniff {
    val normalized = trimStart('\uFEFF', ' ', '\t', '\r', '\n')
    val responseStart = normalized.indexOf("<subsonic-response", ignoreCase = true)
    if (responseStart !in 0..128) {
        return OfflineDownloadSubsonicResponseSniff(looksLikeResponse = false)
    }
    val responseText = normalized.substring(responseStart)
    return OfflineDownloadSubsonicResponseSniff(
        looksLikeResponse = true,
        status = xmlAttributeValue(responseText, "status"),
        errorCode = xmlAttributeValue(responseText, "code"),
        errorMessage = xmlAttributeValue(responseText, "message"),
    )
}

private fun xmlAttributeValue(text: String, name: String): String? {
    return Regex("""\b$name\s*=\s*["']([^"']*)["']""", RegexOption.IGNORE_CASE)
        .find(text)
        ?.groupValues
        ?.getOrNull(1)
        ?.takeIf { it.isNotBlank() }
}

private fun ByteArray.offlineDownloadTextPreview(length: Int): String {
    if (length <= 0) return ""
    val sampleLength = minOf(length, OFFLINE_DOWNLOAD_SNIFF_PREVIEW_BYTES)
    if (!isLikelyTextPayload(sampleLength)) return ""
    return String(this, 0, sampleLength, Charsets.UTF_8)
}

private fun ByteArray.isLikelyTextPayload(length: Int): Boolean {
    if (length <= 0) return true
    for (index in 0 until length) {
        val value = this[index].toInt() and 0xFF
        if (value == 0) return false
        if (value < 0x09 || value in 0x0E..0x1F) return false
    }
    return true
}

private fun ByteArray.offlineDownloadHexPrefix(length: Int): String {
    if (length <= 0) return "<empty>"
    val sampleLength = minOf(length, OFFLINE_DOWNLOAD_SNIFF_HEX_BYTES)
    return (0 until sampleLength).joinToString(separator = " ") { index ->
        (this[index].toInt() and 0xFF).toString(16).padStart(2, '0')
    }
}

private fun String.sanitizeOfflineDownloadPreview(): String {
    return buildString {
        for (char in this@sanitizeOfflineDownloadPreview) {
            when (char) {
                '\r', '\n', '\t' -> append(' ')
                else -> if (!char.isISOControl()) append(char)
            }
        }
    }.trim()
}

private fun String?.isOfflineDownloadXmlContentType(): Boolean {
    val type = this
        ?.substringBefore(';')
        ?.trim()
        ?.lowercase()
        ?: return false
    return type == "application/xml" ||
        type == "text/xml" ||
        type.endsWith("+xml")
}

private const val OFFLINE_DOWNLOAD_SNIFF_PREVIEW_BYTES = 512
private const val OFFLINE_DOWNLOAD_SNIFF_HEX_BYTES = 32

package top.iwesley.lyn.music.domain

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.longOrNull
import kotlinx.serialization.json.put
import top.iwesley.lyn.music.core.model.IMPORT_SOURCE_REQUEST_TIMEOUT_MILLIS
import top.iwesley.lyn.music.core.model.ImportSourceIndexMode
import top.iwesley.lyn.music.core.model.ImportSourceType
import top.iwesley.lyn.music.core.model.LyricsHttpClient
import top.iwesley.lyn.music.core.model.LyricsRequest
import top.iwesley.lyn.music.core.model.NavidromeAudioQuality
import top.iwesley.lyn.music.core.model.RequestMethod
import top.iwesley.lyn.music.core.model.SecureCredentialStore
import top.iwesley.lyn.music.core.model.Track
import top.iwesley.lyn.music.core.model.buildLxMusicSongLocator
import top.iwesley.lyn.music.core.model.parseLxMusicSongLocator
import top.iwesley.lyn.music.data.db.ImportSourceEntity
import top.iwesley.lyn.music.data.db.LynMusicDatabase

data class LxMusicSearchPage(
    val tracks: List<Track>,
    val totalCount: Int? = null,
)

suspend fun testLxMusicBridge(
    httpClient: LyricsHttpClient,
    bridgeUrl: String,
    token: String = "",
) {
    requestLxMusicBridge(
        httpClient = httpClient,
        bridgeUrl = bridgeUrl,
        token = token,
        payload = buildJsonObject {
            put("action", "status")
        },
    )
}

suspend fun searchLxMusicTracks(
    database: LynMusicDatabase,
    secureCredentialStore: SecureCredentialStore,
    httpClient: LyricsHttpClient,
    sourceId: String,
    query: String,
    offset: Int,
    limit: Int,
): LxMusicSearchPage {
    val source = requireOnlineLxMusicSource(database, sourceId)
    val token = source.credentialKey?.let { secureCredentialStore.get(it) }.orEmpty()
    val page = (offset / limit.coerceAtLeast(1)) + 1
    val payload = requestLxMusicBridge(
        httpClient = httpClient,
        bridgeUrl = source.rootReference,
        token = token,
        payload = buildJsonObject {
            put("action", "search")
            put("query", query.trim())
            put("page", page)
            put("offset", offset.coerceAtLeast(0))
            put("limit", limit.coerceAtLeast(1))
        },
    )
    val root = payload.asObjectOrNull()
    val data = root?.get("data").asObjectOrNull() ?: root?.get("result").asObjectOrNull() ?: root
    val list = (payload as? JsonArray)?.mapNotNull { it as? JsonObject }?.takeIf { it.isNotEmpty() }
        ?: data?.get("list").asObjectList()
            .ifEmpty { data?.get("tracks").asObjectList() }
            .ifEmpty { data?.get("songs").asObjectList() }
    return LxMusicSearchPage(
        tracks = list.mapNotNull { item -> item.toLxMusicTrack(sourceId = sourceId) },
        totalCount = data?.int("total") ?: data?.int("totalCount"),
    )
}

suspend fun resolveLxMusicStreamUrl(
    database: LynMusicDatabase,
    secureCredentialStore: SecureCredentialStore,
    httpClient: LyricsHttpClient,
    track: Track,
    audioQuality: NavidromeAudioQuality,
): String? {
    val locator = parseLxMusicSongLocator(track.mediaLocator) ?: return null
    val source = requireOnlineLxMusicSource(database, locator.sourceId)
    val token = source.credentialKey?.let { secureCredentialStore.get(it) }.orEmpty()
    val musicInfo = locator.rawMusicInfoJson
        ?.let { raw -> runCatching { Json.parseToJsonElement(raw).jsonObject }.getOrNull() }
        ?: track.toMinimalLxMusicInfo(locator)
    val payload = requestLxMusicBridge(
        httpClient = httpClient,
        bridgeUrl = source.rootReference,
        token = token,
        payload = buildJsonObject {
            put("action", "musicUrl")
            put("source", locator.platform)
            put("quality", audioQuality.toLxMusicQuality())
            put("type", audioQuality.toLxMusicQuality())
            put(
                "info",
                buildJsonObject {
                    put("type", audioQuality.toLxMusicQuality())
                    put("musicInfo", musicInfo)
                },
            )
            put("musicInfo", musicInfo)
        },
    )
    return payload.urlStringOrNull()
}

private suspend fun requireOnlineLxMusicSource(
    database: LynMusicDatabase,
    sourceId: String,
): ImportSourceEntity {
    val source = database.importSourceDao().getById(sourceId)
        ?: error("LX 音源不存在。")
    require(source.enabled) { "LX 音源已禁用，请先启用。" }
    require(source.type == ImportSourceType.LX_MUSIC.name) { "该来源不是 LX 音源。" }
    require(source.indexMode == ImportSourceIndexMode.ONLINE.name) { "LX 音源必须使用在线模式。" }
    require(source.rootReference.isNotBlank()) { "LX 音源缺少脚本桥接地址。" }
    return source
}

private suspend fun requestLxMusicBridge(
    httpClient: LyricsHttpClient,
    bridgeUrl: String,
    token: String,
    payload: JsonObject,
): JsonElement {
    val response = httpClient.request(
        LyricsRequest(
            method = RequestMethod.POST,
            url = normalizeLxMusicBridgeUrl(bridgeUrl),
            headers = buildMap {
                put("Content-Type", "application/json")
                token.trim().takeIf { it.isNotBlank() }?.let { put("Authorization", "Bearer $it") }
            },
            body = payload.toString(),
            timeoutMillis = IMPORT_SOURCE_REQUEST_TIMEOUT_MILLIS,
        ),
    ).getOrThrow()
    require(response.statusCode in 200..299) { "LX 音源请求失败，HTTP ${response.statusCode}。" }
    val body = response.body.trim()
    return runCatching { Json.parseToJsonElement(body) }
        .getOrElse { JsonPrimitive(body) }
}

private fun normalizeLxMusicBridgeUrl(url: String): String {
    return url.trim().trimEnd('/')
}

private fun JsonObject.toLxMusicTrack(sourceId: String): Track? {
    val platform = string("source") ?: string("platform") ?: string("sourceId") ?: return null
    val songId = string("id")
        ?: string("songmid")
        ?: string("mid")
        ?: string("hash")
        ?: string("rid")
        ?: return null
    val title = string("name") ?: string("title") ?: string("songName") ?: "未知曲目"
    val artist = string("singer")
        ?: string("artist")
        ?: string("artistName")
        ?: get("artists").asStringList().joinToString("、").takeIf { it.isNotBlank() }
    val album = string("albumName") ?: string("album") ?: string("albumTitle")
    val durationMs = durationMillis()
    val rawJson = toString()
    return Track(
        id = lxMusicTrackIdFor(sourceId, platform, songId),
        sourceId = sourceId,
        title = title,
        artistName = artist,
        albumTitle = album,
        durationMs = durationMs,
        mediaLocator = buildLxMusicSongLocator(
            sourceId = sourceId,
            platform = platform,
            songId = songId,
            rawMusicInfoJson = rawJson,
        ),
        relativePath = "$platform/$songId",
        artworkLocator = string("img") ?: string("pic") ?: string("cover") ?: string("coverUrl"),
        sizeBytes = long("size") ?: 0L,
        modifiedAt = 0L,
        bitRate = int("bitrate") ?: int("bitRate"),
        albumId = string("albumId") ?: string("albumMid"),
        artistId = string("artistId") ?: string("singerId"),
    )
}

private fun Track.toMinimalLxMusicInfo(locator: top.iwesley.lyn.music.core.model.LxMusicSongLocator): JsonObject {
    return buildJsonObject {
        put("id", locator.songId)
        put("source", locator.platform)
        put("name", title)
        put("title", title)
        artistName?.let {
            put("singer", it)
            put("artist", it)
        }
        albumTitle?.let {
            put("albumName", it)
            put("album", it)
        }
        if (durationMs > 0L) {
            put("interval", (durationMs / 1_000L).toInt())
            put("duration", durationMs)
        }
        artworkLocator?.let {
            put("img", it)
            put("pic", it)
        }
    }
}

private fun JsonObject.durationMillis(): Long {
    val seconds = long("interval") ?: long("durationSeconds")
    if (seconds != null) return seconds * 1_000L
    val duration = long("duration") ?: return 0L
    return if (duration > 60L * 60L * 1_000L) duration else duration * 1_000L
}

private fun NavidromeAudioQuality.toLxMusicQuality(): String {
    return when (this) {
        NavidromeAudioQuality.Original -> "flac"
        NavidromeAudioQuality.Kbps320 -> "320k"
        NavidromeAudioQuality.Kbps192 -> "320k"
        NavidromeAudioQuality.Kbps128 -> "128k"
    }
}

private fun lxMusicTrackIdFor(sourceId: String, platform: String, songId: String): String {
    return "track:${sourceId}:lx:${platform}:${songId.lowercase()}"
}

private fun JsonElement?.asObjectOrNull(): JsonObject? = this as? JsonObject

private fun JsonElement?.asObjectList(): List<JsonObject> {
    return when (this) {
        is JsonArray -> mapNotNull { it as? JsonObject }
        is JsonObject -> listOf(this)
        else -> emptyList()
    }
}

private fun JsonElement?.urlStringOrNull(): String? {
    return when (this) {
        is JsonPrimitive -> contentOrNull?.trim()?.takeIf { it.startsWith("http://") || it.startsWith("https://") }
        is JsonObject -> string("url")
            ?: get("data").urlStringOrNull()
            ?: get("result").urlStringOrNull()
        else -> null
    }
}

private fun JsonElement?.asStringList(): List<String> {
    return when (this) {
        is JsonArray -> mapNotNull { element ->
            when (element) {
                is JsonPrimitive -> element.contentOrNull
                is JsonObject -> element.string("name")
                else -> null
            }
        }
        is JsonPrimitive -> contentOrNull?.split("/", "、", ",")?.map { it.trim() } ?: emptyList()
        else -> emptyList()
    }
}

private fun JsonObject.string(key: String): String? {
    return (this[key] as? JsonPrimitive)?.contentOrNull?.trim()?.takeIf { it.isNotBlank() }
}

private fun JsonObject.int(key: String): Int? = (this[key] as? JsonPrimitive)?.intOrNull ?: string(key)?.toIntOrNull()

private fun JsonObject.long(key: String): Long? = (this[key] as? JsonPrimitive)?.longOrNull ?: string(key)?.toLongOrNull()

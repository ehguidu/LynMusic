package top.iwesley.lyn.music.feature.importing

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch
import top.iwesley.lyn.music.core.model.EmbySourceDraft
import top.iwesley.lyn.music.core.model.ImportScanPhase
import top.iwesley.lyn.music.core.model.ImportScanProgress
import top.iwesley.lyn.music.core.model.ImportScanProgressSink
import top.iwesley.lyn.music.core.model.ImportScanSummary
import top.iwesley.lyn.music.core.model.ImportSourceIndexMode
import top.iwesley.lyn.music.core.model.ImportSourceType
import top.iwesley.lyn.music.core.model.LocalFolderPickerMode
import top.iwesley.lyn.music.core.model.LocalFolderSelection
import top.iwesley.lyn.music.core.model.LxMusicSourceDraft
import top.iwesley.lyn.music.core.model.NavidromeSourceDraft
import top.iwesley.lyn.music.core.model.PlatformCapabilities
import top.iwesley.lyn.music.core.model.SambaSourceDraft
import top.iwesley.lyn.music.core.model.SourceWithStatus
import top.iwesley.lyn.music.core.model.SubsonicAuthMode
import top.iwesley.lyn.music.core.model.SubsonicSourceDraft
import top.iwesley.lyn.music.core.model.WebDavSourceDraft
import top.iwesley.lyn.music.core.model.displayWebDavRootUrl
import top.iwesley.lyn.music.core.mvi.BaseStore
import top.iwesley.lyn.music.data.repository.ImportSourceRepository
import kotlin.time.Clock

data class RemoteSourceEditorState(
    val sourceId: String,
    val type: ImportSourceType,
    val label: String = "",
    val server: String = "",
    val port: String = "",
    val path: String = "",
    val rootUrl: String = "",
    val wanRootUrl: String = "",
    val username: String = "",
    val password: String = "",
    val allowInsecureTls: Boolean = false,
    val subsonicAuthMode: SubsonicAuthMode = SubsonicAuthMode.PASSWORD,
    val hasStoredCredential: Boolean = false,
    val keepExistingCredential: Boolean = true,
)

sealed interface PendingLargeNavidromeAction {
    data class Create(val draft: NavidromeSourceDraft) : PendingLargeNavidromeAction
    data class Rescan(val sourceId: String, val sourceLabel: String) : PendingLargeNavidromeAction
}

data class PendingLargeNavidromeImport(
    val action: PendingLargeNavidromeAction,
    val remoteTrackCount: Int,
)

sealed interface ImportScanOperation {
    data object CreateLocalFolder : ImportScanOperation
    data class CreateRemote(val type: ImportSourceType) : ImportScanOperation
    data class RescanSource(val sourceId: String) : ImportScanOperation
    data class UpdateRemote(val sourceId: String) : ImportScanOperation
}

data class ImportState(
    val capabilities: PlatformCapabilities,
    val sources: List<SourceWithStatus> = emptyList(),
    val sambaLabel: String = "",
    val sambaServer: String = "",
    val sambaPort: String = "",
    val sambaPath: String = "",
    val sambaUsername: String = "",
    val sambaPassword: String = "",
    val webDavLabel: String = "",
    val webDavRootUrl: String = "",
    val webDavUsername: String = "",
    val webDavPassword: String = "",
    val webDavAllowInsecureTls: Boolean = false,
    val navidromeLabel: String = "",
    val navidromeBaseUrl: String = "",
    val navidromeWanBaseUrl: String = "",
    val navidromeUsername: String = "",
    val navidromePassword: String = "",
    val subsonicLabel: String = "",
    val subsonicBaseUrl: String = "",
    val subsonicWanBaseUrl: String = "",
    val subsonicUsername: String = "",
    val subsonicCredential: String = "",
    val subsonicAuthMode: SubsonicAuthMode = SubsonicAuthMode.PASSWORD,
    val embyLabel: String = "",
    val embyBaseUrl: String = "",
    val embyWanBaseUrl: String = "",
    val embyUsername: String = "",
    val embyPassword: String = "",
    val lxMusicLabel: String = "",
    val lxMusicBridgeUrl: String = "",
    val lxMusicToken: String = "",
    val creatingSourceType: ImportSourceType? = null,
    val editingSource: RemoteSourceEditorState? = null,
    val isWorking: Boolean = false,
    val activeScanOperation: ImportScanOperation? = null,
    val scanProgress: ImportScanProgress? = null,
    val latestScanSummariesBySourceId: Map<String, ImportScanSummary> = emptyMap(),
    val pendingLargeNavidromeImport: PendingLargeNavidromeImport? = null,
    val message: String? = null,
    val testMessage: String? = null,
)

sealed interface ImportIntent {
    data object ImportLocalFolder : ImportIntent
    data class ImportLocalFolderWithPickerMode(val mode: LocalFolderPickerMode) : ImportIntent
    data class ImportSelectedLocalFolder(val selection: LocalFolderSelection) : ImportIntent
    data object TestSambaSource : ImportIntent
    data object AddSambaSource : ImportIntent
    data object TestWebDavSource : ImportIntent
    data object AddWebDavSource : ImportIntent
    data object TestNavidromeSource : ImportIntent
    data object AddNavidromeSource : ImportIntent
    data object ConfirmLargeNavidromeOnlineMode : ImportIntent
    data object ConfirmLargeNavidromeFullImport : ImportIntent
    data object DismissLargeNavidromeChoice : ImportIntent
    data object TestSubsonicSource : ImportIntent
    data object AddSubsonicSource : ImportIntent
    data object TestEmbySource : ImportIntent
    data object AddEmbySource : ImportIntent
    data object TestLxMusicSource : ImportIntent
    data object AddLxMusicSource : ImportIntent
    data class OpenRemoteSourceCreator(val type: ImportSourceType) : ImportIntent
    data object DismissRemoteSourceCreator : ImportIntent
    data class OpenRemoteSourceEditor(val sourceId: String) : ImportIntent
    data object DismissRemoteSourceEditor : ImportIntent
    data object TestRemoteSource : ImportIntent
    data object SaveRemoteSource : ImportIntent
    data class RescanSource(val sourceId: String) : ImportIntent
    data class ToggleSourceEnabled(val sourceId: String, val enabled: Boolean) : ImportIntent
    data class DeleteSource(val sourceId: String) : ImportIntent
    data class SambaLabelChanged(val value: String) : ImportIntent
    data class SambaServerChanged(val value: String) : ImportIntent
    data class SambaPortChanged(val value: String) : ImportIntent
    data class SambaPathChanged(val value: String) : ImportIntent
    data class SambaUsernameChanged(val value: String) : ImportIntent
    data class SambaPasswordChanged(val value: String) : ImportIntent
    data class WebDavLabelChanged(val value: String) : ImportIntent
    data class WebDavRootUrlChanged(val value: String) : ImportIntent
    data class WebDavUsernameChanged(val value: String) : ImportIntent
    data class WebDavPasswordChanged(val value: String) : ImportIntent
    data class WebDavAllowInsecureTlsChanged(val value: Boolean) : ImportIntent
    data class NavidromeLabelChanged(val value: String) : ImportIntent
    data class NavidromeBaseUrlChanged(val value: String) : ImportIntent
    data class NavidromeWanBaseUrlChanged(val value: String) : ImportIntent
    data class NavidromeUsernameChanged(val value: String) : ImportIntent
    data class NavidromePasswordChanged(val value: String) : ImportIntent
    data class SubsonicLabelChanged(val value: String) : ImportIntent
    data class SubsonicBaseUrlChanged(val value: String) : ImportIntent
    data class SubsonicWanBaseUrlChanged(val value: String) : ImportIntent
    data class SubsonicUsernameChanged(val value: String) : ImportIntent
    data class SubsonicCredentialChanged(val value: String) : ImportIntent
    data class SubsonicAuthModeChanged(val value: SubsonicAuthMode) : ImportIntent
    data class EmbyLabelChanged(val value: String) : ImportIntent
    data class EmbyBaseUrlChanged(val value: String) : ImportIntent
    data class EmbyWanBaseUrlChanged(val value: String) : ImportIntent
    data class EmbyUsernameChanged(val value: String) : ImportIntent
    data class EmbyPasswordChanged(val value: String) : ImportIntent
    data class LxMusicLabelChanged(val value: String) : ImportIntent
    data class LxMusicBridgeUrlChanged(val value: String) : ImportIntent
    data class LxMusicTokenChanged(val value: String) : ImportIntent
    data class RemoteSourceLabelChanged(val value: String) : ImportIntent
    data class RemoteSourceServerChanged(val value: String) : ImportIntent
    data class RemoteSourcePortChanged(val value: String) : ImportIntent
    data class RemoteSourcePathChanged(val value: String) : ImportIntent
    data class RemoteSourceRootUrlChanged(val value: String) : ImportIntent
    data class RemoteSourceWanRootUrlChanged(val value: String) : ImportIntent
    data class RemoteSourceUsernameChanged(val value: String) : ImportIntent
    data class RemoteSourcePasswordChanged(val value: String) : ImportIntent
    data class RemoteSourceAllowInsecureTlsChanged(val value: Boolean) : ImportIntent
    data class RemoteSourceSubsonicAuthModeChanged(val value: SubsonicAuthMode) : ImportIntent
    data object ClearMessage : ImportIntent
    data object ClearTestMessage : ImportIntent
}

sealed interface ImportEffect

class ImportStore(
    private val repository: ImportSourceRepository,
    capabilities: PlatformCapabilities,
    scope: CoroutineScope,
) : BaseStore<ImportState, ImportIntent, ImportEffect>(
    initialState = ImportState(capabilities = capabilities),
    scope = scope,
) {
    init {
        scope.launch {
            repository.observeSources().collect { sources ->
                updateState { state ->
                    val sourceIds = sources.mapTo(mutableSetOf()) { it.source.id }
                    state.copy(
                        sources = sources,
                        editingSource = state.editingSource?.takeIf { editing ->
                            sources.any { it.source.id == editing.sourceId && it.source.type == editing.type }
                        },
                        latestScanSummariesBySourceId = state.latestScanSummariesBySourceId.filterKeys { it in sourceIds },
                    )
                }
            }
        }
    }

    override suspend fun handleIntent(intent: ImportIntent) {
        when (intent) {
            ImportIntent.ImportLocalFolder -> {
                importLocalFolder(LocalFolderPickerMode.Automatic)
            }

            is ImportIntent.ImportLocalFolderWithPickerMode -> {
                importLocalFolder(intent.mode)
            }

            is ImportIntent.ImportSelectedLocalFolder -> runScanningImport(ImportScanOperation.CreateLocalFolder) { progressSink ->
                repository.importSelectedLocalFolder(intent.selection, progressSink)
                    .onSuccess { summary ->
                        recordScanSummary(summary)
                        setMessage(scanSuccessMessage("本地音乐源已导入。", summary))
                    }
                    .onFailure { setMessage("导入本地文件夹失败: ${it.message}") }
            }

            ImportIntent.TestSambaSource -> {
                val draft = sambaDraftOrNull(state.value) ?: return
                runImport {
                    repository.testSambaSource(draft)
                        .onSuccess { setTestMessage("Samba 连接测试成功。") }
                        .onFailure { setTestMessage("Samba 连接测试失败: ${it.message}") }
                }
            }

            ImportIntent.AddSambaSource -> {
                val currentState = state.value
                val draft = sambaDraftOrNull(currentState) ?: return
                runScanningImport(ImportScanOperation.CreateRemote(ImportSourceType.SAMBA)) { progressSink ->
                    repository.addSambaSource(draft, progressSink)
                        .onSuccess { summary ->
                            updateState {
                                it.copy(
                                    creatingSourceType = null,
                                    sambaLabel = "",
                                    sambaServer = "",
                                    sambaPort = "",
                                    sambaPath = "",
                                    sambaUsername = "",
                                    sambaPassword = "",
                                    testMessage = null,
                                )
                            }
                            recordScanSummary(summary)
                            setMessage(scanSuccessMessage("Samba 音乐源已导入。", summary))
                        }
                        .onFailure { setCreateOrPageMessage(ImportSourceType.SAMBA, "Samba 导入失败: ${it.message}") }
                }
            }

            ImportIntent.TestWebDavSource -> {
                val draft = webDavDraftOrNull(state.value, allowBlankPassword = true) ?: return
                runImport {
                    repository.testWebDavSource(draft)
                        .onSuccess { setTestMessage("WebDAV 连接测试成功。") }
                        .onFailure { setTestMessage("WebDAV 连接测试失败: ${it.message}") }
                }
            }

            ImportIntent.AddWebDavSource -> {
                val draft = webDavDraftOrNull(state.value, allowBlankPassword = true) ?: return
                runScanningImport(ImportScanOperation.CreateRemote(ImportSourceType.WEBDAV)) { progressSink ->
                    repository.addWebDavSource(draft, progressSink)
                        .onSuccess { summary ->
                            updateState {
                                it.copy(
                                    creatingSourceType = null,
                                    webDavLabel = "",
                                    webDavRootUrl = "",
                                    webDavUsername = "",
                                    webDavPassword = "",
                                    webDavAllowInsecureTls = false,
                                    testMessage = null,
                                )
                            }
                            recordScanSummary(summary)
                            setMessage(scanSuccessMessage("WebDAV 音乐源已导入。", summary))
                        }
                        .onFailure { setCreateOrPageMessage(ImportSourceType.WEBDAV, "WebDAV 导入失败: ${it.message}") }
                }
            }

            ImportIntent.TestNavidromeSource -> {
                val draft = navidromeDraftOrNull(
                    label = state.value.navidromeLabel,
                    baseUrl = state.value.navidromeBaseUrl,
                    wanBaseUrl = state.value.navidromeWanBaseUrl,
                    username = state.value.navidromeUsername,
                    password = state.value.navidromePassword,
                    allowBlankPassword = false,
                ) ?: return
                runImport {
                    repository.testNavidromeSource(draft)
                        .onSuccess { setTestMessage("Navidrome 连接测试成功。") }
                        .onFailure { setTestMessage("Navidrome 连接测试失败: ${it.message}") }
                }
            }

            ImportIntent.AddNavidromeSource -> {
                val draft = navidromeDraftOrNull(
                    label = state.value.navidromeLabel,
                    baseUrl = state.value.navidromeBaseUrl,
                    wanBaseUrl = state.value.navidromeWanBaseUrl,
                    username = state.value.navidromeUsername,
                    password = state.value.navidromePassword,
                    allowBlankPassword = false,
                ) ?: return
                runImport(ImportScanOperation.CreateRemote(ImportSourceType.NAVIDROME)) {
                    repository.probeNavidromeSource(draft)
                        .onSuccess { probe ->
                            val totalTrackCount = probe.totalTrackCount
                            if (
                                probe.supportsOnlineLibraryPaging &&
                                totalTrackCount != null &&
                                totalTrackCount > LARGE_NAVIDROME_LIBRARY_TRACK_THRESHOLD
                            ) {
                                updateState {
                                    it.copy(
                                        pendingLargeNavidromeImport = PendingLargeNavidromeImport(
                                            action = PendingLargeNavidromeAction.Create(draft),
                                            remoteTrackCount = totalTrackCount,
                                        ),
                                        testMessage = null,
                                    )
                                }
                            } else {
                                importNavidromeFull(draft)
                            }
                        }
                        .onFailure { setCreateOrPageMessage(ImportSourceType.NAVIDROME, "Navidrome 导入失败: ${it.message}") }
                }
            }

            ImportIntent.ConfirmLargeNavidromeOnlineMode -> {
                val pending = state.value.pendingLargeNavidromeImport ?: return
                when (val action = pending.action) {
                    is PendingLargeNavidromeAction.Create -> importNavidromeOnline(pending)
                    is PendingLargeNavidromeAction.Rescan -> switchNavidromeRescanToOnline(
                        sourceId = action.sourceId,
                        sourceLabel = action.sourceLabel,
                        remoteTrackCount = pending.remoteTrackCount,
                    )
                }
            }

            ImportIntent.ConfirmLargeNavidromeFullImport -> {
                val pending = state.value.pendingLargeNavidromeImport ?: return
                updateState { it.copy(pendingLargeNavidromeImport = null) }
                when (val action = pending.action) {
                    is PendingLargeNavidromeAction.Create -> importNavidromeFull(action.draft)
                    is PendingLargeNavidromeAction.Rescan -> rescanSourceFull(action.sourceId)
                }
            }

            ImportIntent.DismissLargeNavidromeChoice -> updateState { it.copy(pendingLargeNavidromeImport = null) }

            ImportIntent.TestSubsonicSource -> {
                val draft = subsonicDraftOrNull(
                    label = state.value.subsonicLabel,
                    baseUrl = state.value.subsonicBaseUrl,
                    wanBaseUrl = state.value.subsonicWanBaseUrl,
                    username = state.value.subsonicUsername,
                    credential = state.value.subsonicCredential,
                    authMode = state.value.subsonicAuthMode,
                    allowBlankCredential = false,
                ) ?: return
                runImport {
                    repository.testSubsonicSource(draft)
                        .onSuccess { setTestMessage("Subsonic 连接测试成功。") }
                        .onFailure { setTestMessage("Subsonic 连接测试失败: ${it.message}") }
                }
            }

            ImportIntent.AddSubsonicSource -> {
                val draft = subsonicDraftOrNull(
                    label = state.value.subsonicLabel,
                    baseUrl = state.value.subsonicBaseUrl,
                    wanBaseUrl = state.value.subsonicWanBaseUrl,
                    username = state.value.subsonicUsername,
                    credential = state.value.subsonicCredential,
                    authMode = state.value.subsonicAuthMode,
                    allowBlankCredential = false,
                ) ?: return
                runScanningImport(ImportScanOperation.CreateRemote(ImportSourceType.SUBSONIC)) { progressSink ->
                    repository.addSubsonicSource(draft, progressSink)
                        .onSuccess { summary ->
                            updateState {
                                it.copy(
                                    creatingSourceType = null,
                                    subsonicLabel = "",
                                    subsonicBaseUrl = "",
                                    subsonicWanBaseUrl = "",
                                    subsonicUsername = "",
                                    subsonicCredential = "",
                                    subsonicAuthMode = SubsonicAuthMode.PASSWORD,
                                    testMessage = null,
                                )
                            }
                            recordScanSummary(summary)
                            setMessage(scanSuccessMessage("Subsonic 音乐源已导入。", summary))
                        }
                        .onFailure { setCreateOrPageMessage(ImportSourceType.SUBSONIC, "Subsonic 导入失败: ${it.message}") }
                }
            }

            ImportIntent.TestEmbySource -> {
                val draft = embyDraftOrNull(
                    label = state.value.embyLabel,
                    baseUrl = state.value.embyBaseUrl,
                    wanBaseUrl = state.value.embyWanBaseUrl,
                    username = state.value.embyUsername,
                    password = state.value.embyPassword,
                    allowBlankPassword = false,
                ) ?: return
                runImport {
                    repository.testEmbySource(draft)
                        .onSuccess { setTestMessage("Emby 连接测试成功。") }
                        .onFailure { setTestMessage("Emby 连接测试失败: ${it.message}") }
                }
            }

            ImportIntent.AddEmbySource -> {
                val draft = embyDraftOrNull(
                    label = state.value.embyLabel,
                    baseUrl = state.value.embyBaseUrl,
                    wanBaseUrl = state.value.embyWanBaseUrl,
                    username = state.value.embyUsername,
                    password = state.value.embyPassword,
                    allowBlankPassword = false,
                ) ?: return
                runScanningImport(ImportScanOperation.CreateRemote(ImportSourceType.EMBY)) { progressSink ->
                    repository.addEmbySource(draft, progressSink)
                        .onSuccess { summary ->
                            updateState {
                                it.copy(
                                    creatingSourceType = null,
                                    embyLabel = "",
                                    embyBaseUrl = "",
                                    embyWanBaseUrl = "",
                                    embyUsername = "",
                                    embyPassword = "",
                                    testMessage = null,
                                )
                            }
                            recordScanSummary(summary)
                            setMessage(scanSuccessMessage("Emby 音乐源已导入。", summary))
                        }
                        .onFailure { setCreateOrPageMessage(ImportSourceType.EMBY, "Emby 导入失败: ${it.message}") }
                }
            }

            ImportIntent.TestLxMusicSource -> {
                val draft = lxMusicDraftOrNull(
                    label = state.value.lxMusicLabel,
                    bridgeUrl = state.value.lxMusicBridgeUrl,
                    token = state.value.lxMusicToken,
                    allowBlankToken = true,
                ) ?: return
                runImport {
                    repository.testLxMusicSource(draft)
                        .onSuccess { setTestMessage("LX 音源脚本桥接测试成功。") }
                        .onFailure { setTestMessage("LX 音源脚本桥接测试失败: ${it.message}") }
                }
            }

            ImportIntent.AddLxMusicSource -> {
                val draft = lxMusicDraftOrNull(
                    label = state.value.lxMusicLabel,
                    bridgeUrl = state.value.lxMusicBridgeUrl,
                    token = state.value.lxMusicToken,
                    allowBlankToken = true,
                ) ?: return
                runImport(ImportScanOperation.CreateRemote(ImportSourceType.LX_MUSIC)) {
                    repository.addLxMusicSource(draft)
                        .onSuccess { summary ->
                            updateState {
                                it.copy(
                                    creatingSourceType = null,
                                    lxMusicLabel = "",
                                    lxMusicBridgeUrl = "",
                                    lxMusicToken = "",
                                    testMessage = null,
                                )
                            }
                            recordScanSummary(summary)
                            setMessage("LX 音源已启用，需在曲库来源选择在线来源后搜索歌曲。")
                        }
                        .onFailure { setCreateOrPageMessage(ImportSourceType.LX_MUSIC, "LX 音源保存失败: ${it.message}") }
                }
            }

            is ImportIntent.OpenRemoteSourceCreator -> {
                if (intent.type == ImportSourceType.LOCAL_FOLDER) return
                updateState { state ->
                    state.clearCreateDraft(intent.type).copy(
                        creatingSourceType = intent.type,
                        editingSource = null,
                        pendingLargeNavidromeImport = null,
                        testMessage = null,
                    )
                }
            }

            ImportIntent.DismissRemoteSourceCreator -> updateState { state ->
                val type = state.creatingSourceType
                if (type == null) {
                    state.copy(pendingLargeNavidromeImport = null, testMessage = null)
                } else {
                    state.clearCreateDraft(type).copy(
                        creatingSourceType = null,
                        pendingLargeNavidromeImport = null,
                        testMessage = null,
                    )
                }
            }

            is ImportIntent.OpenRemoteSourceEditor -> {
                val source = state.value.sources.firstOrNull { it.source.id == intent.sourceId }?.source ?: return
                if (source.type == ImportSourceType.LOCAL_FOLDER) return
                updateState {
                    it.copy(
                        editingSource = RemoteSourceEditorState(
                            sourceId = source.id,
                            type = source.type,
                            label = source.label,
                            server = source.server.orEmpty(),
                            port = source.port?.toString().orEmpty(),
                            path = source.path.orEmpty(),
                            rootUrl = if (source.type == ImportSourceType.WEBDAV) {
                                displayWebDavRootUrl(source.rootReference)
                            } else {
                                source.rootReference
                            },
                            wanRootUrl = source.wanRootReference.orEmpty(),
                            username = source.username.orEmpty(),
                            password = "",
                            allowInsecureTls = source.allowInsecureTls,
                            subsonicAuthMode = source.subsonicAuthMode,
                            hasStoredCredential = source.credentialKey != null,
                            keepExistingCredential = true,
                        ),
                    )
                }
            }

            ImportIntent.DismissRemoteSourceEditor -> updateState { it.copy(editingSource = null) }

            ImportIntent.TestRemoteSource -> {
                val editor = state.value.editingSource ?: return
                when (editor.type) {
                    ImportSourceType.SAMBA -> {
                        val draft = editingSambaDraftOrNull(editor) ?: return
                        runImport {
                            repository.testUpdatedSambaSource(
                                sourceId = editor.sourceId,
                                draft = draft,
                                keepExistingCredentialWhenBlankPassword = editor.keepExistingCredential,
                            ).onSuccess {
                                setTestMessage("Samba 连接测试成功。")
                            }.onFailure {
                                setTestMessage("Samba 连接测试失败: ${it.message}")
                            }
                        }
                    }

                    ImportSourceType.WEBDAV -> {
                        val draft = editingWebDavDraftOrNull(editor) ?: return
                        runImport {
                            repository.testUpdatedWebDavSource(
                                sourceId = editor.sourceId,
                                draft = draft,
                                keepExistingCredentialWhenBlankPassword = editor.keepExistingCredential,
                            ).onSuccess {
                                setTestMessage("WebDAV 连接测试成功。")
                            }.onFailure {
                                setTestMessage("WebDAV 连接测试失败: ${it.message}")
                            }
                        }
                    }

                    ImportSourceType.NAVIDROME -> {
                        val draft = editingNavidromeDraftOrNull(editor) ?: return
                        runImport {
                            repository.testUpdatedNavidromeSource(
                                sourceId = editor.sourceId,
                                draft = draft,
                                keepExistingCredentialWhenBlankPassword = editor.keepExistingCredential,
                            ).onSuccess {
                                setTestMessage("Navidrome 连接测试成功。")
                            }.onFailure {
                                setTestMessage("Navidrome 连接测试失败: ${it.message}")
                            }
                        }
                    }

                    ImportSourceType.SUBSONIC -> {
                        val draft = editingSubsonicDraftOrNull(editor) ?: return
                        runImport {
                            repository.testUpdatedSubsonicSource(
                                sourceId = editor.sourceId,
                                draft = draft,
                                keepExistingCredentialWhenBlankCredential = editor.keepExistingCredential,
                            ).onSuccess {
                                setTestMessage("Subsonic 连接测试成功。")
                            }.onFailure {
                                setTestMessage("Subsonic 连接测试失败: ${it.message}")
                            }
                        }
                    }

                    ImportSourceType.EMBY -> {
                        val draft = editingEmbyDraftOrNull(editor) ?: return
                        runImport {
                            repository.testUpdatedEmbySource(
                                sourceId = editor.sourceId,
                                draft = draft,
                                keepExistingCredentialWhenBlankPassword = editor.keepExistingCredential,
                            ).onSuccess {
                                setTestMessage("Emby 连接测试成功。")
                            }.onFailure {
                                setTestMessage("Emby 连接测试失败: ${it.message}")
                            }
                        }
                    }

                    ImportSourceType.LX_MUSIC -> {
                        val draft = editingLxMusicDraftOrNull(editor) ?: return
                        runImport {
                            repository.testUpdatedLxMusicSource(
                                sourceId = editor.sourceId,
                                draft = draft,
                                keepExistingTokenWhenBlank = editor.keepExistingCredential,
                            ).onSuccess {
                                setTestMessage("LX 音源脚本桥接测试成功。")
                            }.onFailure {
                                setTestMessage("LX 音源脚本桥接测试失败: ${it.message}")
                            }
                        }
                    }

                    ImportSourceType.LOCAL_FOLDER -> Unit
                }
            }

            ImportIntent.SaveRemoteSource -> {
                val editor = state.value.editingSource ?: return
                when (editor.type) {
                    ImportSourceType.SAMBA -> {
                        val draft = editingSambaDraftOrNull(editor) ?: return
                        runScanningImport(ImportScanOperation.UpdateRemote(editor.sourceId)) { progressSink ->
                            repository.updateSambaSource(
                                sourceId = editor.sourceId,
                                draft = draft,
                                keepExistingCredentialWhenBlankPassword = editor.keepExistingCredential,
                                progressSink = progressSink,
                            ).onSuccess { summary ->
                                updateState { it.copy(editingSource = null) }
                                recordScanSummary(summary)
                                setMessage(scanSuccessMessage("来源已更新并重新扫描。", summary))
                            }.onFailure {
                                setMessage("更新来源失败: ${it.message}")
                            }
                        }
                    }

                    ImportSourceType.WEBDAV -> {
                        val draft = editingWebDavDraftOrNull(editor) ?: return
                        runScanningImport(ImportScanOperation.UpdateRemote(editor.sourceId)) { progressSink ->
                            repository.updateWebDavSource(
                                sourceId = editor.sourceId,
                                draft = draft,
                                keepExistingCredentialWhenBlankPassword = editor.keepExistingCredential,
                                progressSink = progressSink,
                            ).onSuccess { summary ->
                                updateState { it.copy(editingSource = null) }
                                recordScanSummary(summary)
                                setMessage(scanSuccessMessage("来源已更新并重新扫描。", summary))
                            }.onFailure {
                                setMessage("更新来源失败: ${it.message}")
                            }
                        }
                    }

                    ImportSourceType.NAVIDROME -> {
                        val draft = editingNavidromeDraftOrNull(editor) ?: return
                        runScanningImport(ImportScanOperation.UpdateRemote(editor.sourceId)) { progressSink ->
                            repository.updateNavidromeSource(
                                sourceId = editor.sourceId,
                                draft = draft,
                                keepExistingCredentialWhenBlankPassword = editor.keepExistingCredential,
                                progressSink = progressSink,
                            ).onSuccess { summary ->
                                updateState { it.copy(editingSource = null) }
                                recordScanSummary(summary)
                                setMessage(scanSuccessMessage("来源已更新并重新扫描。", summary))
                            }.onFailure {
                                setMessage("更新来源失败: ${it.message}")
                            }
                        }
                    }

                    ImportSourceType.SUBSONIC -> {
                        val draft = editingSubsonicDraftOrNull(editor) ?: return
                        runScanningImport(ImportScanOperation.UpdateRemote(editor.sourceId)) { progressSink ->
                            repository.updateSubsonicSource(
                                sourceId = editor.sourceId,
                                draft = draft,
                                keepExistingCredentialWhenBlankCredential = editor.keepExistingCredential,
                                progressSink = progressSink,
                            ).onSuccess { summary ->
                                updateState { it.copy(editingSource = null) }
                                recordScanSummary(summary)
                                setMessage(scanSuccessMessage("来源已更新并重新扫描。", summary))
                            }.onFailure {
                                setMessage("更新来源失败: ${it.message}")
                            }
                        }
                    }

                    ImportSourceType.EMBY -> {
                        val draft = editingEmbyDraftOrNull(editor) ?: return
                        runScanningImport(ImportScanOperation.UpdateRemote(editor.sourceId)) { progressSink ->
                            repository.updateEmbySource(
                                sourceId = editor.sourceId,
                                draft = draft,
                                keepExistingCredentialWhenBlankPassword = editor.keepExistingCredential,
                                progressSink = progressSink,
                            ).onSuccess { summary ->
                                updateState { it.copy(editingSource = null) }
                                recordScanSummary(summary)
                                setMessage(scanSuccessMessage("来源已更新并重新扫描。", summary))
                            }.onFailure {
                                setMessage("更新来源失败: ${it.message}")
                            }
                        }
                    }

                    ImportSourceType.LX_MUSIC -> {
                        val draft = editingLxMusicDraftOrNull(editor) ?: return
                        runImport(ImportScanOperation.UpdateRemote(editor.sourceId)) {
                            repository.updateLxMusicSource(
                                sourceId = editor.sourceId,
                                draft = draft,
                                keepExistingTokenWhenBlank = editor.keepExistingCredential,
                            ).onSuccess { summary ->
                                updateState { it.copy(editingSource = null) }
                                recordScanSummary(summary)
                                setMessage("LX 音源已更新。")
                            }.onFailure {
                                setMessage("更新 LX 音源失败: ${it.message}")
                            }
                        }
                    }

                    ImportSourceType.LOCAL_FOLDER -> Unit
                }
            }

            is ImportIntent.RescanSource -> rescanSourceWithLargeNavidromeCheck(intent.sourceId)

            is ImportIntent.ToggleSourceEnabled -> runImport {
                repository.setSourceEnabled(intent.sourceId, intent.enabled)
                    .onSuccess {
                        setMessage(if (intent.enabled) "来源已启用。" else "来源已禁用。")
                    }
                    .onFailure { setMessage("更新来源状态失败: ${it.message}") }
            }

            is ImportIntent.DeleteSource -> runImport {
                repository.deleteSource(intent.sourceId)
                    .onSuccess {
                        clearScanSummary(intent.sourceId)
                        setMessage("音乐源已删除。")
                    }
                    .onFailure { setMessage("删除音乐源失败: ${it.message}") }
            }

            is ImportIntent.SambaLabelChanged -> updateState { it.copy(sambaLabel = intent.value) }
            is ImportIntent.SambaServerChanged -> updateState { it.copy(sambaServer = intent.value) }
            is ImportIntent.SambaPortChanged -> updateState { it.copy(sambaPort = intent.value) }
            is ImportIntent.SambaPathChanged -> updateState { it.copy(sambaPath = intent.value) }
            is ImportIntent.SambaUsernameChanged -> updateState { it.copy(sambaUsername = intent.value) }
            is ImportIntent.SambaPasswordChanged -> updateState { it.copy(sambaPassword = intent.value) }
            is ImportIntent.WebDavLabelChanged -> updateState { it.copy(webDavLabel = intent.value) }
            is ImportIntent.WebDavRootUrlChanged -> updateState { it.copy(webDavRootUrl = intent.value) }
            is ImportIntent.WebDavUsernameChanged -> updateState { it.copy(webDavUsername = intent.value) }
            is ImportIntent.WebDavPasswordChanged -> updateState { it.copy(webDavPassword = intent.value) }
            is ImportIntent.WebDavAllowInsecureTlsChanged -> updateState { it.copy(webDavAllowInsecureTls = intent.value) }
            is ImportIntent.NavidromeLabelChanged -> updateState { it.copy(navidromeLabel = intent.value) }
            is ImportIntent.NavidromeBaseUrlChanged -> updateState { it.copy(navidromeBaseUrl = intent.value) }
            is ImportIntent.NavidromeWanBaseUrlChanged -> updateState { it.copy(navidromeWanBaseUrl = intent.value) }
            is ImportIntent.NavidromeUsernameChanged -> updateState { it.copy(navidromeUsername = intent.value) }
            is ImportIntent.NavidromePasswordChanged -> updateState { it.copy(navidromePassword = intent.value) }
            is ImportIntent.SubsonicLabelChanged -> updateState { it.copy(subsonicLabel = intent.value) }
            is ImportIntent.SubsonicBaseUrlChanged -> updateState { it.copy(subsonicBaseUrl = intent.value) }
            is ImportIntent.SubsonicWanBaseUrlChanged -> updateState { it.copy(subsonicWanBaseUrl = intent.value) }
            is ImportIntent.SubsonicUsernameChanged -> updateState { it.copy(subsonicUsername = intent.value) }
            is ImportIntent.SubsonicCredentialChanged -> updateState { it.copy(subsonicCredential = intent.value) }
            is ImportIntent.SubsonicAuthModeChanged -> updateState {
                if (it.subsonicAuthMode == intent.value) {
                    it
                } else {
                    it.copy(
                        subsonicAuthMode = intent.value,
                        subsonicUsername = if (intent.value == SubsonicAuthMode.API_KEY) "" else it.subsonicUsername,
                        subsonicCredential = "",
                    )
                }
            }
            is ImportIntent.EmbyLabelChanged -> updateState { it.copy(embyLabel = intent.value) }
            is ImportIntent.EmbyBaseUrlChanged -> updateState { it.copy(embyBaseUrl = intent.value) }
            is ImportIntent.EmbyWanBaseUrlChanged -> updateState { it.copy(embyWanBaseUrl = intent.value) }
            is ImportIntent.EmbyUsernameChanged -> updateState { it.copy(embyUsername = intent.value) }
            is ImportIntent.EmbyPasswordChanged -> updateState { it.copy(embyPassword = intent.value) }
            is ImportIntent.LxMusicLabelChanged -> updateState { it.copy(lxMusicLabel = intent.value) }
            is ImportIntent.LxMusicBridgeUrlChanged -> updateState { it.copy(lxMusicBridgeUrl = intent.value) }
            is ImportIntent.LxMusicTokenChanged -> updateState { it.copy(lxMusicToken = intent.value) }
            is ImportIntent.RemoteSourceLabelChanged -> updateEditingSource { it.copy(label = intent.value) }
            is ImportIntent.RemoteSourceServerChanged -> updateEditingSource { it.copy(server = intent.value) }
            is ImportIntent.RemoteSourcePortChanged -> updateEditingSource { it.copy(port = intent.value) }
            is ImportIntent.RemoteSourcePathChanged -> updateEditingSource { it.copy(path = intent.value) }
            is ImportIntent.RemoteSourceRootUrlChanged -> updateEditingSource {
                if (it.type == ImportSourceType.EMBY && it.rootUrl != intent.value) {
                    it.copy(rootUrl = intent.value, password = "", keepExistingCredential = false)
                } else {
                    it.copy(rootUrl = intent.value)
                }
            }
            is ImportIntent.RemoteSourceWanRootUrlChanged -> updateEditingSource { it.copy(wanRootUrl = intent.value) }
            is ImportIntent.RemoteSourceUsernameChanged -> updateEditingSource {
                if (it.type == ImportSourceType.EMBY && it.username != intent.value) {
                    it.copy(username = intent.value, password = "", keepExistingCredential = false)
                } else {
                    it.copy(username = intent.value)
                }
            }
            is ImportIntent.RemoteSourcePasswordChanged -> updateEditingSource {
                it.copy(
                    password = intent.value,
                    keepExistingCredential = intent.value.isBlank(),
                )
            }
            is ImportIntent.RemoteSourceAllowInsecureTlsChanged -> updateEditingSource { it.copy(allowInsecureTls = intent.value) }
            is ImportIntent.RemoteSourceSubsonicAuthModeChanged -> updateEditingSource {
                if (it.subsonicAuthMode == intent.value) {
                    it
                } else {
                    it.copy(
                        subsonicAuthMode = intent.value,
                        username = if (intent.value == SubsonicAuthMode.API_KEY) "" else it.username,
                        password = "",
                        keepExistingCredential = false,
                    )
                }
            }
            ImportIntent.ClearMessage -> updateState { it.copy(message = null) }
            ImportIntent.ClearTestMessage -> updateState { it.copy(testMessage = null) }
        }
    }

    private suspend fun importLocalFolder(mode: LocalFolderPickerMode) {
        runScanningImport(ImportScanOperation.CreateLocalFolder) { progressSink ->
            repository.importLocalFolder(mode, progressSink)
                .onSuccess { summary ->
                    summary?.let {
                        recordScanSummary(it)
                        setMessage(scanSuccessMessage("本地音乐源已导入。", it))
                    }
                }
                .onFailure { setMessage("导入本地文件夹失败: ${it.message}") }
        }
    }

    private suspend fun importNavidromeFull(draft: NavidromeSourceDraft) {
        runScanningImport(ImportScanOperation.CreateRemote(ImportSourceType.NAVIDROME)) { progressSink ->
            repository.addNavidromeSource(draft, progressSink)
                .onSuccess { summary ->
                    clearNavidromeCreator()
                    recordScanSummary(summary)
                    setMessage(scanSuccessMessage("Navidrome 音乐源已导入。", summary))
                }
                .onFailure { setCreateOrPageMessage(ImportSourceType.NAVIDROME, "Navidrome 导入失败: ${it.message}") }
        }
    }

    private suspend fun importNavidromeOnline(pending: PendingLargeNavidromeImport) {
        runImport(ImportScanOperation.CreateRemote(ImportSourceType.NAVIDROME)) {
            val draft = (pending.action as? PendingLargeNavidromeAction.Create)?.draft
                ?: return@runImport
            repository.addNavidromeSourceOnline(
                draft = draft,
                remoteTrackCount = pending.remoteTrackCount,
            ).onSuccess { summary ->
                clearNavidromeCreator()
                recordScanSummary(summary)
                setMessage("Navidrome 在线模式已启用，需在曲库来源选择在线来源。远端共有 ${pending.remoteTrackCount} 首歌曲。")
            }.onFailure {
                updateState { state -> state.copy(pendingLargeNavidromeImport = null) }
                setCreateOrPageMessage(ImportSourceType.NAVIDROME, "Navidrome 在线模式保存失败: ${it.message}")
            }
        }
    }

    private suspend fun rescanSourceWithLargeNavidromeCheck(sourceId: String) {
        val source = state.value.sources.firstOrNull { it.source.id == sourceId }?.source
        if (source?.type == ImportSourceType.NAVIDROME && source.indexMode == ImportSourceIndexMode.LOCAL_INDEX) {
            runImport(ImportScanOperation.RescanSource(sourceId)) {
                repository.probeExistingNavidromeSource(sourceId)
                    .onSuccess { probe ->
                        val totalTrackCount = probe.totalTrackCount
                        if (
                            probe.supportsOnlineLibraryPaging &&
                            totalTrackCount != null &&
                            totalTrackCount > LARGE_NAVIDROME_LIBRARY_TRACK_THRESHOLD
                        ) {
                            updateState {
                                it.copy(
                                    pendingLargeNavidromeImport = PendingLargeNavidromeImport(
                                        action = PendingLargeNavidromeAction.Rescan(
                                            sourceId = sourceId,
                                            sourceLabel = source.label,
                                        ),
                                        remoteTrackCount = totalTrackCount,
                                    ),
                                    testMessage = null,
                                )
                            }
                        } else {
                            rescanSourceFull(sourceId)
                        }
                    }
                    .onFailure { setMessage("重新扫描失败: ${it.message}") }
            }
        } else {
            rescanSourceFull(sourceId)
        }
    }

    private suspend fun rescanSourceFull(sourceId: String) {
        runScanningImport(ImportScanOperation.RescanSource(sourceId)) { progressSink ->
            repository.rescanSource(sourceId, progressSink)
                .onSuccess { summary ->
                    summary?.let(::recordScanSummary)
                    setMessage(scanSuccessMessage("音乐源已重新扫描。", summary))
                }
                .onFailure { setMessage("重新扫描失败: ${it.message}") }
        }
    }

    private suspend fun switchNavidromeRescanToOnline(
        sourceId: String,
        sourceLabel: String,
        remoteTrackCount: Int,
    ) {
        runImport(ImportScanOperation.RescanSource(sourceId)) {
            repository.switchNavidromeSourceToOnline(
                sourceId = sourceId,
                remoteTrackCount = remoteTrackCount,
            ).onSuccess { summary ->
                updateState { it.copy(pendingLargeNavidromeImport = null) }
                recordScanSummary(summary)
                setMessage("“$sourceLabel”已切换为 Navidrome 在线模式。远端共有 $remoteTrackCount 首歌曲，旧本地索引已隐藏并保留。")
            }.onFailure {
                updateState { state -> state.copy(pendingLargeNavidromeImport = null) }
                setMessage("切换在线模式失败: ${it.message}")
            }
        }
    }

    private fun clearNavidromeCreator() {
        updateState {
            it.copy(
                creatingSourceType = null,
                navidromeLabel = "",
                navidromeBaseUrl = "",
                navidromeWanBaseUrl = "",
                navidromeUsername = "",
                navidromePassword = "",
                pendingLargeNavidromeImport = null,
                testMessage = null,
            )
        }
    }

    private fun sambaDraftOrNull(state: ImportState): SambaSourceDraft? {
        val port = state.sambaPort.trim().takeIf { it.isNotBlank() }?.toIntOrNull()
        if (state.sambaServer.isBlank()) {
            setCreateOrPageMessage(ImportSourceType.SAMBA, "请先填写 Samba 服务器地址。")
            return null
        }
        if (state.sambaPort.isNotBlank() && port == null) {
            setCreateOrPageMessage(ImportSourceType.SAMBA, "端口号格式不正确。")
            return null
        }
        if (state.sambaPath.isBlank()) {
            setCreateOrPageMessage(ImportSourceType.SAMBA, "请填写路径，至少包含共享名，例如 Media 或 Media/Music。")
            return null
        }
        return SambaSourceDraft(
            label = state.sambaLabel,
            server = state.sambaServer,
            port = port,
            path = state.sambaPath,
            username = state.sambaUsername,
            password = state.sambaPassword,
        )
    }

    private fun webDavDraftOrNull(
        state: ImportState,
        allowBlankPassword: Boolean,
    ): WebDavSourceDraft? {
        if (state.webDavRootUrl.isBlank()) {
            setCreateOrPageMessage(ImportSourceType.WEBDAV, "请先填写 WebDAV 根 URL。")
            return null
        }
        if (!allowBlankPassword && state.webDavPassword.isBlank()) {
            setCreateOrPageMessage(ImportSourceType.WEBDAV, "请先填写 WebDAV 密码。")
            return null
        }
        if (state.webDavPassword.isNotBlank() && state.webDavUsername.isBlank()) {
            setCreateOrPageMessage(ImportSourceType.WEBDAV, "WebDAV 使用密码时必须填写用户名。")
            return null
        }
        return WebDavSourceDraft(
            label = state.webDavLabel,
            rootUrl = state.webDavRootUrl,
            username = state.webDavUsername,
            password = state.webDavPassword,
            allowInsecureTls = state.webDavAllowInsecureTls,
        )
    }

    private fun navidromeDraftOrNull(
        label: String,
        baseUrl: String,
        wanBaseUrl: String,
        username: String,
        password: String,
        allowBlankPassword: Boolean,
    ): NavidromeSourceDraft? {
        if (baseUrl.isBlank() && wanBaseUrl.isBlank()) {
            setCreateOrPageMessage(ImportSourceType.NAVIDROME, "请至少填写一个服务器地址。")
            return null
        }
        if (username.isBlank()) {
            setCreateOrPageMessage(ImportSourceType.NAVIDROME, "请先填写 Navidrome 用户名。")
            return null
        }
        if (!allowBlankPassword && password.isBlank()) {
            setCreateOrPageMessage(ImportSourceType.NAVIDROME, "请先填写 Navidrome 密码。")
            return null
        }
        return NavidromeSourceDraft(
            label = label,
            baseUrl = baseUrl,
            wanBaseUrl = wanBaseUrl,
            username = username,
            password = password,
        )
    }

    private fun subsonicDraftOrNull(
        label: String,
        baseUrl: String,
        wanBaseUrl: String,
        username: String,
        credential: String,
        authMode: SubsonicAuthMode,
        allowBlankCredential: Boolean,
    ): SubsonicSourceDraft? {
        if (baseUrl.isBlank() && wanBaseUrl.isBlank()) {
            setCreateOrPageMessage(ImportSourceType.SUBSONIC, "请至少填写一个服务器地址。")
            return null
        }
        if (authMode == SubsonicAuthMode.PASSWORD && username.isBlank()) {
            setCreateOrPageMessage(ImportSourceType.SUBSONIC, "请先填写 Subsonic 用户名。")
            return null
        }
        if (!allowBlankCredential && credential.isBlank()) {
            val labelText = if (authMode == SubsonicAuthMode.API_KEY) "API Key" else "密码"
            setCreateOrPageMessage(ImportSourceType.SUBSONIC, "请先填写 Subsonic $labelText。")
            return null
        }
        return SubsonicSourceDraft(
            label = label,
            baseUrl = baseUrl,
            wanBaseUrl = wanBaseUrl,
            username = username,
            credential = credential,
            authMode = authMode,
        )
    }

    private fun embyDraftOrNull(
        label: String,
        baseUrl: String,
        wanBaseUrl: String,
        username: String,
        password: String,
        allowBlankPassword: Boolean,
    ): EmbySourceDraft? {
        if (baseUrl.isBlank() && wanBaseUrl.isBlank()) {
            setCreateOrPageMessage(ImportSourceType.EMBY, "请至少填写一个服务器地址。")
            return null
        }
        if (username.isBlank()) {
            setCreateOrPageMessage(ImportSourceType.EMBY, "请先填写 Emby 用户名。")
            return null
        }
        if (!allowBlankPassword && password.isBlank()) {
            setCreateOrPageMessage(ImportSourceType.EMBY, "请先填写 Emby 密码。")
            return null
        }
        return EmbySourceDraft(
            label = label,
            baseUrl = baseUrl,
            wanBaseUrl = wanBaseUrl,
            username = username,
            password = password,
        )
    }

    private fun lxMusicDraftOrNull(
        label: String,
        bridgeUrl: String,
        token: String,
        allowBlankToken: Boolean,
    ): LxMusicSourceDraft? {
        if (bridgeUrl.isBlank()) {
            setCreateOrPageMessage(ImportSourceType.LX_MUSIC, "请先填写 LX 音源脚本桥接 URL。")
            return null
        }
        if (!bridgeUrl.startsWith("http://") && !bridgeUrl.startsWith("https://")) {
            setCreateOrPageMessage(ImportSourceType.LX_MUSIC, "LX 音源脚本桥接 URL 必须以 http:// 或 https:// 开头。")
            return null
        }
        if (!allowBlankToken && token.isBlank()) {
            setCreateOrPageMessage(ImportSourceType.LX_MUSIC, "请先填写 token。")
            return null
        }
        return LxMusicSourceDraft(
            label = label,
            bridgeUrl = bridgeUrl,
            token = token,
        )
    }

    private fun editingSambaDraftOrNull(editor: RemoteSourceEditorState): SambaSourceDraft? {
        val port = editor.port.trim().takeIf { it.isNotBlank() }?.toIntOrNull()
        if (editor.server.isBlank()) {
            setMessage("请先填写 Samba 服务器地址。")
            return null
        }
        if (editor.port.isNotBlank() && port == null) {
            setMessage("端口号格式不正确。")
            return null
        }
        if (editor.path.isBlank()) {
            setMessage("请填写路径，至少包含共享名，例如 Media 或 Media/Music。")
            return null
        }
        return SambaSourceDraft(
            label = editor.label,
            server = editor.server,
            port = port,
            path = editor.path,
            username = editor.username,
            password = editor.password,
        )
    }

    private fun editingWebDavDraftOrNull(editor: RemoteSourceEditorState): WebDavSourceDraft? {
        if (editor.rootUrl.isBlank()) {
            setMessage("请先填写 WebDAV 根 URL。")
            return null
        }
        if (!editor.keepExistingCredential && editor.password.isBlank()) {
            setMessage("请先填写 WebDAV 密码。")
            return null
        }
        if (editor.password.isNotBlank() && editor.username.isBlank()) {
            setMessage("WebDAV 使用密码时必须填写用户名。")
            return null
        }
        return WebDavSourceDraft(
            label = editor.label,
            rootUrl = editor.rootUrl,
            username = editor.username,
            password = editor.password,
            allowInsecureTls = editor.allowInsecureTls,
        )
    }

    private fun editingNavidromeDraftOrNull(editor: RemoteSourceEditorState): NavidromeSourceDraft? {
        val canReuseStoredPassword = editor.keepExistingCredential && editor.hasStoredCredential
        return navidromeDraftOrNull(
            label = editor.label,
            baseUrl = editor.rootUrl,
            wanBaseUrl = editor.wanRootUrl,
            username = editor.username,
            password = editor.password,
            allowBlankPassword = canReuseStoredPassword,
        )
    }

    private fun editingSubsonicDraftOrNull(editor: RemoteSourceEditorState): SubsonicSourceDraft? {
        val canReuseStoredCredential = editor.keepExistingCredential && editor.hasStoredCredential
        return subsonicDraftOrNull(
            label = editor.label,
            baseUrl = editor.rootUrl,
            wanBaseUrl = editor.wanRootUrl,
            username = editor.username,
            credential = editor.password,
            authMode = editor.subsonicAuthMode,
            allowBlankCredential = canReuseStoredCredential,
        )
    }

    private fun editingEmbyDraftOrNull(editor: RemoteSourceEditorState): EmbySourceDraft? {
        val canReuseStoredCredential = editor.keepExistingCredential && editor.hasStoredCredential
        return embyDraftOrNull(
            label = editor.label,
            baseUrl = editor.rootUrl,
            wanBaseUrl = editor.wanRootUrl,
            username = editor.username,
            password = editor.password,
            allowBlankPassword = canReuseStoredCredential,
        )
    }

    private fun editingLxMusicDraftOrNull(editor: RemoteSourceEditorState): LxMusicSourceDraft? {
        return lxMusicDraftOrNull(
            label = editor.label,
            bridgeUrl = editor.rootUrl,
            token = editor.password,
            allowBlankToken = true,
        )
    }

    private fun updateEditingSource(transform: (RemoteSourceEditorState) -> RemoteSourceEditorState) {
        updateState { state ->
            state.copy(editingSource = state.editingSource?.let(transform))
        }
    }

    private fun ImportState.clearCreateDraft(type: ImportSourceType): ImportState {
        return when (type) {
            ImportSourceType.SAMBA -> copy(
                sambaLabel = "",
                sambaServer = "",
                sambaPort = "",
                sambaPath = "",
                sambaUsername = "",
                sambaPassword = "",
            )

            ImportSourceType.WEBDAV -> copy(
                webDavLabel = "",
                webDavRootUrl = "",
                webDavUsername = "",
                webDavPassword = "",
                webDavAllowInsecureTls = false,
            )

            ImportSourceType.NAVIDROME -> copy(
                navidromeLabel = "",
                navidromeBaseUrl = "",
                navidromeWanBaseUrl = "",
                navidromeUsername = "",
                navidromePassword = "",
            )

            ImportSourceType.SUBSONIC -> copy(
                subsonicLabel = "",
                subsonicBaseUrl = "",
                subsonicWanBaseUrl = "",
                subsonicUsername = "",
                subsonicCredential = "",
                subsonicAuthMode = SubsonicAuthMode.PASSWORD,
            )

            ImportSourceType.EMBY -> copy(
                embyLabel = "",
                embyBaseUrl = "",
                embyWanBaseUrl = "",
                embyUsername = "",
                embyPassword = "",
            )

            ImportSourceType.LX_MUSIC -> copy(
                lxMusicLabel = "",
                lxMusicBridgeUrl = "",
                lxMusicToken = "",
            )

            ImportSourceType.LOCAL_FOLDER -> this
        }
    }

    private fun recordScanSummary(summary: ImportScanSummary) {
        updateState { state ->
            state.copy(
                latestScanSummariesBySourceId = state.latestScanSummariesBySourceId + (summary.sourceId to summary),
            )
        }
    }

    private fun clearScanSummary(sourceId: String) {
        updateState { state ->
            state.copy(latestScanSummariesBySourceId = state.latestScanSummariesBySourceId - sourceId)
        }
    }

    private suspend fun runImport(
        scanOperation: ImportScanOperation? = null,
        block: suspend () -> Unit,
    ) {
        updateState { it.copy(isWorking = true, activeScanOperation = scanOperation) }
        runCatching { block() }
        updateState { it.copy(isWorking = false, activeScanOperation = null) }
    }

    private suspend fun runScanningImport(
        scanOperation: ImportScanOperation,
        block: suspend (ImportScanProgressSink) -> Unit,
    ) {
        val progressSink = ThrottledImportScanProgressSink(emit = { progress ->
            updateState { state -> state.copy(scanProgress = progress) }
        })
        updateState {
            it.copy(
                isWorking = true,
                activeScanOperation = scanOperation,
                scanProgress = null,
            )
        }
        runCatching { block(progressSink) }
        updateState { it.copy(isWorking = false, activeScanOperation = null, scanProgress = null) }
    }

    private fun setMessage(message: String) {
        updateState { it.copy(message = message) }
    }

    private fun setTestMessage(message: String) {
        updateState { it.copy(testMessage = message) }
    }

    private fun setCreateOrPageMessage(type: ImportSourceType, message: String) {
        if (state.value.creatingSourceType == type) {
            setTestMessage(message)
        } else {
            setMessage(message)
        }
    }
}

private class ThrottledImportScanProgressSink(
    private val emit: (ImportScanProgress) -> Unit,
    private val now: () -> Long = { Clock.System.now().toEpochMilliseconds() },
) : ImportScanProgressSink {
    private var lastEmittedAt: Long = 0L

    override fun onProgress(progress: ImportScanProgress) {
        val currentTime = now()
        if (progress.phase == ImportScanPhase.Persisting || lastEmittedAt == 0L || currentTime - lastEmittedAt >= 200L) {
            emit(progress)
            lastEmittedAt = currentTime
        }
    }
}

fun formatImportScanSummary(summary: ImportScanSummary): String {
    return "发现 ${summary.discoveredAudioFileCount} 个音频文件，" +
        "成功导入 ${summary.importedTrackCount} 首，" +
        "${summary.failedAudioFileCount} 个失败"
}

const val LARGE_NAVIDROME_LIBRARY_TRACK_THRESHOLD: Int = 100_000

private fun scanSuccessMessage(prefix: String, summary: ImportScanSummary?): String {
    return summary?.let { "$prefix${formatImportScanSummary(it)}。" } ?: prefix
}

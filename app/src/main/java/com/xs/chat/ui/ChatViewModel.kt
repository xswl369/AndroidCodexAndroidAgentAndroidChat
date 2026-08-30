package com.xs.chat.ui

import android.app.Application
import android.util.Log
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.xs.chat.data.AiModel
import com.xs.chat.data.ApiConfig
import com.xs.chat.data.ApiStore
import com.xs.chat.data.Attachment
import com.xs.chat.data.AttachmentKind
import com.xs.chat.data.AttachmentStore
import com.xs.chat.data.CallMeta
import com.xs.chat.data.CallRole
import com.xs.chat.data.CallRoleStore
import com.xs.chat.data.ChatMessage
import com.xs.chat.data.ContentPart
import com.xs.chat.data.Conversation
import com.xs.chat.data.ConversationMeta
import com.xs.chat.data.ConversationStore
import com.xs.chat.data.ModelStore
import com.xs.chat.data.OpenAiApi
import com.xs.chat.data.Usage
import com.xs.chat.data.PickedAttachment
import com.xs.chat.data.Role
import com.xs.chat.data.SettingsStore
import com.xs.chat.plugins.FileEditPlugin
import com.xs.chat.plugins.MemoryPlugin
import com.xs.chat.plugins.ImagePlugin
import com.xs.chat.plugins.VideoPlugin
import com.xs.chat.plugins.DeviceControlPlugin
import com.xs.chat.plugins.WebSearchPlugin
import com.xs.chat.plugins.PluginRegistry
import com.xs.chat.plugins.PluginRegistry.PluginInfo
import com.wirelessdebug.service.RootController
import java.io.File
import android.util.Base64
import java.util.Locale
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.util.UUID

data class ChatUiState(
    val messages: List<ChatMessage> = emptyList(),
    val models: List<AiModel> = emptyList(),
    val activeModel: AiModel? = null,
    val isStreaming: Boolean = false,
    val conversationTitle: String = "",
    val hasConversation: Boolean = false,
    val history: List<ConversationMeta> = emptyList(),
    val baseUrl: String = "",
    val apiKey: String = "",
    val systemPrompt: String = "",
    val temperature: Float = 0.7f,
    val darkMode: String = "system",
    val loadingModels: Boolean = false,
    val notice: String? = null,
    val pendingAttachments: List<Attachment> = emptyList(),
    val language: String = "zh",
    val imageSize: String = "1024x1024",
    val videoResolution: String = "720P",
    val videoDuration: String = "5",
    val historyEditMode: Boolean = false,
    val selectedHistory: Set<String> = emptySet(),
    val apiProfiles: List<ApiConfig> = emptyList(),
    val memoryLog: List<String> = emptyList(),
    val memoryLimit: Int = 2000,
    val callRoles: List<CallRole> = emptyList(),
    val callRoleId: String = "",
    val rootControlEnabled: Boolean = true,
    val plugins: List<PluginInfo> = PluginRegistry.plugins,
    val enabledPlugins: Set<String> = PluginRegistry.plugins.map { it.id }.toSet(),
    /** 正在被 AI 完善定义的插件 id（添加插件后异步生成触发指令/使用说明）。 */
    val generatingPluginId: String? = null
)

class ChatViewModel(app: Application) : AndroidViewModel(app) {

    private val settings = SettingsStore(app)
    private val modelStore = ModelStore(app)
    private val conversationStore = ConversationStore(app)
    private val apiStore = ApiStore(app)
    private val callRoleStore = CallRoleStore(app)

    private val _ui = MutableStateFlow(ChatUiState())
    val ui: StateFlow<ChatUiState> = _ui.asStateFlow()

    private var conversationId: String? = null
    private var streamingJob: Job? = null
    private var api: OpenAiApi? = null
    private var pendingId: String? = null
    private var pluginJob: Job? = null

    init {
        refreshModels()
        refreshSettings()
        refreshHistory()
        refreshMemory()
        refreshCallRoles()
    }

    // ---------- 配置 ----------

    fun refreshModels() {
        val models = modelStore.getAll()
        val active = models.firstOrNull { it.id == settings.lastModelId }
            ?: modelStore.activeModel()
            ?: models.firstOrNull()
        _ui.update { it.copy(models = models, activeModel = active) }
    }

    fun refreshSettings() {
        _ui.update {
            it.copy(
                baseUrl = settings.baseUrl,
                apiKey = settings.apiKey,
                systemPrompt = settings.systemPrompt,
                temperature = settings.temperature,
                darkMode = settings.darkMode,
                language = settings.language,
                imageSize = settings.imageSize,
                videoResolution = settings.videoResolution,
                videoDuration = settings.videoDuration,
                apiProfiles = apiStore.getAll(),
                memoryLimit = settings.memoryLimit,
                rootControlEnabled = settings.rootControlEnabled,
                plugins = PluginRegistry.all(settings),
                enabledPlugins = PluginRegistry.all(settings).map { it.id }.filter { settings.pluginEnabled(it) }.toSet()
            )
        }
    }

    /** 切换内置插件开关（设置 → 插件）。 */
    fun togglePlugin(pluginId: String, enabled: Boolean) {
        settings.setPluginEnabled(pluginId, enabled)
        refreshPlugins()
        MemoryPlugin.log(getApplication(), if (enabled) "启用插件" else "停用插件", pluginId)
    }

    /** 添加用户插件（设置 → 插件 → 添加）。返回是否添加成功（id 冲突/非法为 false）。 */
    fun addPlugin(id: String, name: String, desc: String): Boolean {
        val ok = PluginRegistry.addUserPlugin(settings, id, name, desc)
        if (ok) {
            refreshPlugins()
            MemoryPlugin.log(getApplication(), "添加插件", id)
            aiPolishPlugin(id, name, desc)
        }
        return ok
    }

    /** 添加插件后自动调用当前模型完善插件定义（触发指令 / 使用说明），AI 失败不影响插件本身。 */
    private fun aiPolishPlugin(id: String, name: String, desc: String) {
        val model = _ui.value.activeModel ?: return
        val app = getApplication<android.app.Application>()
        _ui.update { it.copy(generatingPluginId = id) }
        viewModelScope.launch(Dispatchers.IO) {
            try {
                val api = OpenAiApi(model.baseUrl, model.apiKey)
                val system = "你是手机 App「XS Chat」的插件定义生成器。用户登记了一个新插件，请生成该插件的使用说明，要求：\n" +
                    "① 2-3 条中文触发指令示例，每行一条，格式如「查一下 广州天气」\n" +
                    "② 一句话说明插件功能\n直接输出文本，不要代码块，不要加标题符号。"
                val user = "插件名称：$name\n插件 ID：$id\n用户描述：${desc.ifBlank { "无" }}"
                // 优先非流式；部分网关对 stream=false 路由异常（HTTP 503 无通道）时回退流式聚合（聊天同款路径）
                var result = runCatching {
                    api.completeChat(model.modelId, system, listOf("user" to user), 0.6f)
                }.getOrNull()?.trim()
                if (result.isNullOrBlank()) {
                    result = runCatching {
                        val sb = StringBuilder()
                        val msgs = listOf(ChatMessage(role = Role.USER, content = user))
                        api.streamChat(model.modelId, msgs, system, 0.6f, onDelta = { sb.append(it) })
                        sb.toString()
                    }.onFailure { Log.w(TAG, "aiPolish streamChat failed id=$id", it) }.getOrNull()?.trim()
                }
                if (!result.isNullOrBlank()) {
                    PluginRegistry.updatePluginUsage(settings, id, result)
                    MemoryPlugin.log(app, "AI 完善插件", id)
                }
            } catch (_: Exception) {
            } finally {
                _ui.update { it.copy(generatingPluginId = null) }
                refreshPlugins()
            }
        }
    }

    /** 删除用户插件（内置插件不可删）。 */
    fun removePlugin(pluginId: String) {
        if (PluginRegistry.removeUserPlugin(settings, pluginId)) {
            refreshPlugins()
            MemoryPlugin.log(getApplication(), "删除插件", pluginId)
        }
    }

    private fun refreshPlugins() {
        _ui.update {
            it.copy(
                plugins = PluginRegistry.all(settings),
                enabledPlugins = PluginRegistry.all(settings).map { p -> p.id }.filter { settings.pluginEnabled(it) }.toSet()
            )
        }
    }

    /** 切换 Root 最高权限控制（已 root 设备默认开启）。 */
    fun setRootControl(enabled: Boolean) {
        settings.rootControlEnabled = enabled
        RootController.enabled = enabled
        _ui.update { it.copy(rootControlEnabled = enabled) }
    }

    // ---------- 通话角色 ----------

    fun refreshCallRoles() {
        val roles = callRoleStore.getAll()
        val id = settings.callRoleId
        val active = roles.firstOrNull { it.id == id } ?: roles.firstOrNull()
        if (active != null && settings.callRoleId != active.id) settings.callRoleId = active.id
        _ui.update { it.copy(callRoles = roles, callRoleId = active?.id ?: "") }
    }

    fun activeCallRole(): CallRole? = _ui.value.callRoles.firstOrNull { it.id == _ui.value.callRoleId }

    fun selectCallRole(id: String) {
        settings.callRoleId = id
        MemoryPlugin.log(getApplication(), "选择通话角色", callRoleStore.getAll().firstOrNull { it.id == id }?.name ?: id)
        refreshCallRoles()
    }

    fun addCallRole(role: CallRole) {
        callRoleStore.save(role)
        MemoryPlugin.log(getApplication(), "新增通话角色", role.name)
        refreshCallRoles()
    }

    fun deleteCallRole(id: String) {
        val name = callRoleStore.getAll().firstOrNull { it.id == id }?.name ?: ""
        callRoleStore.delete(id)
        MemoryPlugin.log(getApplication(), "删除通话角色", name)
        refreshCallRoles()
    }

    /** 调节当前通话角色语速（0.5x-2.0x），持久化。 */
    fun updateCallRoleSpeed(speed: Float) {
        val active = activeCallRole() ?: return
        callRoleStore.save(active.copy(speed = speed.coerceIn(0.5f, 2.0f)))
        MemoryPlugin.log(getApplication(), "调节角色语速", active.name + " " + speed + "x")
        refreshCallRoles()
    }

    fun updateMemoryLimit(n: Int) {
        settings.memoryLimit = n
        MemoryPlugin.log(getApplication(), "更新记忆上限", "$n 条")
        refreshSettings()
    }

    // ---------- 记忆插件：操作日志（启动自动读取） ----------

    fun refreshMemory() {
        val logs = MemoryPlugin.readAll(getApplication())
        _ui.update { it.copy(memoryLog = logs) }
    }

    fun clearMemory() {
        MemoryPlugin.clear(getApplication())
        _ui.update { it.copy(memoryLog = emptyList()) }
    }

    fun refreshHistory() {
        viewModelScope.launch(Dispatchers.IO) {
            val meta = conversationStore.listMeta()
            _ui.update { it.copy(history = meta) }
        }
    }

    fun saveSettings(baseUrl: String, apiKey: String, temperature: Float, systemPrompt: String, darkMode: String, language: String? = null) {
        settings.baseUrl = baseUrl
        settings.apiKey = apiKey
        settings.temperature = temperature
        settings.systemPrompt = systemPrompt
        settings.darkMode = darkMode
        if (language != null) settings.language = language
        refreshSettings()
    }

    /** 保存媒体生成参数（图片像素 / 视频分辨率 / 视频时长）。 */
    fun updateMediaSettings(imageSize: String, videoResolution: String, videoDuration: String) {
        settings.imageSize = imageSize
        settings.videoResolution = videoResolution
        settings.videoDuration = videoDuration
        MemoryPlugin.log(getApplication(), "更新媒体参数", "$imageSize / $videoResolution / ${videoDuration}s")
        refreshSettings()
    }

    fun selectModel(model: AiModel) {
        if (_ui.value.isStreaming) return
        settings.lastModelId = model.id
        refreshModels()
    }

    // ---------- API 多配置（可叠加多个服务商及其模型） ----------

    /** 将当前 Base URL / API Key 保存为一条 API 配置。 */
    fun saveApiProfile(name: String) {
        val n = name.trim()
        if (n.isEmpty()) return
        val st = _ui.value
        if (st.baseUrl.isBlank()) {
            notice("请先填写 Base URL")
            return
        }
        apiStore.save(ApiConfig(name = n, baseUrl = st.baseUrl, apiKey = st.apiKey))
        MemoryPlugin.log(getApplication(), "保存 API", n)
        refreshSettings()
    }

    /** 使用指定 API 配置：填入 Base URL / API Key，之后可从该服务加载模型。 */
    fun useApiProfile(id: String) {
        val cfg = apiStore.getAll().firstOrNull { it.id == id } ?: return
        settings.baseUrl = cfg.baseUrl
        settings.apiKey = cfg.apiKey
        MemoryPlugin.log(getApplication(), "切换 API", cfg.name)
        refreshSettings()
    }

    fun deleteApiProfile(id: String) {
        val name = apiStore.getAll().firstOrNull { it.id == id }?.name ?: ""
        apiStore.delete(id)
        MemoryPlugin.log(getApplication(), "删除 API", name)
        refreshSettings()
    }

    // ---------- 模型管理 ----------

    fun addModel(model: AiModel) {
        modelStore.upsert(model)
        refreshModels()
    }

    fun updateModel(model: AiModel) {
        modelStore.upsert(model)
        refreshModels()
    }

    fun deleteModel(id: String) {
        modelStore.delete(id)
        if (settings.lastModelId == id) settings.lastModelId = ""
        refreshModels()
    }

    fun setDefaultModel(id: String) {
        modelStore.setDefault(id)
        settings.lastModelId = id
        refreshModels()
    }

    /** 从所有已保存的 API 配置（含当前设置）加载模型，合并进模型池供智能调度。 */
    fun loadModelsFromServer() {
        if (_ui.value.loadingModels) return
        val endpoints = buildList {
            if (settings.baseUrl.isNotBlank()) add(settings.baseUrl.trimEnd('/') to settings.apiKey)
            apiStore.getAll().forEach { cfg ->
                val key = cfg.baseUrl.trimEnd('/') to cfg.apiKey
                if (key !in this) add(key)
            }
        }
        if (endpoints.isEmpty()) {
            _ui.update { it.copy(notice = "请先填写 Base URL") }
            return
        }
        _ui.update { it.copy(loadingModels = true) }
        viewModelScope.launch {
            try {
                var total = 0
                val errors = mutableListOf<String>()
                for ((base, key) in endpoints) {
                    runCatching {
                        val infos = withContext(Dispatchers.IO) { OpenAiApi(base, key).listModels() }
                        if (infos.isEmpty()) return@runCatching
                        val existing = modelStore.getAll()
                        val seen = existing.map { it.modelId to base }.toSet()
                        val fresh = infos.mapNotNull { info ->
                            if (info.id to base in seen) null
                            else AiModel(modelId = info.id, name = info.id, baseUrl = base, apiKey = key)
                        }
                        if (fresh.isNotEmpty()) {
                            val merged = existing + fresh
                            val hasDefault = merged.any { it.isDefault }
                            modelStore.saveAll(
                                if (hasDefault) merged
                                else merged.mapIndexed { i, m -> if (i == 0) m.copy(isDefault = true) else m }
                            )
                            if (settings.lastModelId.isBlank()) settings.lastModelId = fresh.first().id
                            total += fresh.size
                        }
                    }.onFailure { e -> errors += "${base.take(40)}: ${e.message ?: "网络错误"}" }
                }
                refreshModels()
                notice(
                    when {
                        total > 0 -> "加载成功：新增 $total 个模型"
                        errors.isEmpty() -> "模型已是最新，未发现新模型"
                        else -> "部分 API 加载失败：${errors.joinToString("；").take(140)}"
                    }
                )
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                notice("加载模型失败：${e.message ?: "网络错误"}")
            } finally {
                _ui.update { it.copy(loadingModels = false) }
            }
        }
    }

    // ---------- 会话 ----------

    fun newConversation() {
        stop()
        MemoryPlugin.log(getApplication(), "新建会话")
        conversationId = null
        pendingId = null
        _ui.update { it.copy(messages = emptyList(), conversationTitle = "", hasConversation = false, notice = null) }
    }

    fun openConversation(id: String) {
        stop()
        viewModelScope.launch(Dispatchers.IO) {
            val c = conversationStore.load(id)
            withContext(Dispatchers.Main) {
                if (c != null) {
                    conversationId = c.id
                    pendingId = null
                    MemoryPlugin.log(getApplication(), "打开会话", c.title.take(40))
                    _ui.update {
                        it.copy(
                            messages = c.messages,
                            conversationTitle = c.title,
                            hasConversation = true,
                            notice = null
                        )
                    }
                }
            }
        }
    }

    /** 通话中实时同步消息到当前对话（语音识别文本 / AI 回复），实时显示并持久化。 */
    fun appendCallMessage(role: Role, content: String) {
        val text = content.trim()
        if (text.isEmpty()) return
        val id = conversationId ?: UUID.randomUUID().toString()
        conversationId = id
        val msg = ChatMessage(role = role, content = text)
        val messages = _ui.value.messages + msg
        val title = _ui.value.conversationTitle.ifBlank { text.take(30) }
        _ui.update { it.copy(messages = messages, hasConversation = true, conversationTitle = title, notice = null) }
        persist(id, messages, title)
        refreshHistory()
    }

    fun deleteConversation(id: String) {
        conversationStore.delete(id)
        if (conversationId == id) newConversation()
        refreshHistory()
    }

    // ---------- 对话 ----------

    fun sendMessage(text: String) {
        val content = text.trim()
        val pending = _ui.value.pendingAttachments
        if (content.isEmpty() && pending.isEmpty()) return
        if (_ui.value.isStreaming) return
        val model = _ui.value.activeModel
        if (model == null) {
            _ui.update { it.copy(notice = "请先添加并选择模型（设置 → 添加模型 / 加载模型）") }
            return
        }
        stop()
        MemoryPlugin.log(getApplication(), "发送消息", content.ifBlank { "（图片/文件）" }.take(60))

        // 设备控制意图优先接管：命中则直接操控手机（类 Codex 电脑版控制）
        if (deviceControlIntent(content)) return

        // 联网搜索意图接管：命中则实时联网搜索
        if (webSearchIntent(content)) return

        // 媒体生成意图优先接管：命中则直接调生图/生视频插件，避免 AI 只回复提示词
        if (mediaGenIntent(content, pending)) return

        val isNew = conversationId == null
        val id = conversationId ?: UUID.randomUUID().toString()
        conversationId = id
        val firstDesc = content.ifBlank { pending.firstOrNull()?.name?.take(30) ?: "图片" }
        val title = if (isNew) firstDesc.take(30) else _ui.value.conversationTitle

        _ui.update { it.copy(pendingAttachments = emptyList()) }
        val userMsg = ChatMessage(role = Role.USER, content = content, attachments = pending)
        val assistant = ChatMessage(role = Role.ASSISTANT, content = "")
        val messages = _ui.value.messages + userMsg + assistant
        pendingId = assistant.id

        _ui.update {
            it.copy(
                messages = messages,
                hasConversation = true,
                conversationTitle = title,
                notice = null
            )
        }
        persist(id, messages, title)
        stream(model, messages)
    }

    fun regenerate() {
        val st = _ui.value
        if (st.isStreaming) return
        val model = st.activeModel ?: return
        val msgs = st.messages
        if (msgs.size < 2 || msgs.last().role != Role.ASSISTANT) return

        val truncated = msgs.dropLast(1)
        val assistant = ChatMessage(role = Role.ASSISTANT, content = "")
        val messages = truncated + assistant
        pendingId = assistant.id
        _ui.update { it.copy(messages = messages, notice = null) }
        persist(conversationId ?: UUID.randomUUID().toString(), messages, st.conversationTitle)
        stream(model, messages)
    }

    fun editMessage(index: Int, newText: String) {
        val content = newText.trim()
        if (content.isEmpty() || _ui.value.isStreaming) return
        val st = _ui.value
        if (index !in st.messages.indices) return
        val model = st.activeModel ?: return

        val base = st.messages.take(index)
        val updated = base + st.messages[index].copy(content = content)
        val assistant = ChatMessage(role = Role.ASSISTANT, content = "")
        val messages = updated + assistant
        pendingId = assistant.id
        _ui.update { it.copy(messages = messages, notice = null) }
        persist(conversationId ?: UUID.randomUUID().toString(), messages, st.conversationTitle)
        stream(model, messages)
    }

    fun deleteMessage(index: Int) {
        val st = _ui.value
        if (index !in st.messages.indices || st.isStreaming) return
        val messages = st.messages.filterIndexed { i, _ -> i != index }
        _ui.update { it.copy(messages = messages) }
        persist(conversationId ?: return, messages, st.conversationTitle)
    }

    fun stop() {
        api?.cancel()
        streamingJob?.cancel()
        streamingJob = null
        pluginJob?.cancel()
        pluginJob = null
        api = null
        val id = pendingId
        pendingId = null
        if (id != null) {
            _ui.update { it.copy(isStreaming = false) }
            val cid = conversationId
            if (cid != null) persist(cid, _ui.value.messages, _ui.value.conversationTitle)
            refreshHistory()
        }
    }

    fun clearNotice() {
        _ui.update { it.copy(notice = null) }
    }

    // ---------- 附件 ----------

    fun addPendingAttachments(picked: List<PickedAttachment>) {
        if (picked.isEmpty()) return
        viewModelScope.launch {
            val imported = withContext(Dispatchers.IO) {
                picked.mapNotNull { AttachmentStore.importToLocal(getApplication(), it) }
            }
            _ui.update { st -> st.copy(pendingAttachments = st.pendingAttachments + imported) }
            if (imported.size != picked.size) notice("部分附件导入失败")
        }
    }

    fun removePendingAttachment(id: String) {
        _ui.update { st -> st.copy(pendingAttachments = st.pendingAttachments.filterNot { it.id == id }) }
    }

    // ---------- 会话重命名 ----------

    fun renameConversation(title: String) {
        val t = title.trim()
        if (t.isEmpty()) return
        val cid = conversationId
        if (cid == null) {
            notice("请先开始对话再重命名")
            return
        }
        _ui.update { it.copy(conversationTitle = t) }
        persist(cid, _ui.value.messages, t)
        refreshHistory()
    }

    // ---------- 历史会话编辑模式（全选/单选/删除/置顶） ----------

    fun toggleHistoryEditMode() {
        _ui.update { it.copy(historyEditMode = !it.historyEditMode, selectedHistory = emptySet()) }
    }

    fun exitHistoryEditMode() {
        _ui.update { it.copy(historyEditMode = false, selectedHistory = emptySet()) }
    }

    fun toggleSelectHistory(id: String) {
        _ui.update { st ->
            val sel = st.selectedHistory
            st.copy(selectedHistory = if (id in sel) sel - id else sel + id)
        }
    }

    fun selectAllHistory() {
        _ui.update { st ->
            val all = st.history.map { it.id }.toSet()
            st.copy(selectedHistory = if (st.selectedHistory.size == all.size) emptySet() else all)
        }
    }

    fun deleteSelectedHistory() {
        val ids = _ui.value.selectedHistory.toList()
        if (ids.isEmpty()) return
        conversationStore.deleteMany(ids)
        if (conversationId in ids) newConversation()
        exitHistoryEditMode()
        refreshHistory()
    }

    fun pinSelectedHistory(pinned: Boolean) {
        val ids = _ui.value.selectedHistory.toList()
        if (ids.isEmpty()) return
        conversationStore.setPinned(ids, pinned)
        exitHistoryEditMode()
        refreshHistory()
    }

    /** 单个会话置顶/取消置顶（侧边栏单击置顶图标 / 长按菜单）。 */
    fun pinConversation(id: String, pinned: Boolean) {
        conversationStore.setPinned(listOf(id), pinned)
        refreshHistory()
    }

    // ---------- 消息操作 ----------

    /** 编辑 assistant 消息内容（不重新请求）。 */
    fun editAssistantMessage(index: Int, newText: String) {
        val text = newText.trim()
        if (text.isEmpty() || _ui.value.isStreaming) return
        val st = _ui.value
        if (index !in st.messages.indices) return
        val messages = st.messages.toMutableList()
        messages[index] = messages[index].copy(content = text)
        _ui.update { it.copy(messages = messages) }
        val cid = conversationId ?: return
        persist(cid, messages, st.conversationTitle)
    }

    /** 翻译指定消息：调用当前模型，译文作为新 assistant 消息追加。 */
    fun translateMessage(index: Int) {
        if (_ui.value.isStreaming) return
        val model = _ui.value.activeModel
        if (model == null) {
            notice("请先选择模型")
            return
        }
        val st = _ui.value
        if (index !in st.messages.indices) return
        val content = st.messages[index].content
        if (content.isBlank()) return
        val target = when (st.language) {
            "en" -> "English"
            "system" -> if (Locale.getDefault().language == "en") "English" else "简体中文"
            else -> "简体中文"
        }
        val assistant = ChatMessage(role = Role.ASSISTANT, content = "翻译中…")
        val messages = st.messages + assistant
        pendingId = assistant.id
        val startMs = System.currentTimeMillis()
        _ui.update { it.copy(messages = messages, notice = null) }
        pluginJob = viewModelScope.launch {
            _ui.update { it.copy(isStreaming = true) }
            try {
                val result = withContext(Dispatchers.IO) {
                    var u: Usage? = null
                    val text = OpenAiApi(model.baseUrl, model.apiKey, readTimeoutMs = 120_000).completeChat(
                        model = model.modelId,
                        systemPrompt = "你是专业翻译。将用户消息翻译成 $target，只输出译文，不要任何解释或前后缀。",
                        userMessages = listOf("user" to content),
                        temperature = 0.2f,
                        onUsage = { u = it }
                    )
                    text to u
                }
                completeAssistant(
                    assistant.id,
                    "🌐 翻译：\n" + result.first.trim(),
                    emptyList(),
                    CallMeta(
                        promptTokens = result.second?.promptTokens ?: 0,
                        completionTokens = result.second?.completionTokens ?: 0,
                        durationMs = System.currentTimeMillis() - startMs
                    )
                )
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                pluginError(assistant.id, e.message ?: "翻译失败")
            } finally {
                finishPluginWork()
            }
        }
    }

    // ---------- 内置插件 ----------

    fun runImageGen(prompt: String, picked: List<PickedAttachment>? = null, refsOverride: List<Attachment>? = null) {
        if (_ui.value.isStreaming) return
        val model = pickMediaModel("image") ?: run {
            notice("未找到可用模型，请先在设置中添加模型")
            return
        }
        val clean = prompt.trim()
        if (clean.isEmpty()) {
            notice("请输入图片描述")
            return
        }
        val app = getApplication<android.app.Application>()
        pluginJob = viewModelScope.launch {
            // 对话框内多选参考图先全部导入本地，再合并既有待发送附件
            val pickedRefs = withContext(Dispatchers.IO) {
                picked.orEmpty().mapNotNull { runCatching { AttachmentStore.importToLocal(app, it) }.getOrNull() }
            }
            val refs = refsOverride ?: pickedRefs + _ui.value.pendingAttachments.filter { it.kind == AttachmentKind.IMAGE }
            if (refs.isEmpty()) notice("提示：未附加参考图，将执行文生图")
            val assistantId = beginPluginWork("【AI 生图】$clean", refs)
            val startMs = System.currentTimeMillis()
            _ui.update { it.copy(isStreaming = true) }
            // 图片接口为同步请求，用模拟进度递增到 90%，完成时置 100
            val sim = viewModelScope.launch {
                var p = 3
                while (isActive && p < 90) {
                    p = (p + 4).coerceAtMost(90)
                    updateMediaProgress(assistantId, p)
                    delay(300)
                }
            }
            try {
                val refBytes = withContext(Dispatchers.IO) {
                    refs.firstOrNull()?.let { AttachmentStore.readBytes(app, it) }
                }
                val attach = ImagePlugin.generate(app, model, clean, refBytes, _ui.value.imageSize)
                sim.cancel()
                updateMediaProgress(assistantId, 100)
                MemoryPlugin.log(app, "生成图片", clean.take(60))
                completeAssistant(assistantId, "✅ 已生成图片", listOf(attach), CallMeta(durationMs = System.currentTimeMillis() - startMs))
            } catch (e: CancellationException) {
                sim.cancel()
                throw e
            } catch (e: Exception) {
                sim.cancel()
                pluginError(assistantId, e.message?.takeIf { it.isNotBlank() } ?: e::class.java.simpleName)
            } finally {
                finishPluginWork()
            }
        }
    }

    fun runVideoGen(prompt: String, picked: List<PickedAttachment>? = null, refsOverride: List<Attachment>? = null) {
        if (_ui.value.isStreaming) return
        val model = pickMediaModel("video") ?: run {
            notice("未找到可用模型，请先在设置中添加模型")
            return
        }
        val clean = prompt.trim().ifEmpty { "让图片动起来" }
        val app = getApplication<android.app.Application>()
        pluginJob = viewModelScope.launch {
            // 对话框内多选图片先全部导入本地，再合并既有待发送图片
            val pickedRefs = withContext(Dispatchers.IO) {
                picked.orEmpty().mapNotNull { runCatching { AttachmentStore.importToLocal(app, it) }.getOrNull() }
            }
            val refs = refsOverride ?: pickedRefs + _ui.value.pendingAttachments.filter { it.kind == AttachmentKind.IMAGE }
            if (refs.isEmpty()) notice("提示：未附加参考图，将执行文生视频")
            val assistantId = beginPluginWork("【AI 生视频】$clean", refs)
            val startMs = System.currentTimeMillis()
            _ui.update { it.copy(isStreaming = true) }
            // 模拟进度兜底（服务端轮询进度返回后会覆盖为真实值）
            val sim = viewModelScope.launch {
                var p = 2
                while (isActive && p < 90) {
                    p = (p + 2).coerceAtMost(90)
                    updateMediaProgress(assistantId, p)
                    delay(500)
                }
            }
            try {
                val frame = withContext(Dispatchers.IO) {
                    refs.firstOrNull()?.let { AttachmentStore.readBytes(app, it) }
                }
                val seconds = _ui.value.videoDuration.toIntOrNull() ?: 5
                val attach = VideoPlugin.generate(
                    app, model, clean, frame,
                    _ui.value.videoResolution,
                    seconds
                ) { p -> updateMediaProgress(assistantId, p) }
                sim.cancel()
                updateMediaProgress(assistantId, 100)
                MemoryPlugin.log(app, "生成视频", (clean.take(50) + "（$seconds 秒）"))
                completeAssistant(assistantId, "✅ 已生成视频（$seconds 秒）", listOf(attach), CallMeta(durationMs = System.currentTimeMillis() - startMs))
            } catch (e: CancellationException) {
                sim.cancel()
                throw e
            } catch (e: Exception) {
                sim.cancel()
                pluginError(assistantId, e.message?.takeIf { it.isNotBlank() } ?: e::class.java.simpleName)
            } finally {
                finishPluginWork()
            }
        }
    }

    fun runFileEdit(prompt: String, picked: List<PickedAttachment>? = null) {
        if (_ui.value.isStreaming) return
        if (!_ui.value.enabledPlugins.contains("file_edit")) {
            notice("文件修改插件已关闭（设置 → 插件可重新开启）")
            return
        }
        val model = _ui.value.activeModel ?: run {
            notice("请先选择模型")
            return
        }
        val clean = prompt.trim()
        if (clean.isEmpty()) {
            notice("请输入修改要求")
            return
        }
        val app = getApplication<android.app.Application>()
        pluginJob = viewModelScope.launch {
            // 对话框内选择的文件先导入本地（单选），再合并既有待发送文件
            val pickedRef = withContext(Dispatchers.IO) {
                picked?.firstOrNull()?.let { runCatching { AttachmentStore.importToLocal(app, it) }.getOrNull() }
            }
            val refs = listOfNotNull(pickedRef) + _ui.value.pendingAttachments.filter { it.kind == AttachmentKind.FILE }
            if (refs.isEmpty()) {
                notice("请先添加一个文本文件")
                return@launch
            }
            val assistantId = beginPluginWork("【AI 修改文件】$clean", refs)
            val startMs = System.currentTimeMillis()
            _ui.update { it.copy(isStreaming = true) }
            try {
                val target = refs.first()
                val content = withContext(Dispatchers.IO) { AttachmentStore.readText(app, target) }
                    ?: throw RuntimeException("附件不是可读文本或超过 300KB")
                val edited = withContext(Dispatchers.IO) {
                    var u: Usage? = null
                    val text = FileEditPlugin.edit(model, clean, target.name, content, onUsage = { u = it })
                    text to u
                }
                val (path, savedPath) = withContext(Dispatchers.IO) {
                    // 私有副本（附件可点击查看）+ 公共下载目录导出（用户可直接取用）
                    val local = AttachmentStore.saveEditedFile(app, target.name, edited.first)
                    val pub = AttachmentStore.exportTextToDownloads(app, target.name, edited.first)
                    local to (pub ?: "（导出下载目录失败，仅保存在应用私有目录）")
                }
                val attach = Attachment(
                    kind = AttachmentKind.FILE,
                    name = target.name,
                    mimeType = "text/plain",
                    sizeBytes = File(path).length(),
                    uri = "file://" + path,
                    generated = true
                )
                val preview = previewEdited(target.name, edited.first)
                completeAssistant(
                    assistantId,
                    "✅ 文件已修改（${attach.name}，${attach.sizeBytes / 1024} KB），已保留原文件格式（换行符/BOM/缩进）\n\n" +
                        "修改要求：$clean\n保存位置：$savedPath\n\n" +
                        "$preview\n\n点击下方附件可在应用内查看完整内容并再次保存",
                    listOf(attach),
                    CallMeta(
                        promptTokens = edited.second?.promptTokens ?: 0,
                        completionTokens = edited.second?.completionTokens ?: 0,
                        durationMs = System.currentTimeMillis() - startMs
                    )
                )
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                pluginError(assistantId, e.message?.takeIf { it.isNotBlank() } ?: e::class.java.simpleName)
            } finally {
                finishPluginWork()
            }
        }
    }

    /** 插件开始：插入用户消息（含参考附件）+ 空 assistant 占位，返回 assistantId。 */
    private fun beginPluginWork(userContent: String, refs: List<Attachment>): String {
        val id = conversationId ?: UUID.randomUUID().toString()
        conversationId = id
        val userMsg = ChatMessage(role = Role.USER, content = userContent, attachments = refs)
        val assistant = ChatMessage(role = Role.ASSISTANT, content = "")
        val messages = _ui.value.messages + userMsg + assistant
        pendingId = assistant.id
        _ui.update {
            it.copy(messages = messages, hasConversation = true, conversationTitle = _ui.value.conversationTitle.ifBlank { userContent.take(30) }, notice = null)
        }
        persist(id, messages, _ui.value.conversationTitle)
        return assistant.id
    }

    /** 实时更新指定 assistant 消息的生成进度（0-100），用于图片/视频进度条。 */
    private fun updateMediaProgress(assistantId: String, progress: Int) {
        _ui.update { st ->
            val idx = st.messages.indexOfLast { it.id == assistantId }
            if (idx < 0) return@update st
            val p = progress.coerceIn(0, 100)
            val list = st.messages.toMutableList()
            val m = list[idx]
            if (m.progress == p) return@update st
            list[idx] = m.copy(progress = p)
            st.copy(messages = list)
        }
    }

    /** 修改后内容的 Markdown 预览：按扩展名带语言标签，截断过长内容。 */
    private fun previewEdited(fileName: String, content: String): String {
        val max = 4000
        val text = content.removePrefix("\uFEFF")
        val truncated = text.length > max
        val body = if (truncated) text.take(max) + "\n…（已截断，点击附件查看完整内容）" else text
        return "```${langTagFor(fileName)}\n$body\n```"
    }

    /** 按文件扩展名返回 Markdown 代码块语言标签。 */
    private fun langTagFor(fileName: String): String {
        val ext = fileName.substringAfterLast('.', "").lowercase(Locale.ROOT)
        return when (ext) {
            "kt", "kts" -> "kotlin"
            "java" -> "java"
            "py" -> "python"
            "js", "mjs", "cjs" -> "javascript"
            "ts" -> "typescript"
            "html", "htm" -> "html"
            "css" -> "css"
            "json" -> "json"
            "xml" -> "xml"
            "yml", "yaml" -> "yaml"
            "md", "markdown" -> "markdown"
            "sql" -> "sql"
            "sh", "bash" -> "bash"
            "c" -> "c"
            "cpp", "cc", "h" -> "cpp"
            "go" -> "go"
            "rs" -> "rust"
            "swift" -> "swift"
            "php" -> "php"
            "rb" -> "ruby"
            "gradle", "gradle.kts" -> "gradle"
            "txt", "" -> "text"
            else -> ext
        }
    }

    private fun completeAssistant(assistantId: String, content: String, attachments: List<Attachment>, callMeta: CallMeta? = null) {
        MemoryPlugin.log(getApplication(), "AI 回复", content.replace("\n", " ").take(40))
        _ui.update { st ->
            val last = st.messages.lastOrNull() ?: return@update st
            if (last.id != assistantId) return@update st
            st.copy(messages = st.messages.dropLast(1) + last.copy(content = content, attachments = attachments, callMeta = callMeta, progress = null))
        }
    }

    /** 为指定 assistant 消息补记调用统计（流式结束后写入）。 */
    private fun attachCallMeta(assistantId: String, meta: CallMeta) {
        _ui.update { st ->
            val last = st.messages.lastOrNull() ?: return@update st
            if (last.id != assistantId) return@update st
            st.copy(messages = st.messages.dropLast(1) + last.copy(callMeta = meta))
        }
    }

    private fun pluginError(assistantId: String, message: String) {
        appendError(assistantId, message)
    }

    private fun finishPluginWork() {
        if (!_ui.value.isStreaming) return
        _ui.update { it.copy(isStreaming = false) }
        pluginJob = null
        val id = pendingId
        pendingId = null
        if (id != null) {
            val cid = conversationId
            if (cid != null) persist(cid, _ui.value.messages, _ui.value.conversationTitle)
            refreshHistory()
        }
    }

    /** 智能调度：模型池来自所有已保存 API，按能力关键词在全池匹配；当前模型具备能力则优先，否则取最优匹配，最后兜底当前模型。 */
    private fun pickMediaModel(keyword: String): AiModel? {
        val st = _ui.value
        val active = st.activeModel ?: return st.models.firstOrNull()
        val keywords = when (keyword) {
            "image" -> IMAGE_CAPABILITY_KEYWORDS
            "video" -> VIDEO_CAPABILITY_KEYWORDS
            else -> listOf(keyword)
        }
        val pool = st.models.filter { m ->
            val id = m.modelId.lowercase(Locale.ROOT)
            keywords.any { it in id }
        }
        return pool.firstOrNull { it.id == active.id }
            ?: pool.firstOrNull()
            ?: active
    }

    /** 输入框消息自动识别媒体生成意图：命中接管为生图/生视频（排除疑问句式，避免误伤普通对话）。 */
    private fun mediaGenIntent(content: String, pending: List<Attachment>): Boolean {
        val lower = content.lowercase(Locale.ROOT)
        // 去掉空格再匹配，规避输入法丢空格导致关键词断词（如 "video generation" → "videogeneration"）
        val compact = lower.replace(" ", "")
        val question = Regex("(如何|怎么|怎样|教程|方法|推荐|能否|能不能|可以吗|会吗)").containsMatchIn(lower)
            || lower.trimEnd().endsWith("?") || lower.trimEnd().endsWith("？") || lower.trimEnd().endsWith("吗")
        val images = pending.filter { it.kind == AttachmentKind.IMAGE }
        return when {
            !question && VIDEO_INTENT_KEYWORDS.any { compact.contains(it.replace(" ", "")) } -> {
                if (_ui.value.enabledPlugins.contains("video_gen")) {
                    _ui.update { it.copy(pendingAttachments = emptyList()) }
                    runVideoGen(content, refsOverride = images)
                    true
                } else false
            }
            !question && IMAGE_INTENT_KEYWORDS.any { compact.contains(it.replace(" ", "")) } -> {
                if (_ui.value.enabledPlugins.contains("image_gen")) {
                    _ui.update { it.copy(pendingAttachments = emptyList()) }
                    runImageGen(content, refsOverride = images)
                    true
                } else false
            }
            else -> false
        }
    }

    /** 输入框消息自动识别联网搜索意图：命中实时联网搜索（排除疑问句式）。 */
    private fun webSearchIntent(content: String): Boolean {
        if (!_ui.value.enabledPlugins.contains("web_search")) return false
        val lower = content.lowercase(Locale.ROOT).trim().replace(Regex("^(请帮我|帮我|请|麻烦)"), "")
        val question = Regex("(如何|怎么|怎样|教程|方法|推荐|能否|能不能|可以吗|会吗)").containsMatchIn(lower)
            || lower.endsWith("?") || lower.endsWith("？") || lower.endsWith("吗")
        if (question) return false
        val m = Regex("^(搜索|搜一下|搜|查一下|查找|查|联网搜|联网搜索|帮我搜|帮我查|web search|search)\\s+(.+)$").find(lower)
        if (m != null) {
            _ui.update { it.copy(pendingAttachments = emptyList()) }
            runWebSearch(m.groupValues[2])
            return true
        }
        return false
    }

    fun runWebSearch(query: String) {
        if (_ui.value.isStreaming) return
        val app = getApplication<android.app.Application>()
        pluginJob = viewModelScope.launch {
            val assistantId = beginPluginWork("【联网搜索】$query", emptyList())
            _ui.update { it.copy(isStreaming = true) }
            try {
                val result = WebSearchPlugin.search(query)
                MemoryPlugin.log(app, "联网搜索", query.take(40))
                completeAssistant(assistantId, result, emptyList())
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                pluginError(assistantId, e.message?.takeIf { it.isNotBlank() } ?: e::class.java.simpleName)
            } finally {
                finishPluginWork()
            }
        }
    }

    /** 输入框消息自动识别设备控制意图：命中直接操控手机（类 Codex 电脑版控制）。 */
    private fun deviceControlIntent(content: String): Boolean {
        if (!_ui.value.enabledPlugins.contains("device_control")) return false
        val lower = content.lowercase(Locale.ROOT)
        val compact = lower.replace(" ", "")
        val question = Regex("(如何|怎么|怎样|教程|方法|推荐|能否|能不能|可以吗|会吗)").containsMatchIn(lower)
            || lower.trimEnd().endsWith("?") || lower.trimEnd().endsWith("？") || lower.trimEnd().endsWith("吗")
        if (question) return false
        val openTrigger = Regex("^(打开|启动|开启|open|launch)\\s*\\S.*$").matches(lower)
        val kwTrigger = DEVICE_CONTROL_KEYWORDS.any { compact.contains(it.replace(" ", "")) }
        if (openTrigger || kwTrigger) {
            _ui.update { it.copy(pendingAttachments = emptyList()) }
            runDeviceControl(content)
            return true
        }
        return false
    }

    fun runDeviceControl(instruction: String) {
        if (_ui.value.isStreaming) return
        val app = getApplication<android.app.Application>()
        pluginJob = viewModelScope.launch {
            val assistantId = beginPluginWork("【设备控制】$instruction", emptyList())
            _ui.update { it.copy(isStreaming = true) }
            try {
                val result = DeviceControlPlugin.execute(app, instruction)
                MemoryPlugin.log(app, "设备控制", instruction.take(40))
                completeAssistant(assistantId, result, emptyList())
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                pluginError(assistantId, e.message?.takeIf { it.isNotBlank() } ?: e::class.java.simpleName)
            } finally {
                finishPluginWork()
            }
        }
    }

    companion object {
        private const val TAG = "ChatViewModel"

        /** 输入框自动触发生图/生视频的意图关键词（疑问句式由 mediaGenIntent 排除）。 */
        private val IMAGE_INTENT_KEYWORDS = listOf(
            "生成图片", "生成图像", "生成一张", "生成图", "画一张", "画个", "画一幅", "帮我画",
            "画图", "绘图", "文生图", "图生图", "生成画面", "画一下",
            "generate image", "generate a picture", "generate an image", "draw a",
            "text to image", "image to image", "image generation"
        )
        private val VIDEO_INTENT_KEYWORDS = listOf(
            "生成视频", "做视频", "制作视频", "图生视频", "文生视频", "视频生成",
            "生成一段视频", "生成个视频", "动起来", "生成动画", "动画视频", "动态视频",
            "generate video", "make a video", "create a video", "video generation",
            "text to video", "image to video", "t2v", "i2v"
        )

        /** 设备控制（类 Codex 电脑版）意图关键词。 */
        private val DEVICE_CONTROL_KEYWORDS = listOf(
            "控制手机", "操作手机", "操控手机", "控制设备", "帮我点", "点击屏幕", "点一下",
            "打开应用", "读屏", "看看屏幕", "查看屏幕", "屏幕内容", "屏幕上有什么",
            "帮我打开", "帮我输入", "输入文字", "上滑", "下滑", "左滑", "右滑", "滑动屏幕",
            "返回桌面", "锁屏", "通知栏", "下拉通知", "back", "home", "read screen",
            "tap", "click", "swipe", "control phone", "control device"
        )

        /** 文生图/图生图模型命名特征（覆盖 OpenAI/Flux/SD/即梦/混元/通义等）。 */
        private val IMAGE_CAPABILITY_KEYWORDS = listOf(
            "image", "dall-e", "dalle", "flux", "sdxl", "stable-diffusion", "imagen",
            "midjourney", "cogview", "seedream", "kolors", "hunyuan", "gpt-image",
            "txt2img", "img2img", "wanx", "tongyi", "draw", "photogen", "painting",
            "gemini", "nano-banana", "seedream", "cogview", "minimax", "art"
        )

        /** 图生视频/文生视频模型命名特征（Sora/Veo/Kling/可灵/即梦/海螺等）。 */
        private val VIDEO_CAPABILITY_KEYWORDS = listOf(
            "video", "sora", "veo", "kling", "runway", "hailuo", "pika", "luma",
            "cogvideo", "cogvideox", "dreamina", "movavi", "wanx", "gen-3", "gen-4",
            "pexels", "stable-video", "video-01", "wan2", "hunyuan-video", "seedance"
        )
    }

    // ---------- 内部 ----------

    // ---------- 内部 ----------

    private fun stream(model: AiModel, messages: List<ChatMessage>) {
        val instance = OpenAiApi(model.baseUrl, model.apiKey)
        api = instance
        val assistantId = pendingId ?: return
        val startMs = System.currentTimeMillis()
        _ui.update { it.copy(isStreaming = true) }
        streamingJob = viewModelScope.launch {
            var completed = false
            var usage: Usage? = null
            try {
                completed = withContext(Dispatchers.IO) {
                    val parts = resolveParts(messages)
                    instance.streamChat(
                        model = model.modelId,
                        messages = messages.filter { it.role != Role.SYSTEM },
                        systemPrompt = _ui.value.systemPrompt.ifBlank { null },
                        temperature = _ui.value.temperature,
                        attachmentParts = parts,
                        onDelta = { delta -> appendDelta(assistantId, delta) },
                        onUsage = { usage = it }
                    )
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                appendError(assistantId, e.message ?: "网络请求失败")
            }
            if (!_ui.value.isStreaming) return@launch // 已被 stop() 接管
            if (completed) {
                attachCallMeta(
                    assistantId,
                    CallMeta(
                        promptTokens = usage?.promptTokens ?: 0,
                        completionTokens = usage?.completionTokens ?: 0,
                        durationMs = System.currentTimeMillis() - startMs
                    )
                )
            }
            _ui.update { it.copy(isStreaming = false) }
            streamingJob = null
            api = null
            pendingId = null
            val cid = conversationId
            if (cid != null) persist(cid, _ui.value.messages, _ui.value.conversationTitle)
            refreshHistory()
        }
    }

    private fun appendDelta(assistantId: String, delta: String) {
        _ui.update { st ->
            val last = st.messages.lastOrNull() ?: return@update st
            if (last.id != assistantId) return@update st
            st.copy(messages = st.messages.dropLast(1) + last.copy(content = last.content + delta))
        }
    }

    private fun appendError(assistantId: String, message: String) {
        _ui.update { st ->
            val last = st.messages.lastOrNull() ?: return@update st
            if (last.id != assistantId) return@update st
            val content = if (last.content.isBlank()) "请求失败：$message" else last.content
            st.copy(messages = st.messages.dropLast(1) + last.copy(content = content, error = true))
        }
    }

    private fun notice(text: String) {
        _ui.update { it.copy(notice = text) }
    }

    private fun resolveParts(messages: List<ChatMessage>): Map<String, List<ContentPart>> {
        val result = mutableMapOf<String, List<ContentPart>>()
        val app = getApplication<android.app.Application>()
        messages.forEach { msg ->
            val parts = msg.attachments.orEmpty().mapNotNull { resolvePart(app, it) }
            if (parts.isNotEmpty()) result[msg.id] = parts
        }
        return result
    }

    private fun resolvePart(app: android.app.Application, a: Attachment): ContentPart? {
        val bytes = AttachmentStore.readBytes(app, a) ?: run {
            notice("附件 ${a.name} 读取失败，已跳过")
            return null
        }
        return when (a.kind) {
            AttachmentKind.IMAGE -> ContentPart(
                type = "image_url",
                dataUrl = "data:${a.mimeType.ifBlank { "image/png" }};base64," + Base64.encodeToString(bytes, Base64.NO_WRAP)
            )
            AttachmentKind.VIDEO -> ContentPart(
                type = "input_video",
                dataUrl = "data:${a.mimeType.ifBlank { "video/mp4" }};base64," + Base64.encodeToString(bytes, Base64.NO_WRAP)
            )
            AttachmentKind.FILE -> {
                val text = AttachmentStore.readText(app, a)
                if (text != null) ContentPart(type = "text", text = "\n[附件：${a.name}]\n$text")
                else ContentPart(
                    type = "file",
                    dataUrl = "data:${a.mimeType.ifBlank { "application/octet-stream" }};base64," + Base64.encodeToString(bytes, Base64.NO_WRAP),
                    fileName = a.name
                )
            }
        }
    }

    private fun persist(id: String, messages: List<ChatMessage>, title: String) {
        conversationStore.save(
            Conversation(
                id = id,
                title = title,
                messages = messages,
                createdAt = System.currentTimeMillis(),
                updatedAt = System.currentTimeMillis(),
                modelId = _ui.value.activeModel?.id ?: ""
            )
        )
    }

    override fun onCleared() {
        stop()
        super.onCleared()
    }
}

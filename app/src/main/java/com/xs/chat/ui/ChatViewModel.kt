package com.xs.chat.ui

import android.app.Application
import android.net.Uri
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
import com.xs.chat.data.SearchReference
import com.xs.chat.data.SettingsStore
import com.xs.chat.plugins.FileEditPlugin
import com.xs.chat.plugins.MemoryPlugin
import com.xs.chat.plugins.ImagePlugin
import com.xs.chat.plugins.VideoPlugin
import com.xs.chat.plugins.AppIndexPlugin
import com.xs.chat.plugins.DeviceControlPlugin
import com.xs.chat.plugins.LocalIntentClassifier
import com.xs.chat.plugins.KnowledgeBase
import com.xs.chat.plugins.WebSearchPlugin
import com.xs.chat.plugins.PluginRegistry
import com.xs.chat.plugins.PluginRegistry.PluginInfo
import com.xs.chat.plugins.ScriptRunner
import com.xs.chat.plugins.ScriptStore
import com.wirelessdebug.service.AdbShellController
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
import kotlinx.coroutines.withTimeoutOrNull
import java.util.UUID
import java.util.concurrent.atomic.AtomicBoolean

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
    /** 无线调试内置 key 未被本机授权（换机/撤销后），需要一键重新配对。 */
    val devicePairNeeded: Boolean = false,
    val plugins: List<PluginInfo> = PluginRegistry.plugins,
    val enabledPlugins: Set<String> = PluginRegistry.plugins.map { it.id }.toSet(),
    /** 联网搜索模式（元宝同款）：0 关闭 / 1 自动 / 2 总是开启，默认自动。 */
    val webSearchMode: Int = 1,
    /** 思考深度（Codex 同款）：auto / low / medium / high / xhigh。 */
    val reasoningEffort: String = "auto",
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
    /** 设备意图云端二次确认任务（候选阶段防并发重入）。 */
    private var intentCheckJob: Job? = null

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
                webSearchMode = settings.webSearchMode,
                reasoningEffort = settings.reasoningEffort,
                enabledPlugins = PluginRegistry.all(settings).map { it.id }.filter { settings.pluginEnabled(it) }.toSet()
            )
        }
    }

    /** 切换思考深度（Codex 同款）：auto / low / medium / high / xhigh。 */
    fun setReasoningEffort(effort: String) {
        settings.reasoningEffort = effort
        _ui.update { it.copy(reasoningEffort = effort) }
        MemoryPlugin.log(getApplication(), "思考深度", effort)
    }

    /** 切换联网搜索模式（元宝同款）：0 关闭 / 1 自动 / 2 总是开启。 */
    fun setWebSearchMode(mode: Int) {
        settings.webSearchMode = mode
        _ui.update { it.copy(webSearchMode = mode) }
        MemoryPlugin.log(
            getApplication(), "联网搜索",
            when (mode) { 0 -> "关闭"; 2 -> "总是开启"; else -> "自动" }
        )
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

    /**
     * 添加脚本型插件（设置 → 插件 → 添加）：先把脚本写盘，成功后再登记元数据。
     * 返回 null 表示成功，否则返回错误原因（由弹窗直接展示）。
     */
    fun addScriptPlugin(name: String, desc: String, deps: String, uri: Uri?, fileName: String): String? {
        val app = getApplication<Application>()
        val fname = fileName.substringAfterLast('/').substringAfterLast('\\').trim()
        if (fname.isEmpty()) return "请选择脚本文件"
        val pluginId = PluginRegistry.addScriptPlugin(settings, name, desc, fname, deps)
            ?: return "仅支持 .sh / .py / .js / .lua 脚本"
        val realUri = uri
            ?: return rollbackScriptPlugin(app, pluginId, "无法读取所选文件")
        val err = ScriptStore.save(app, PluginRegistry.userPlugins(settings).first { it.id == pluginId }, realUri, fname)
        if (err != null) return rollbackScriptPlugin(app, pluginId, err)
        refreshPlugins()
        MemoryPlugin.log(app, "添加脚本插件", pluginId)
        val p = PluginRegistry.userPlugins(settings).firstOrNull { it.id == pluginId }
        if (p != null) aiPolishPlugin(p.id, p.name, "上传的 " + PluginRegistry.langLabel(p.lang) + " 脚本插件。" + desc)
        return null
    }

    /** 脚本落盘失败时回滚：移除注册项并清理目录，返回原错误文案。 */
    private fun rollbackScriptPlugin(app: Application, pluginId: String, reason: String): String {
        PluginRegistry.removeUserPlugin(settings, pluginId)
        ScriptStore.deleteDir(app, pluginId)
        refreshPlugins()
        return reason
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

    /** 删除用户插件（内置插件不可删）；脚本插件连同脚本目录一并清理。 */
    fun removePlugin(pluginId: String) {
        if (PluginRegistry.removeUserPlugin(settings, pluginId)) {
            ScriptStore.deleteDir(getApplication(), pluginId)
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
        if (intentCheckJob?.isActive == true) {
            _ui.update { it.copy(notice = "正在判断指令意图，请稍候…") }
            return
        }
        val model = _ui.value.activeModel
        if (model == null) {
            _ui.update { it.copy(notice = "请先添加并选择模型（设置 → 添加模型 / 加载模型）") }
            return
        }
        stop()
        MemoryPlugin.log(getApplication(), "发送消息", content.ifBlank { "（图片/文件）" }.take(60))

        // 内置知识快答（计算器/单位换算/星座/节气/省份/元素…离线毫秒级直答，不依赖模型与网络）
        KnowledgeBase.answer(content)?.let { ans ->
            builtInReply(content, ans)
            return
        }

        // 内置验证命令（自动化/ADB 可测，不依赖模型）：check news / check lunar / check huangli / check date
        if (content.startsWith("check", ignoreCase = true)) {
            runSelfCheck(if (content.length > 5) content.substring(5).trimStart() else "")
            return
        }

        // 显式联网搜索指令优先：说「搜/查一下…」必联网（元宝同款）
        if (webSearchIntent(content, explicitOnly = true)) return

        // 设备控制意图：两段式——本地词表快判 + 云端小模型兜底（避免换个说法就漏判/误判）
        when (deviceIntentVerdict(content)) {
            IntentVerdict.DEVICE -> {
                _ui.update { it.copy(pendingAttachments = emptyList()) }
                runDeviceControl(content)
                return
            }
            IntentVerdict.CANDIDATE -> {
                _ui.update { it.copy(notice = "正在判断指令意图…") }
                intentCheckJob = viewModelScope.launch {
                    try {
                        if (cloudConfirmDevice(content)) {
                            _ui.update { it.copy(pendingAttachments = emptyList()) }
                            runDeviceControl(content)
                        } else {
                            startChatFlow(content)
                        }
                    } finally {
                        intentCheckJob = null
                        _ui.update { it.copy(notice = null) }
                    }
                }
                return
            }
            IntentVerdict.NONE -> {}
        }

        // 媒体生成意图优先接管：命中则直接调生图/生视频插件，避免 AI 只回复提示词
        if (mediaGenIntent(content, pending)) return

        // 脚本插件意图接管：说「用<插件名>…」时执行用户上传的脚本，其余文字作为脚本参数
        if (scriptPluginIntent(content)) return

        // 内置联网搜索（元宝同款）：按当前模式自动/总是开启触发
        if (webSearchIntent(content, explicitOnly = false)) return

        startChatFlow(content)
    }

    /** 常规聊天流：将消息加入会话并流式回答（设备意图候选判定为聊天后也走这里）。 */
    private fun startChatFlow(content: String) {
        val model = _ui.value.activeModel ?: return
        val pending = _ui.value.pendingAttachments
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
        intentCheckJob?.cancel()
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
            // 图片接口为同步请求且无服务端回调：按耗时匀速模拟，45s 内到 90%，完成时置 100
            val sim = launchSimulatedProgress(assistantId, AtomicBoolean(false), capPct = 90, pctPerSecond = 2f)
            try {
                val refBytes = withContext(Dispatchers.IO) {
                    refs.firstOrNull()?.let { AttachmentStore.readBytes(app, it, maxBytes = Long.MAX_VALUE) }
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
            // 模拟进度仅作兜底：未收到服务端真实进度前，保守走到 40% 封顶；
            // 轮询返回真实进度后立即停掉模拟，此后进度条只显示服务端真实值（只增不减）
            val realProgressSeen = AtomicBoolean(false)
            val sim = launchSimulatedProgress(assistantId, realProgressSeen, capPct = 40, pctPerSecond = 0.9f)
            try {
                val frame = withContext(Dispatchers.IO) {
                    refs.firstOrNull()?.let { AttachmentStore.readBytes(app, it, maxBytes = Long.MAX_VALUE) }
                }
                val seconds = _ui.value.videoDuration.toIntOrNull() ?: 5
                val attach = VideoPlugin.generate(
                    app, model, clean, frame,
                    _ui.value.videoResolution,
                    seconds
                ) { p -> realProgressSeen.set(true); updateMediaProgress(assistantId, p) }
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
                val content = withContext(Dispatchers.IO) { AttachmentStore.readText(app, target, maxBytes = Long.MAX_VALUE) }
                    ?: throw RuntimeException("附件不是文本文件，无法用 AI 修改")
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

    /** 实时更新指定 assistant 消息的生成进度（0-100）：单调递增，杜绝进度条回退。 */
    private fun updateMediaProgress(assistantId: String, progress: Int) {
        _ui.update { st ->
            val idx = st.messages.indexOfLast { it.id == assistantId }
            if (idx < 0) return@update st
            val p = progress.coerceIn(0, 100)
            val list = st.messages.toMutableList()
            val m = list[idx]
            if (p <= (m.progress ?: 0)) return@update st
            list[idx] = m.copy(progress = p)
            st.copy(messages = list)
        }
    }

    /** 模拟生成进度：按耗时匀速推进；收到服务端真实进度（realSeen）后立即退出。 */
    private fun launchSimulatedProgress(assistantId: String, realSeen: AtomicBoolean, capPct: Int, pctPerSecond: Float): Job {
        return viewModelScope.launch {
            val t0 = System.currentTimeMillis()
            var shown = 0
            while (isActive && !realSeen.get()) {
                val p = (((System.currentTimeMillis() - t0) / 1000f) * pctPerSecond + 1).toInt().coerceAtMost(capPct)
                if (p != shown) {
                    shown = p
                    updateMediaProgress(assistantId, p)
                }
                delay(250)
            }
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

    /** 事实型问题关键词：命中即自动触发内置联网搜索（DeepSeek 联网同策略）。 */
    private val WEB_QUERY_KEYWORDS = listOf(
        "如何", "怎么", "怎样", "什么样", "是什么", "什么是", "有哪些", "多少", "几号", "哪里", "何处",
        "什么时候", "何时", "是否", "会不会", "能不能", "有没有", "最新", "最近", "今天", "明天", "昨日",
        "本周", "下周", "新闻", "热搜", "热点", "股票", "股价", "价格", "行情", "汇率", "天气", "气温",
        "预报", "台风", "地震", "比赛", "比分", "赛程", "结果", "名单", "榜单", "排行榜", "发布", "上线",
        "更新", "事件", "事故", "评测", "推荐", "攻略", "教程", "措施", "下载", "怎么买", "多少钱", "为什么",
        "农历", "阴历", "黄历", "宜", "忌", "吉日", "人民日报",
        "新闻", "热点", "热搜", "热门", "头条", "要闻", "十大",
        "进展", "现状", "对比", "哪个好", "值不值得", "是真的吗", "会怎样"
    )

    /** 内置联网搜索（元宝同款）：explicitOnly=true 只认显式指令；false 时按当前模式自动触发。 */
    private fun webSearchIntent(content: String, explicitOnly: Boolean): Boolean {
        val found = extractSearchQuery(content, explicitOnly) ?: return false
        _ui.update { it.copy(pendingAttachments = emptyList()) }
        runWebSearchReply(content, found.first, found.second)
        return true
    }

    /**
     * 提取联网搜索 query，返回 (query, 是否显式指令)。
     * 显式指令：搜索/查一下/联网搜索+内容；自动触发：仅含事实型关键词的问句；总是开启：一切提问。
     */
    private fun extractSearchQuery(content: String, explicitOnly: Boolean): Pair<String, Boolean>? {
        if (!_ui.value.enabledPlugins.contains("web_search")) return null
        val mode = _ui.value.webSearchMode
        val text = content.trim()
        if (text.isEmpty()) return null
        // ① 显式指令：去掉礼貌前缀后直接取关键词后的内容（“搜索华为”“查一下天气”都可以）
        val explicit = Regex(
            "^(?:请|麻烦|帮我|请帮我)?(?:联网搜索|搜索一下|搜一下|搜索|查找|查询|帮我搜|帮我查|搜|查|web\\s*search|search)\\s*[:：,，]?\\s*(.+)$",
            RegexOption.IGNORE_CASE
        ).find(text)
        if (explicit != null) {
            val q = explicit.groupValues[1].trim()
            if (q.isNotEmpty()) return if (mode == WebSearchPlugin.MODE_OFF) null else q to true
        }
        if (explicitOnly) return null
        // ② 总是开启：每次提问都自动联网搜索（元宝同款）
        if (mode == WebSearchPlugin.MODE_ALWAYS) return text to false
        // ③ 自动：闲聊 / 关于 AI 自身的问题不联网
        if (text.length !in 4..200) return null
        val lower = text.lowercase(Locale.ROOT)
        if (Regex("^(你好|您好|hello|hi|哈喽|谢谢|感谢|好的|ok|知道了|继续|再来|再见|拜拜|晚安|早上好|下午好|晚上好)").containsMatchIn(lower)) return null
        if (Regex("你(?:是谁|叫什么|的名字|能做什么|会什么|会干什么|有什么功能|是什么|是什么模型|的作者|怎么用|是什么时候|是谁做的)").containsMatchIn(lower)) return null
        // ④ 事实/时效关键词或日期数字（如“2026年9月3日”“3月30日”）命中 → 触发联网
        val dateLike = Regex("""[0-9\u4E00-\u9FA5]{1,4}\s*[年月日号]""").containsMatchIn(text)
        if (!dateLike && WEB_QUERY_KEYWORDS.none { lower.contains(it) }) return null
        // 短追问自动合并上一轮问题，还原完整检索意图（如“我要的是2026年9月3日的”）
        val prevUser = _ui.value.messages.lastOrNull { it.role == Role.USER }?.content?.trim()
        if (prevUser != null && prevUser != text && text.length <= 24 && dateLike) {
            return "$prevUser $text" to false
        }
        return text to false
    }

    /**
     * 联网搜索主流程（元宝同款）：气泡顶部先出「正在全网搜索…」状态，
     * 搜索完成后变为「已找到 N 篇相关内容，正在生成回答…」，
     * 结果注入 AI 控制提示词，由 AI 流式整理回答并标注 [N] 引用，回答下方渲染参考资料卡片。
     */
    private fun runSelfCheck(kind: String) {
        if (_ui.value.isStreaming) return
        pluginJob = viewModelScope.launch {
            val assistantId = beginPluginWork("自检 check $kind", emptyList())
            val out = try {
                withContext(Dispatchers.IO) { WebSearchPlugin.selfCheck(kind) }
            } catch (e: Exception) {
                "❌ 自检异常：${e::class.simpleName}: ${e.message}"
            }
            completeAssistant(assistantId, out, emptyList())
            finishPluginWork()
        }
    }

    /** 内置知识快答：把本地直答结果作为普通 AI 回复展示（不依赖模型 / 网络）。 */
    private fun builtInReply(userContent: String, answer: String) {
        pluginJob = viewModelScope.launch {
            val assistantId = beginPluginWork(userContent, emptyList())
            _ui.update { it.copy(isStreaming = true) }
            MemoryPlugin.log(getApplication(), "内置知识快答", answer.replace("\n", " ").take(40))
            completeAssistant(assistantId, answer, emptyList())
            finishPluginWork()
        }
    }

    /**
     * 联网搜索主流程（元宝同款）：气泡顶部先出「正在全网搜索…」状态，
     * 搜索完成后变为「已找到 N 篇相关内容，正在生成回答…」，
     * 结果注入 AI 控制提示词，由 AI 流式整理回答并标注 [N] 引用，回答下方渲染参考资料卡片。
     */
    private fun runWebSearchReply(userContent: String, query: String, explicit: Boolean) {
        if (_ui.value.isStreaming) return
        pluginJob = viewModelScope.launch {
            val assistantId = beginPluginWork(userContent, emptyList())
            updateSearchMeta(assistantId, "正在全网搜索「$query」…")
            val outcome = try {
                withContext(Dispatchers.IO) { WebSearchPlugin.search(query) }
            } catch (e: CancellationException) {
                // 手动停止（停止按钮）：清空状态，保留已发送提问
                clearSearchPlaceholder(assistantId)
                finishPluginWork()
                throw e
            } catch (e: Exception) {
                null
            }
            val failed = outcome == null
            if (failed) MemoryPlugin.log(getApplication(), "联网搜索失败", query.take(40))
            else MemoryPlugin.log(getApplication(), "联网搜索", query.take(40))
            if (failed) {
                // 搜索无结果：如实告知，不再让模型无依据自由发挥（避免编造错误答案）
                clearSearchPlaceholder(assistantId)
                completeAssistant(
                    assistantId,
                    if (explicit) "⚠️ 未找到相关内容：联网搜索未返回结果，请换个说法或稍后重试"
                    else "⚠️ 未找到与「${query.take(30)}」相关的网页内容，已按普通聊天回答（建议切换到「总是开启」并精简提问）",
                    emptyList()
                )
                finishPluginWork()
                return@launch
            }
            // 元宝同款进度：找到结果后更新状态文案，随后开始流式生成
            updateSearchMetaOnly(
                assistantId,
                if (outcome?.refs.isNullOrEmpty()) "✅ 已获取相关内容，正在生成回答…"
                else "✅ 已从全网找到 ${outcome?.refs?.size ?: 0} 篇相关内容，正在生成回答…"
            )
            streamWithSearchContext(assistantId, outcome?.text, outcome?.refs)
        }
    }

    /** 搜索结果就绪后进入 AI 流式回复（无模型时退回直接显示原始结果）。 */
    private fun streamWithSearchContext(
        assistantId: String,
        searchResult: String?,
        refs: List<SearchReference>? = null
    ) {
        val model = _ui.value.activeModel
        if (model == null) {
            completeAssistant(
                assistantId,
                searchResult ?: "⚠️ 联网搜索暂时不可用，未获取到结果",
                emptyList()
            )
            if (!refs.isNullOrEmpty()) attachReferences(assistantId, refs)
            finishPluginWork()
            return
        }
        stream(model, _ui.value.messages, searchResult = searchResult, refs = refs)
    }

    /** AI 回复中的搜索状态（元宝同款）：写入 assistant 气泡的 searchMeta（不进入模型上下文）。 */
    private fun updateSearchMeta(assistantId: String, meta: String) {
        _ui.update { st ->
            val last = st.messages.lastOrNull() ?: return@update st
            if (last.id != assistantId) return@update st
            st.copy(
                messages = st.messages.dropLast(1) + last.copy(searchMeta = meta),
                isStreaming = true,
                notice = null
            )
        }
    }

    /** 仅更新搜索状态文案（搜索完成后的进度提示，不改动流式状态）。 */
    private fun updateSearchMetaOnly(assistantId: String, meta: String) {
        _ui.update { st ->
            val idx = st.messages.indexOfLast { it.id == assistantId }
            if (idx < 0) return@update st
            val msg = st.messages[idx] ?: return@update st
            st.copy(messages = st.messages.toMutableList().also { it[idx] = msg.copy(searchMeta = meta) })
        }
    }

    /** 为指定 assistant 消息补充联网搜索参考资料（UI 渲染「参考资料」卡片）。 */
    private fun attachReferences(assistantId: String, references: List<SearchReference>) {
        _ui.update { st ->
            val idx = st.messages.indexOfLast { it.id == assistantId }
            if (idx < 0) return@update st
            if (!st.messages[idx].references.isNullOrEmpty()) return@update st
            val list = st.messages.toMutableList()
            list[idx] = st.messages[idx].copy(references = references)
            st.copy(messages = list)
        }
    }

    /** 清掉“正在搜索”状态（手动停止 / 显式搜索失败时调用）。 */
    private fun clearSearchPlaceholder(assistantId: String) {
        _ui.update { st ->
            val idx = st.messages.indexOfLast { it.id == assistantId }
            if (idx < 0) return@update st
            val list = st.messages.toMutableList()
            if (list[idx].searchMeta != null) list[idx] = list[idx].copy(searchMeta = null)
            st.copy(messages = list)
        }
    }

    /** 输入框消息自动识别脚本插件意图：「用<插件名>…」或「<插件名>：任务…」时执行对应脚本。 */
    private fun scriptPluginIntent(content: String): Boolean {
        val enabled = _ui.value.enabledPlugins
        val scripts = PluginRegistry.all(settings)
            .filter { it.isScript && it.id in enabled }
            .sortedByDescending { it.name.length }
        if (scripts.isEmpty()) return false
        val text = content.trim()
        for (p in scripts) {
            val esc = Regex.escape(p.name)
            val labeled = Regex(
                "^(?:请|麻烦)?(?:用|使用|调用|运行|执行|帮我用|use|run)\\s*$esc\\s*[:：,，]?\\s*(.*)$",
                RegexOption.IGNORE_CASE
            ).find(text)
            val named = Regex("^$esc\\s*[:：]\\s*(.+)$", RegexOption.IGNORE_CASE).find(text)
            val args = labeled?.groupValues?.get(1)?.trim() ?: named?.groupValues?.get(1)?.trim()
            if (args == null) continue
            runScriptPlugin(p, text, args)
            return true
        }
        return false
    }

    /** 执行脚本插件：聊天内插入「【插件名】内容」，运行输出（stdout/stderr）回填为 assistant 回复。 */
    fun runScriptPlugin(plugin: PluginInfo, raw: String, args: String) {
        launchPluginRun(plugin, "【" + plugin.name + "】" + raw, args, file = plugin.scriptFile ?: plugin.name)
    }

    /**
     * 聊天内联脚本实时测试：代码块「一键运行」入口。
     * 将代码落盘为临时脚本，复用 [ScriptRunner] 按语言路由（Python 走内置运行时），
     * 运行输出回填为 assistant 回复；结束后清理临时目录。
     */
    fun runCodeBlock(fenceLang: String, code: String) {
        Log.w("XSRunDebug", "runCodeBlock begin lang=" + fenceLang)
        if (_ui.value.isStreaming) {
            notice("请等待当前回复完成后再次运行代码")
            return
        }
        val lang = ScriptRunner.langFromFence(fenceLang)
        if (lang == null) {
            notice("暂不支持运行「" + fenceLang.trim().ifBlank { "未知" } + "」；本机内置：Python / JavaScript / Shell / Lua / SQL")
            return
        }
        if (code.isBlank()) {
            notice("代码内容为空，无法运行")
            return
        }
        if (code.length > 200_000) {
            notice("代码过长（超过 200KB），请拆分后运行")
            return
        }
        val app = getApplication<Application>()
        val id = "inline_" + UUID.randomUUID().toString().take(8)
        val scriptName = "main." + when (lang) {
            "py" -> "py"; "js" -> "js"; "sh" -> "sh"; "sql" -> "sql"; else -> "lua"
        }
        val plugin = PluginInfo(
            id = id,
            name = "代码运行",
            desc = "聊天内联脚本（一键实时测试）",
            scriptFile = scriptName,
            lang = lang
        )
        val scriptDir = ScriptStore.dir(app, plugin.id)
        try {
            if (!scriptDir.exists() && !scriptDir.mkdirs()) {
                notice("无法创建脚本目录")
                return
            }
            File(scriptDir, scriptName).writeText(code.trim() + "\n")
        } catch (e: Exception) {
            notice("写入脚本失败：" + (e.message ?: e.javaClass.simpleName))
            return
        }
        MemoryPlugin.log(app, "运行内联脚本", PluginRegistry.langLabel(lang) + " " + code.length + " 字符")
        launchPluginRun(
            plugin = plugin,
            label = "【运行 " + PluginRegistry.langLabel(lang) + " 脚本】",
            args = "",
            file = scriptName,
            cleanupDir = true
        )
    }

    /** 通用脚本执行：插入用户消息 + assistant 占位，输出回填，结束后落库并可选清理临时目录。 */
    private fun launchPluginRun(
        plugin: PluginInfo,
        label: String,
        args: String,
        file: String,
        cleanupDir: Boolean = false
    ) {
        if (_ui.value.isStreaming) return
        val app = getApplication<Application>()
        pluginJob = viewModelScope.launch {
            val assistantId = beginPluginWork(label, emptyList())
            _ui.update { it.copy(isStreaming = true) }
            updateAssistantProgress(assistantId, "▶ 运行脚本：" + file)
            try {
                val result = ScriptRunner.run(app, plugin, args) { step ->
                    updateAssistantProgress(assistantId, step)
                }
                if (!cleanupDir) MemoryPlugin.log(app, "脚本插件", plugin.name + " " + args.take(30))
                val output = result.output
                val text = if (result.ok) {
                    output.ifBlank { "（脚本执行成功，无输出）" }
                } else {
                    val head = result.error ?: "脚本执行失败"
                    val reason = ScriptRunner.explainFailure(result)
                    buildString {
                        append("❌ ").append(head.ifBlank { "脚本执行失败" })
                        if (reason.isNotBlank()) append("\n原因：").append(reason)
                        if (output.isNotBlank()) append("\n\n原始输出：\n").append(output.take(4000))
                    }
                }
                completeAssistant(assistantId, text, emptyList())
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                pluginError(assistantId, e.message?.takeIf { it.isNotBlank() } ?: e::class.java.simpleName)
            } finally {
                if (cleanupDir) ScriptStore.deleteDir(app, plugin.id)
                finishPluginWork()
            }
        }
    }

    private enum class IntentVerdict { DEVICE, CANDIDATE, NONE }

    /** 设备控制意图两段式判定：本地词表快决；词表认定聊天但存在设备弱信号 → 交云端小模型二次确认。 */
    private fun deviceIntentVerdict(content: String): IntentVerdict {
        if (!_ui.value.enabledPlugins.contains("device_control")) return IntentVerdict.NONE
        val lower = content.lowercase(Locale.ROOT)
        // 疑问句一律放行（“怎么打开开发者模式”是提问不是指令）
        val question = Regex("(如何|怎么|怎样|教程|方法|推荐|能否|能不能|可以吗|会吗)").containsMatchIn(lower)
            || lower.trimEnd().endsWith("?") || lower.trimEnd().endsWith("？") || lower.trimEnd().endsWith("吗")
        if (question) return IntentVerdict.NONE
        val (kind, score) = LocalIntentClassifier.classify(content)
        if (kind != LocalIntentClassifier.Intent.CHAT) {
            val openTrigger = Regex("^(打开|启动|开启|open|launch)\\s*\\S.*$").matches(lower)
            val modelTrigger = kind == LocalIntentClassifier.Intent.DEVICE_CONTROL &&
                score >= LocalIntentClassifier.DEVICE_MIN_SCORE
            // 复合指令索引（“打开抖音搜索华为手机并点进第一个视频”等）作为补充信号
            val idxTrigger = AppIndexPlugin.isDeviceCommand(getApplication(), lower)
            if (openTrigger || modelTrigger || idxTrigger) return IntentVerdict.DEVICE
        }
        // 词表够不着但带设备域弱信号（亮度/屏幕/打开…）：让云端小模型兜底，换种说法也能识别
        return if (LocalIntentClassifier.hasDeviceSignal(lower)) IntentVerdict.CANDIDATE else IntentVerdict.NONE
    }

    /** 云端小模型兜底：只输出单一意图标签；超时/失败一律按聊天放行，绝不误抢。 */
    private suspend fun cloudConfirmDevice(content: String): Boolean {
        val model = _ui.value.activeModel ?: return false
        val reply: String = withTimeoutOrNull(4500) {
            withContext(Dispatchers.IO) {
                try {
                    val api = OpenAiApi(model.baseUrl, model.apiKey, connectTimeoutMs = 2500, readTimeoutMs = 3000)
                    runCatching {
                        api.completeChat(model.modelId, INTENT_CHECK_SYSTEM, listOf("user" to content), 0f)
                    }.getOrElse {
                        // 部分网关对 stream=false 路由异常时回退流式聚合
                        val sb = StringBuilder()
                        api.streamChat(
                            model.modelId,
                            listOf(ChatMessage(role = Role.USER, content = content)),
                            INTENT_CHECK_SYSTEM, 0f,
                            onDelta = { sb.append(it) }
                        )
                        sb.toString()
                    }
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Exception) {
                    Log.w(TAG, "intent cloud confirm failed", e)
                    ""
                }
            }
        } ?: return false
        if (reply.isBlank()) return false
        val isDevice = isDeviceIntentToken(reply)
        MemoryPlugin.log(getApplication(), "意图二次确认", "${content.take(30)} -> ${if (isDevice) "device" else "chat"}")
        return isDevice
    }

    private fun isDeviceIntentToken(reply: String): Boolean {
        val first = reply.trim().lowercase(Locale.ROOT)
            .split(Regex("""[\s,，:;；.。!！?？]+"""))
            .firstOrNull { it.isNotBlank() }.orEmpty()
        return first.contains("device") || first.contains("设备")
    }

    fun runDeviceControl(instruction: String) {
        if (_ui.value.isStreaming) return
        val app = getApplication<android.app.Application>()
        val model = _ui.value.activeModel
        pluginJob = viewModelScope.launch {
            val assistantId = beginPluginWork("【设备控制】$instruction", emptyList())
            _ui.update { it.copy(isStreaming = true) }
            try {
                val result = DeviceControlPlugin.execute(app, instruction, model) { step ->
                    updateAssistantProgress(assistantId, step)
                }
                MemoryPlugin.log(app, "设备控制", instruction.take(40))
                completeAssistant(assistantId, result, emptyList())
                // 仅当内置 key 确实未被授权（换机/撤销）时才弹「一键配对」引导；
                // 已配对但连接失败/通道未开启等场景不误报
                if (result.startsWith("❌") && (result.contains("尚未授权") || result.contains("重新配对") || result.contains("未配对"))) {
                    _ui.update { it.copy(devicePairNeeded = true) }
                    notice("无线调试未配对，点击下方提示可一键配对")
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                pluginError(assistantId, e.message?.takeIf { it.isNotBlank() } ?: e::class.java.simpleName)
            } finally {
                finishPluginWork()
            }
        }
    }

    /** 设备控制 Agent 的中间步骤实时追加到 assistant 消息（不打断流程，仅保留最近若干行）。 */
    private fun updateAssistantProgress(assistantId: String, step: String) {
        if (step.isBlank()) return
        _ui.update { st ->
            val idx = st.messages.indexOfLast { it.id == assistantId }
            if (idx < 0) return@update st
            val m = st.messages[idx]
            val current = if (m.content.isBlank()) "" else m.content + "\n"
            val lines = (current + step).lines().takeLast(24)
            st.copy(messages = st.messages.toMutableList().apply { this[idx] = m.copy(content = lines.joinToString("\n")) })
        }
    }

    /** 一键配对成功后：关闭提示条并后台建立 shell 通道。 */
    fun onDevicePairSuccess() {
        _ui.update { it.copy(devicePairNeeded = false) }
        viewModelScope.launch(Dispatchers.IO) { runCatching { AdbShellController.ensureConnected() } }
    }

    fun dismissPairPrompt() {
        _ui.update { it.copy(devicePairNeeded = false) }
    }

    companion object {
        private const val TAG = "ChatViewModel"

        /** 云端小模型意图判定 System Prompt：只允许输出一个英文意图标签。 */
        private const val INTENT_CHECK_SYSTEM =
            "你是手机助手「XS Chat」的指令意图分类器。用户输入一句话，只判断它是要直接操控这台手机/平板设备的系统操作（如" +
                "打开/切换/关闭应用、点击、滑动、调亮度音量、锁屏、通知、截图、设闹钟等），还是要和 AI 聊天提问或闲聊。" +
                "只输出一个英文单词：设备操作输出 device，聊天提问输出 chat。不要输出任何其他内容，不要解释。"

        /** 单次请求最多发送的消息条数（含最新一条），超出部分裁剪以加快响应。 */
        private const val MAX_CONTEXT_MESSAGES = 24

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

    private fun stream(
        model: AiModel,
        messages: List<ChatMessage>,
        searchResult: String? = null,
        refs: List<SearchReference>? = null
    ) {
        val instance = OpenAiApi(model.baseUrl, model.apiKey)
        api = instance
        val assistantId = pendingId ?: return
        val startMs = System.currentTimeMillis()
        _ui.update { it.copy(isStreaming = true) }
        streamingJob = viewModelScope.launch {
            var completed = false
            var usage: Usage? = null
            var currentSearch = searchResult
            var currentRefs = refs
            var toolFollowMsg: ChatMessage? = null
            var pass = 0
            var sawContent = false
            try {
                while (pass < 3) {
                    pass++
                    try {
                completed = withContext(Dispatchers.IO) {
                    // 上下文裁剪：长对话只发最近消息，控制 prompt 长度、降低首 token 时延
                    val trimmed = trimContext(toolFollowMsg?.let { messages + it } ?: messages)
                    val parts = resolveParts(trimmed)
                    val deltaBuf = StringBuilder()
                    var lastFlush = 0L
                    fun flushDelta() {
                        val chunk = deltaBuf.toString().also { deltaBuf.setLength(0) }
                        if (chunk.isNotEmpty()) appendDelta(assistantId, chunk)
                    }
                    instance.streamChat(
                        model = model.modelId,
                        messages = trimmed.filter { it.role != Role.SYSTEM },
                        systemPrompt = buildSystemPrompt(if (toolFollowMsg != null) currentSearch else searchResult),
                        temperature = _ui.value.temperature,
                        reasoningEffort = _ui.value.reasoningEffort,
                        attachmentParts = parts,
                        onDelta = { delta ->
                            sawContent = true
                            deltaBuf.append(delta)
                            val now = System.currentTimeMillis()
                            // 流式 UI 节流：合并 ~40ms 内的增量，减少每 token 一次 Compose 重组
                            if (now - lastFlush >= 40) {
                                lastFlush = now
                                flushDelta()
                            }
                        },
                        onUsage = { usage = it }
                    ).also { flushDelta() }
                }
                // 模型输出原生 function:web_search 工具语法时：执行真实搜索并追加一轮续答
                if (completed) {
                    val toolQuery = WebSearchPlugin.extractToolSearchQuery(assistantContent(assistantId))
                    sanitizeAssistantContent(assistantId)
                    if (toolQuery == null) break
                    if (!_ui.value.enabledPlugins.contains("web_search") || _ui.value.webSearchMode == WebSearchPlugin.MODE_OFF) break
                    val res = withContext(Dispatchers.IO) { WebSearchPlugin.search(toolQuery) }
                    if (res == null) break
                    Log.w(TAG, "WebSearch follow-up query=$toolQuery")
                    MemoryPlugin.log(getApplication(), "联网搜索(由模型改写)", toolQuery.take(40))
                    currentSearch = res.text
                    currentRefs = res.refs
                    updateSearchMetaOnly(assistantId, "✅ 已从全网找到 ${res.refs.size} 篇相关内容，正在继续生成回答…")
                    clearAssistantContent(assistantId)
                    toolFollowMsg = ChatMessage(
                        role = Role.USER,
                        content = "请直接综合以上联网搜索结果，把答案完整写出来：包含具体事实、数字、时间与结论，正文要独立可读，禁止出现任何网址、链接或“请自行查看”的表达；不要输出任何工具调用标记或搜索过程描述。",
                        attachments = emptyList()
                    )
                    }
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Exception) {
                    // Socket/网关瞬时中断且未产出任何内容：自动重试一次，规避偶发断流
                    if (!sawContent && pass < 3) {
                        Log.w(TAG, "stream retry after: " + (e.message ?: e.javaClass.simpleName))
                        continue
                    }
                    appendError(assistantId, e.message ?: "网络请求失败")
                    break
                }
                }
            } catch (e: CancellationException) {
                throw e
            }
            if (!_ui.value.isStreaming) return@launch // 已被 stop() 接管
            if (completed && assistantContent(assistantId).isBlank()) {
                // 流式空回复：改用非流式补全再试一次（规避网关只回 tool_calls/思考、或丢包只读一半流），仍空才报错
                val fb = runCatching {
                    withContext(Dispatchers.IO) {
                        val base = toolFollowMsg?.let { messages + it } ?: messages
                        instance.completeChat(
                            model = model.modelId,
                            systemPrompt = buildSystemPrompt(if (toolFollowMsg != null) currentSearch else searchResult),
                            userMessages = base.map { it.role.name.lowercase() to it.content },
                            temperature = _ui.value.temperature
                        )
                    }
                }.getOrNull()?.trim()
                if (!fb.isNullOrBlank()) {
                    Log.w(TAG, "empty stream: non-stream fallback ok len=${fb.length}")
                    appendDelta(assistantId, fb)
                } else {
                    Log.w(TAG, "empty stream: final fallback failed pass=$pass search=${currentSearch?.length ?: 0}")
                    _ui.update { st ->
                        val idx = st.messages.indexOfLast { it.id == assistantId }
                        if (idx < 0) return@update st
                        val msg = st.messages[idx]
                        st.copy(messages = st.messages.toMutableList().also {
                            it[idx] = msg.copy(content = "⚠️ 模型未返回有效内容，请稍后重试或换个问法。", error = true)
                        })
                    }
                }
            }
            if (completed) {
                attachCallMeta(
                    assistantId,
                    CallMeta(
                        promptTokens = usage?.promptTokens ?: 0,
                        completionTokens = usage?.completionTokens ?: 0,
                        durationMs = System.currentTimeMillis() - startMs
                    )
                )
                if (!currentRefs.isNullOrEmpty()) attachReferences(assistantId, currentRefs)
            }
            // 兜底：模型偷懒只回链接列表时，用搜索材料重写成一版完整答案（不影响参考资料卡片）
            if (completed && !currentRefs.isNullOrEmpty() && !currentSearch.isNullOrBlank()) {
                val raw = assistantContent(assistantId)
                if (looksLikeLinkList(raw)) {
                    val rewritten = withContext(Dispatchers.IO) {
                        rewriteLinkListAnswer(raw, currentSearch)
                    }
                    if (!rewritten.isNullOrBlank()) {
                        _ui.update { st ->
                            val idx = st.messages.indexOfLast { it.id == assistantId }
                            if (idx < 0) return@update st
                            val list = st.messages.toMutableList()
                            list[idx] = list[idx].copy(content = rewritten)
                            st.copy(messages = list)
                        }
                        MemoryPlugin.log(getApplication(), "链接回答已重写", raw.take(40))
                    }
                }
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

    /** 只发送最近 [MAX_CONTEXT_MESSAGES] 条消息（UI 仍显示完整历史）。 */
    private fun trimContext(messages: List<ChatMessage>): List<ChatMessage> =
        if (messages.size <= MAX_CONTEXT_MESSAGES) messages
        else messages.takeLast(MAX_CONTEXT_MESSAGES)

    /** 组装 system prompt：用户自定义提示词 + 联网搜索结果控制提示词（元宝联网回复策略）。 */
    private fun buildSystemPrompt(searchResult: String?): String? {
        val base = listOfNotNull(
            _ui.value.systemPrompt.ifBlank { null },
            buildCapabilityNote()
        ).joinToString("\n\n").ifBlank { null }
        if (searchResult.isNullOrBlank()) return base
        val control = """
            以下是针对你问题的实时联网搜索结果（每条以 [来源N] 开头）：

            <联网搜索结果>
            $searchResult
            </联网搜索结果>

            回答规则：
            1. 优先基于搜索结果回答；引用位置用 [N] 数字编号标注（如 [1]、[2]），编号必须与 <联网搜索结果> 中 [来源N] 的序号一一对应，禁止编造不存在的编号；
            2. 【硬性要求】必须“直接给答案”：把每条来源里的具体事实、数字、时间、结论展开写进正文，用户看完回答就不需要再点任何链接；禁止把回答写成链接清单，禁止说“请查看链接”“详情见链接”“你自己打开看看”之类的推诿话术；
            3. 涉及时间、数字、名称时以搜索结果为准；多个来源矛盾时汇总差异并说明；
            4. 参考文献可能远多于 10 条，不要只挑前几条：汇总/列举/对比类提问应尽量覆盖全部相关来源（编号可顺延到 [10]+）；无关来源可直接跳过；
            5. 用与用户提问相同的语言作答，保持自然流畅；
            7. 不要在回答末尾手动列出网址或参考文献（界面会自动展示参考资料列表），正文结束后直接结束；禁止输出任何工具调用标记（如 <|tool_call>、<tool_call>、function:web_search 等）。
        """.trimIndent()
        return if (base == null) control else "$base\n\n$control"
    }

    /**
     * 应用自身实时联网能力声明（根因修复：模型不知道本 App 已内置联网搜索，
     * 被问「能不能实时联网/能联网吗」时只会凭知识回答“不能”）。
     * 联网开关开启时注入 system prompt，保证能力类提问一律如实回答“可以”。
     */
    private fun buildCapabilityNote(): String? {
        if (!_ui.value.enabledPlugins.contains("web_search")) return null
        val modeDesc = when (_ui.value.webSearchMode) {
            WebSearchPlugin.MODE_ALWAYS -> "总是开启，每次提问都会先进行全网实时搜索"
            WebSearchPlugin.MODE_AUTO -> "自动模式，事实型/时效性问题会自动进行全网实时搜索"
            else -> return null
        }
        return "【本 App 能力声明】你在 XS Chat 中使用，当前已开启实时联网搜索：$modeDesc，" +
            "AI 能获取最新实时信息并参考资料回答（回答中带 [N] 编号引用）。" +
            "当用户询问“能不能实时联网”“可以联网吗”“支持联网搜索吗”等关于本 App 能力的问题时，" +
            "必须如实回答：可以实时联网，并简单说明当前的联网模式；禁止回答“无法联网”或“信息截止于训练时间”之类的否定话术。"
    }

    /** 读取指定 assistant 消息当前完整内容（流式过程）。 */
    private fun assistantContent(assistantId: String): String {
        val m = _ui.value.messages.lastOrNull { it.id == assistantId } ?: return ""
        return m.content
    }

    /** 清洗消息里的模型工具调用残留（<|tool_call>、function:web_search 等）。 */
    private fun sanitizeAssistantContent(assistantId: String) {
        _ui.update { st ->
            val idx = st.messages.indexOfLast { it.id == assistantId }
            if (idx < 0) return@update st
            val msg = st.messages[idx]
            val clean = WebSearchPlugin.stripToolMarkup(msg.content)
            if (clean == msg.content) return@update st
            st.copy(messages = st.messages.toMutableList().also { it[idx] = msg.copy(content = clean) })
        }
    }

    /** 清空消息内容（续答前丢弃“我来帮您搜索…”这类开场白）。 */
    private fun clearAssistantContent(assistantId: String) {
        _ui.update { st ->
            val idx = st.messages.indexOfLast { it.id == assistantId }
            if (idx < 0) return@update st
            val msg = st.messages[idx]
            if (msg.content.isEmpty()) return@update st
            st.copy(messages = st.messages.toMutableList().also { it[idx] = msg.copy(content = "") })
        }
    }

    /** 判断回答是否退化成“只见链接不见正文”（超过一半有效行是网址时判定）。 */
    private fun looksLikeLinkList(text: String): Boolean {
        val lines = text.lineSequence().map { it.trim() }.filter { it.isNotEmpty() }.toList()
        if (lines.isEmpty()) return false
        val urlish = lines.count { Regex("""^(?:https?://|www\.|ftp://|\d+\s*[.、．]\s*https?://)""").containsMatchIn(it) }
        return urlish >= 2 && urlish >= lines.size / 2
    }

    /** 将纯链接回答重写为完整正文（单次非流式调用，失败返回 null 保持原样）。 */
    private fun rewriteLinkListAnswer(raw: String, searchText: String): String? {
        val model = _ui.value.activeModel ?: return null
        return runCatching {
            val system = "你是联网搜索答案整理器。下面给了一份只罗列了链接、没有展开内容的回答草稿。请根据《搜索材料》把关键内容提炼出来，改写成一段信息量完整、直接可读的中文正文：" +
                "包含具体数字、时间、结论与要点；禁止出现任何网址/链接，禁止出现“点开看看”“见链接”等字样，禁止再罗列链接列表。\n\n" +
                "《搜索材料》\n${searchText.take(6000)}\n\n《回答草稿》\n${raw.take(4000)}"
            val api = OpenAiApi(model.baseUrl, model.apiKey, connectTimeoutMs = 10_000, readTimeoutMs = 60_000)
            api.completeChat(model.modelId, system, listOf("user" to "请输出整理后的最终答案"), 0.3f)
        }.getOrNull()?.trim()?.takeIf { it.isNotEmpty() }
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
        val bytes = AttachmentStore.readBytes(app, a, maxBytes = Long.MAX_VALUE) ?: run {
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
                val text = AttachmentStore.readText(app, a, maxBytes = Long.MAX_VALUE)
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

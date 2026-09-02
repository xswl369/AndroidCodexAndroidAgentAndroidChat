package com.xs.chat.ui

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.Manifest
import android.content.Intent
import android.app.Activity
import android.media.projection.MediaProjectionManager
import android.widget.Toast
import com.xs.chat.wireless.AutoPair
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.background
import androidx.compose.foundation.Image
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Add
import androidx.compose.material.icons.rounded.ArrowDownward
import androidx.compose.material.icons.rounded.ArrowUpward
import androidx.compose.material.icons.rounded.AutoAwesome
import androidx.compose.material.icons.rounded.Call
import androidx.compose.material.icons.rounded.Mic
import androidx.compose.material.icons.rounded.Videocam
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material.icons.rounded.DeleteOutline
import androidx.compose.material.icons.rounded.Description
import androidx.compose.material.icons.rounded.Edit
import androidx.compose.material.icons.rounded.EditNote
import androidx.compose.material.icons.rounded.Image
import androidx.compose.material.icons.rounded.Menu
import androidx.compose.material.icons.rounded.Movie
import androidx.compose.material.icons.rounded.PushPin
import androidx.compose.material.icons.rounded.SelectAll
import androidx.compose.material.icons.rounded.Settings
import androidx.compose.material.icons.rounded.Stop
import androidx.compose.material.icons.rounded.VideoLibrary
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.AssistChip
import androidx.compose.material3.CenterAlignedTopAppBar
import androidx.compose.material3.Checkbox
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalDrawerSheet
import androidx.compose.material3.ModalNavigationDrawer
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.rememberDrawerState
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.xs.chat.data.AttachmentKind
import com.xs.chat.data.AttachmentStore
import com.xs.chat.data.pickVisionModel
import com.xs.chat.data.PickedAttachment
import com.xs.chat.plugins.MemoryPlugin
import com.xs.chat.data.Role
import com.offlinevoice.input.VoskEngine
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ChatScreen(
    state: ChatUiState,
    vm: ChatViewModel,
    onOpenSettings: () -> Unit
) {
    val listState = rememberLazyListState()
    val snackbarHostState = remember { SnackbarHostState() }
    val scope = rememberCoroutineScope()
    val context = LocalContext.current
    var pairing by remember { mutableStateOf(false) }
    var pairStatus by remember { mutableStateOf("") }
    val pairMpm = remember { context.getSystemService(Context.MEDIA_PROJECTION_SERVICE) as MediaProjectionManager }
    val pairLauncher = rememberLauncherForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
        val data = result.data
        if (result.resultCode == Activity.RESULT_OK && data != null) {
            AutoPair.startCapture(context, result.resultCode, data)
            // 先授权录屏再打开配对页，避免从后台 Activity 发起授权被系统丢弃
            AutoPair.openPairingPage(context)
            scope.launch {
                pairStatus = AutoPair.runAutoPair(context) { s -> pairStatus = s }
                pairing = false
                vm.onDevicePairSuccess()
            }
        } else {
            pairing = false
            pairStatus = "未获得录屏授权，配对中止"
        }
    }
    val drawerState = rememberDrawerState(androidx.compose.material3.DrawerValue.Closed)
    var showModels by rememberSaveable { mutableStateOf(false) }
    var editingIndex by rememberSaveable { mutableStateOf<Int?>(null) }
    var draft by rememberSaveable { mutableStateOf("") }
    var pluginKind by remember { mutableStateOf<PluginKind?>(null) }
    var pluginDraft by rememberSaveable { mutableStateOf("") }
    var callType by remember { mutableStateOf<Int?>(null) }
    var pendingCall by remember { mutableStateOf<Int?>(null) }
    val lang = LocalLanguage.current

    fun shareText(text: String) {
        if (text.isBlank()) return
        val intent = Intent(Intent.ACTION_SEND).apply {
            type = "text/plain"
            putExtra(Intent.EXTRA_TEXT, text)
        }
        runCatching { context.startActivity(Intent.createChooser(intent, Lang.t(lang, "share_message"))) }
    }

    // 系统附件选择器：图片支持多选，文件/视频单选
    val pickImages = rememberLauncherForActivityResult(ActivityResultContracts.GetMultipleContents()) { uris ->
        if (uris.isNotEmpty()) {
            vm.addPendingAttachments(
                uris.map { PickedAttachment(it.toString(), AttachmentStore.queryName(context, it), AttachmentStore.queryMime(context, it)) }
            )
        }
    }
    val pickFile = rememberLauncherForActivityResult(ActivityResultContracts.GetContent()) { uri ->
        if (uri != null) {
            vm.addPendingAttachments(
                listOf(PickedAttachment(uri.toString(), AttachmentStore.queryName(context, uri), AttachmentStore.queryMime(context, uri)))
            )
        }
    }
    val pickVideo = rememberLauncherForActivityResult(ActivityResultContracts.GetContent()) { uri ->
        if (uri != null) {
            vm.addPendingAttachments(
                listOf(PickedAttachment(uri.toString(), AttachmentStore.queryName(context, uri), AttachmentStore.queryMime(context, uri)))
            )
        }
    }

    val callLauncher = rememberLauncherForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { grants ->
        val t = pendingCall
        pendingCall = null
        if (t != null && grants.values.all { it }) {
            callType = t
            MemoryPlugin.log(context, if (t == 1) "发起视频通话" else "发起语音通话")
        } else if (t != null) {
            Toast.makeText(context, "需要麦克风/摄像头权限", Toast.LENGTH_SHORT).show()
        }
    }

    val nearBottom by remember {
        derivedStateOf {
            val info = listState.layoutInfo
            val last = info.visibleItemsInfo.lastOrNull() ?: return@derivedStateOf true
            last.index >= info.totalItemsCount - 2
        }
    }

    // 新消息或会话切换时滚到底部
    LaunchedEffect(state.messages.size) {
        if (state.messages.isNotEmpty()) listState.scrollToItem(state.messages.size - 1)
    }
    // 流式输出时，若用户停留在底部则跟随滚动
    LaunchedEffect(state.isStreaming, state.messages.lastOrNull()?.content?.length) {
        if (state.isStreaming && nearBottom && state.messages.isNotEmpty()) {
            listState.scrollToItem(state.messages.size - 1)
        }
    }
    // 提示自动消失
    LaunchedEffect(state.notice) {
        state.notice?.let {
            snackbarHostState.showSnackbar(it)
            vm.clearNotice()
        }
    }

    fun copyText(text: String) {
        val cm = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
        cm.setPrimaryClip(ClipData.newPlainText("xs_chat", text))
        scope.launch { snackbarHostState.showSnackbar("已复制") }
    }

    ModalNavigationDrawer(
        drawerState = drawerState,
        drawerContent = {
            HistoryDrawer(
                history = state.history,
                editMode = state.historyEditMode,
                selected = state.selectedHistory,
                onToggleEditMode = { vm.toggleHistoryEditMode() },
                onSelectAll = { vm.selectAllHistory() },
                onNewChat = { scope.launch { drawerState.close() }; vm.newConversation() },
                onOpen = { id -> scope.launch { drawerState.close() }; vm.openConversation(id) },
                onDelete = { id -> vm.deleteConversation(id) },
                onToggleSelect = { id -> vm.toggleSelectHistory(id) },
                onDeleteSelected = { vm.deleteSelectedHistory() },
                onPinSelected = { pinned -> vm.pinSelectedHistory(pinned) },
                onPinOne = { id, pinned -> vm.pinConversation(id, pinned) }
            )
        }
    ) {
        Scaffold(
            snackbarHost = { SnackbarHost(snackbarHostState) },
            topBar = {
                CenterAlignedTopAppBar(
                    title = {
                        Column(horizontalAlignment = Alignment.CenterHorizontally) {
                            Text(
                                if (state.hasConversation) state.conversationTitle else Lang.t(lang, "new_chat"),
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis
                            )
                            Spacer(Modifier.height(2.dp))
                            AssistChip(
                                onClick = { showModels = true },
                                label = {
                                    Text(
                                        state.activeModel?.name ?: Lang.t(lang, "select_model"),
                                        maxLines = 1,
                                        overflow = TextOverflow.Ellipsis
                                    )
                                }
                            )
                        }
                    },
                    navigationIcon = {
                        IconButton(onClick = { scope.launch { drawerState.open() } }) {
                            Icon(Icons.Rounded.Menu, contentDescription = Lang.t(lang, "history"))
                        }
                    },
                    actions = {
                        IconButton(onClick = { vm.newConversation() }) {
                            Icon(Icons.Rounded.Add, contentDescription = Lang.t(lang, "new_chat"))
                        }
                        IconButton(onClick = onOpenSettings) {
                            Icon(Icons.Rounded.Settings, contentDescription = Lang.t(lang, "settings"))
                        }
                    }
                )
            },
            bottomBar = {
                Column {
                    if (state.devicePairNeeded || pairing) {
                        PairPromptBar(
                            status = pairStatus,
                            pairing = pairing,
                            onPair = {
                                pairing = true
                                pairStatus = "请在系统弹窗中允许屏幕录制…"
                                pairLauncher.launch(pairMpm.createScreenCaptureIntent())
                            },
                            onDismiss = {
                                vm.dismissPairPrompt()
                                pairStatus = ""
                            }
                        )
                    }
                    ChatInputBar(
                        state = state,
                        onSend = { text -> vm.sendMessage(text) },
                        onStop = { vm.stop() },
                        onPick = { kind ->
                            when (kind) {
                                AttachmentKind.IMAGE -> pickImages.launch("image/*")
                                AttachmentKind.FILE -> pickFile.launch("*/*")
                                AttachmentKind.VIDEO -> pickVideo.launch("video/*")
                            }
                        },
                        onRemoveAttachment = { id -> vm.removePendingAttachment(id) },
                        onPluginImage = { text -> pluginKind = PluginKind.IMAGE_GEN; pluginDraft = text },
                        onPluginVideo = { text -> pluginKind = PluginKind.VIDEO_GEN; pluginDraft = text },
                        onPluginFile = { text -> pluginKind = PluginKind.FILE_EDIT; pluginDraft = text },
                        onVoiceCall = {
                            pendingCall = 0
                            callLauncher.launch(arrayOf(Manifest.permission.RECORD_AUDIO))
                        },
                        onVideoCall = {
                            pendingCall = 1
                            callLauncher.launch(arrayOf(Manifest.permission.RECORD_AUDIO, Manifest.permission.CAMERA))
                        }
                    )
                }
            }
        ) { padding ->
            Box(
                Modifier
                    .padding(padding)
                    .fillMaxSize()
            ) {
                if (state.messages.isEmpty()) {
                    WelcomeView(state = state, onOpenSettings = onOpenSettings)
                } else {
                    LazyColumn(
                        state = listState,
                        contentPadding = PaddingValues(horizontal = 16.dp, vertical = 12.dp),
                        verticalArrangement = Arrangement.spacedBy(18.dp),
                        modifier = Modifier.fillMaxSize()
                    ) {
                        itemsIndexed(state.messages, key = { _, m -> m.id }) { index, message ->
                            when (message.role) {
                                Role.USER -> UserMessageBubble(
                                    content = message.content,
                                    attachments = message.attachments.orEmpty(),
                                    onEdit = {
                                        editingIndex = index
                                        draft = message.content
                                    },
                                    onTranslate = { vm.translateMessage(index) },
                                    onShare = { shareText(message.content) },
                                    onDelete = { vm.deleteMessage(index) }
                                )
                                Role.ASSISTANT -> AssistantMessageView(
                                    message = message,
                                    isStreaming = state.isStreaming && message.id == state.messages.lastOrNull()?.id,
                                    onRegenerate = { vm.regenerate() },
                                    onCopy = { copyText(message.content) },
                                    onEdit = {
                                        editingIndex = index
                                        draft = message.content
                                    },
                                    onTranslate = { vm.translateMessage(index) },
                                    onShare = { shareText(message.content) },
                                    onDelete = { vm.deleteMessage(index) },
                                    onRunCode = { lang, code -> vm.runCodeBlock(lang, code) }
                                )
                                Role.SYSTEM -> {}
                            }
                        }
                    }
                    if (state.messages.isNotEmpty() && !nearBottom) {
                        JumpToBottomButton(
                            onClick = { scope.launch { listState.scrollToItem(state.messages.size - 1) } },
                            modifier = Modifier.align(Alignment.BottomEnd).padding(end = 16.dp, bottom = 16.dp)
                        )
                    }
                }
            }
        }
    }

    if (showModels) {
        ModelSheet(
            models = state.models,
            activeModel = state.activeModel,
            loadingModels = state.loadingModels,
            onSelect = { model ->
                vm.selectModel(model)
                showModels = false
            },
            onLoadFromServer = { vm.loadModelsFromServer() },
            onManage = {
                showModels = false
                onOpenSettings()
            },
            onDismiss = { showModels = false }
        )
    }

    pluginKind?.let { kind ->
        PluginPromptDialog(
            kind = kind,
            initial = pluginDraft,
            onDismiss = { pluginKind = null },
            onConfirm = { desc, picked ->
                val text = desc.trim()
                if (text.isNotEmpty()) {
                    when (kind) {
                        PluginKind.IMAGE_GEN -> vm.runImageGen(text, picked)
                        PluginKind.VIDEO_GEN -> vm.runVideoGen(text, picked)
                        PluginKind.FILE_EDIT -> vm.runFileEdit(text, picked)
                    }
                }
                pluginKind = null
            }
        )
    }

    editingIndex?.let { index ->
        EditMessageDialog(
            initial = draft,
            onDismiss = { editingIndex = null },
            onConfirm = { newText ->
                if (state.messages[index].role == Role.ASSISTANT) vm.editAssistantMessage(index, newText)
                else vm.editMessage(index, newText)
                editingIndex = null
            }
        )
    }

    callType?.let { t ->
        val role = vm.activeCallRole()
        val baseUrl = state.baseUrl
        val apiKey = state.apiKey
        val modelId = state.activeModel?.id
        // 实时视觉智能调度：自动选择可识别图像的模型
        val visionModel = pickVisionModel(state.models, state.activeModel)
        if (t == 1) {
            VideoCallDialog(
                role = role,
                baseUrl = baseUrl,
                apiKey = apiKey,
                modelId = modelId,
                model = state.activeModel,
                visionModel = visionModel,
                onUserText = { vm.appendCallMessage(Role.USER, it) },
                onAiReply = { vm.appendCallMessage(Role.ASSISTANT, it) },
                onDismiss = {
                    callType = null
                    MemoryPlugin.log(context, "挂断通话", role?.name ?: "AI")
                }
            )
        } else {
            VoiceCallDialog(
                role = role,
                baseUrl = baseUrl,
                apiKey = apiKey,
                modelId = modelId,
                model = state.activeModel,
                visionModel = visionModel,
                onUserText = { vm.appendCallMessage(Role.USER, it) },
                onAiReply = { vm.appendCallMessage(Role.ASSISTANT, it) },
                onDismiss = {
                    callType = null
                    MemoryPlugin.log(context, "挂断通话", role?.name ?: "AI")
                }
            )
        }
    }
}

@Composable
private fun WelcomeView(
    state: ChatUiState,
    onOpenSettings: () -> Unit
) {
    val lang = LocalLanguage.current
    Column(
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
        modifier = Modifier.fillMaxSize().padding(24.dp)
    ) {
        Text("XS Chat", style = MaterialTheme.typography.headlineMedium)
        // 无模型时才给出引导，其余保持干净
        if (state.activeModel == null) {
            Spacer(Modifier.height(24.dp))
            Text(
                Lang.t(lang, "no_model"),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.error
            )
            Spacer(Modifier.height(8.dp))
            androidx.compose.material3.Button(onClick = onOpenSettings) {
                Text(Lang.t(lang, "go_add_model"))
            }
        }
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun HistoryDrawer(
    history: List<com.xs.chat.data.ConversationMeta>,
    editMode: Boolean,
    selected: Set<String>,
    onToggleEditMode: () -> Unit,
    onSelectAll: () -> Unit,
    onNewChat: () -> Unit,
    onOpen: (String) -> Unit,
    onDelete: (String) -> Unit,
    onToggleSelect: (String) -> Unit,
    onDeleteSelected: () -> Unit,
    onPinSelected: (Boolean) -> Unit,
    onPinOne: (String, Boolean) -> Unit
) {
    val lang = LocalLanguage.current
    var longMenuId by remember { mutableStateOf<String?>(null) }
    ModalDrawerSheet(modifier = Modifier.width(320.dp)) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 12.dp)
        ) {
            if (editMode) {
                Text(
                    if (selected.isEmpty()) Lang.t(lang, "edit_history") else Lang.t(lang, "edit_history") + "（${selected.size}）",
                    style = MaterialTheme.typography.titleLarge,
                    modifier = Modifier.weight(1f)
                )
                IconButton(onClick = onSelectAll) {
                    Icon(Icons.Rounded.SelectAll, contentDescription = Lang.t(lang, "select_all"))
                }
                IconButton(onClick = onToggleEditMode) {
                    Icon(Icons.Rounded.Close, contentDescription = Lang.t(lang, "cancel"))
                }
            } else {
                Text(Lang.t(lang, "history"), style = MaterialTheme.typography.titleLarge, modifier = Modifier.weight(1f))
                IconButton(onClick = onToggleEditMode) {
                    Icon(Icons.Rounded.Edit, contentDescription = Lang.t(lang, "edit_history"))
                }
            }
        }
        LazyColumn(Modifier.weight(1f)) {
            itemsIndexed(history, key = { _, m -> m.id }) { _, meta ->
                Box {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        modifier = Modifier
                            .fillMaxWidth()
                            .then(
                                if (editMode) {
                                    Modifier.clickable { onToggleSelect(meta.id) }
                                } else {
                                    Modifier.combinedClickable(
                                        onClick = { onOpen(meta.id) },
                                        onLongClick = { longMenuId = meta.id }
                                    )
                                }
                            )
                            .padding(start = if (editMode) 0.dp else 16.dp, end = 4.dp, top = 6.dp, bottom = 6.dp)
                    ) {
                        if (editMode) {
                            Checkbox(
                                checked = meta.id in selected,
                                onCheckedChange = { onToggleSelect(meta.id) }
                            )
                        }
                        Column(Modifier.weight(1f)) {
                            Text(
                                meta.title.ifBlank { Lang.t(lang, "empty_conversation") },
                                style = MaterialTheme.typography.bodyMedium,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis
                            )
                            Text(
                                Lang.t(lang, "messages_count").format(meta.messageCount),
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                        if (meta.pinned == true) {
                            if (editMode) {
                                Icon(
                                    Icons.Rounded.PushPin,
                                    contentDescription = Lang.t(lang, "pin"),
                                    tint = MaterialTheme.colorScheme.primary,
                                    modifier = Modifier.size(16.dp)
                                )
                                Spacer(Modifier.width(4.dp))
                            } else {
                                // 单击置顶图标立即取消置顶
                                IconButton(onClick = { onPinOne(meta.id, false) }, modifier = Modifier.size(32.dp)) {
                                    Icon(
                                        Icons.Rounded.PushPin,
                                        contentDescription = Lang.t(lang, "unpin"),
                                        tint = MaterialTheme.colorScheme.primary,
                                        modifier = Modifier.size(16.dp)
                                    )
                                }
                            }
                        }
                        if (!editMode) {
                            IconButton(onClick = { onDelete(meta.id) }) {
                                Icon(
                                    Icons.Rounded.DeleteOutline,
                                    contentDescription = Lang.t(lang, "delete"),
                                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                                    modifier = Modifier.size(18.dp)
                                )
                            }
                        }
                    }
                    // 长按对话 → 置顶 / 取消置顶 / 删除
                    DropdownMenu(expanded = longMenuId == meta.id, onDismissRequest = { longMenuId = null }) {
                        if (meta.pinned == true) {
                            DropdownMenuItem(
                                text = { Text(Lang.t(lang, "unpin")) },
                                onClick = { longMenuId = null; onPinOne(meta.id, false) }
                            )
                        } else {
                            DropdownMenuItem(
                                text = { Text(Lang.t(lang, "pin")) },
                                onClick = { longMenuId = null; onPinOne(meta.id, true) }
                            )
                        }
                        HorizontalDivider()
                        DropdownMenuItem(
                            text = { Text(Lang.t(lang, "delete")) },
                            onClick = { longMenuId = null; onDelete(meta.id) }
                        )
                    }
                }
            }
        }
        Spacer(Modifier.height(8.dp))
        if (editMode) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier.fillMaxWidth().padding(horizontal = 8.dp)
            ) {
                TextButton(onClick = { onPinSelected(true) }, enabled = selected.isNotEmpty()) {
                    Icon(Icons.Rounded.PushPin, contentDescription = null, modifier = Modifier.size(16.dp))
                    Spacer(Modifier.width(4.dp))
                    Text(Lang.t(lang, "pin"))
                }
                TextButton(onClick = { onPinSelected(false) }, enabled = selected.isNotEmpty()) {
                    Icon(
                        Icons.Rounded.PushPin,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.size(16.dp)
                    )
                    Spacer(Modifier.width(4.dp))
                    Text(Lang.t(lang, "unpin"))
                }
                TextButton(onClick = onDeleteSelected, enabled = selected.isNotEmpty()) {
                    Icon(Icons.Rounded.DeleteOutline, contentDescription = null, modifier = Modifier.size(16.dp))
                    Spacer(Modifier.width(4.dp))
                    Text(Lang.t(lang, "delete"))
                }
            }
        } else {
            androidx.compose.material3.Button(
                onClick = onNewChat,
                modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp)
            ) {
                Icon(Icons.Rounded.Add, contentDescription = null)
                Spacer(Modifier.width(8.dp))
                Text(Lang.t(lang, "new_chat"))
            }
        }
        Spacer(Modifier.navigationBarsPadding().height(16.dp))
    }
}

/** 无线调试未配对提示条：一键进入「配对码页面 + 录屏 OCR 自动配对」流程。 */
@Composable
private fun PairPromptBar(status: String, pairing: Boolean, onPair: () -> Unit, onDismiss: () -> Unit) {
    Surface(
        color = MaterialTheme.colorScheme.primaryContainer,
        modifier = Modifier.fillMaxWidth()
    ) {
        Row(
            modifier = Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 6.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(
                status.ifBlank { "无线调试未配对，点击一键配对" },
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onPrimaryContainer,
                modifier = Modifier.weight(1f)
            )
            TextButton(onClick = onPair, enabled = !pairing) { Text(if (pairing) "配对中…" else "一键配对") }
            IconButton(onClick = onDismiss, modifier = Modifier.size(28.dp)) {
                Icon(Icons.Rounded.Close, contentDescription = "关闭", modifier = Modifier.size(18.dp))
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class, ExperimentalFoundationApi::class)
@Composable
private fun ChatInputBar(
    state: ChatUiState,
    onSend: (String) -> Unit,
    onStop: () -> Unit,
    onPick: (AttachmentKind) -> Unit,
    onRemoveAttachment: (String) -> Unit,
    onPluginImage: (String) -> Unit,
    onPluginVideo: (String) -> Unit,
    onPluginFile: (String) -> Unit,
    onVoiceCall: () -> Unit,
    onVideoCall: () -> Unit
) {
    var text by rememberSaveable { mutableStateOf("") }
    var showMenu by remember { mutableStateOf(false) }
    var showCallMenu by remember { mutableStateOf(false) }
    val lang = LocalLanguage.current
    val primary = MaterialTheme.colorScheme.primary
    val canSend = text.isNotBlank() || state.pendingAttachments.isNotEmpty()
    var micRecording by remember { mutableStateOf(false) }
    val micScope = rememberCoroutineScope()
    val micContext = LocalContext.current

    // 长按麦克风：智能录音（有语音识别、无语音等待），识别后自动发送
    fun startMicRecord() {
        if (micRecording) return
        micRecording = true
        micScope.launch {
            try {
                if (!VoskEngine.isReady() && !ensureVoskReady(micContext)) {
                    Toast.makeText(micContext, Lang.t(lang, "call_model_loading"), Toast.LENGTH_SHORT).show()
                    return@launch
                }
                val voiceText = withTimeoutOrNull(15000) {
                    voskSmartRecognizeOnce(
                        onSpeaking = {},
                        onError = { msg -> Toast.makeText(micContext, "录音失败：" + msg, Toast.LENGTH_SHORT).show() }
                    )
                }.orEmpty().trim()
                if (voiceText.isNotEmpty()) onSend(voiceText)
            } finally {
                micRecording = false
            }
        }
    }

    Surface(shadowElevation = 8.dp) {
        Column(
            Modifier
                .fillMaxWidth()
                .navigationBarsPadding()
                .imePadding()
                .padding(start = 12.dp, end = 12.dp, top = 8.dp, bottom = 8.dp)
        ) {
            // 待发送附件悬浮在输入框上方
            if (state.pendingAttachments.isNotEmpty()) {
                Row(
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    modifier = Modifier
                        .fillMaxWidth()
                        .horizontalScroll(rememberScrollState())
                        .padding(bottom = 8.dp)
                ) {
                    state.pendingAttachments.forEach { a ->
                        PendingAttachmentChip(attachment = a, onRemove = { onRemoveAttachment(a.id) })
                    }
                }
            }
            if (micRecording) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    modifier = Modifier.fillMaxWidth().padding(bottom = 6.dp)
                ) {
                    CircularProgressIndicator(modifier = Modifier.size(12.dp), strokeWidth = 2.dp, color = Color(0xFFF44336))
                    Spacer(Modifier.width(6.dp))
                    Text(Lang.t(lang, "mic_recording"), style = MaterialTheme.typography.bodySmall, color = Color(0xFFF44336))
                }
            }
            Row(verticalAlignment = Alignment.Bottom) {
                OutlinedTextField(
                    value = text,
                    onValueChange = { text = it },
                    placeholder = {
                        Text(
                            if (state.activeModel == null) Lang.t(lang, "input_placeholder_no_model")
                            else Lang.t(lang, "input_placeholder")
                        )
                    },
                    maxLines = 6,
                    shape = RoundedCornerShape(24.dp),
                    modifier = Modifier.weight(1f),
                    leadingIcon = {
                        Box {
                            Box(
                                contentAlignment = Alignment.Center,
                                modifier = Modifier
                                    .size(34.dp)
                                    .clip(CircleShape)
                                    .background(if (micRecording) Color(0x33F44336) else Color.Transparent)
                                    .combinedClickable(
                                        onClick = { showCallMenu = true },
                                        onLongClick = { startMicRecord() }
                                    )
                            ) {
                                Icon(
                                    Icons.Rounded.Mic,
                                    contentDescription = Lang.t(lang, "mic"),
                                    modifier = Modifier.size(20.dp),
                                    tint = if (micRecording) Color(0xFFF44336) else Color.Unspecified
                                )
                            }
                            DropdownMenu(expanded = showCallMenu, onDismissRequest = { showCallMenu = false }) {
                                DropdownMenuItem(
                                    text = { Text(Lang.t(lang, "voice_call")) },
                                    leadingIcon = { Icon(Icons.Rounded.Call, null) },
                                    onClick = { showCallMenu = false; onVoiceCall() }
                                )
                                DropdownMenuItem(
                                    text = { Text(Lang.t(lang, "video_call")) },
                                    leadingIcon = { Icon(Icons.Rounded.Videocam, null) },
                                    onClick = { showCallMenu = false; onVideoCall() }
                                )
                            }
                        }
                    },
                    trailingIcon = {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            // 加号：添加附件 / 触发内置插件（位于发送按钮左侧，输入框内部）
                            Box {
                                IconButton(onClick = { showMenu = true }, modifier = Modifier.size(34.dp)) {
                                    Icon(Icons.Rounded.Add, contentDescription = Lang.t(lang, "add"), modifier = Modifier.size(20.dp))
                                }
                                DropdownMenu(expanded = showMenu, onDismissRequest = { showMenu = false }) {
                                    DropdownMenuItem(
                                        text = { Text(Lang.t(lang, "image")) },
                                        leadingIcon = { Icon(Icons.Rounded.Image, null) },
                                        onClick = { showMenu = false; onPick(AttachmentKind.IMAGE) }
                                    )
                                    DropdownMenuItem(
                                        text = { Text(Lang.t(lang, "file")) },
                                        leadingIcon = { Icon(Icons.Rounded.Description, null) },
                                        onClick = { showMenu = false; onPick(AttachmentKind.FILE) }
                                    )
                                    DropdownMenuItem(
                                        text = { Text(Lang.t(lang, "video")) },
                                        leadingIcon = { Icon(Icons.Rounded.VideoLibrary, null) },
                                        onClick = { showMenu = false; onPick(AttachmentKind.VIDEO) }
                                    )
                                    HorizontalDivider()
                                    DropdownMenuItem(
                                        text = { Text(Lang.t(lang, "ai_image")) },
                                        leadingIcon = { Icon(Icons.Rounded.AutoAwesome, null) },
                                        onClick = { showMenu = false; onPluginImage(text); text = "" }
                                    )
                                    DropdownMenuItem(
                                        text = { Text(Lang.t(lang, "ai_video")) },
                                        leadingIcon = { Icon(Icons.Rounded.Movie, null) },
                                        onClick = { showMenu = false; onPluginVideo(text); text = "" }
                                    )
                                    DropdownMenuItem(
                                        text = { Text(Lang.t(lang, "ai_edit_file")) },
                                        leadingIcon = { Icon(Icons.Rounded.EditNote, null) },
                                        onClick = { showMenu = false; onPluginFile(text); text = "" }
                                    )
                                }
                            }
                            if (state.isStreaming) {
                                IconButton(onClick = onStop, modifier = Modifier.size(34.dp)) {
                                    Icon(
                                        Icons.Rounded.Stop,
                                        contentDescription = Lang.t(lang, "stop"),
                                        tint = MaterialTheme.colorScheme.error,
                                        modifier = Modifier.size(20.dp)
                                    )
                                }
                            } else {
                                IconButton(
                                    onClick = {
                                        if (canSend) {
                                            onSend(text.trim())
                                            text = ""
                                        }
                                    },
                                    enabled = canSend,
                                    modifier = Modifier.size(34.dp)
                                ) {
                                    Icon(
                                        Icons.Rounded.ArrowUpward,
                                        contentDescription = Lang.t(lang, "send"),
                                        tint = if (canSend) primary else MaterialTheme.colorScheme.outline,
                                        modifier = Modifier.size(20.dp)
                                    )
                                }
                            }
                        }
                    }
                )
            }
        }
    }
}

@Composable
private fun EditMessageDialog(
    initial: String,
    onDismiss: () -> Unit,
    onConfirm: (String) -> Unit
) {
    var text by rememberSaveable { mutableStateOf(initial) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("编辑消息") },
        text = {
            OutlinedTextField(
                value = text,
                onValueChange = { text = it },
                minLines = 3,
                maxLines = 8,
                modifier = Modifier.fillMaxWidth()
            )
        },
        confirmButton = {
            TextButton(onClick = { onConfirm(text) }) { Text("发送") }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text("取消") }
        }
    )
}
/** 插件类型：AI 生图 / AI 生视频 / AI 修改文件。 */
private enum class PluginKind { IMAGE_GEN, VIDEO_GEN, FILE_EDIT }

/** 插件描述悬浮框：点击 + 菜单中的 AI 功能后弹出，让用户输入描述；支持添加参考图/视频/文件并实时预览。 */
@Composable
private fun PluginPromptDialog(
    kind: PluginKind,
    initial: String,
    onDismiss: () -> Unit,
    onConfirm: (String, List<PickedAttachment>?) -> Unit
) {
    var text by rememberSaveable { mutableStateOf(initial) }
    var pickedItems by remember { mutableStateOf(listOf<PickedAttachment>()) }
    val lang = LocalLanguage.current
    val context = LocalContext.current
    val title = when (kind) {
        PluginKind.IMAGE_GEN -> Lang.t(lang, "ai_image")
        PluginKind.VIDEO_GEN -> Lang.t(lang, "ai_video")
        PluginKind.FILE_EDIT -> Lang.t(lang, "ai_edit_file")
    }
    val hint = when (kind) {
        PluginKind.IMAGE_GEN -> Lang.t(lang, "plugin_prompt_hint_image")
        PluginKind.VIDEO_GEN -> Lang.t(lang, "plugin_prompt_hint_video")
        PluginKind.FILE_EDIT -> Lang.t(lang, "plugin_prompt_hint_file")
    }
    // 添加类型：图生图/图生视频多选图片（视频取首帧作参考），文件修改单选任意文件
    val isImagePicker = kind != PluginKind.FILE_EDIT
    // 多图选择器：一次可添加多张图片
    val multiLauncher = rememberLauncherForActivityResult(ActivityResultContracts.GetMultipleContents()) { uris ->
        if (uris.isNotEmpty()) {
            pickedItems = pickedItems + uris.map { uri ->
                PickedAttachment(uri.toString(), AttachmentStore.queryName(context, uri), AttachmentStore.queryMime(context, uri))
            }
        }
    }
    // 单选选择器：文件修改用任意文件
    val singleLauncher = rememberLauncherForActivityResult(ActivityResultContracts.GetContent()) { uri ->
        if (uri != null) {
            pickedItems = listOf(PickedAttachment(uri.toString(), AttachmentStore.queryName(context, uri), AttachmentStore.queryMime(context, uri)))
        }
    }
    val addPick: () -> Unit = {
        if (isImagePicker) multiLauncher.launch("image/*") else singleLauncher.launch("*/*")
    }
    // 小缩略图：按 96px 解码，省内存
    val thumbnails = remember(pickedItems) {
        pickedItems.map { item ->
            if (item.mimeType.startsWith("image/")) {
                runCatching { AttachmentStore.decodeBitmap(context, item.uri, 96)?.asImageBitmap() }.getOrNull()
            } else null
        }
    }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(Lang.t(lang, "plugin_prompt_title").format(title)) },
        text = {
            Column {
                OutlinedTextField(
                    value = text,
                    onValueChange = { text = it },
                    placeholder = { Text(hint) },
                    minLines = 2,
                    maxLines = 6,
                    modifier = Modifier.fillMaxWidth()
                )
                if (pickedItems.isNotEmpty()) {
                    Spacer(Modifier.height(8.dp))
                    Row(
                        modifier = Modifier.horizontalScroll(rememberScrollState()),
                        horizontalArrangement = Arrangement.spacedBy(6.dp)
                    ) {
                        pickedItems.forEachIndexed { index, item ->
                            ThumbnailTile(
                                item = item,
                                bitmap = thumbnails[index],
                                onRemove = { pickedItems = pickedItems.filterIndexed { i, _ -> i != index } }
                            )
                        }
                    }
                }
                if (kind == PluginKind.VIDEO_GEN || kind == PluginKind.FILE_EDIT) {
                    Spacer(Modifier.height(8.dp))
                    Text(
                        if (kind == PluginKind.VIDEO_GEN) Lang.t(lang, "video_need_ref")
                        else Lang.t(lang, "file_need_ref"),
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }
        },
        confirmButton = {
            TextButton(
                onClick = {
                    if (text.isNotBlank()) {
                        onConfirm(text, pickedItems.ifEmpty { null })
                    }
                },
                enabled = text.isNotBlank()
            ) { Text(Lang.t(lang, "generate")) }
        },
        dismissButton = {
            // 添加按钮与取消保持同一水平
            Row {
                TextButton(onClick = addPick) {
                    Icon(Icons.Rounded.Add, contentDescription = null, modifier = Modifier.size(16.dp))
                    Spacer(Modifier.width(4.dp))
                    Text(Lang.t(lang, "add"))
                }
                TextButton(onClick = onDismiss) { Text(Lang.t(lang, "cancel")) }
            }
        }
    )
}













/** 插件对话框附件小缩略图：图片 48dp 缩略图，文件显示名称 chip，右上角可移除。 */
@Composable
private fun ThumbnailTile(item: PickedAttachment, bitmap: ImageBitmap?, onRemove: () -> Unit) {
    Box {
        // 内容整体向右下让位，移除按钮完全落在图片右上角外侧（留出间隙），可完整看清
        Box(Modifier.padding(top = 24.dp, end = 24.dp)) {
            if (bitmap != null) {
                Image(
                    bitmap = bitmap,
                    contentDescription = item.name,
                    contentScale = ContentScale.Crop,
                    modifier = Modifier
                        .size(48.dp)
                        .clip(RoundedCornerShape(6.dp))
                )
            } else {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    modifier = Modifier
                        .height(48.dp)
                        .clip(RoundedCornerShape(6.dp))
                        .background(MaterialTheme.colorScheme.surfaceVariant)
                        .padding(horizontal = 8.dp)
                ) {
                    Icon(
                        if (item.mimeType.startsWith("video/")) Icons.Rounded.VideoLibrary else Icons.Rounded.Description,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.size(14.dp)
                    )
                    Spacer(Modifier.width(4.dp))
                    Text(
                        item.name,
                        style = MaterialTheme.typography.labelSmall,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.widthIn(max = 80.dp)
                    )
                }
            }
        }
        IconButton(
            onClick = onRemove,
            modifier = Modifier
                .align(Alignment.TopEnd)
                .size(20.dp)
                .semantics { contentDescription = "移除" }
        ) {
            val closeColor = MaterialTheme.colorScheme.onSurface
            // 加粗 ×：两条圆头粗线，替代细叉图标
            Canvas(Modifier.size(12.dp)) {
                val stroke = 3.dp.toPx()
                val inset = 2.dp.toPx()
                drawLine(closeColor, Offset(inset, inset), Offset(size.width - inset, size.height - inset), strokeWidth = stroke, cap = StrokeCap.Round)
                drawLine(closeColor, Offset(size.width - inset, inset), Offset(inset, size.height - inset), strokeWidth = stroke, cap = StrokeCap.Round)
            }
        }
    }
}

/** Codex 风格悬浮按钮：旋转圆环动画，点击跳转到最新消息。 */
@Composable
private fun JumpToBottomButton(onClick: () -> Unit, modifier: Modifier = Modifier) {
    val transition = rememberInfiniteTransition(label = "jump")
    val angle by transition.animateFloat(
        initialValue = 0f,
        targetValue = 360f,
        animationSpec = infiniteRepeatable(tween(1200, easing = LinearEasing)),
        label = "spin"
    )
    val primary = MaterialTheme.colorScheme.primary
    Box(
        contentAlignment = Alignment.Center,
        modifier = modifier
            .size(44.dp)
            .clip(CircleShape)
            .background(MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.9f))
            .clickable(onClick = onClick)
    ) {
        Canvas(Modifier.size(26.dp).graphicsLayer { rotationZ = angle }) {
            drawArc(
                color = primary,
                startAngle = -90f,
                sweepAngle = 280f,
                useCenter = false,
                style = Stroke(width = 3.dp.toPx(), cap = StrokeCap.Round)
            )
        }
        Icon(
            Icons.Rounded.ArrowDownward,
            contentDescription = Lang.t(LocalLanguage.current, "jump_to_latest"),
            tint = primary,
            modifier = Modifier.size(15.dp)
        )
    }
}

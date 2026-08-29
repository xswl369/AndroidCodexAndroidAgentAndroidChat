package com.xs.chat.ui

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.util.Base64
import android.util.Log
import android.net.Uri
import android.speech.tts.TextToSpeech
import android.speech.tts.Voice
import android.speech.tts.UtteranceProgressListener
import android.media.AudioAttributes
import android.media.AudioFocusRequest
import android.media.AudioFormat
import android.media.AudioManager
import android.media.AudioRecord
import android.media.MediaRecorder
import android.os.Build
import android.os.Handler
import android.os.Looper
import com.google.gson.JsonParser
import com.offlinevoice.input.VoskEngine
import androidx.camera.core.CameraSelector
import androidx.camera.core.ImageAnalysis
import androidx.camera.core.ImageProxy
import androidx.camera.core.Preview
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.camera.view.PreviewView
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.CallEnd
import androidx.compose.material.icons.rounded.FlipCameraAndroid
import androidx.compose.material.icons.rounded.Mic
import androidx.compose.material.icons.rounded.MicOff
import androidx.compose.material.icons.rounded.Send
import androidx.compose.material.icons.rounded.Videocam
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.produceState
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalLifecycleOwner
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.core.content.ContextCompat
import com.xs.chat.data.AiModel
import com.xs.chat.data.CallRole
import com.xs.chat.data.ChatMessage
import com.xs.chat.data.ContentPart
import com.xs.chat.data.OpenAiApi
import com.xs.chat.data.Role
import com.xs.chat.data.SettingsStore
import java.util.Locale
import java.util.UUID
import kotlin.coroutines.resume
import java.io.ByteArrayOutputStream
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicBoolean
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull

/** 实时语音通话：拨号 → AI 接听 → 语音实时互动（系统识别 + Chat + 系统 TTS）。 */
@Composable
fun VoiceCallDialog(
    role: CallRole?,
    baseUrl: String,
    apiKey: String,
    modelId: String?,
    model: AiModel? = null,
    visionModel: AiModel? = null,
    onUserText: (String) -> Unit = {},
    onAiReply: (String) -> Unit = {},
    onDismiss: () -> Unit
) {
    CallDialogUi(
        type = 0, role = role, baseUrl = baseUrl, apiKey = apiKey, modelId = modelId, model = model, visionModel = visionModel,
        onUserText = onUserText, onAiReply = onAiReply, onDismiss = onDismiss
    )
}

/** 实时视频通话：前置/后置摄像头实时预览 + 语音实时互动，可随时切换镜头。 */
@Composable
fun VideoCallDialog(
    role: CallRole?,
    baseUrl: String,
    apiKey: String,
    modelId: String?,
    model: AiModel? = null,
    visionModel: AiModel? = null,
    onUserText: (String) -> Unit = {},
    onAiReply: (String) -> Unit = {},
    onDismiss: () -> Unit
) {
    CallDialogUi(
        type = 1, role = role, baseUrl = baseUrl, apiKey = apiKey, modelId = modelId, model = model, visionModel = visionModel,
        onUserText = onUserText, onAiReply = onAiReply, onDismiss = onDismiss
    )
}

@Composable
private fun CallDialogUi(
    type: Int,
    role: CallRole?,
    baseUrl: String,
    apiKey: String,
    modelId: String?,
    model: AiModel? = null,
    visionModel: AiModel? = null,
    onUserText: (String) -> Unit = {},
    onAiReply: (String) -> Unit = {},
    onDismiss: () -> Unit
) {
    val context = LocalContext.current
    val lang = LocalLanguage.current
    var phase by remember { mutableStateOf("dialing") }
    var seconds by remember { mutableStateOf(0) }
    var muted by remember { mutableStateOf(false) }
    var busy by remember { mutableStateOf(false) }
    var statusText by remember { mutableStateOf("") }
    var transcripts by remember { mutableStateOf<List<Pair<String, String>>>(emptyList()) }
    // 表演模式（song/book/story）：由用户指令智能触发；播放与识别并行，可随时说“停”中断
    var performMode by remember { mutableStateOf<String?>(null) }
    val performStop = remember { AtomicBoolean(false) }
    var playingJob by remember { mutableStateOf<Job?>(null) }
    // AI 正在播放语音：播放期间暂停聆听，防止扬声器回声被识别触发自答循环
    var playing by remember { mutableStateOf(false) }
    val scope = rememberCoroutineScope()
    // 实时视觉：开启后循环抓帧送视觉模型识别画面（参考 gemini-live 开源实现：帧→JPEG→base64→多模态请求）
    var visionOn by remember { mutableStateOf(type == 1) }
    var latestFrame by remember { mutableStateOf<String?>(null) }
    var lastCaptureAt = 0L
    val cameraExecutor = remember { Executors.newSingleThreadExecutor() }
    fun captureFrame(image: ImageProxy) {
        val now = System.currentTimeMillis()
        if (now - lastCaptureAt < 2000) { image.close(); return }
        lastCaptureAt = now
        val dataUrl = try {
            @Suppress("DEPRECATION")
            val bmp = image.toBitmap()
            val w = bmp.width
            val h = bmp.height
            val m = maxOf(w, h)
            val max = 1024
            val scaled = if (m > max) {
                val scale = max.toFloat() / m
                Bitmap.createScaledBitmap(bmp, (w * scale).toInt().coerceAtLeast(1), (h * scale).toInt().coerceAtLeast(1), true)
            } else bmp
            val out = ByteArrayOutputStream()
            scaled.compress(Bitmap.CompressFormat.JPEG, 55, out)
            "data:image/jpeg;base64," + Base64.encodeToString(out.toByteArray(), Base64.NO_WRAP)
        } catch (_: Exception) {
            null
        } finally {
            image.close()
        }
        if (dataUrl != null) latestFrame = dataUrl
    }

    // 优先用模型自身的 baseUrl/apiKey（与正常对话一致），避免全局配置与模型不匹配导致请求失败
    val api = remember(model, baseUrl, apiKey) {
        OpenAiApi(
            (model?.baseUrl ?: baseUrl).ifBlank { "https://api.openai.com/v1" },
            model?.apiKey ?: apiKey
        )
    }
    // 视觉模型的 API 实例（智能调度：当前模型无视觉时自动切换）
    val visionApi = remember(visionModel, model, api) {
        if (visionModel != null && visionModel.id != model?.id) {
            OpenAiApi(visionModel.baseUrl.ifBlank { "https://api.openai.com/v1" }, visionModel.apiKey)
        } else api
    }

    // 拨号动画期间立即加载离线语音模型，接通后零等待
    LaunchedEffect(Unit) {
        if (!VoskEngine.isReady()) {
            statusText = Lang.t(lang, "call_model_loading")
            ensureVoskReady(context)
        }
    }

    // 拨号动画后 AI 接听
    LaunchedEffect(Unit) {
        delay(1800)
        phase = "active"
        statusText = Lang.t(lang, "call_connected")
        delay(1200)
        statusText = Lang.t(lang, "call_listening")
    }
    LaunchedEffect(phase) {
        while (phase == "active") { delay(1000); seconds++ }
    }

    // 摄像头（视频通话）
    val previewView = remember { PreviewView(context).apply { scaleType = PreviewView.ScaleType.FILL_CENTER } }
    var cameraProvider by remember { mutableStateOf<ProcessCameraProvider?>(null) }
    var front by remember { mutableStateOf(true) }
    val lifecycleOwner = LocalLifecycleOwner.current
    DisposableEffect(Unit) {
        val future = ProcessCameraProvider.getInstance(context)
        val listener = Runnable { cameraProvider = runCatching { future.get() }.getOrNull() }
        future.addListener(listener, ContextCompat.getMainExecutor(context))
        onDispose {
            cameraProvider?.unbindAll()
            runCatching { cameraExecutor.shutdown() }
        }
    }
    LaunchedEffect(cameraProvider, front, phase, visionOn) {
        if (type != 1 || phase != "active") return@LaunchedEffect
        val p = cameraProvider ?: return@LaunchedEffect
        p.unbindAll()
        val preview = Preview.Builder().build().also { it.setSurfaceProvider(previewView.surfaceProvider) }
        val analysis = if (visionOn) {
            ImageAnalysis.Builder()
                .setBackpressureStrategy(ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST)
                .build()
                .also { it.setAnalyzer(cameraExecutor) { image -> captureFrame(image) } }
        } else null
        val selector = if (front) CameraSelector.DEFAULT_FRONT_CAMERA else CameraSelector.DEFAULT_BACK_CAMERA
        runCatching {
            if (analysis != null) p.bindToLifecycle(lifecycleOwner, selector, preview, analysis)
            else p.bindToLifecycle(lifecycleOwner, selector, preview)
        }
    }

    // 语音实时互动：Vosk 离线识别 → Chat → TTS（角色音色，扬声器/耳机输出），循环聆听
    var ttsReady by remember { mutableStateOf(false) }
    var tts by remember { mutableStateOf<TextToSpeech?>(null) }
    DisposableEffect(Unit) {
        var engine: TextToSpeech? = null
        var fallbackTried = false
        val listener = TextToSpeech.OnInitListener { status ->
            ttsReady = status == TextToSpeech.SUCCESS
            // 指定引擎初始化失败（未安装/服务异常）：回退系统默认引擎，保证通话有声
            if (status != TextToSpeech.SUCCESS && !fallbackTried) {
                fallbackTried = true
                runCatching { engine?.shutdown() }
                val fb = runCatching { TextToSpeech(context, TextToSpeech.OnInitListener { st -> ttsReady = st == TextToSpeech.SUCCESS }) }.getOrNull()
                if (fb != null) {
                    engine = fb
                    tts = fb
                }
            }
        }
        // 优先内置讯飞引擎（中文音色全），缺失时回退系统默认引擎
        engine = runCatching { TextToSpeech(context, listener, "com.iflytek.speechsuite") }.getOrNull()
        if (engine == null) engine = runCatching { TextToSpeech(context, listener) }.getOrNull()
        tts = engine
        val am = context.getSystemService(Context.AUDIO_SERVICE) as? AudioManager
        val focusReq = if (Build.VERSION.SDK_INT >= 26) {
            AudioFocusRequest.Builder(AudioManager.AUDIOFOCUS_GAIN_TRANSIENT_MAY_DUCK)
                .setAudioAttributes(
                    AudioAttributes.Builder()
                        .setUsage(AudioAttributes.USAGE_MEDIA)
                        .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
                        .build()
                )
                .build()
                .also { runCatching { am?.requestAudioFocus(it) } }
        } else {
            @Suppress("DEPRECATION")
            runCatching { am?.requestAudioFocus(null, AudioManager.STREAM_MUSIC, AudioManager.AUDIOFOCUS_GAIN_TRANSIENT_MAY_DUCK) }
            null
        }
        onDispose {
            runCatching { engine?.shutdown() }
            if (Build.VERSION.SDK_INT >= 26 && focusReq != null) {
                runCatching { am?.abandonAudioFocusRequest(focusReq) }
            } else {
                @Suppress("DEPRECATION")
                runCatching { am?.abandonAudioFocus(null) }
            }
            tts = null
            ttsReady = false
        }
    }

    val engine = tts

    // 交互核心：识别/自动观察的文本 → 发模型（可携带实时视频帧） → 回复入字幕 + 同步聊天 + 角色音色朗读
    suspend fun sendWithFrame(text: String, frame: String?, mode: String?): String = withContext(Dispatchers.IO) {
        val msgs = listOf(ChatMessage(role = Role.USER, content = text))
        val parts = if (!frame.isNullOrBlank()) {
            listOf(
                ContentPart(type = "text", text = text),
                ContentPart(type = "image_url", dataUrl = frame)
            )
        } else emptyList()
        // 表演模式附加指令（说书/故事/唱歌）
        val systemPrompt = buildString {
            append(role?.prompt ?: "你是一位 AI 助手。")
            if (mode != null) append("\n").append(performPromptFor(mode))
        }
        // 带帧时优先用视觉模型，纯文本回退用当前聊天模型
        suspend fun call(fr: String?, m: AiModel?, a: OpenAiApi): String = runCatching {
            val sb = StringBuilder()
            a.streamChat(
                m?.modelId ?: modelId ?: "gpt-4o-mini",
                msgs,
                systemPrompt,
                0.7f,
                attachmentParts = if (fr != null) mapOf(msgs[0].id to parts) else emptyMap(),
                onDelta = { sb.append(it) }
            )
            sb.toString().trim().ifBlank { "（无回复）" }
        }.getOrElse { "（AI 回复失败，请检查网络或模型配置）" }
        val r = call(frame, if (frame != null && visionModel != null) visionModel else model,
            if (frame != null && visionModel != null) visionApi else api)
        // 视觉请求失败时自动降级为纯文本重试一次（兼容不支持图片输入的模型）
        if (frame != null && r.startsWith("（AI 回复失败")) call(null, model, api) else r
    }

    suspend fun speakReply(reply: String) {
        // 新回复打断旧播放；播放放后台协程，聆听循环持续运行（说“停”可随时中断表演）
        playingJob?.cancel()
        playing = true
        playingJob = scope.launch {
            try {
                val mode = performMode
                val wantFemale = role?.voice == "alloy" || role?.voice == "nova" || role?.voice == "shimmer" || role?.voice == "fable"
                val speed = role?.speed ?: 1.0f
                // 性别基础音高：女声明亮、男声低沉、童声更高（与表演模式倍率叠加）
                val genderPitch = if (role?.voice == "fable") 1.35f else if (wantFemale) 1.12f else 0.8f
                val segs = splitIntoSegments(reply, mode)
                val segments = segs
                val eng = engine
                if (eng == null || !ttsReady) {
                    Log.w("CallScreen", "system tts unavailable, skip speaking")
                    return@launch
                }
                runCatching { eng.language = localeFor(role) }
                applyRoleVoice(eng, role)
                runCatching {
                    eng.setAudioAttributes(
                        AudioAttributes.Builder()
                            .setUsage(AudioAttributes.USAGE_MEDIA)
                            .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
                            .build()
                    )
                }
                for ((i, seg) in segments.withIndex()) {
                    if (performStop.get() || !isActive) break
                    // 表演模式：按说书/故事/唱歌调节音调与语速（叠加性别基础音高）
                    runCatching { eng.setPitch(performPitchLocal(mode, wantFemale) * genderPitch) }
                    runCatching { eng.setSpeechRate(speed * performRateLocal(mode)) }
                    val ok = runCatching { withTimeoutOrNull(60000) { speakTts(eng, seg) } }.getOrDefault(null) != null
                    runCatching { eng.setPitch(genderPitch) }
                    runCatching { eng.setSpeechRate(speed) }
                    Log.i("CallScreen", "tts seg[$i/${segments.size}] spoken=$ok")
                    if (i < segments.lastIndex && !performStop.get() && isActive) delay(performPause(mode))
                }
                // 播放结束后留 1500ms 余音消散窗口，防止扬声器残响被麦克风拾取触发自答
                if (isActive) delay(1500)
                performStop.set(false)
            } finally {
                playing = false
            }
        }
    }

    suspend fun exchange(raw: String) {
        val t = raw.trim()
        if (t.isEmpty()) return
        val mode = detectPerformance(t)
        if (mode != null) {
            // 表演指令：设置模式并立即反馈
            performMode = mode
            performStop.set(false)
            statusText = performStatus(mode)
        } else if (isStopCommand(t)) {
            // 停止指令：中断当前表演，恢复聆听
            performMode = null
            performStop.set(true)
            playingJob?.cancel()
            transcripts = transcripts + ("你" to t)
            onUserText(t)
            delay(200)
            statusText = Lang.t(lang, "call_listening")
            return
        } else {
            performMode = null
            performStop.set(false)
        }
        transcripts = transcripts + ("你" to t)
        onUserText(t)
        busy = true
        if (mode == null) statusText = Lang.t(lang, "call_thinking")
        val frame = if (type == 1 && visionOn) latestFrame else null
        val reply = sendWithFrame(t, frame, performMode)
        busy = false
        transcripts = transcripts + ((role?.name ?: "AI") to reply)
        onAiReply(reply)
        speakReply(reply)
    }

    LaunchedEffect(phase, muted) {
        if (phase != "active" || muted) return@LaunchedEffect
        // 加载离线 Vosk 模型（首次解压约 65MB，需数秒）
        if (!VoskEngine.isReady()) {
            statusText = Lang.t(lang, "call_model_loading")
            if (!ensureVoskReady(context)) {
                statusText = Lang.t(lang, "call_voice_unavailable").format("离线模型加载失败")
                return@LaunchedEffect
            }
        }
        var waited = 0
        while (isActive && !ttsReady && waited < 30) { delay(100); waited++ }
        if (engine == null || !ttsReady) {
            statusText = Lang.t(lang, "call_tts_unavailable").format("语音引擎不可用")
        } else {
            runCatching { engine.language = localeFor(role) }
            applyRoleVoice(engine, role)
            runCatching {
                engine.setAudioAttributes(
                    AudioAttributes.Builder()
                        .setUsage(AudioAttributes.USAGE_MEDIA)
                        .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
                        .build()
                )
            }
        }
        val mainHandler = Handler(Looper.getMainLooper())
        var lastErrAt = 0L
        while (isActive) {
            // AI 播放期间暂停聆听：扬声器回声会被麦克风拾取并误识别为“用户发言”，
            // 触发 AI 自动抢答/自答循环；等播放完成（或被打断）后再恢复聆听
            if (playing) {
                statusText = Lang.t(lang, "call_reply_playing")
                delay(200)
                continue
            }
            statusText = Lang.t(lang, "call_listening")
            busy = false
            val text = voskSmartRecognizeOnce(
                onSpeaking = { speaking ->
                    mainHandler.post {
                        statusText = if (speaking) Lang.t(lang, "call_recognizing") else Lang.t(lang, "call_listening")
                    }
                },
                onError = { msg ->
                    if (System.currentTimeMillis() - lastErrAt > 3000) {
                        lastErrAt = System.currentTimeMillis()
                        mainHandler.post { statusText = Lang.t(lang, "call_voice_unavailable").format(msg) }
                    }
                }
            ).trim()
            if (!isActive) break
            if (text.isBlank()) {
                delay(300) // 无语音：继续聆听等待
                continue
            }
            exchange(text)
        }
    }

    Dialog(onDismissRequest = onDismiss, properties = DialogProperties(usePlatformDefaultWidth = false)) {
        Box(Modifier.fillMaxSize().background(Color.Black)) {
            if (type == 1) {
                AndroidView(factory = { previewView }, modifier = Modifier.fillMaxSize())
                RoleOverlay(role = role)
            }
            if (phase == "dialing") {
                DialingContent(role = role)
            } else {
                TranscriptPanel(transcripts = transcripts, busy = busy, statusText = statusText, muted = muted)
            }
            // 顶部角色信息
            Column(
                Modifier
                    .align(Alignment.TopCenter)
                    .statusBarsPadding()
                    .background(Color(0x88000000))
                    .fillMaxWidth()
                    .padding(vertical = 8.dp),
                horizontalAlignment = Alignment.CenterHorizontally
            ) {
                Text(
                    (role?.name ?: "AI") + " · " + formatCallTime(seconds),
                    color = Color.White,
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.Bold
                )
                if (role != null) {
                    Text(
                        role.voice + " · " + role.dialect,
                        color = Color(0xFFBBBBBB),
                        style = MaterialTheme.typography.bodySmall
                    )
                }
            }
            // 镜头切换（视频通话）
            if (type == 1 && phase == "active") {
                IconButton(
                    onClick = { front = !front },
                    modifier = Modifier
                        .align(Alignment.BottomEnd)
                        .navigationBarsPadding()
                        .padding(end = 24.dp, bottom = 150.dp)
                        .size(52.dp)
                        .background(Color(0x88000000), CircleShape)
                ) {
                    Icon(
                        Icons.Rounded.FlipCameraAndroid,
                        contentDescription = Lang.t(lang, "call_switch_camera"),
                        tint = Color.White,
                        modifier = Modifier.size(28.dp)
                    )
                }
            }
            // 静音（语音通话）
            if (type == 0 && phase == "active") {
                IconButton(
                    onClick = { muted = !muted },
                    modifier = Modifier
                        .align(Alignment.BottomCenter)
                        .navigationBarsPadding()
                        .padding(bottom = 130.dp)
                        .size(52.dp)
                        .background(if (muted) Color(0xFFF57C00) else Color(0x88000000), CircleShape)
                ) {
                    Icon(
                        if (muted) Icons.Rounded.MicOff else Icons.Rounded.Mic,
                        contentDescription = if (muted) Lang.t(lang, "call_muted") else Lang.t(lang, "call_unmuted"),
                        tint = Color.White,
                        modifier = Modifier.size(26.dp)
                    )
                }
            }
            // 挂断
            IconButton(
                onClick = onDismiss,
                modifier = Modifier
                    .align(Alignment.BottomCenter)
                    .navigationBarsPadding()
                    .padding(bottom = 40.dp)
                    .size(64.dp)
                    .background(Color(0xFFD32F2F), CircleShape)
            ) {
                Icon(
                    Icons.Rounded.CallEnd,
                    contentDescription = Lang.t(lang, "call_end"),
                    tint = Color.White,
                    modifier = Modifier.size(36.dp)
                )
            }
        }
    }
}

/** 拨号动画：旋转圆环 + 角色形象 + 呼叫文案。 */
@Composable
private fun DialingContent(role: CallRole?) {
    val lang = LocalLanguage.current
    Column(
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
        modifier = Modifier.fillMaxSize().padding(24.dp)
    ) {
        Box(contentAlignment = Alignment.Center, modifier = Modifier.size(140.dp)) {
            CircularProgressIndicator(
                color = Color(0xFF4CAF50),
                strokeWidth = 6.dp,
                modifier = Modifier.size(140.dp)
            )
            Box(
                contentAlignment = Alignment.Center,
                modifier = Modifier.size(96.dp).background(Color(0x334CAF50), CircleShape)
            ) {
                val bmp = rememberRoleBitmap(role?.imageUri)
                if (bmp != null) {
                    Image(
                        bmp,
                        contentDescription = null,
                        contentScale = ContentScale.Crop,
                        modifier = Modifier.size(96.dp).clip(CircleShape)
                    )
                } else {
                    Text(role?.emoji ?: "🤖", fontSize = 52.sp)
                }
            }
        }
        Spacer(Modifier.height(28.dp))
        Text(
            Lang.t(lang, "call_dialing").format(role?.name ?: "AI"),
            color = Color.White,
            style = MaterialTheme.typography.titleLarge
        )
        Spacer(Modifier.height(8.dp))
        Text(
            role?.dialect ?: "",
            color = Color(0xFFBBBBBB),
            style = MaterialTheme.typography.bodyMedium
        )
    }
}

/** 角色信息浮层（视频通话）。 */
@Composable
private fun RoleOverlay(role: CallRole?) {
    Column(
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
        modifier = Modifier.fillMaxSize()
    ) {
        val bmp = rememberRoleBitmap(role?.imageUri)
        if (bmp != null) {
            Image(
                bmp,
                contentDescription = null,
                contentScale = ContentScale.Crop,
                modifier = Modifier.size(160.dp).clip(CircleShape).background(Color(0x88000000), CircleShape)
            )
        } else {
            Text(role?.emoji ?: "🤖", fontSize = 72.sp)
        }
        Spacer(Modifier.height(12.dp))
        Text(
            role?.name ?: "AI",
            color = Color.White,
            style = MaterialTheme.typography.titleMedium
        )
    }
}

/** 字幕面板：实时显示 用户语音识别 / AI 回复。 */
@Composable
private fun TranscriptPanel(
    transcripts: List<Pair<String, String>>,
    busy: Boolean,
    statusText: String,
    muted: Boolean
) {
    Column(
        verticalArrangement = Arrangement.Bottom,
        modifier = Modifier
            .fillMaxSize()
            .navigationBarsPadding()
            .padding(start = 24.dp, end = 24.dp, bottom = 120.dp)
    ) {
        transcripts.takeLast(6).forEach { (who, text) ->
            Box(Modifier.fillMaxWidth().padding(vertical = 4.dp)) {
                Text(
                    "$who：$text",
                    color = if (who == "你") Color(0xFFCCE5FF) else Color.White,
                    style = MaterialTheme.typography.bodyMedium
                )
            }
        }
        if (busy) {
            Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(vertical = 4.dp)) {
                CircularProgressIndicator(
                    color = Color(0xFF4CAF50),
                    strokeWidth = 2.dp,
                    modifier = Modifier.size(14.dp)
                )
                Spacer(Modifier.width(8.dp))
                Text(statusText, color = Color(0xFFAAAAAA), style = MaterialTheme.typography.bodySmall)
            }
        } else if (statusText.isNotBlank()) {
            Text(statusText, color = Color(0xFFAAAAAA), style = MaterialTheme.typography.bodySmall)
        }
        if (muted) {
            Text(
                Lang.t(LocalLanguage.current, "call_muted"),
                color = Color(0xFFF57C00),
                style = MaterialTheme.typography.bodySmall
            )
        }
    }
}

/** 确保 Vosk 离线模型已就绪（首次会从 assets 解压）。 */
internal suspend fun ensureVoskReady(context: Context): Boolean =
    suspendCancellableCoroutine { cont ->
        if (VoskEngine.isReady()) {
            cont.resume(true) {}
            return@suspendCancellableCoroutine
        }
        VoskEngine.ensureModel(
            context,
            "vosk-model-small-cn",
            { if (cont.isActive) cont.resume(true) {} },
            { if (cont.isActive) cont.resume(false) {} }
        )
    }

// 最小语音能量阈值（RMS/32768）：低于该值视为环境噪音，防止无声误触发
private const val MIN_SPEECH_RMS = 700.0

/** 智能聆听一轮：直接读取麦克风 PCM，Vosk 内置 VAD 有语音才识别，
 *  无语音持续等待（上限 30s 防挂死）；取消安全。 */
internal suspend fun voskSmartRecognizeOnce(
    onSpeaking: (Boolean) -> Unit = {},
    onError: (String) -> Unit = {}
): String = withContext(Dispatchers.IO) {
    suspendCancellableCoroutine { cont ->
        val recognizer = try {
            VoskEngine.newRecognizer()
        } catch (e: Exception) {
            onError("识别引擎初始化失败")
            if (cont.isActive) cont.resume("") {}
            return@suspendCancellableCoroutine
        }
        val sampleRate = 16000
        val chunk = ShortArray(sampleRate / 10) // 100ms 一块
        val minBuf = runCatching {
            AudioRecord.getMinBufferSize(sampleRate, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT)
        }.getOrDefault(sampleRate)
        val recorder = runCatching {
            AudioRecord(
                MediaRecorder.AudioSource.MIC,
                sampleRate,
                AudioFormat.CHANNEL_IN_MONO,
                AudioFormat.ENCODING_PCM_16BIT,
                maxOf(minBuf, chunk.size * 2)
            )
        }.getOrNull()
        if (recorder == null || recorder.state != AudioRecord.STATE_INITIALIZED) {
            runCatching { recognizer.close() }
            runCatching { recorder?.release() }
            onError("麦克风不可用")
            if (cont.isActive) cont.resume("") {}
            return@suspendCancellableCoroutine
        }
        var cleaned = false
        fun cleanup() {
            if (cleaned) return
            cleaned = true
            runCatching { if (recorder.recordingState == AudioRecord.RECORDSTATE_RECORDING) recorder.stop() }
            runCatching { recorder.release() }
            runCatching { recognizer.close() }
        }
        cont.invokeOnCancellation { cleanup() }
        runCatching { recorder.startRecording() }.onFailure {
            cleanup()
            onError("录音启动失败")
            if (cont.isActive) cont.resume("") {}
            return@suspendCancellableCoroutine
        }
        val main = Handler(Looper.getMainLooper())
        var result = ""
        var idleMs = 0L
        var readFail = 0
        var spoke = false
        var peakRms = 0.0
        while (cont.isActive) {
            val n = recorder.read(chunk, 0, chunk.size)
            if (n <= 0) {
                readFail++
                if (readFail > 20) {
                    onError("录音读取失败")
                    break
                }
                Thread.sleep(20)
                continue
            }
            readFail = 0
            // 能量检测：过滤环境噪音（电视/风声/远处人声），避免无声环境误触发
            var sum = 0L
            for (j in 0 until n) sum += kotlin.math.abs(chunk[j].toInt())
            val rms = sum.toDouble() / n
            if (rms > peakRms) peakRms = rms
            if (recognizer.acceptWaveForm(chunk, n)) {
                // Vosk 检测到一句话说完；弱信号视为误触发，丢弃
                result = if (peakRms >= MIN_SPEECH_RMS) extractVoskText(recognizer.result) else ""
                break
            }
            if (!spoke) {
                val partial = runCatching { extractVoskText(recognizer.partialResult) }.getOrDefault("")
                if (partial.isNotBlank()) {
                    spoke = true
                    main.post { onSpeaking(true) }
                }
            }
            idleMs += 100
            if (idleMs >= 30000) {
                // 30s 无完整语句兜底：仅有语音活动时才返回已识别内容；
                // 无声/纯噪音（无 partial）返回空继续聆听，避免环境声误触发
                result = if (spoke) runCatching { extractVoskText(recognizer.finalResult) }.getOrDefault("") else ""
                break
            }
        }
        cleanup()
        main.post { onSpeaking(false) }
        if (cont.isActive) cont.resume(result) {}
    }
}

/** 解析 Vosk 结果 JSON：{"text":"..."}。 */
private fun extractVoskText(json: String): String = runCatching {
    JsonParser.parseString(json).asJsonObject.get("text")?.asString.orEmpty().trim()
}.getOrDefault("")

/** 阻塞等待 TTS 朗读完成（取消安全）。 */
private suspend fun speakTts(tts: TextToSpeech, text: String) {
    if (text.isBlank()) return
    suspendCancellableCoroutine { cont ->
        tts.setOnUtteranceProgressListener(object : UtteranceProgressListener() {
            override fun onDone(utteranceId: String?) { if (cont.isActive) cont.resume(Unit) {} }
            override fun onError(utteranceId: String?) { if (cont.isActive) cont.resume(Unit) {} }
            override fun onStart(utteranceId: String?) {}
        })
        cont.invokeOnCancellation { runCatching { tts.stop() } }
        val id = UUID.randomUUID().toString()
        if (tts.speak(text, TextToSpeech.QUEUE_FLUSH, null, id) == TextToSpeech.ERROR) {
            if (cont.isActive) cont.resume(Unit) {}
        }
    }
}

/** 角色形象位图（本地 file://）。 */
@Composable
private fun rememberRoleBitmap(uri: String?): androidx.compose.ui.graphics.ImageBitmap? {
    val context = LocalContext.current
    val state by produceState<androidx.compose.ui.graphics.ImageBitmap?>(null, uri) {
        value = withContext(Dispatchers.IO) {
            if (uri.isNullOrBlank()) null
            else runCatching {
                val path = Uri.parse(uri).path ?: return@runCatching null
                BitmapFactory.decodeFile(path)?.asImageBitmap()
            }.getOrNull()
        }
    }
    return state
}

/** 按角色音色设置 TTS 声音：方言优先 → 性别匹配 → 中文默认（OpenAI 音色名 → Android TTS 音色）。 */
private fun applyRoleVoice(tts: TextToSpeech, role: CallRole?) {
    val r = role ?: return
    val voice = r.voice
    val dialect = r.dialect
    val wantFemale = voice == "alloy" || voice == "nova" || voice == "shimmer" || voice == "fable"
    val prefer = when {
        dialect.contains("粤") -> listOf("xiaoyue", "yue", "cantonese")
        dialect.contains("闽") -> listOf("xiaoan", "minnan", "zh-tw", "taiwan")
        dialect.contains("川") -> listOf("xiaoqing", "qing", "sichuan")
        dialect.contains("鲁") -> listOf("xiaolu", "shandong")
        dialect.contains("东") -> listOf("xiaomei", "mei", "dongbei")
        dialect.contains("沪") || dialect.contains("上") -> listOf("xiaolin", "lin", "shanghai")
        dialect.contains("津") -> listOf("xiaotian", "tianjin")
        dialect.contains("豫") -> listOf("xiaohe", "henan")
        dialect.contains("陕") -> listOf("xiaoxi", "shaanxi")
        dialect.contains("湘") -> listOf("xiaoxiang", "hunan")
        dialect.contains("鄂") -> listOf("xiaobei", "hubei")
        dialect.contains("滇") || dialect.contains("云") -> listOf("xiaoyun", "yunnan")
        dialect.contains("冀") -> listOf("xiaojin", "hebei")
        dialect.contains("晋") -> listOf("xiaojin", "shanxi")
        voice == "fable" -> listOf("xiaomeng", "meng", "child")
        else -> emptyList()
    }
    val all = runCatching { tts.voices.orEmpty() }.getOrDefault(emptyList())
    // 候选池：普通话 + 粤语（方言 locale），避免 yue 音色被语言过滤误排除
    val zh = all.filter { it.locale.language.equals("zh", true) || it.locale.language.equals("yue", true) }
    val pool = zh.ifEmpty { all }
    val chosen = prefer.firstNotNullOfOrNull { n -> pool.firstOrNull { it.name.contains(n, true) || it.locale.toString().contains(n, true) } }
        ?: pool.firstOrNull {
            // 性别分明：按女声/男声音色名关键词匹配（讯飞/Google/系统引擎通用）
            val n = it.name.lowercase() + " " + it.locale.toString().lowercase()
            if (wantFemale) {
                listOf("xiaoyan", "xiaoxiao", "xiaoyi", "xiaochen", "xiaohan", "xiaomeng", "xiaomei",
                    "xiaolin", "xiaoyue", "xiaoshuang", "xiaoting", "xiaoyou", "female", "woman", "girl", "女")
                    .any { n.contains(it) }
            } else {
                listOf("yunxi", "yunjian", "yunyang", "yunfeng", "yunhao", "yunxia", "yunjie",
                    "xiaoyu", "xiaofeng", "xiaoqiang", "xiaogang", "xiaokun", "male", "man", "boy", "男")
                    .any { n.contains(it) }
            }
        }
    if (chosen != null) {
        runCatching { tts.setVoice(chosen) }
    } else {
        runCatching { tts.language = localeFor(role) }
    }
    // 角色特色：性别音高分明（女声明亮、男声低沉、童声更高）；语速取角色设置
    runCatching {
        when {
            voice == "fable" -> tts.setPitch(1.35f)
            wantFemale -> tts.setPitch(1.12f)
            else -> tts.setPitch(0.8f)
        }
        tts.setSpeechRate(r.speed.coerceIn(0.5f, 2.0f))
    }
}

/** 方言 → 系统 TTS Locale 映射。 */
private fun localeFor(role: CallRole?): Locale {
    val d = role?.dialect ?: "普通话"
    return when {
        d.contains("粤") -> Locale("zh", "HK")
        d.contains("闽") -> Locale("zh", "TW")
        else -> Locale.CHINA
    }
}

private fun formatCallTime(total: Int): String {
    val m = total / 60
    val s = total % 60
    return "%02d:%02d".format(m, s)
}

// ===== 表演模式（说书/故事/唱歌）：智能识别、生成提示、TTS 韵律参数 =====

/** 表演指令识别：返回 "song"/"book"/"story"，否则 null。 */
private fun detectPerformance(text: String): String? {
    val t = text.trim()
    return when {
        Regex("说书|评书|说.{0,2}书|来.{0,3}段书|讲.{0,2}评书").containsMatchIn(t) -> "book"
        Regex("讲.{0,3}(个|段|一|一段).{0,4}(故事|童话)|睡前故事|听.{0,3}(故事|童话)|讲故事|童话故事").containsMatchIn(t) -> "story"
        Regex("唱.{0,4}(歌|首|曲|一段|段)|来.{0,3}(首|段|个).{0,3}歌|rap|说唱|儿歌|哼.{0,2}(歌|曲)|来一段|来两句").containsMatchIn(t) -> "song"
        else -> null
    }
}

/** 停止指令识别（短句才判定，避免误伤正常提问）。 */
private fun isStopCommand(text: String): Boolean {
    val t = text.trim()
    if (t.length > 10) return false
    if (t.contains("停下来唱") || t.contains("停下唱") || t.contains("继续")) return false
    return t.contains("停") || t.contains("别") || t.contains("不唱") || t.contains("够了") ||
        t.contains("好了") || t.contains("打住") || t.contains("结束") || t.contains("收")
}

/** 表演模式附加系统提示词：引导 LLM 直接输出表演内容。 */
private fun performPromptFor(mode: String): String = when (mode) {
    "song" -> "【演唱指令】现在请直接输出一首歌的歌词（不要任何解释、前缀或后缀）。要求：押韵上口、节奏感强，多用“啦啦啦～”“哦～”“呀”等衬词，每句不超过14个字，包含2段主歌和1段副歌，结尾加一句“谢谢大家”。歌词整体要适合用欢快轻快的语气朗读演唱。"
    "book" -> "【说书指令】现在请像说书先生一样开讲一段评书（直接输出内容，不要任何解释）。要求：使用“话说”“只见那”“欲知后事如何，且听下回分解”等评书套话，句子短促有力、有悬念和停顿感，150-300字。"
    else -> "【讲故事指令】现在请讲一个生动完整的小故事（直接输出内容，不要任何解释）。要求：情节有趣完整、语言口语化、有画面感，150-300字，适合温暖地朗读。"
}

/** 表演状态提示（通话状态栏）。 */
private fun performStatus(mode: String): String = when (mode) {
    "song" -> "🎤 唱歌中…"
    "book" -> "📖 说书中…"
    else -> "📚 讲故事中…"
}

/** 表演模式 → Edge TTS 音调（Hz）。 */
private fun performPitch(mode: String?, wantFemale: Boolean): String = when (mode) {
    "book" -> if (wantFemale) "-3Hz" else "-7Hz"
    "story" -> if (wantFemale) "-1Hz" else "-3Hz"
    "song" -> if (wantFemale) "+4Hz" else "+2Hz"
    else -> "+0Hz"
}

/** 表演模式 → Edge TTS 语速偏移（%）。 */
private fun performRate(mode: String?): String = when (mode) {
    "book" -> "-8%"
    "story" -> "-4%"
    "song" -> "+10%"
    else -> "+0%"
}

/** 表演模式 → 段间停顿（毫秒）：评书慢、故事中、歌曲快。 */
private fun performPause(mode: String?): Long = when (mode) {
    "book" -> 350
    "story" -> 260
    "song" -> 140
    else -> 200
}

/** 表演模式 → 本地 TTS 音调倍率。 */
private fun performPitchLocal(mode: String?, wantFemale: Boolean): Float = when (mode) {
    "book" -> if (wantFemale) 0.92f else 0.85f
    "story" -> if (wantFemale) 0.98f else 0.94f
    "song" -> if (wantFemale) 1.12f else 1.06f
    else -> 1.0f
}

/** 表演模式 → 本地 TTS 语速倍率。 */
private fun performRateLocal(mode: String?): Float = when (mode) {
    "book" -> 0.9f
    "story" -> 0.95f
    "song" -> 1.15f
    else -> 1.0f
}

/** 长文本分段：按标点/换行切句并合并为 ≤ 上限字符的段；表演模式段更短以便节奏控制。 */
private fun splitIntoSegments(text: String, mode: String?): List<String> {
    val clean = text.replace("\r", "").trim()
    if (clean.isEmpty()) return emptyList()
    val max = if (mode != null) 40 else 45
    val sentences = Regex("(?<=[。！？!?；;\n])\\s*").split(clean).map { it.trim() }.filter { it.isNotBlank() }
    if (sentences.isEmpty()) return clean.chunked(max)
    val out = mutableListOf<String>()
    val cur = StringBuilder()
    for (sen in sentences) {
        if (cur.isNotEmpty() && cur.length + sen.length > max) {
            out.add(cur.toString())
            cur.setLength(0)
        }
        cur.append(sen)
    }
    if (cur.isNotEmpty()) out.add(cur.toString())
    // 兜底：仍超长的句子按最大长度硬切（保证每段可被 TTS 合成）
    return out.flatMap { seg -> if (seg.length > max * 2) seg.chunked(max) else listOf(seg) }
}

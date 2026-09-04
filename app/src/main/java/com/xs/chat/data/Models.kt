package com.xs.chat.data

import com.google.gson.annotations.SerializedName
import java.util.UUID

enum class Role { SYSTEM, USER, ASSISTANT }

/** 附件类型：图片 / 文件 / 视频。 */
enum class AttachmentKind { IMAGE, FILE, VIDEO }

/** 消息附件：本地 file:// 或 content:// URI，持久化后仍可读取渲染。 */
data class Attachment(
    val id: String = UUID.randomUUID().toString(),
    val kind: AttachmentKind,
    val name: String,
    val mimeType: String,
    val sizeBytes: Long,
    val uri: String,
    val generated: Boolean = false
)

/** 多模态内容片段：type = text / image_url / input_video / file */
data class ContentPart(
    val type: String,
    val text: String = "",
    val dataUrl: String = "",
    val fileName: String = ""
)

/** 一次 API 调用的 token 用量（OpenAI 兼容 usage 字段）。 */
data class Usage(
    val promptTokens: Long = 0,
    val completionTokens: Long = 0
)

/** 消息调用统计（Codex 风格）：输入/输出 token、耗时、调用次数。 */
data class CallMeta(
    val promptTokens: Long = 0,
    val completionTokens: Long = 0,
    val durationMs: Long = 0,
    val callCount: Int = 1
)

/** 联网搜索（元宝同款）：参考资料条目，编号与正文 [N] 引用一一对应。 */
data class SearchReference(
    val title: String,
    val url: String,
    val snippet: String = ""
) {
    /** 展示域名（如 bing.com），便于辨认来源站点。 */
    val domain: String get() = runCatching { java.net.URI(url).host }.getOrDefault("")
}

data class ChatMessage(
    val id: String = UUID.randomUUID().toString(),
    val role: Role,
    val content: String,
    val createdAt: Long = System.currentTimeMillis(),
    val error: Boolean = false,
    /** 旧版本 JSON 无此字段，Gson 反序列化后为 null，使用处一律 orEmpty() */
    val attachments: List<Attachment>? = null,
    /** 调用统计；旧版本 JSON 无此字段时为 null */
    val callMeta: CallMeta? = null,
    /** 生成进度 0-100；图片/视频生成中实时更新，完成后置 null */
    val progress: Int? = null,
    /** 模型思考过程（reasoning 流式）实时展示；不入模型上下文，旧 JSON 无此字段时为空 */
    val reasoning: String = "",
    /** 联网搜索资料；旧版本 JSON 无此字段时为 null */
    val references: List<SearchReference>? = null,
    /** 联网搜索状态（「正在全网搜索…」「已找到 N 篇相关内容」）；仅供 UI 展示，不进入模型上下文 */
    val searchMeta: String? = null
)

/** 附件预览：图片附件可以没有文字说明。 */
data class PickedAttachment(
    val uri: String,
    val name: String,
    val mimeType: String
)

/** 用户配置的模型：可独立指定 baseUrl / apiKey，兼容任意 OpenAI 风格服务。 */
/** 模型是否可能支持图片/视频理解（用于实时视觉智能调度）。 */
fun looksVisionCapable(modelId: String): Boolean {
    val id = modelId.lowercase()
    // agnes 系聊天模型（flash/pro 等）实测支持 image_url 视觉输入；图像/视频生成模型除外
    if (id.contains("agnes")) return !id.contains("image") && !id.contains("video")
    return listOf(
        "4o", "4.1", "gpt-4", "gpt-5", "gemini", "qwen", "-vl", "vision",
        "internvl", "minicpm", "glm-4v", "llava", "claude", "molmo",
        "pixtral", "phi-4-vision", "llama-3.2-vision", "gemma-3"
    ).any { id.contains(it) }
}

/** 实时视觉智能调度：优先当前模型 → 已保存的视觉模型 → 按 baseUrl 猜测同服务视觉模型。 */
fun pickVisionModel(models: List<AiModel>, active: AiModel?): AiModel? {
    if (active != null && looksVisionCapable(active.modelId)) return active
    models.firstOrNull { looksVisionCapable(it.modelId) }?.let { return it }
    val base = active ?: return null
    val guess = when {
        base.baseUrl.contains("agnes") -> "agnes-2.5-pro"
        base.baseUrl.contains("gemini") -> "gemini-2.0-flash"
        base.baseUrl.contains("openai") -> "gpt-4o-mini"
        base.baseUrl.contains("dashscope") || base.baseUrl.contains("aliyun") -> "qwen-vl-plus"
        else -> null
    } ?: return null
    return base.copy(id = base.id + "-vision", modelId = guess, name = guess)
}

data class AiModel(
    val id: String = UUID.randomUUID().toString(),
    val modelId: String,
    val name: String,
    val baseUrl: String,
    val apiKey: String,
    val isDefault: Boolean = false
)

data class ConversationMeta(
    val id: String,
    val title: String,
    val updatedAt: Long,
    val messageCount: Int = 0,
    val modelId: String = "",
    /** 置顶标记；旧 index.json 无此字段时为 null，判断一律用 == true */
    val pinned: Boolean? = false
)

data class Conversation(
    val id: String,
    val title: String,
    val messages: List<ChatMessage>,
    val createdAt: Long,
    val updatedAt: Long,
    val modelId: String
)

data class ServerModelInfo(
    @SerializedName("id") val id: String,
    @SerializedName("owned_by") val ownedBy: String = "",
    @SerializedName("created") val createdAt: Long = 0
)
/** 已保存的 API 配置（Base URL + Key），用于多 API 叠加加载模型。 */
data class ApiConfig(
    val id: String = UUID.randomUUID().toString(),
    val name: String,
    val baseUrl: String,
    val apiKey: String
)

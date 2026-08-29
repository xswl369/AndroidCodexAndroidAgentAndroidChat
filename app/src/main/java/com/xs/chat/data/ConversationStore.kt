package com.xs.chat.data

import android.content.Context
import com.google.gson.Gson
import com.google.gson.reflect.TypeToken
import java.io.File
import java.util.concurrent.Executors

/**
 * 会话存储：每个会话一个 JSON 文件，索引单独维护，避免大文本常驻内存。
 */
class ConversationStore(context: Context) {
    private val gson = Gson()
    private val dir = File(context.filesDir, "xs_chat")
    private val indexFile = File(dir, "index.json")
    private val io = Executors.newSingleThreadExecutor()

    init {
        dir.mkdirs()
    }

    fun listMeta(): List<ConversationMeta> {
        // 与写入操作共用同一单线程执行器，确保读到最新索引
        val future = io.submit<List<ConversationMeta>> {
            val idx = readIndex()
            idx.sortedWith(
                compareByDescending<ConversationMeta> { it.pinned == true }
                    .thenByDescending { it.updatedAt }
            )
        }
        return runCatching { future.get() }.getOrDefault(emptyList())
    }

    /** 批量置顶/取消置顶。 */
    fun setPinned(ids: List<String>, pinned: Boolean) {
        if (ids.isEmpty()) return
        io.execute {
            runCatching {
                val idx = readIndex().toMutableList()
                for (i in idx.indices) {
                    if (idx[i].id in ids) idx[i] = idx[i].copy(pinned = pinned)
                }
                writeIndex(idx)
            }
        }
    }

    /** 批量删除会话。 */
    fun deleteMany(ids: List<String>) {
        if (ids.isEmpty()) return
        io.execute {
            runCatching {
                ids.forEach { File(dir, it + ".json").delete() }
                writeIndex(readIndex().filterNot { it.id in ids })
            }
        }
    }

    fun save(conversation: Conversation) {
        io.execute {
            runCatching {
                val file = File(dir, conversation.id + ".json")
                file.writeText(gson.toJson(conversation))
                val idx = readIndex().toMutableList()
                val meta = ConversationMeta(
                    id = conversation.id,
                    title = conversation.title,
                    updatedAt = conversation.updatedAt,
                    messageCount = conversation.messages.size,
                    modelId = conversation.modelId,
                    pinned = idx.firstOrNull { it.id == conversation.id }?.pinned ?: false
                )
                val i = idx.indexOfFirst { it.id == conversation.id }
                if (i >= 0) idx[i] = meta else idx.add(meta)
                // 容量上限：仅保留最近 100 个会话
                val sorted = idx.sortedWith(
                    compareByDescending<ConversationMeta> { it.pinned == true }
                        .thenByDescending { it.updatedAt }
                )
                sorted.drop(MAX_CONVERSATIONS).forEach { File(dir, it.id + ".json").delete() }
                writeIndex(sorted.take(MAX_CONVERSATIONS))
            }
        }
    }

    fun load(id: String): Conversation? {
        val file = File(dir, id + ".json")
        if (!file.exists()) return null
        return try {
            gson.fromJson(file.readText(), Conversation::class.java)
        } catch (e: Exception) {
            null
        }
    }

    fun delete(id: String) {
        io.execute {
            runCatching {
                File(dir, id + ".json").delete()
                writeIndex(readIndex().filterNot { it.id == id })
            }
        }
    }

    private fun writeIndex(idx: List<ConversationMeta>) {
        indexFile.writeText(gson.toJson(idx))
    }

    @Synchronized
    private fun readIndex(): List<ConversationMeta> {
        if (!indexFile.exists()) return emptyList()
        return try {
            gson.fromJson(indexFile.readText(), object : TypeToken<List<ConversationMeta>>() {}.type)
        } catch (e: Exception) {
            emptyList()
        }
    }

    private companion object {
        const val MAX_CONVERSATIONS = 100
    }
}




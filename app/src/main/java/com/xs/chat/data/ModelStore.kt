package com.xs.chat.data

import android.content.Context
import java.util.UUID
import com.google.gson.Gson
import com.google.gson.reflect.TypeToken

class ModelStore(context: Context) {
    private val prefs = context.getSharedPreferences("xs_models", Context.MODE_PRIVATE)
    private val gson = Gson()

    @Synchronized
    fun getAll(): List<AiModel> {
        val json = prefs.getString(KEY_MODELS, null) ?: return emptyList()
        return try {
            gson.fromJson(json, object : TypeToken<List<AiModel>>() {}.type)
        } catch (e: Exception) {
            emptyList()
        }
    }

    @Synchronized
    fun saveAll(models: List<AiModel>) {
        prefs.edit().putString(KEY_MODELS, gson.toJson(models)).apply()
    }

    fun upsert(model: AiModel) {
        val m = if (model.id.isBlank()) model.copy(id = UUID.randomUUID().toString()) else model
        val list = getAll().toMutableList()
        val idx = list.indexOfFirst { it.id == m.id }
        if (idx >= 0) list[idx] = m else list.add(m)
        saveAll(list)
    }

    fun delete(id: String) {
        saveAll(getAll().filterNot { it.id == id })
    }

    fun setDefault(id: String) {
        saveAll(getAll().map { it.copy(isDefault = it.id == id) })
    }

    /** 默认模型优先，否则取第一个。 */
    fun activeModel(): AiModel? {
        val list = getAll()
        if (list.isEmpty()) return null
        return list.firstOrNull { it.isDefault } ?: list.first()
    }

    private companion object {
        const val KEY_MODELS = "models"
    }
}


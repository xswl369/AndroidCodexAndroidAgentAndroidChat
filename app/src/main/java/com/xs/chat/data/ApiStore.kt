package com.xs.chat.data

import android.content.Context
import android.content.SharedPreferences
import com.google.gson.Gson
import com.google.gson.reflect.TypeToken

/** 已保存 API 配置的持久化存储（SharedPreferences + JSON）。 */
class ApiStore(context: Context) {
    private val sp: SharedPreferences = context.getSharedPreferences("xs_apis", Context.MODE_PRIVATE)
    private val gson = Gson()

    @Synchronized
    fun getAll(): List<ApiConfig> {
        val json = sp.getString(KEY_APIS, null) ?: return emptyList()
        return try {
            gson.fromJson(json, object : TypeToken<List<ApiConfig>>() {}.type)
        } catch (e: Exception) {
            emptyList()
        }
    }

    @Synchronized
    fun save(config: ApiConfig) {
        val list = getAll().toMutableList()
        val idx = list.indexOfFirst { it.id == config.id }
        if (idx >= 0) list[idx] = config else list.add(config)
        put(list)
    }

    @Synchronized
    fun delete(id: String) {
        put(getAll().filterNot { it.id == id })
    }

    private fun put(list: List<ApiConfig>) {
        sp.edit().putString(KEY_APIS, gson.toJson(list)).apply()
    }

    private companion object {
        const val KEY_APIS = "api_configs"
    }
}

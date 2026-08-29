package com.xs.chat.data

import android.content.Context
import android.content.SharedPreferences
import com.google.gson.Gson
import com.google.gson.reflect.TypeToken

/** 通话角色存储：内置预设 + 用户自定义（SharedPreferences + JSON）。 */
class CallRoleStore(context: Context) {
    private val sp: SharedPreferences = context.getSharedPreferences("xs_call_roles", Context.MODE_PRIVATE)
    private val gson = Gson()

    fun getAll(): List<CallRole> {
        // 旧数据缺少 speed 字段时 Gson 会给 0.0f，统一规整为默认 1.0x
        val saved = runCatching {
            gson.fromJson<List<CallRole>>(sp.getString(KEY_ROLES, null), object : TypeToken<List<CallRole>>() {}.type)
        }.getOrNull().orEmpty()
            .map { if (it.speed <= 0f || it.speed > 2f) it.copy(speed = 1.0f) else it }
        // 用户修改过语速等设置的预设角色：用保存的副本覆盖同名预设
        val presetIds = CallRolePresets.all.map { it.id }.toSet()
        val overridden = CallRolePresets.all.map { p -> saved.firstOrNull { it.id == p.id } ?: p }
        return overridden + saved.filter { it.id !in presetIds }
    }

    fun find(id: String): CallRole? = getAll().firstOrNull { it.id == id }

    @Synchronized
    fun save(role: CallRole) {
        val list = runCatching {
            gson.fromJson<List<CallRole>>(sp.getString(KEY_ROLES, null), object : TypeToken<List<CallRole>>() {}.type)
        }.getOrNull().orEmpty().toMutableList()
        val idx = list.indexOfFirst { it.id == role.id }
        if (idx >= 0) list[idx] = role else list.add(role)
        sp.edit().putString(KEY_ROLES, gson.toJson(list)).apply()
    }

    @Synchronized
    fun delete(id: String) {
        val list = runCatching {
            gson.fromJson<List<CallRole>>(sp.getString(KEY_ROLES, null), object : TypeToken<List<CallRole>>() {}.type)
        }.getOrNull().orEmpty().filterNot { it.id == id }
        sp.edit().putString(KEY_ROLES, gson.toJson(list)).apply()
    }

    private companion object {
        const val KEY_ROLES = "custom_call_roles"
    }
}

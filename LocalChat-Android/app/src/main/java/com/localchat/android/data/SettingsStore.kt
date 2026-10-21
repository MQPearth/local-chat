package com.localchat.android.data

import android.content.Context
import kotlinx.serialization.json.Json
import java.io.File

class SettingsStore(context: Context) {
    private val file = File(context.filesDir, "settings.json")
    private val json = Json {
        ignoreUnknownKeys = true
        prettyPrint = true
        encodeDefaults = true
    }

    fun load(): AppSettings = try {
        if (file.exists()) json.decodeFromString(AppSettings.serializer(), file.readText())
        else Defaults.create()
    } catch (_: Exception) {
        // 损坏的配置文件按首次启动处理
        Defaults.create()
    }

    fun save(settings: AppSettings) {
        try {
            file.parentFile?.mkdirs()
            val tmp = File(file.path + ".tmp")
            tmp.writeText(json.encodeToString(AppSettings.serializer(), settings))
            if (file.exists()) file.delete()
            tmp.renameTo(file)
        } catch (_: Exception) {
        }
    }
}

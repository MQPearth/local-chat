package com.localchat.android.ui

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.setValue
import com.localchat.android.data.ApiFormat
import com.localchat.android.data.AppSettings
import com.localchat.android.data.ModelConfig
import com.localchat.android.data.ProviderConfig

/** 设置页模型行 UI 状态包装:revision 驱动刷新,数据写入原配置对象。 */
class ModelRow(val data: ModelConfig) {
    var revision by mutableIntStateOf(0)

    val id: String get() { revision; return data.id }
    val contextWindow: Int get() { revision; return data.contextWindow }
    val enabled: Boolean get() { revision; return data.enabled }

    fun refresh() {
        revision++
    }
}

/** 设置页服务商卡片 UI 状态包装。 */
class ProviderRow(val data: ProviderConfig) {
    var revision by mutableIntStateOf(0)
    val models = mutableStateListOf<ModelRow>()

    init {
        data.models.forEach { models.add(ModelRow(it)) }
    }

    val name: String get() { revision; return data.name }
    val enabled: Boolean get() { revision; return data.enabled }
    val baseUrl: String get() { revision; return data.baseUrl }
    val apiKey: String get() { revision; return data.apiKey }
    val formatIndex: Int get() { revision; return data.format.ordinal }

    fun setName(v: String) { data.name = v; revision++ }
    fun setEnabled(v: Boolean) { data.enabled = v; revision++ }
    fun setBaseUrl(v: String) { data.baseUrl = v; revision++ }
    fun setApiKey(v: String) { data.apiKey = v; revision++ }
    fun setFormat(v: ApiFormat) { data.format = v; revision++ }

    fun addModel(m: ModelConfig) {
        data.models.add(m)
        models.add(ModelRow(m))
    }

    fun removeModel(row: ModelRow) {
        data.models.remove(row.data)
        models.remove(row)
    }
}

/** 设置页整体状态:增删服务商时同步底层设置对象。 */
class ProviderSettingsState(val settings: AppSettings) {
    var revision by mutableIntStateOf(0)
    val providers = mutableStateListOf<ProviderRow>()

    init {
        settings.providers.forEach { providers.add(ProviderRow(it)) }
    }

    fun addProvider(): ProviderRow {
        val cfg = ProviderConfig(name = "服务商 ${providers.size + 1}", format = ApiFormat.OPENAI, baseUrl = "")
        settings.providers.add(cfg)
        val row = ProviderRow(cfg)
        providers.add(row)
        revision++
        return row
    }

    fun removeProvider(row: ProviderRow) {
        settings.providers.remove(row.data)
        providers.remove(row)
        revision++
    }
}

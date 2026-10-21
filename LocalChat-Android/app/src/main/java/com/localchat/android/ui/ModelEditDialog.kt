package com.localchat.android.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.scale
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.localchat.android.data.ApiFormat
import com.localchat.android.data.ModelConfig
import com.localchat.android.data.ProviderConfig
import com.localchat.android.network.LlmClient
import kotlinx.coroutines.delay

/** 模型新增/编辑对话框;orig 为 null 时新增。保存时把工作副本交给 onSave。 */
@Composable
fun ModelEditDialog(
    provider: ProviderConfig,
    orig: ModelConfig?,
    onDismiss: () -> Unit,
    onSave: (ModelConfig) -> Unit
) {
    val work = remember { orig?.copy() ?: ModelConfig() }
    var modelId by remember { mutableStateOf(work.id) }
    var ctx by remember { mutableStateOf(work.contextWindow.toString()) }
    var maxOut by remember { mutableStateOf(work.maxOutputTokens.toString()) }
    var temp by remember { mutableStateOf(fmtNum(work.temperature)) }
    var topP by remember { mutableStateOf(fmtNum(work.topP)) }
    var sys by remember { mutableStateOf(work.systemPrompt) }
    var enabled by remember { mutableStateOf(work.enabled) }
    var smart by remember { mutableStateOf(false) }
    var hintText by remember { mutableStateOf("") }
    var hintIsError by remember { mutableStateOf(false) }
    fun hint(text: String) { hintText = text; hintIsError = false }
    fun error(text: String) { hintText = text; hintIsError = true }
    // 智能获取:开关打开且模型 ID 变化后,自动拉取 Ollama 上下文长度填充
    LaunchedEffect(smart, modelId) {
        if (!smart) return@LaunchedEffect
        val id = modelId.trim()
        if (id.isEmpty()) {
            hint("填写模型 ID 后可自动获取")
            return@LaunchedEffect
        }
        hint("正在获取模型信息…")
        delay(300)
        try {
            val c = LlmClient.fetchContextLength(provider, id)
            if (c != null && c > 0) {
                ctx = c.toString()
                maxOut = (c / 4).coerceIn(256, 8192).toString()
                hint("已自动填充:上下文 $c tokens")
            } else {
                hint(
                    if (provider.format == ApiFormat.OLLAMA) "未能自动获取,请手动填写"
                    else "当前 API 格式暂不支持自动获取,请手动填写"
                )
            }
        } catch (e: Exception) {
            error("自动获取失败:" + e.message)
        }
    }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(if (orig == null) "添加模型" else "编辑模型配置") },
        text = {
            Column(
                Modifier.verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                OutlinedTextField(
                    value = modelId, onValueChange = { modelId = it }, singleLine = true,
                    label = { Text("模型 ID") }, modifier = Modifier.fillMaxWidth()
                )
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Switch(checked = smart, onCheckedChange = { smart = it }, modifier = Modifier.scale(0.7f))
                    Text("自动获取上下文(仅 Ollama)", fontSize = 12.sp, color = Color(0xFF6B7280), modifier = Modifier.padding(start = 4.dp))
                }
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedTextField(
                        value = ctx, onValueChange = { ctx = it }, singleLine = true,
                        label = { Text("上下文窗口") }, modifier = Modifier.weight(1f)
                    )
                    OutlinedTextField(
                        value = maxOut, onValueChange = { maxOut = it }, singleLine = true,
                        label = { Text("最大输出") }, modifier = Modifier.weight(1f)
                    )
                }
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedTextField(
                        value = temp, onValueChange = { temp = it }, singleLine = true,
                        label = { Text("温度") }, modifier = Modifier.weight(1f)
                    )
                    OutlinedTextField(
                        value = topP, onValueChange = { topP = it }, singleLine = true,
                        label = { Text("Top P") }, modifier = Modifier.weight(1f)
                    )
                }
                OutlinedTextField(
                    value = sys, onValueChange = { sys = it },
                    label = { Text("系统提示词(可选)") }, maxLines = 3,
                    textStyle = TextStyle(fontSize = 13.5.sp),
                    modifier = Modifier.fillMaxWidth()
                )
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Switch(checked = enabled, onCheckedChange = { enabled = it }, modifier = Modifier.scale(0.7f))
                    Text("启用", fontSize = 12.sp, color = Color(0xFF6B7280), modifier = Modifier.padding(start = 4.dp))
                }
                if (hintText.isNotEmpty()) {
                    Text(
                        hintText, fontSize = 12.sp,
                        color = if (hintIsError) Color(0xFFD5423F) else Color(0xFF8A8F98),
                        fontWeight = if (hintIsError) FontWeight.Medium else FontWeight.Normal
                    )
                }
            }
        },
        confirmButton = {
            TextButton(onClick = {
                val id = modelId.trim()
                if (id.isEmpty()) {
                    error("请填写模型 ID")
                    return@TextButton
                }
                work.id = id
                work.contextWindow = parseIntWithK(ctx, 8192, 256, 1_000_000)
                work.maxOutputTokens = parseIntWithK(maxOut, 2048, 16, 1_000_000)
                work.temperature = parseDouble(temp, 0.7, 0.0, 2.0)
                work.topP = parseDouble(topP, 0.9, 0.01, 1.0)
                work.systemPrompt = sys
                work.enabled = enabled
                onSave(work)
            }) { Text("保存") }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("取消") } }
    )
}

/** 支持以 k/K 结尾的千位写法(8k → 8192);非法输入返回默认值。 */
private fun parseIntWithK(s0: String, def: Int, min: Int, max: Int): Int {
    val hasK = s0.trim().endsWith("k", ignoreCase = true)
    val s = s0.trim().trimEnd('k', 'K')
    val v = s.toIntOrNull() ?: return def
    if (v < min) return def
    val scaled = if (hasK) v * 1024 else v
    return scaled.coerceAtMost(max)
}

private fun parseDouble(s: String, def: Double, min: Double, max: Double): Double {
    val v = s.trim().toDoubleOrNull() ?: return def
    return if (v in min..max) v else def
}

private fun fmtNum(d: Double): String =
    if (d == d.toInt().toDouble()) d.toInt().toString()
    else "%.2f".format(d).trimEnd('0').trimEnd('.')

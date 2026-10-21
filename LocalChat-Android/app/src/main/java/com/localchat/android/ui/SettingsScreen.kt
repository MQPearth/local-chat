package com.localchat.android.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.ArrowDropDown
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Visibility
import androidx.compose.material.icons.filled.VisibilityOff
import androidx.compose.material.icons.outlined.Delete
import androidx.compose.material.icons.outlined.Edit
import androidx.compose.material.icons.outlined.Schedule
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Slider
import androidx.compose.material3.Switch
import androidx.compose.material3.Tab
import androidx.compose.material3.TabRow
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.scale
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.localchat.android.data.ApiFormat
import com.localchat.android.data.ApiFormatNames
import com.localchat.android.data.Defaults
import com.localchat.android.data.ModelConfig
import com.localchat.android.data.copyTo
import com.localchat.android.network.LlmClient
import kotlinx.coroutines.launch
import java.util.Locale

private val CardBg = Color.White
private val PageBg = Color(0xFFF7F8FA)
private val CardBorder = Color(0xFFE7E9EC)
private val RowBorder = Color(0xFFEDEFF2)
private val LabelGray = Color(0xFF6B7280)
private val BadgeBg = Color(0xFFF1F2F5)
private val BadgeText = Color(0xFF6B7280)
private val DisabledText = Color(0xFFB0B4BA)

private sealed class SettingsDialog {
    data class DeleteProvider(val row: ProviderRow) : SettingsDialog()
    data class DeleteModel(val provider: ProviderRow, val model: ModelRow) : SettingsDialog()
    data class ModelEdit(val provider: ProviderRow, val model: ModelRow?) : SettingsDialog()
    data class Notice(val title: String, val text: String) : SettingsDialog()
}

/** 设置页:页签切换「显示设置 / 模型服务」,默认显示字体设置(与桌面版设置窗口一致)。 */
@Composable
fun SettingsScreen(vm: MainViewModel, onBack: () -> Unit) {
    var tab by rememberSaveable { mutableIntStateOf(0) } // 0=显示设置(默认) 1=模型服务
    // 字号在 FontSettings 里即时生效,返回聊天页时统一同步回设置并落盘
    DisposableEffect(Unit) {
        onDispose { vm.onSettingsClosed() }
    }
    Column(Modifier.fillMaxSize().background(PageBg).windowInsetsPadding(WindowInsets.safeDrawing)) {
        Row(Modifier.fillMaxWidth().background(Color.White), verticalAlignment = Alignment.CenterVertically) {
            IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, "返回") }
            Text("设置", fontSize = uiFont(15), fontWeight = FontWeight.SemiBold, modifier = Modifier.weight(1f))
        }
        HorizontalDivider(thickness = 0.5.dp, color = CardBorder)
        TabRow(selectedTabIndex = tab, containerColor = Color.White) {
            Tab(selected = tab == 0, onClick = { tab = 0 }, text = { Text("显示设置", fontSize = uiFont(13)) })
            Tab(selected = tab == 1, onClick = { tab = 1 }, text = { Text("模型服务", fontSize = uiFont(13)) })
        }
        Box(Modifier.weight(1f)) {
            if (tab == 0) DisplaySettingsContent() else ModelSettingsContent(vm)
        }
    }
}

/** 显示设置页:界面/聊天区两个字号滑块 + 预览卡片,修改即时生效,返回时统一保存。 */
@Composable
private fun DisplaySettingsContent() {
    Column(Modifier.fillMaxSize()) {
        Column(Modifier.fillMaxWidth().background(Color.White).padding(horizontal = 18.dp, vertical = 12.dp)) {
            Text("显示设置", fontSize = uiFont(14.5), fontWeight = FontWeight.SemiBold)
            Text(
                "调整界面与聊天区的文字大小,修改即时生效",
                fontSize = uiFont(12.5), color = Color(0xFF8A8F98), modifier = Modifier.padding(top = 3.dp)
            )
        }
        HorizontalDivider(thickness = 0.5.dp, color = CardBorder)
        Column(
            Modifier.weight(1f).verticalScroll(rememberScrollState())
                .padding(horizontal = 16.dp, vertical = 14.dp)
        ) {
            FontSliderCard(
                "界面字体大小", "侧边栏、顶栏与设置页等界面文字",
                11.0f..20.0f, FontSettings.uiFontSize
            ) { FontSettings.apply(it, FontSettings.chatFontSize) }
            Spacer(Modifier.height(14.dp))
            FontSliderCard(
                "聊天区字体大小", "聊天气泡、Markdown 正文与代码块",
                11.0f..22.0f, FontSettings.chatFontSize
            ) { FontSettings.apply(FontSettings.uiFontSize, it) }
            Spacer(Modifier.height(14.dp))
            Column(
                Modifier.fillMaxWidth().clip(RoundedCornerShape(10.dp))
                    .background(CardBg).border(1.dp, CardBorder, RoundedCornerShape(10.dp))
                    .padding(horizontal = 14.dp, vertical = 11.dp)
            ) {
                Text(
                    "助手回复示例:支持标题、列表与代码块等 Markdown 元素。",
                    fontSize = chatFont(13.5), lineHeight = chatFont(20), color = Color(0xFF1F2328)
                )
                Text(
                    "思考与次要信息的示例文字",
                    fontSize = chatFont(12.5), color = Color(0xFF8A8F98), modifier = Modifier.padding(top = 3.dp)
                )
            }
            TextButton(onClick = { FontSettings.apply(Defaults.UiFontSize, Defaults.ChatFontSize) }) {
                Text("恢复默认", fontSize = uiFont(12.5))
            }
        }
    }
}

/** 字号滑块卡片:标题 + 当前值 + 滑块(0.5 步进,与桌面版一致)+ 说明。 */
@Composable
private fun FontSliderCard(
    title: String,
    desc: String,
    range: ClosedFloatingPointRange<Float>,
    value: Double,
    onChange: (Double) -> Unit
) {
    Column(
        Modifier.fillMaxWidth().clip(RoundedCornerShape(12.dp))
            .background(CardBg).border(1.dp, CardBorder, RoundedCornerShape(12.dp))
            .padding(16.dp, 14.dp, 16.dp, 6.dp)
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(title, fontSize = uiFont(14), fontWeight = FontWeight.Medium, modifier = Modifier.weight(1f))
            Text(fmtSize(value), fontSize = uiFont(13.5), color = LabelGray)
        }
        Slider(
            value = value.toFloat(),
            onValueChange = { onChange(it.toDouble()) },
            valueRange = range,
            steps = ((range.endInclusive - range.start) / 0.5f).toInt() - 1
        )
        Text(desc, fontSize = uiFont(12), color = LabelGray, modifier = Modifier.padding(bottom = 8.dp))
    }
}

/** 字号值显示:13.0 → "13",13.5 → "13.5"(与桌面版一致)。 */
private fun fmtSize(v: Double): String =
    String.format(Locale.US, "%.1f", v).trimEnd('0').trimEnd('.')

/** 模型服务页:服务商与模型配置(由原设置页整体迁入)。 */
@Composable
private fun ModelSettingsContent(vm: MainViewModel) {
    val state = remember { ProviderSettingsState(vm.settings) }
    val scope = rememberCoroutineScope()
    var dialog by remember { mutableStateOf<SettingsDialog?>(null) }
    Column(Modifier.fillMaxSize()) {
        Row(
            Modifier.fillMaxWidth().background(Color.White).padding(start = 18.dp, end = 8.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text("管理大模型服务商、接口地址与模型参数", fontSize = uiFont(12.5), color = Color(0xFF8A8F98), modifier = Modifier.weight(1f))
            TextButton(onClick = { state.addProvider(); vm.save() }) {
                Icon(Icons.Filled.Add, null, Modifier.size(15.dp))
                Spacer(Modifier.width(4.dp))
                Text("添加服务商", fontSize = uiFont(12.5))
            }
        }
        HorizontalDivider(thickness = 0.5.dp, color = CardBorder)
        LazyColumn(
            Modifier.weight(1f).fillMaxWidth(),
            contentPadding = PaddingValues(16.dp, 14.dp, 16.dp, 8.dp),
            verticalArrangement = Arrangement.spacedBy(14.dp)
        ) {
            items(state.providers, key = { it.data.id }) { row ->
                ProviderCard(vm, row, scope) { dialog = it }
            }
        }
        HorizontalDivider(thickness = 0.5.dp, color = CardBorder)
        Text(
            "修改即时生效,返回聊天页自动保存",
            fontSize = uiFont(11.5), color = Color(0xFF9AA0A6),
            modifier = Modifier.fillMaxWidth().padding(horizontal = 18.dp, vertical = 10.dp)
        )
    }
    when (val d = dialog) {
        is SettingsDialog.DeleteProvider -> ConfirmDialog(
            "删除服务商", "确定删除服务商「${d.row.name}」及其全部模型配置吗?", "删除",
            onConfirm = { state.removeProvider(d.row); vm.save() }, onDismiss = { dialog = null }
        )
        is SettingsDialog.DeleteModel -> ConfirmDialog(
            "删除模型", "确定删除模型「${d.model.id}」吗?", "删除",
            onConfirm = { d.provider.removeModel(d.model); vm.save() }, onDismiss = { dialog = null }
        )
        is SettingsDialog.ModelEdit -> ModelEditDialog(
            provider = d.provider.data,
            orig = d.model?.data,
            onDismiss = { dialog = null },
            onSave = { work ->
                val target = d.model?.data
                if (target != null) {
                    work.copyTo(target)
                    d.model.refresh()
                } else {
                    d.provider.addModel(work)
                }
                vm.save()
                dialog = null
            }
        )
        is SettingsDialog.Notice -> MessageDialog(d.title, d.text, onDismiss = { dialog = null })
        null -> { }
    }
}

@Composable
private fun ProviderCard(vm: MainViewModel, row: ProviderRow, scope: kotlinx.coroutines.CoroutineScope, openDialog: (SettingsDialog) -> Unit) {
    var menuOpen by remember { mutableStateOf(false) }
    var keyHidden by remember { mutableStateOf(true) }
    var formatMenuOpen by remember { mutableStateOf(false) }
    Column(
        Modifier.fillMaxWidth().clip(RoundedCornerShape(12.dp))
            .background(CardBg).border(1.dp, CardBorder, RoundedCornerShape(12.dp))
            .padding(16.dp, 14.dp)
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text("📦", fontSize = 15.sp)
            BasicTextField(
                value = row.name,
                onValueChange = { row.setName(it) },
                singleLine = true,
                textStyle = TextStyle(fontSize = uiFont(14), fontWeight = FontWeight.Medium, color = Color(0xFF1F2328)),
                modifier = Modifier.weight(1f).padding(start = 8.dp)
            )
            Switch(checked = row.enabled, onCheckedChange = { row.setEnabled(it) }, modifier = Modifier.scale(0.75f))
            Box {
                IconButton(onClick = { menuOpen = true }, modifier = Modifier.size(30.dp)) {
                    Icon(Icons.Filled.MoreVert, "更多操作", Modifier.size(16.dp))
                }
                DropdownMenu(expanded = menuOpen, onDismissRequest = { menuOpen = false }) {
                    DropdownMenuItem(text = { Text("删除服务商") }, onClick = { menuOpen = false; openDialog(SettingsDialog.DeleteProvider(row)) })
                }
            }
        }
        FieldLabel("Base URL")
        SettingsTextField(row.baseUrl) { row.setBaseUrl(it) }
        FieldLabel("API 格式")
        Box {
            Row(
                Modifier.fillMaxWidth().clip(RoundedCornerShape(8.dp))
                    .border(1.dp, Color(0xFFD8DBE0), RoundedCornerShape(8.dp))
                    .clickable { formatMenuOpen = true }
                    .padding(horizontal = 12.dp, vertical = 11.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(ApiFormatNames.all.getOrElse(row.formatIndex) { "" }, fontSize = uiFont(13.5), modifier = Modifier.weight(1f))
                Icon(Icons.Filled.ArrowDropDown, null)
            }
            DropdownMenu(expanded = formatMenuOpen, onDismissRequest = { formatMenuOpen = false }) {
                ApiFormatNames.all.forEachIndexed { i, name ->
                    DropdownMenuItem(
                        text = { Text(name) },
                        onClick = { row.setFormat(ApiFormat.entries[i]); formatMenuOpen = false }
                    )
                }
            }
        }
        FieldLabel("API Key(本地服务通常留空)")
        Row(verticalAlignment = Alignment.CenterVertically) {
            BasicTextField(
                value = row.apiKey,
                onValueChange = { row.setApiKey(it) },
                singleLine = true,
                textStyle = TextStyle(fontSize = uiFont(13.5), color = Color(0xFF1F2328)),
                visualTransformation = if (keyHidden) PasswordVisualTransformation() else VisualTransformation.None,
                modifier = Modifier.weight(1f).clip(RoundedCornerShape(8.dp))
                    .border(1.dp, Color(0xFFD8DBE0), RoundedCornerShape(8.dp))
                    .padding(horizontal = 12.dp, vertical = 11.dp)
            )
            IconButton(onClick = { keyHidden = !keyHidden }, modifier = Modifier.size(34.dp)) {
                Icon(
                    if (keyHidden) Icons.Filled.Visibility else Icons.Filled.VisibilityOff,
                    "显示/隐藏 API Key", Modifier.size(17.dp), tint = LabelGray
                )
            }
        }
        Row(Modifier.padding(top = 14.dp), verticalAlignment = Alignment.CenterVertically) {
            Text("模型列表", fontSize = uiFont(12), color = LabelGray)
            var fetching by remember { mutableStateOf(false) }
            IconButton(
                onClick = {
                    if (fetching) return@IconButton
                    fetching = true
                    scope.launch {
                        try {
                            val ids = LlmClient.getModelIds(row.data)
                            val existing = row.data.models.map { it.id }.toHashSet()
                            var added = 0
                            for (id in ids) {
                                if (!existing.add(id)) continue
                                val mc = ModelConfig(id = id)
                                if (row.data.format == ApiFormat.OLLAMA) {
                                    try {
                                        val ctx = LlmClient.fetchContextLength(row.data, id)
                                        if (ctx != null && ctx > 0) {
                                            mc.contextWindow = ctx.coerceAtMost(Int.MAX_VALUE.toLong()).toInt()
                                            mc.maxOutputTokens = (mc.contextWindow / 4).coerceIn(256, 8192)
                                        }
                                    } catch (_: Exception) { }
                                }
                                row.addModel(mc)
                                added++
                            }
                            vm.save()
                            openDialog(SettingsDialog.Notice("拉取模型", if (added > 0) "已拉取 $added 个新模型。" else "未发现新模型(服务端共 ${ids.size} 个)。"))
                        } catch (e: Exception) {
                            openDialog(SettingsDialog.Notice("拉取模型", "拉取失败:" + e.message))
                        } finally {
                            fetching = false
                        }
                    }
                },
                modifier = Modifier.size(26.dp)
            ) {
                Icon(Icons.Filled.Refresh, "从服务拉取模型列表", Modifier.size(14.dp), tint = if (fetching) Color(0xFFB0B4BA) else Color(0xFF6B7280))
            }
            Spacer(Modifier.weight(1f))
            TextButton(
                onClick = { openDialog(SettingsDialog.ModelEdit(row, null)) },
                contentPadding = PaddingValues(horizontal = 10.dp, vertical = 4.dp),
                modifier = Modifier.height(30.dp)
            ) {
                Icon(Icons.Filled.Add, null, Modifier.size(13.dp))
                Spacer(Modifier.width(4.dp))
                Text("添加模型", fontSize = uiFont(12))
            }
        }
        row.models.forEach { m -> ModelItem(row, m, scope, openDialog) }
    }
}

@Composable
private fun ModelItem(provider: ProviderRow, model: ModelRow, scope: kotlinx.coroutines.CoroutineScope, openDialog: (SettingsDialog) -> Unit) {
    var testing by remember { mutableStateOf(false) }
    Row(
        Modifier.fillMaxWidth().padding(top = 2.dp)
            .border(0.5.dp, RowBorder).padding(horizontal = 2.dp, vertical = 7.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Row(Modifier.weight(1f), verticalAlignment = Alignment.CenterVertically) {
            Text(model.id, fontSize = uiFont(13), fontWeight = FontWeight.Medium, maxLines = 1, overflow = TextOverflow.Ellipsis)
            Spacer(Modifier.width(8.dp))
            Box(Modifier.clip(RoundedCornerShape(6.dp)).background(BadgeBg).padding(horizontal = 5.dp, vertical = 1.dp)) {
                Text(ctxBadge(model.contextWindow), fontSize = uiFont(10.5), color = BadgeText)
            }
            if (!model.enabled) {
                Spacer(Modifier.width(8.dp))
                Text("已停用", fontSize = uiFont(11), color = DisabledText)
            }
        }
        IconButton(
            onClick = {
                if (testing) return@IconButton
                testing = true
                scope.launch {
                    val msg = LlmClient.test(provider.data, model.data)
                    testing = false
                    openDialog(SettingsDialog.Notice("测试连接", msg))
                }
            },
            modifier = Modifier.size(28.dp)
        ) {
            Icon(Icons.Outlined.Schedule, "测试连接", Modifier.size(15.dp), tint = if (testing) Color(0xFFB0B4BA) else Color(0xFF6B7280))
        }
        IconButton(onClick = { openDialog(SettingsDialog.ModelEdit(provider, model)) }, modifier = Modifier.size(28.dp)) {
            Icon(Icons.Outlined.Edit, "编辑模型", Modifier.size(15.dp), tint = Color(0xFF6B7280))
        }
        IconButton(onClick = { openDialog(SettingsDialog.DeleteModel(provider, model)) }, modifier = Modifier.size(28.dp)) {
            Icon(Icons.Outlined.Delete, "删除模型", Modifier.size(15.dp), tint = Color(0xFF6B7280))
        }
        Switch(checked = model.enabled, onCheckedChange = { model.data.enabled = it; model.refresh() }, modifier = Modifier.padding(start = 4.dp).scale(0.7f))
    }
}

@Composable
private fun FieldLabel(text: String) {
    Text(text, fontSize = uiFont(12), color = LabelGray, modifier = Modifier.padding(top = 12.dp, bottom = 4.dp))
}

@Composable
private fun SettingsTextField(value: String, onValueChange: (String) -> Unit) {
    BasicTextField(
        value = value,
        onValueChange = onValueChange,
        singleLine = true,
        textStyle = TextStyle(fontSize = uiFont(13.5), color = Color(0xFF1F2328)),
        modifier = Modifier.fillMaxWidth().clip(RoundedCornerShape(8.dp))
            .border(1.dp, Color(0xFFD8DBE0), RoundedCornerShape(8.dp))
            .padding(horizontal = 12.dp, vertical = 11.dp)
    )
}

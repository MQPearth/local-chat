package com.localchat.android.ui

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.Send
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.ArrowDropDown
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material.icons.filled.KeyboardArrowUp
import androidx.compose.material.icons.filled.Menu
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.Stop
import androidx.compose.material.icons.outlined.ContentCopy
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.ModalNavigationDrawer
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberDrawerState
import androidx.compose.material3.DrawerValue
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.scale
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.localchat.android.markdown.MarkdownText
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch

private val BgColor = Color(0xFFF7F8FA)
private val CardBorder = Color(0xFFE7E9EC)
private val DividerColor = Color(0xFFECEEF1)
private val MutedText = Color(0xFF9AA0A6)

@Composable
fun ChatScreen(vm: MainViewModel, onOpenSettings: () -> Unit) {
    val drawerState = rememberDrawerState(DrawerValue.Closed)
    val scope = rememberCoroutineScope()
    val context = LocalContext.current
    var renameTarget by remember { mutableStateOf<ConversationUi?>(null) }
    var deleteTarget by remember { mutableStateOf<ConversationUi?>(null) }
    var compactConfirm by remember { mutableStateOf(false) }
    var pendingExport by remember { mutableStateOf<Pair<String, String>?>(null) }
    val exportLauncher = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("text/plain")) { uri ->
        val content = pendingExport?.second
        if (uri != null && content != null) {
            try {
                context.contentResolver.openOutputStream(uri)?.use { it.write(content.toByteArray(Charsets.UTF_8)) }
                Toast.makeText(context, "已导出 HTTP 记录", Toast.LENGTH_LONG).show()
            } catch (e: Exception) {
                Toast.makeText(context, "导出失败:" + e.message, Toast.LENGTH_LONG).show()
            }
        }
        pendingExport = null
    }
    val doExport: (ConversationUi) -> Unit = { conv ->
        vm.exportHttp(conv)?.let { pair ->
            pendingExport = pair
            exportLauncher.launch(pair.first)
        }
    }
    ModalNavigationDrawer(
        drawerState = drawerState,
        drawerContent = { ConversationsDrawer(vm, drawerState, scope, onRename = { renameTarget = it }, onDelete = { deleteTarget = it }, onExport = doExport) }
    ) {
        Column(Modifier.fillMaxSize().background(BgColor).windowInsetsPadding(WindowInsets.safeDrawing)) {
            ChatTopBar(vm, onOpenSettings = onOpenSettings, onMenu = { scope.launch { drawerState.open() } })
            Box(Modifier.weight(1f)) {
                MessageList(vm)
                val conv = vm.selected
                if (conv == null || conv.messages.isEmpty()) EmptyHint()
            }
            InputBar(vm, onCompact = { compactConfirm = true })
        }
    }
    renameTarget?.let { conv ->
        InputDialog("重命名对话", conv.title, onConfirm = { vm.renameConversation(conv, it) }, onDismiss = { renameTarget = null })
    }
    deleteTarget?.let { conv ->
        ConfirmDialog("删除对话", "确定删除对话「${conv.title}」吗?", "删除", onConfirm = { vm.deleteConversation(conv) }, onDismiss = { deleteTarget = null })
    }
    if (compactConfirm) {
        ConfirmDialog(
            "压缩上下文",
            "将把「${vm.selected?.title}」的全部历史压缩为一条摘要,原消息会被替换。继续吗?",
            "压缩", onConfirm = { vm.compact() }, onDismiss = { compactConfirm = false }
        )
    }
}

@Composable
private fun ConversationsDrawer(
    vm: MainViewModel,
    drawerState: androidx.compose.material3.DrawerState,
    scope: CoroutineScope,
    onRename: (ConversationUi) -> Unit,
    onDelete: (ConversationUi) -> Unit,
    onExport: (ConversationUi) -> Unit
) {
    Column(Modifier.fillMaxHeight().width(280.dp).background(Color.White)) {
        Row(Modifier.padding(start = 18.dp, top = 16.dp, bottom = 8.dp), verticalAlignment = Alignment.CenterVertically) {
            Text("💬", fontSize = 17.sp)
            Text(" 本地聊天", fontSize = 15.sp, fontWeight = FontWeight.Bold, modifier = Modifier.padding(start = 4.dp))
        }
        TextButton(
            onClick = { vm.newChat(); scope.launch { drawerState.close() } },
            modifier = Modifier.padding(horizontal = 12.dp, vertical = 2.dp)
        ) {
            Icon(Icons.Filled.Add, null, Modifier.size(16.dp))
            Spacer(Modifier.width(6.dp))
            Text("新建对话", fontSize = 13.sp)
        }
        HorizontalDivider(color = DividerColor)
        LazyColumn(Modifier.weight(1f)) {
            items(vm.conversations, key = { it.data.id }) { conv ->
                ConversationRow(
                    vm, conv, drawerState, scope,
                    onRename = { onRename(conv) },
                    onDelete = { onDelete(conv) },
                    onExport = { onExport(conv) }
                )
            }
        }
        HorizontalDivider(color = DividerColor)
        Text("${vm.conversations.size} 个对话", fontSize = 11.sp, color = MutedText, modifier = Modifier.padding(16.dp))
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun ConversationRow(
    vm: MainViewModel,
    conv: ConversationUi,
    drawerState: androidx.compose.material3.DrawerState,
    scope: CoroutineScope,
    onRename: () -> Unit,
    onDelete: () -> Unit,
    onExport: () -> Unit
) {
    var menuOpen by remember { mutableStateOf(false) }
    Box(
        Modifier.fillMaxWidth().padding(horizontal = 4.dp, vertical = 1.dp)
            .clip(RoundedCornerShape(10.dp))
            .background(if (vm.selected == conv) Color(0xFFF1F2F5) else Color.Transparent)
            .combinedClickable(
                onClick = { vm.select(conv); scope.launch { drawerState.close() } },
                onLongClick = { menuOpen = true }
            )
    ) {
        Column(Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 9.dp)) {
            Text(
                conv.title, fontSize = 13.sp, fontWeight = FontWeight.SemiBold, color = Color(0xFF23272D),
                maxLines = 1, overflow = TextOverflow.Ellipsis
            )
            Row(Modifier.padding(top = 3.dp)) {
                Text(formatTime(conv.updatedAt), fontSize = 11.sp, color = MutedText)
                if (conv.messageCountText.isNotEmpty()) {
                    Spacer(Modifier.width(8.dp))
                    Text(conv.messageCountText, fontSize = 11.sp, color = MutedText)
                }
            }
        }
        DropdownMenu(expanded = menuOpen, onDismissRequest = { menuOpen = false }) {
            DropdownMenuItem(text = { Text("重命名") }, onClick = { menuOpen = false; onRename() })
            DropdownMenuItem(text = { Text("删除对话") }, onClick = { menuOpen = false; onDelete() })
            DropdownMenuItem(text = { Text("导出 HTTP 数据") }, onClick = { menuOpen = false; onExport() })
        }
    }
}

@Composable
private fun ChatTopBar(vm: MainViewModel, onOpenSettings: () -> Unit, onMenu: () -> Unit) {
    var modelMenu by remember { mutableStateOf(false) }
    Column(Modifier.fillMaxWidth().background(Color.White)) {
        Row(
            Modifier.fillMaxWidth().padding(start = 4.dp, end = 8.dp, top = 4.dp, bottom = 4.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            IconButton(onClick = onMenu) { Icon(Icons.Filled.Menu, "会话列表") }
            Column(Modifier.weight(1f)) {
                Text(
                    vm.selected?.title ?: "本地聊天", fontSize = 14.5.sp, fontWeight = FontWeight.SemiBold,
                    maxLines = 1, overflow = TextOverflow.Ellipsis
                )
                val ctx = vm.selectedModel?.model?.contextWindow ?: 0
                Text(
                    "${vm.selectedModel?.toString() ?: "未选择模型"}  ·  上下文 ${if (ctx > 0) ctx.toString() else "-"} tokens",
                    fontSize = 11.sp, color = MutedText, maxLines = 1, overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.padding(top = 2.dp)
                )
            }
            Box {
                Row(
                    Modifier.clip(RoundedCornerShape(8.dp)).clickable { modelMenu = true }.padding(8.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(
                        vm.selectedModel?.toString() ?: "选择模型", fontSize = 12.5.sp, color = Color(0xFF374151),
                        maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.widthIn(max = 140.dp)
                    )
                    Icon(Icons.Filled.ArrowDropDown, null)
                }
                DropdownMenu(expanded = modelMenu, onDismissRequest = { modelMenu = false }) {
                    if (vm.modelOptions.isEmpty()) {
                        DropdownMenuItem(text = { Text("暂无可用模型,请先在设置中添加") }, onClick = { modelMenu = false })
                    }
                    vm.modelOptions.forEach { opt ->
                        DropdownMenuItem(text = { Text(opt.toString()) }, onClick = { vm.selectModel(opt); modelMenu = false })
                    }
                }
            }
            IconButton(onClick = onOpenSettings) { Icon(Icons.Filled.Settings, "模型服务设置") }
        }
        HorizontalDivider(thickness = 0.5.dp, color = DividerColor)
    }
}

@Composable
private fun MessageList(vm: MainViewModel) {
    val conv = vm.selected ?: return
    if (conv.messages.isEmpty()) return
    val listState = rememberLazyListState()
    val count = conv.messages.size
    val lastRevision = conv.messages.lastOrNull()?.revision ?: 0
    val pinned = !listState.canScrollForward // 视图贴底时流式输出自动跟随,用户上滚即暂停跟随
    LaunchedEffect(count, lastRevision) {
        if (pinned && count > 0) listState.animateScrollToItem(count - 1)
    }
    LazyColumn(
        state = listState,
        modifier = Modifier.fillMaxSize(),
        contentPadding = PaddingValues(horizontal = 16.dp, vertical = 12.dp),
        verticalArrangement = Arrangement.spacedBy(4.dp)
    ) {
        items(conv.messages) { msg -> MessageItem(msg) }
    }
}

@Composable
private fun MessageItem(msg: MessageUi) {
    when (msg.role) {
        "user" -> UserMessage(msg)
        "compact" -> CompactMessage(msg)
        else -> AssistantMessage(msg)
    }
}

@Composable
private fun UserMessage(msg: MessageUi) {
    Row(Modifier.fillMaxWidth().padding(vertical = 5.dp), horizontalArrangement = Arrangement.End) {
        Box(
            Modifier.widthIn(max = 300.dp).clip(RoundedCornerShape(14.dp))
                .background(Color(0xFF1C1C1E)).padding(horizontal = 14.dp, vertical = 10.dp)
        ) {
            SelectionContainer {
                Text(msg.content, color = Color(0xFFF5F5F5), fontSize = 13.5.sp, lineHeight = 20.sp)
            }
        }
    }
}

@Composable
private fun AssistantMessage(msg: MessageUi) {
    val context = LocalContext.current
    Row(Modifier.fillMaxWidth().padding(vertical = 5.dp), verticalAlignment = Alignment.Top) {
        Column(
            Modifier.weight(1f, fill = false).widthIn(max = 320.dp)
                .clip(RoundedCornerShape(14.dp))
                .background(Color.White)
                .border(1.dp, CardBorder, RoundedCornerShape(14.dp))
                .padding(horizontal = 14.dp, vertical = 10.dp)
        ) {
            SelectionContainer {
                Column {
                    if (msg.reasoning.isNotEmpty()) ThinkingBlock(msg)
                    MarkdownText(msg.content, msg.isError)
                }
            }
            if (msg.showWaiting) {
                Text("正在生成…", fontSize = 12.5.sp, color = MutedText, modifier = Modifier.padding(top = 2.dp))
            }
        }
        IconButton(
            onClick = {
                val text = if (msg.content.isNotEmpty()) msg.content else msg.reasoning
                if (text.isNotEmpty()) {
                    val clip = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
                    clip.setPrimaryClip(ClipData.newPlainText("msg", text))
                    Toast.makeText(context, "已复制回复", Toast.LENGTH_SHORT).show()
                }
            },
            modifier = Modifier.size(30.dp).padding(top = 8.dp)
        ) {
            Icon(Icons.Outlined.ContentCopy, "复制回复", Modifier.size(14.dp), tint = Color(0xFF8A8F98))
        }
    }
}

@Composable
private fun ThinkingBlock(msg: MessageUi) {
    Column(Modifier.padding(bottom = 6.dp)) {
        Row(
            Modifier.clip(RoundedCornerShape(6.dp)).clickable {
                msg.thinkingTouched = true
                msg.thinkingOpen = !msg.thinkingOpen
            }.padding(vertical = 2.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text("💭", fontSize = 12.sp)
            Text(msg.thinkingLabel, fontSize = 12.sp, color = Color(0xFF8A8F98), modifier = Modifier.padding(start = 6.dp))
            Icon(
                if (msg.thinkingOpen) Icons.Filled.KeyboardArrowUp else Icons.Filled.KeyboardArrowDown,
                null, Modifier.size(14.dp), tint = Color(0xFF8A8F98)
            )
        }
        if (msg.thinkingOpen && msg.reasoning.isNotEmpty()) {
            Box(
                Modifier.padding(top = 4.dp, start = 2.dp).clip(RoundedCornerShape(8.dp))
                    .background(Color(0xFFF7F7F8)).padding(horizontal = 11.dp, vertical = 9.dp)
            ) {
                Text(
                    msg.reasoning, color = Color(0xFF6F7680), fontSize = 12.5.sp, lineHeight = 19.sp,
                    fontStyle = FontStyle.Italic
                )
            }
        }
    }
}

@Composable
private fun CompactMessage(msg: MessageUi) {
    var open by remember { mutableStateOf(false) }
    Column(Modifier.fillMaxWidth().padding(vertical = 5.dp)) {
        Row(
            Modifier.clip(RoundedCornerShape(6.dp)).clickable { open = !open }.padding(vertical = 2.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text("🗜", fontSize = 12.sp)
            Text(msg.compactHeaderText, fontSize = 12.sp, color = Color(0xFF8A8F98), modifier = Modifier.padding(start = 6.dp))
            Icon(
                if (open) Icons.Filled.KeyboardArrowUp else Icons.Filled.KeyboardArrowDown,
                null, Modifier.size(14.dp), tint = Color(0xFF8A8F98)
            )
        }
        if (open) {
            Box(
                Modifier.padding(top = 4.dp, start = 2.dp).clip(RoundedCornerShape(8.dp))
                    .background(Color(0xFFF7F7F8)).border(1.dp, Color(0xFFE7EAED), RoundedCornerShape(8.dp))
                    .padding(horizontal = 11.dp, vertical = 9.dp)
            ) {
                Text(msg.content, color = Color(0xFF6F7680), fontSize = 12.5.sp, lineHeight = 19.sp)
            }
        }
    }
}

@Composable
private fun EmptyHint() {
    Column(
        Modifier.fillMaxSize().padding(24.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center
    ) {
        Text("💬", fontSize = 42.sp)
        Text("开始新的对话", fontSize = 16.sp, fontWeight = FontWeight.SemiBold, modifier = Modifier.padding(top = 12.dp))
        Text(
            "在下方输入消息开始 · 点击右上角齿轮配置大模型服务",
            fontSize = 12.5.sp, color = MutedText, textAlign = TextAlign.Center, modifier = Modifier.padding(top = 6.dp)
        )
    }
}

@Composable
private fun InputBar(vm: MainViewModel, onCompact: () -> Unit) {
    val context = LocalContext.current
    Column(
        Modifier.fillMaxWidth().padding(horizontal = 14.dp, vertical = 8.dp)
            .clip(RoundedCornerShape(14.dp))
            .background(Color.White)
            .border(1.dp, CardBorder, RoundedCornerShape(14.dp))
    ) {
        BasicTextField(
            value = vm.input,
            onValueChange = { vm.input = it },
            modifier = Modifier.fillMaxWidth().heightIn(min = 46.dp).padding(horizontal = 14.dp, vertical = 11.dp),
            textStyle = TextStyle(fontSize = 13.5.sp, lineHeight = 20.sp, color = Color(0xFF1F2328)),
            maxLines = 6
        )
        Row(
            Modifier.fillMaxWidth().padding(start = 12.dp, end = 12.dp, bottom = 11.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Switch(checked = vm.thinkingEnabled, onCheckedChange = { vm.setThinking(it) }, modifier = Modifier.scale(0.7f))
            Text("思考", fontSize = 12.5.sp, color = Color(0xFF374151))
            TextButton(
                onClick = onCompact,
                enabled = !vm.isStreaming && (vm.selected?.messages?.isNotEmpty() == true),
                contentPadding = PaddingValues(horizontal = 10.dp, vertical = 4.dp),
                modifier = Modifier.padding(start = 12.dp).height(30.dp)
            ) {
                Text("压缩上下文", fontSize = 12.sp)
            }
            if (vm.isStreaming) {
                Spacer(Modifier.width(8.dp))
                Text("生成中,可点右侧停止", fontSize = 11.sp, color = MutedText, maxLines = 1)
            }
            Spacer(Modifier.weight(1f))
            val used = vm.selected?.estimatedTokens ?: 0
            val window = vm.selectedModel?.model?.contextWindow ?: 0
            TokenRing(used, window) {
                Toast.makeText(
                    context,
                    "已用约 $used / $window tokens(中文约 1 字 1 token,英文约 4 字符 1 token;取最近 30 条,与实际发送范围一致)",
                    Toast.LENGTH_LONG
                ).show()
            }
            Spacer(Modifier.width(8.dp))
            if (!vm.isStreaming) {
                val canSend = vm.input.isNotBlank()
                Box(
                    Modifier.size(38.dp).clip(CircleShape)
                        .background(if (canSend) Color(0xFF171717) else Color(0xFFE5E7EB))
                        .clickable(enabled = canSend) { vm.send() },
                    contentAlignment = Alignment.Center
                ) {
                    Icon(
                        Icons.AutoMirrored.Filled.Send, "发送", Modifier.size(17.dp),
                        tint = if (canSend) Color.White else Color(0xFF9AA0A6)
                    )
                }
            } else {
                Box(
                    Modifier.size(38.dp).clip(CircleShape).clickable { vm.stop() },
                    contentAlignment = Alignment.Center
                ) {
                    Icon(Icons.Filled.Stop, "停止生成", Modifier.size(20.dp), tint = Color(0xFF1F2328))
                }
            }
        }
    }
}

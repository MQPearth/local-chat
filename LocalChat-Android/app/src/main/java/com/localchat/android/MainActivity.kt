package com.localchat.android

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.viewmodel.compose.viewModel
import com.localchat.android.ui.ChatScreen
import com.localchat.android.ui.MainViewModel
import com.localchat.android.ui.NoticeHost
import com.localchat.android.ui.SettingsScreen
import com.localchat.android.ui.theme.LocalChatTheme

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent {
            LocalChatTheme {
                AppRoot()
            }
        }
    }
}

@Composable
private fun AppRoot() {
    val vm: MainViewModel = viewModel()
    var screen by rememberSaveable { mutableStateOf("chat") }
    LaunchedEffect(Unit) { vm.initialize() }
    NoticeHost(vm.notice) { vm.clearNotice() }
    // App 切后台时落盘,避免进程被杀丢配置
    val owner = LocalLifecycleOwner.current
    DisposableEffect(owner) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_STOP) vm.save()
        }
        owner.lifecycle.addObserver(observer)
        onDispose { owner.lifecycle.removeObserver(observer) }
    }
    if (screen == "settings") {
        SettingsScreen(vm, onBack = { screen = "chat" })
    } else {
        ChatScreen(vm, onOpenSettings = { screen = "settings" })
    }
}

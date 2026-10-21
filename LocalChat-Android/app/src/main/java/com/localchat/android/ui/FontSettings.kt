package com.localchat.android.ui

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.unit.TextUnit
import androidx.compose.ui.unit.sp
import com.localchat.android.data.Defaults

/**
 * 字号设置单例(桌面版 FontSettingsService 的等价物):
 * 滑块修改即时写入;各界面按 当前字号/基准字号 的比例换算真实字号。
 * 换算发生在组合期,字号变化时相关组件自动重组刷新,等价于桌面版的 Changed 事件。
 */
object FontSettings {
    const val UiBase = 13.0
    const val ChatBase = 13.5

    var uiFontSize by mutableStateOf(Defaults.UiFontSize)
        private set
    var chatFontSize by mutableStateOf(Defaults.ChatFontSize)
        private set

    fun apply(ui: Double, chat: Double) {
        uiFontSize = ui.coerceIn(10.0, 28.0)
        chatFontSize = chat.coerceIn(10.0, 28.0)
    }
}

/** 界面文字字号:基准值 × (界面字号 / 13)。 */
fun uiFont(base: Double): TextUnit = scaled(base, FontSettings.uiFontSize, FontSettings.UiBase)

/** 界面文字字号,接受整数字面量基准值。 */
fun uiFont(base: Int): TextUnit = uiFont(base.toDouble())

/** 聊天区文字字号:基准值 × (聊天字号 / 13.5)。 */
fun chatFont(base: Double): TextUnit = scaled(base, FontSettings.chatFontSize, FontSettings.ChatBase)

/** 聊天区文字字号,接受整数字面量基准值。 */
fun chatFont(base: Int): TextUnit = chatFont(base.toDouble())

/** 换算并四舍五入到 0.1,避免浮点噪声(与桌面版一致)。 */
private fun scaled(base: Double, current: Double, baseRef: Double): TextUnit =
    (Math.round(base * current / baseRef * 10) / 10.0).sp

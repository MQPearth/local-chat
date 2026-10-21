package com.localchat.android.ui

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.size
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.OutlinedTextField
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
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import android.widget.Toast
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Date
import java.util.Locale
import kotlin.math.abs
import kotlin.math.roundToInt

/** 桌面 TimeTextConverter:今天显示 HH:mm,同年显示 M月d日,其余 yyyy/M/d。 */
fun formatTime(millis: Long): String {
    val cal = Calendar.getInstance()
    val now = Calendar.getInstance()
    cal.timeInMillis = millis
    return when {
        cal.get(Calendar.ERA) == now.get(Calendar.ERA) &&
            cal.get(Calendar.YEAR) == now.get(Calendar.YEAR) &&
            cal.get(Calendar.DAY_OF_YEAR) == now.get(Calendar.DAY_OF_YEAR) ->
            SimpleDateFormat("HH:mm", Locale.getDefault()).format(Date(millis))
        cal.get(Calendar.ERA) == now.get(Calendar.ERA) && cal.get(Calendar.YEAR) == now.get(Calendar.YEAR) ->
            SimpleDateFormat("M月d日", Locale.getDefault()).format(Date(millis))
        else -> SimpleDateFormat("yyyy/M/d", Locale.getDefault()).format(Date(millis))
    }
}

/** 桌面 CtxBadgeConverter:1024 以上显示 K,如 8192 → 8K。 */
fun ctxBadge(v: Int): String {
    if (v <= 0) return "--"
    if (v >= 1024) {
        val k = v / 1024.0
        val text = if (abs(k - k.roundToInt()) < 0.05) k.roundToInt().toString() else String.format(Locale.US, "%.1f", k)
        return text + "K"
    }
    return v.toString()
}

/** 上下文用量圆环:已用估算 / 模型窗口,从正上方顺时针。 */
@Composable
fun TokenRing(used: Int, total: Int, modifier: Modifier = Modifier, onClick: () -> Unit) {
    Box(modifier.size(26.dp).clickable(onClick = onClick), contentAlignment = Alignment.Center) {
        Canvas(Modifier.fillMaxSize()) {
            val stroke = 4.dp.toPx()
            val radius = (size.minDimension - stroke) / 2f
            val center = Offset(size.width / 2f, size.height / 2f)
            drawCircle(
                color = Color(0xFFE5E7EA),
                radius = radius,
                center = center,
                style = Stroke(stroke)
            )
            if (total > 0 && used > 0) {
                val sweep = (used.toFloat() / total).coerceIn(0f, 1f) * 360f
                drawArc(
                    color = Color(0xFF6F7680),
                    startAngle = -90f,
                    sweepAngle = sweep,
                    useCenter = false,
                    topLeft = Offset(center.x - radius, center.y - radius),
                    size = Size(radius * 2, radius * 2),
                    style = Stroke(stroke, cap = StrokeCap.Round)
                )
            }
        }
    }
}

@Composable
fun ConfirmDialog(
    title: String,
    text: String,
    confirmText: String = "确定",
    onConfirm: () -> Unit,
    onDismiss: () -> Unit
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(title) },
        text = { Text(text) },
        confirmButton = {
            TextButton(onClick = { onDismiss(); onConfirm() }) { Text(confirmText) }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("取消") } }
    )
}

@Composable
fun InputDialog(
    title: String,
    initial: String,
    onConfirm: (String) -> Unit,
    onDismiss: () -> Unit
) {
    var text by remember { mutableStateOf(initial) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(title) },
        text = {
            OutlinedTextField(
                value = text,
                onValueChange = { text = it },
                singleLine = true,
                modifier = Modifier.fillMaxWidth()
            )
        },
        confirmButton = {
            TextButton(onClick = { onDismiss(); onConfirm(text.trim()) }) { Text("确定") }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("取消") } }
    )
}

/** 结果提示对话框。 */
@Composable
fun MessageDialog(title: String, text: String, onDismiss: () -> Unit) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(title) },
        text = { Text(text) },
        confirmButton = { TextButton(onClick = onDismiss) { Text("确定") } }
    )
}

/** VM.notice 转为 Toast 展示。 */
@Composable
fun NoticeHost(notice: String?, onConsumed: () -> Unit) {
    val context = LocalContext.current
    LaunchedEffect(notice) {
        notice?.let {
            Toast.makeText(context, it, Toast.LENGTH_LONG).show()
            onConsumed()
        }
    }
}

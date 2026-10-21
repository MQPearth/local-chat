package com.localchat.android.data

/** 粗略 token 估算:中日韩字符按 1 字 1 token,其余按 4 字符 1 token,每条消息另加固定开销。 */
object TokenEstimator {
    fun estimate(texts: List<String>, perMessageOverhead: Int = 8): Int {
        var tokens = 0
        for (t in texts) {
            if (t.isEmpty()) continue
            var wide = 0
            var narrow = 0
            for (ch in t) {
                if (isWide(ch)) wide++ else narrow++
            }
            tokens += wide + (narrow + 3) / 4 + perMessageOverhead
        }
        return tokens
    }

    private fun isWide(ch: Char) =
        ch in '\u2E80'..'\u9FFF' || ch in '\uF900'..'\uFAFF' || ch in '\uFF00'..'\uFFEF'
}

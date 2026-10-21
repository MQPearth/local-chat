namespace LocalChat.Services;

/// <summary>粗略 token 估算:中日韩字符按 1 字 1 token,其余按 4 字符 1 token,每条消息另加固定开销。</summary>
public static class TokenEstimator
{
    public static int Estimate(IReadOnlyList<string> texts, int perMessageOverhead = 8)
    {
        int tokens = 0;
        foreach (var t in texts)
        {
            if (t.Length == 0) continue;
            int wide = 0, narrow = 0;
            foreach (var ch in t)
            {
                if (IsWide(ch)) wide++;
                else narrow++;
            }
            tokens += wide + (narrow + 3) / 4 + perMessageOverhead;
        }
        return tokens;
    }

    private static bool IsWide(char ch) =>
        ch is >= '\u2E80' and <= '\u9FFF' or >= '\uF900' and <= '\uFAFF' or >= '\uFF00' and <= '\uFFEF';
}

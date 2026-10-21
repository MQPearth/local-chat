namespace LocalChat.Services;

// ===== 行内中间模型 =====
public abstract record MdInline;

/// <summary>普通文本片段(可带粗体/斜体/删除线/行内代码标记)。</summary>
public sealed record MdRun(string Text, bool Bold = false, bool Italic = false, bool Strike = false, bool Code = false) : MdInline;

/// <summary>超链接。</summary>
public sealed record MdLink(string Text, string Url) : MdInline;

// ===== 块级中间模型 =====
public abstract record MdBlock;

public sealed record MdParagraph(IReadOnlyList<MdInline> Inlines) : MdBlock;
public sealed record MdHeading(int Level, IReadOnlyList<MdInline> Inlines) : MdBlock;
public sealed record MdCode(string Lang, string Code) : MdBlock;
public sealed record MdQuote(IReadOnlyList<MdInline> Inlines) : MdBlock;
public sealed record MdItem(int Level, string Marker, IReadOnlyList<MdInline> Inlines) : MdBlock;
public sealed record MdRule : MdBlock;
public sealed record MdTable(IReadOnlyList<IReadOnlyList<MdInline>> Header,
    IReadOnlyList<IReadOnlyList<IReadOnlyList<MdInline>>> Rows) : MdBlock;

/// <summary>
/// 轻量 Markdown 解析器:块级(标题/段落/代码围栏/引用/列表/分隔线/表格)+ 行内
/// (粗体 **x**、斜体 *x*、删除线 ~~x~~、行内代码 `x`、链接 [t](u))。纯逻辑,不依赖 UI。
/// </summary>
public static class MarkdownParser
{
    public static List<MdBlock> Parse(string markdown)
    {
        var blocks = new List<MdBlock>();
        var lines = markdown.Replace("\r\n", "\n").Split('\n');
        var para = new List<List<MdInline>>();
        int i = 0;

        void FlushPara()
        {
            if (para.Count == 0) return;
            var inlines = new List<MdInline>();
            for (int k = 0; k < para.Count; k++)
            {
                if (k > 0) inlines.Add(new MdRun("\n"));
                inlines.AddRange(para[k]);
            }
            blocks.Add(new MdParagraph(inlines));
            para.Clear();
        }

        while (i < lines.Length)
        {
            var line = lines[i];
            var t = line.Trim();
            if (t.Length == 0) { FlushPara(); i++; continue; }

            // 代码围栏(流式输出中未闭合时,剩余行都算代码)
            if (t.StartsWith("```"))
            {
                FlushPara();
                var lang = t[3..].Trim();
                var code = new System.Text.StringBuilder();
                i++;
                while (i < lines.Length && !lines[i].TrimStart().StartsWith("```"))
                {
                    code.AppendLine(lines[i]);
                    i++;
                }
                i++; // 跳过结束围栏(不存在则越界结束)
                blocks.Add(new MdCode(lang, code.ToString().TrimEnd('\n')));
                continue;
            }

            // ATX 标题 # ~ ######
            if (t[0] == '#')
            {
                int lv = 0;
                while (lv < t.Length && t[lv] == '#') lv++;
                if (lv <= 6 && lv < t.Length && t[lv] == ' ')
                {
                    FlushPara();
                    blocks.Add(new MdHeading(lv, ParseInlines(t[(lv + 1)..].Trim())));
                    i++;
                    continue;
                }
            }

            // 水平分隔线 --- *** - - -
            if (t.All(c => c == '-' || c == '*' || c == ' ') && t.Any(c => c == '-' || c == '*'))
            {
                FlushPara();
                blocks.Add(new MdRule());
                i++;
                continue;
            }

            // 引用块
            if (t.StartsWith(">"))
            {
                FlushPara();
                var q = new System.Text.StringBuilder();
                while (i < lines.Length)
                {
                    var lt = lines[i].TrimStart();
                    if (!lt.StartsWith(">")) break;
                    q.AppendLine(lt.Length > 1 && lt[1] == ' ' ? lt[2..] : lt[1..].TrimStart());
                    i++;
                }
                blocks.Add(new MdQuote(ParseInlines(q.ToString().TrimEnd('\n'))));
                continue;
            }

            // 列表项(- * + 或 1. 1))
            var lm = System.Text.RegularExpressions.Regex.Match(line, @"^(\s*)([-*+]|\d{1,3}[.)])\s+(.*)$");
            if (lm.Success)
            {
                FlushPara();
                int level = Math.Min(lm.Groups[1].Value.Length, 6) / 2;
                var marker = lm.Groups[2].Value;
                var markerText = marker is "-" or "*" or "+" ? "•" : marker.TrimEnd('.') + ".";
                blocks.Add(new MdItem(level, markerText, ParseInlines(lm.Groups[3].Value.Trim())));
                i++;
                continue;
            }

            // 表格(下一行是 |---|---| 分隔行)
            if (t.Contains('|') && i + 1 < lines.Length && IsTableSep(lines[i + 1].Trim()))
            {
                FlushPara();
                var header = SplitRow(t);
                i += 2;
                var rows = new List<List<List<MdInline>>>();
                while (i < lines.Length)
                {
                    var rt = lines[i].Trim();
                    if (rt.Length == 0 || !rt.Contains('|')) break;
                    rows.Add(SplitRow(rt));
                    i++;
                }
                blocks.Add(new MdTable(header, rows));
                continue;
            }

            para.Add(ParseInlines(t));
            i++;
        }
        FlushPara();
        return blocks;
    }

    /// <summary>解析行内标记(粗体/斜体/删除线/行内代码/链接),未闭合的标记保持字面量。</summary>
    public static List<MdInline> ParseInlines(string s)
    {
        var list = new List<MdInline>();
        var buf = new System.Text.StringBuilder();
        int i = 0;
        void Flush()
        {
            if (buf.Length > 0)
            {
                list.Add(new MdRun(buf.ToString()));
                buf.Clear();
            }
        }
        while (i < s.Length)
        {
            char c = s[i];
            if (c == '`')
            {
                int end = s.IndexOf('`', i + 1);
                if (end > i)
                {
                    Flush();
                    list.Add(new MdRun(s[(i + 1)..end], Code: true));
                    i = end + 1;
                    continue;
                }
            }
            else if (c == '*' && i + 1 < s.Length && s[i + 1] == '*')
            {
                int end = s.IndexOf("**", i + 2, StringComparison.Ordinal);
                if (end > i + 1)
                {
                    Flush();
                    foreach (var x in ParseInlines(s[(i + 2)..end]))
                        list.Add(x is MdRun r ? r with { Bold = true } : x);
                    i = end + 2;
                    continue;
                }
            }
            else if (c == '~' && i + 1 < s.Length && s[i + 1] == '~')
            {
                int end = s.IndexOf("~~", i + 2, StringComparison.Ordinal);
                if (end > i + 1)
                {
                    Flush();
                    foreach (var x in ParseInlines(s[(i + 2)..end]))
                        list.Add(x is MdRun r ? r with { Strike = true } : x);
                    i = end + 2;
                    continue;
                }
            }
            else if (c == '*' || c == '_')
            {
                int end = s.IndexOf(c, i + 1);
                // 下划线夹在单词字母之间(如 some_var_name)不算斜体
                bool bound = c == '*' ||
                    ((i == 0 || !char.IsLetterOrDigit(s[i - 1])) &&
                     (end + 1 >= s.Length || !char.IsLetterOrDigit(s[end + 1])));
                if (end > i + 1 && !char.IsWhiteSpace(s[i + 1]) && bound)
                {
                    Flush();
                    foreach (var x in ParseInlines(s[(i + 1)..end]))
                        list.Add(x is MdRun r ? r with { Italic = true } : x);
                    i = end + 1;
                    continue;
                }
            }
            else if (c == '[')
            {
                int close = s.IndexOf("](", i + 1, StringComparison.Ordinal);
                if (close > i)
                {
                    int end = s.IndexOf(')', close + 2);
                    if (end > close)
                    {
                        Flush();
                        list.Add(new MdLink(s[(i + 1)..close], s[(close + 2)..end]));
                        i = end + 1;
                        continue;
                    }
                }
            }
            buf.Append(c);
            i++;
        }
        Flush();
        return list;
    }

    private static List<List<MdInline>> SplitRow(string line)
    {
        var t = line.Trim().Trim('|');
        return t.Split('|').Select(c => ParseInlines(c.Trim())).ToList();
    }

    private static bool IsTableSep(string t)
    {
        if (t.Length == 0 || !t.Contains('-')) return false;
        return t.Replace("|", "").Replace("-", "").Replace(":", "").Replace(" ", "").Length == 0;
    }
}

using System.Text;

namespace LocalChat.Services;

/// <summary>一次 LLM API 调用的原始 HTTP 记录;含失败重试时一次回复会有多条。</summary>
public sealed class HttpExchange
{
    public DateTime StartedAt { get; } = DateTime.Now;
    public DateTime? EndedAt { get; set; }
    public string Method { get; set; } = "POST";
    public string Url { get; set; } = "";
    public Dictionary<string, string> RequestHeaders { get; } = new();
    public string RequestBody { get; set; } = "";
    public int? StatusCode { get; set; }
    public string? ReasonPhrase { get; set; }
    public Dictionary<string, string> ResponseHeaders { get; } = new();
    public readonly StringBuilder ResponseBody = new();
    public string? Error { get; set; }
}

/// <summary>把 HTTP 记录格式化为可读文本,供「导出 HTTP 数据」使用。</summary>
public static class HttpLogFormatter
{
    public static string Build(string title, string model, IReadOnlyList<HttpExchange> list)
    {
        var sb = new StringBuilder();
        sb.AppendLine($"对话: {title}");
        sb.AppendLine($"模型: {model}");
        sb.AppendLine($"导出时间: {DateTime.Now:yyyy-MM-dd HH:mm:ss}");
        sb.AppendLine($"HTTP 请求: {list.Count} 次(响应体为原始流式数据;请求头可能包含 API Key,分享前请注意脱敏)");
        for (int i = 0; i < list.Count; i++)
        {
            var e = list[i];
            sb.AppendLine();
            sb.Append('═', 56).AppendLine();
            var dur = e.EndedAt is { } end ? $"  ·  {(long)(end - e.StartedAt).TotalMilliseconds} ms" : "";
            sb.AppendLine($"#{i + 1}  {e.StartedAt:yyyy-MM-dd HH:mm:ss}{dur}");
            sb.Append('═', 56).AppendLine();
            sb.AppendLine();
            sb.AppendLine($"► {e.Method} {e.Url}");
            foreach (var h in e.RequestHeaders)
                sb.AppendLine($"{h.Key}: {h.Value}");
            sb.AppendLine();
            sb.AppendLine(e.RequestBody);
            sb.AppendLine();
            if (e.StatusCode is { } code)
            {
                sb.AppendLine($"◄ {(int)code} {e.ReasonPhrase}");
                foreach (var h in e.ResponseHeaders)
                    sb.AppendLine($"{h.Key}: {h.Value}");
                sb.AppendLine();
                var body = e.ResponseBody.ToString();
                sb.AppendLine(body);
                if (body.Length > 0 && !body.EndsWith('\n'))
                    sb.AppendLine();
            }
            if (e.Error is { } err)
                sb.AppendLine($"!! 请求失败: {err}");
        }
        return sb.ToString();
    }
}

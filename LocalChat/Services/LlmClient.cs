using System.Diagnostics;
using System.IO;
using System.Net.Http;
using System.Net.Http.Headers;
using System.Text;
using System.Text.Json;
using LocalChat.Models;

namespace LocalChat.Services;

public sealed record ChatTurn(string Role, string Content);

public static class LlmClient
{
    private static readonly HttpClient Http = new() { Timeout = TimeSpan.FromMinutes(10) };

    /// <summary>流式对话。onContent / onReasoning 在后台线程回调;log 不为 null 时记录原始 HTTP 请求/响应。</summary>
    public static async Task StreamAsync(ProviderConfig provider, ModelConfig model,
        IReadOnlyList<ChatTurn> turns, string? systemPrompt, bool think,
        Action<string> onContent, Action<string> onReasoning, CancellationToken ct,
        List<HttpExchange>? log = null)
    {
        var body = BuildBody(provider, model, turns, systemPrompt, think, includeReasoningField: true);
        var ex = new HttpExchange();
        log?.Add(ex);
        try
        {
            var resp = await PostAsync(provider, body, ct, ex);

            // OpenAI 兼容服务对参数支持差异大:400 时按报错逐步去掉 reasoning_effort / thinking 重试(最多两次)
            if (!resp.IsSuccessStatusCode && provider.Format == ApiFormat.OpenAI && (int)resp.StatusCode == 400)
            {
                var includeReasoning = true;
                var includeThinking = true;
                for (int attempt = 0; attempt < 2 && !resp.IsSuccessStatusCode; attempt++)
                {
                    var preview = await resp.Content.ReadAsStringAsync(ct);
                    if (think && includeReasoning && preview.Contains("reasoning", StringComparison.OrdinalIgnoreCase))
                        includeReasoning = false;
                    else if (includeThinking && (preview.Contains("thinking", StringComparison.OrdinalIgnoreCase) ||
                                                 preview.Contains("unrecognized", StringComparison.OrdinalIgnoreCase)))
                        includeThinking = false;
                    else break;

                    ex.ResponseBody.Append(preview);
                    resp.Dispose();
                    body = BuildBody(provider, model, turns, systemPrompt, think, includeReasoning, includeThinking);
                    ex = new HttpExchange();
                    log?.Add(ex);
                    resp = await PostAsync(provider, body, ct, ex);
                }
            }

            using (resp)
            {
                if (!resp.IsSuccessStatusCode)
                {
                    var err = await resp.Content.ReadAsStringAsync(ct);
                    ex.ResponseBody.Append(err);
                    throw new HttpRequestException($"HTTP {(int)resp.StatusCode}: {Snippet(err)}");
                }

                await using var stream = await resp.Content.ReadAsStreamAsync(ct);
                using var reader = new StreamReader(stream);
                bool done = false;
                while (!done)
                {
                    var line = await reader.ReadLineAsync(ct);
                    if (line is null) break;
                    ex.ResponseBody.AppendLine(line);

                    string? payload;
                    if (line.StartsWith("data:", StringComparison.Ordinal))
                    {
                        payload = line[5..].Trim();
                        if (payload == "[DONE]") break;
                        if (payload.Length == 0) continue;
                    }
                    else if (line.StartsWith('{'))
                    {
                        payload = line; // Ollama 原生接口为 NDJSON
                    }
                    else continue;

                    using var doc = JsonDocument.Parse(payload);
                    switch (provider.Format)
                    {
                        case ApiFormat.OpenAI:
                            done = HandleOpenAi(doc.RootElement, onContent, onReasoning);
                            break;
                        case ApiFormat.Anthropic:
                            done = HandleAnthropic(doc.RootElement, onContent, onReasoning);
                            break;
                        default:
                            done = HandleOllama(doc.RootElement, onContent, onReasoning);
                            break;
                    }
                }
            }
            ex.EndedAt = DateTime.Now;
        }
        catch (Exception e)
        {
            ex.Error = e.Message;
            ex.EndedAt = DateTime.Now;
            throw;
        }
    }

    private static async Task<HttpResponseMessage> PostAsync(ProviderConfig p,
        Dictionary<string, object?> body, CancellationToken ct, HttpExchange? ex = null)
    {
        var json = JsonSerializer.Serialize(body);
        var req = new HttpRequestMessage(HttpMethod.Post, Endpoint(p))
        {
            Content = new StringContent(json, Encoding.UTF8, "application/json")
        };
        ApplyHeaders(req, p);
        if (ex is not null)
        {
            ex.Method = req.Method.Method;
            ex.Url = req.RequestUri?.ToString() ?? "";
            foreach (var h in req.Headers)
                ex.RequestHeaders[h.Key] = string.Join(", ", h.Value);
            if (req.Content is not null)
                foreach (var h in req.Content.Headers)
                    ex.RequestHeaders[h.Key] = string.Join(", ", h.Value);
            ex.RequestBody = json;
        }
        var resp = await Http.SendAsync(req, HttpCompletionOption.ResponseHeadersRead, ct);
        if (ex is not null)
        {
            ex.StatusCode = (int)resp.StatusCode;
            ex.ReasonPhrase = resp.ReasonPhrase;
            foreach (var h in resp.Headers)
                ex.ResponseHeaders[h.Key] = string.Join(", ", h.Value);
            if (resp.Content is not null)
                foreach (var h in resp.Content.Headers)
                    ex.ResponseHeaders[h.Key] = string.Join(", ", h.Value);
        }
        return resp;
    }

    private static string Base(ProviderConfig p) => p.BaseUrl.Trim().TrimEnd('/');

    private static string EnsureV1(string b) =>
        b.EndsWith("/v1", StringComparison.OrdinalIgnoreCase) ? b : b + "/v1";

    private static string Endpoint(ProviderConfig p) => p.Format switch
    {
        ApiFormat.OpenAI => EnsureV1(Base(p)) + "/chat/completions",
        ApiFormat.Anthropic => EnsureV1(Base(p)) + "/messages",
        _ => (Base(p).EndsWith("/v1", StringComparison.OrdinalIgnoreCase) ? Base(p)[..^3] : Base(p)) + "/api/chat"
    };

    private static void ApplyHeaders(HttpRequestMessage req, ProviderConfig p)
    {
        if (string.IsNullOrWhiteSpace(p.ApiKey)) return;
        if (p.Format == ApiFormat.Anthropic)
        {
            req.Headers.Add("x-api-key", p.ApiKey);
            req.Headers.Add("anthropic-version", "2023-06-01");
        }
        else
        {
            req.Headers.Authorization = new AuthenticationHeaderValue("Bearer", p.ApiKey);
        }
    }

    private static Dictionary<string, object?> BuildBody(ProviderConfig p, ModelConfig m,
        IReadOnlyList<ChatTurn> turns, string? sys, bool think,
        bool includeReasoningField = true, bool includeThinkingField = true)
    {
        var msgs = turns.Select(t => (object?)new Dictionary<string, object?>
        {
            ["role"] = t.Role,
            ["content"] = t.Content
        }).ToList();

        var body = new Dictionary<string, object?> { ["model"] = m.Id, ["stream"] = true };
        double temp = Math.Clamp(m.Temperature, 0, 2);

        switch (p.Format)
        {
            case ApiFormat.OpenAI:
            {
                if (!string.IsNullOrWhiteSpace(sys))
                    msgs.Insert(0, new Dictionary<string, object?> { ["role"] = "system", ["content"] = sys });
                body["messages"] = msgs;
                body["max_tokens"] = Math.Max(16, m.MaxOutputTokens);
                if (temp > 0) body["temperature"] = temp;
                // 显式传递思考开关(GLM/OpenRouter 等兼容网关的统一写法)
                if (includeThinkingField)
                    body["thinking"] = new Dictionary<string, object?>
                    {
                        ["type"] = think ? "enabled" : "disabled"
                    };
                if (think && includeReasoningField) body["reasoning_effort"] = "medium";
                break;
            }
            case ApiFormat.Anthropic:
            {
                if (!string.IsNullOrWhiteSpace(sys)) body["system"] = sys;
                body["messages"] = msgs;
                int maxTok = Math.Max(256, m.MaxOutputTokens);
                if (think)
                {
                    int budget = Math.Clamp(maxTok / 2, 1024, 32768);
                    if (maxTok <= budget) maxTok += budget;
                    body["max_tokens"] = maxTok;
                    body["thinking"] = new Dictionary<string, object?>
                    {
                        ["type"] = "enabled",
                        ["budget_tokens"] = budget
                    };
                }
                else
                {
                    body["max_tokens"] = maxTok;
                    // 混合思考模型(如 qwen3.5)默认开启思考,须显式关闭;
                    // 个别网关对该参数会返回空响应,由 StreamAsync 的零输出重试兜底。
                    body["thinking"] = new Dictionary<string, object?> { ["type"] = "disabled" };
                    if (temp > 0) body["temperature"] = temp;
                }
                break;
            }
            default:
            {
                if (!string.IsNullOrWhiteSpace(sys))
                    msgs.Insert(0, new Dictionary<string, object?> { ["role"] = "system", ["content"] = sys });
                body["messages"] = msgs;
                body["think"] = think;
                body["options"] = new Dictionary<string, object?>
                {
                    ["num_ctx"] = Math.Max(512, m.ContextWindow),
                    ["num_predict"] = Math.Max(16, m.MaxOutputTokens),
                    ["temperature"] = temp,
                    ["top_p"] = Math.Clamp(m.TopP, 0.01, 1)
                };
                break;
            }
        }
        return body;
    }

    private static bool HandleOpenAi(JsonElement root, Action<string> onContent, Action<string> onReasoning)
    {
        if (root.TryGetProperty("error", out var err))
            throw new HttpRequestException(
                err.ValueKind == JsonValueKind.Object && err.TryGetProperty("message", out var em)
                    ? em.GetString() ?? "OpenAI API 错误"
                    : err.ToString());

        if (!root.TryGetProperty("choices", out var choices) || choices.GetArrayLength() == 0)
            return false;

        var c0 = choices[0];
        if (c0.TryGetProperty("delta", out var delta) && delta.ValueKind == JsonValueKind.Object)
        {
            EmitText(delta, onContent, onReasoning);
        }
        else if (c0.TryGetProperty("message", out var msg)) // 服务端忽略了 stream 时的整段返回
        {
            EmitText(msg, onContent, onReasoning);
            return true;
        }

        return c0.TryGetProperty("finish_reason", out var fr) && fr.ValueKind == JsonValueKind.String;
    }

    private static void EmitText(JsonElement e, Action<string> onContent, Action<string> onReasoning)
    {
        if (e.TryGetProperty("reasoning_content", out var rc) && rc.ValueKind == JsonValueKind.String)
            onReasoning(rc.GetString()!);
        else if (e.TryGetProperty("reasoning", out var r2) && r2.ValueKind == JsonValueKind.String)
            onReasoning(r2.GetString()!);

        if (e.TryGetProperty("content", out var c) && c.ValueKind == JsonValueKind.String)
        {
            var s = c.GetString();
            if (!string.IsNullOrEmpty(s)) onContent(s);
        }
    }

    private static bool HandleAnthropic(JsonElement root, Action<string> onContent, Action<string> onReasoning)
    {
        if (!root.TryGetProperty("type", out var type)) return false;
        switch (type.GetString())
        {
            case "content_block_delta":
                if (root.TryGetProperty("delta", out var d))
                {
                    var dt = d.TryGetProperty("type", out var dtt) ? dtt.GetString() : null;
                    if (dt == "text_delta" && d.TryGetProperty("text", out var t) && t.ValueKind == JsonValueKind.String)
                        onContent(t.GetString()!);
                    else if (dt == "thinking_delta" && d.TryGetProperty("thinking", out var th) && th.ValueKind == JsonValueKind.String)
                        onReasoning(th.GetString()!);
                }
                break;
            case "message_stop":
                return true;
            case "message_delta":
                if (root.TryGetProperty("delta", out var md) &&
                    md.TryGetProperty("stop_reason", out var sr) && sr.ValueKind == JsonValueKind.String)
                    return true;
                break;
            case "error":
                throw new HttpRequestException(
                    root.TryGetProperty("error", out var e2) && e2.TryGetProperty("message", out var m2)
                        ? m2.GetString()
                        : "Anthropic API 错误");
        }
        return false;
    }

    private static bool HandleOllama(JsonElement root, Action<string> onContent, Action<string> onReasoning)
    {
        if (root.TryGetProperty("error", out var err))
            throw new HttpRequestException(err.GetString() ?? "Ollama 错误");
        if (root.TryGetProperty("message", out var msg) && msg.ValueKind == JsonValueKind.Object)
        {
            if (msg.TryGetProperty("thinking", out var th) && th.ValueKind == JsonValueKind.String)
                onReasoning(th.GetString()!);
            if (msg.TryGetProperty("content", out var c) && c.ValueKind == JsonValueKind.String)
            {
                var s = c.GetString();
                if (!string.IsNullOrEmpty(s)) onContent(s);
            }
        }
        return root.TryGetProperty("done", out var done) && done.ValueKind == JsonValueKind.True;
    }

    /// <summary>拉取服务端模型 ID 列表。</summary>
    public static async Task<List<string>> GetModelIdsAsync(ProviderConfig p)
    {
        var url = p.Format switch
        {
            ApiFormat.Ollama => Base(p) + "/api/tags",
            _ => EnsureV1(Base(p)) + "/models"
        };
        using var req = new HttpRequestMessage(HttpMethod.Get, url);
        ApplyHeaders(req, p);
        using var cts = new CancellationTokenSource(TimeSpan.FromSeconds(8));
        using var resp = await Http.SendAsync(req, cts.Token);
        var json = await resp.Content.ReadAsStringAsync(cts.Token);
        resp.EnsureSuccessStatusCode();

        using var doc = JsonDocument.Parse(json);
        var list = new List<string>();
        var root = doc.RootElement;
        if (root.TryGetProperty("models", out var arr) && arr.ValueKind == JsonValueKind.Array)
        {
            foreach (var e in arr.EnumerateArray())
                if (e.TryGetProperty("name", out var n) && n.ValueKind == JsonValueKind.String)
                    list.Add(n.GetString()!);
        }
        else if (root.TryGetProperty("data", out var d2) && d2.ValueKind == JsonValueKind.Array)
        {
            foreach (var e in d2.EnumerateArray())
                if (e.TryGetProperty("id", out var n) && n.ValueKind == JsonValueKind.String)
                    list.Add(n.GetString()!);
        }
        return list;
    }

    /// <summary>Ollama:查询模型上下文长度。</summary>
    public static async Task<long?> FetchContextLengthAsync(ProviderConfig p, string modelId)
    {
        if (p.Format != ApiFormat.Ollama) return null;
        var req = new HttpRequestMessage(HttpMethod.Post, Base(p) + "/api/show")
        {
            Content = new StringContent(
                JsonSerializer.Serialize(new Dictionary<string, object?> { ["model"] = modelId }),
                Encoding.UTF8, "application/json")
        };
        using var cts = new CancellationTokenSource(TimeSpan.FromSeconds(10));
        using var resp = await Http.SendAsync(req, cts.Token);
        var json = await resp.Content.ReadAsStringAsync(cts.Token);
        resp.EnsureSuccessStatusCode();

        using var doc = JsonDocument.Parse(json);
        if (doc.RootElement.TryGetProperty("model_info", out var info) && info.ValueKind == JsonValueKind.Object)
        {
            foreach (var prop in info.EnumerateObject())
            {
                if (prop.Name.EndsWith("context_length", StringComparison.OrdinalIgnoreCase) &&
                    prop.Value.ValueKind == JsonValueKind.Number && prop.Value.TryGetInt64(out var v))
                    return v;
            }
        }
        return null;
    }

    /// <summary>测试连通性,返回结果描述。</summary>
    public static async Task<string> TestAsync(ProviderConfig p, ModelConfig m)
    {
        var sw = Stopwatch.StartNew();
        try
        {
            if (p.Format == ApiFormat.Ollama)
            {
                var ids = await GetModelIdsAsync(p);
                sw.Stop();
                var has = ids.Contains(m.Id, StringComparer.Ordinal);
                return $"✓ 连接成功({sw.ElapsedMilliseconds} ms),服务端共 {ids.Count} 个模型" +
                       (has ? "" : $";未找到 “{m.Id}”");
            }

            var body = new Dictionary<string, object?>
            {
                ["model"] = m.Id,
                ["messages"] = new List<object>
                {
                    new Dictionary<string, object?> { ["role"] = "user", ["content"] = "hi" }
                },
                ["max_tokens"] = 1
            };
            var url = p.Format == ApiFormat.OpenAI
                ? EnsureV1(Base(p)) + "/chat/completions"
                : EnsureV1(Base(p)) + "/messages";
            using var req = new HttpRequestMessage(HttpMethod.Post, url)
            {
                Content = new StringContent(JsonSerializer.Serialize(body), Encoding.UTF8, "application/json")
            };
            ApplyHeaders(req, p);
            using var cts = new CancellationTokenSource(TimeSpan.FromSeconds(30));
            using var resp = await Http.SendAsync(req, cts.Token);
            var text = await resp.Content.ReadAsStringAsync(cts.Token);
            sw.Stop();
            if (!resp.IsSuccessStatusCode)
                throw new HttpRequestException($"HTTP {(int)resp.StatusCode}: {Snippet(text)}");
            return $"✓ 连接成功({sw.ElapsedMilliseconds} ms)";
        }
        catch (Exception ex)
        {
            return $"✗ 连接失败:{ex.Message}";
        }
    }

    private static string Snippet(string s)
    {
        s = s.Trim();
        return s.Length > 300 ? s[..300] + "…" : s;
    }
}

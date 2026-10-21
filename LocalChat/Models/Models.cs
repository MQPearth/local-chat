using System.Text.Json;

namespace LocalChat.Models;

public enum ApiFormat
{
    OpenAI,
    Anthropic,
    Ollama
}

public static class ApiFormatNames
{
    public static readonly string[] All =
    {
        "OpenAI 兼容 (/v1/chat/completions)",
        "Anthropic Messages (/v1/messages)",
        "Ollama 原生 (/api/chat)"
    };
}

public class ModelConfig
{
    public string Id { get; set; } = "";
    public int ContextWindow { get; set; } = 8192;
    public int MaxOutputTokens { get; set; } = 2048;
    public double Temperature { get; set; } = 0.7;
    public double TopP { get; set; } = 0.9;
    public string SystemPrompt { get; set; } = "";
    public bool Enabled { get; set; } = true;
}

public class ProviderConfig
{
    public string Id { get; set; } = Guid.NewGuid().ToString("N");
    public string Name { get; set; } = "新服务商";
    public bool Enabled { get; set; } = true;
    public string BaseUrl { get; set; } = "http://localhost:11434";
    public ApiFormat Format { get; set; } = ApiFormat.Ollama;
    public string ApiKey { get; set; } = "";
    public List<ModelConfig> Models { get; set; } = new();
    public bool AutoImported { get; set; }
}

public class ChatMessageData
{
    public string Role { get; set; } = "user"; // user | assistant | compact(压缩标记)
    public string Content { get; set; } = "";
    public string Reasoning { get; set; } = "";
    public DateTime Time { get; set; } = DateTime.Now;
    public bool IsError { get; set; }
    /// <summary>compact 角色专用:本次压缩覆盖的消息条数。</summary>
    public int CompactedCount { get; set; }
    /// <summary>思考阶段耗时(秒),持久化后重启仍可显示"已深度思考 (用时 X 秒)"。</summary>
    public double ReasonSeconds { get; set; }
}

public class ConversationData
{
    public string Id { get; set; } = Guid.NewGuid().ToString("N");
    public string Title { get; set; } = "新对话";
    public DateTime CreatedAt { get; set; } = DateTime.Now;
    public DateTime UpdatedAt { get; set; } = DateTime.Now;
    public string? ProviderId { get; set; }
    public string? ModelId { get; set; }
    public List<ChatMessageData> Messages { get; set; } = new();
}

public class AppSettings
{
    public List<ProviderConfig> Providers { get; set; } = new();
    public List<ConversationData> Conversations { get; set; } = new();
    public string? CurrentProviderId { get; set; }
    public string? CurrentModelId { get; set; }
    public bool ThinkingEnabled { get; set; }
    public double UiFontSize { get; set; } = Defaults.UiFontSize;
    public double ChatFontSize { get; set; } = Defaults.ChatFontSize;
    public double WindowWidth { get; set; }
    public double WindowHeight { get; set; }
    public bool WindowMaximized { get; set; }
}

public static class Defaults
{
    /// <summary>界面字体大小默认值,同时是界面档位字号的换算基准。</summary>
    public const double UiFontSize = 13.0;
    /// <summary>聊天区字体大小默认值,同时是聊天档位字号的换算基准。</summary>
    public const double ChatFontSize = 13.5;

    public static AppSettings Create() => new()
    {
        Providers =
        {
            new ProviderConfig
            {
                Name = "本地 Ollama",
                BaseUrl = "http://localhost:11434",
                Format = ApiFormat.Ollama,
                Models = { new ModelConfig { Id = "qwen3.5:4b", ContextWindow = 8192, MaxOutputTokens = 2000 } }
            }
        }
    };
}

public static class ModelConfigExt
{
    public static ModelConfig Clone(this ModelConfig m) =>
        JsonSerializer.Deserialize<ModelConfig>(JsonSerializer.Serialize(m)) ?? new ModelConfig();

    public static void CopyTo(this ModelConfig src, ModelConfig dst)
    {
        dst.Id = src.Id;
        dst.ContextWindow = src.ContextWindow;
        dst.MaxOutputTokens = src.MaxOutputTokens;
        dst.Temperature = src.Temperature;
        dst.TopP = src.TopP;
        dst.SystemPrompt = src.SystemPrompt;
        dst.Enabled = src.Enabled;
    }
}

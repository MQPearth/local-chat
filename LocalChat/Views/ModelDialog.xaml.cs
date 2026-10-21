using System.Windows;
using System.Windows.Input;
using LocalChat.Models;
using LocalChat.Services;

namespace LocalChat.Views;

public partial class ModelDialog : Window
{
    private readonly ProviderConfig _provider;
    private readonly ModelConfig? _orig;
    private ModelConfig _work;
    private bool _initialized;

    /// <summary>新增模型时的结果;编辑已有模型时直接写回原对象。</summary>
    public ModelConfig? Result { get; private set; }

    public ModelDialog(ProviderConfig provider, ModelConfig? orig)
    {
        InitializeComponent();
        _provider = provider;
        _orig = orig;
        _work = orig is null ? new ModelConfig() : orig.Clone();
        Title = orig is null ? "添加模型" : "编辑模型配置";
        _initialized = true; // IsChecked="True" 会在 XAML 初始化时触发 Checked,此时控件尚未就绪
        Load();
        Loaded += (_, _) => ModelIdBox.Focus();
    }

    private void Load()
    {
        ModelIdBox.Text = _work.Id;
        CtxBox.Text = _work.ContextWindow.ToString();
        MaxBox.Text = _work.MaxOutputTokens.ToString();
        TempBox.Text = _work.Temperature.ToString("0.##");
        TopPBox.Text = _work.TopP.ToString("0.##");
        SysBox.Text = _work.SystemPrompt;
        Hint(null);
    }

    private void Hint(string? text)
    {
        if (string.IsNullOrEmpty(text))
        {
            HintText.Visibility = Visibility.Collapsed;
            return;
        }
        HintText.Text = text;
        HintText.Foreground = new System.Windows.Media.SolidColorBrush(
            System.Windows.Media.Color.FromRgb(0x8A, 0x8F, 0x98));
        HintText.Visibility = Visibility.Visible;
    }

    private void Error(string text)
    {
        Hint(text);
        HintText.Foreground = new System.Windows.Media.SolidColorBrush(
            System.Windows.Media.Color.FromRgb(0xD5, 0x42, 0x3F));
    }

    private async void Smart_Changed(object sender, RoutedEventArgs e)
    {
        if (_initialized && SmartToggle.IsChecked == true) await TrySmartAsync();
    }

    private async void ModelId_LostFocus(object sender, RoutedEventArgs e)
    {
        if (_initialized && SmartToggle.IsChecked == true) await TrySmartAsync();
    }

    private async Task TrySmartAsync()
    {
        var id = ModelIdBox.Text.Trim();
        if (id.Length == 0)
        {
            Hint("填写模型 ID 后可自动获取配置");
            return;
        }
        Hint("正在获取模型信息…");
        try
        {
            var ctx = await LlmClient.FetchContextLengthAsync(_provider, id);
            if (ctx is long c && c > 0)
            {
                CtxBox.Text = c.ToString();
                MaxBox.Text = Math.Clamp((int)(c / 4), 256, 8192).ToString();
                Hint($"已自动填充:上下文 {c} tokens");
            }
            else
            {
                Hint(_provider.Format == ApiFormat.Ollama
                    ? "未能自动获取,请手动填写"
                    : "当前 API 格式暂不支持自动获取,请手动填写");
            }
        }
        catch (Exception ex)
        {
            Error("自动获取失败:" + ex.Message);
        }
    }

    private void Save_Click(object sender, RoutedEventArgs e)
    {
        var id = ModelIdBox.Text.Trim();
        if (id.Length == 0)
        {
            Error("请填写模型 ID");
            return;
        }

        _work.Id = id;
        _work.ContextWindow = ParseInt(CtxBox.Text, 8192, 256, 1_000_000);
        _work.MaxOutputTokens = ParseInt(MaxBox.Text, 2048, 16, 1_000_000);
        _work.Temperature = ParseDouble(TempBox.Text, 0.7, 0, 2);
        _work.TopP = ParseDouble(TopPBox.Text, 0.9, 0.01, 1);
        _work.SystemPrompt = SysBox.Text;

        if (_orig is null)
        {
            Result = _work;
        }
        else
        {
            _orig.Id = _work.Id;
            _orig.ContextWindow = _work.ContextWindow;
            _orig.MaxOutputTokens = _work.MaxOutputTokens;
            _orig.Temperature = _work.Temperature;
            _orig.TopP = _work.TopP;
            _orig.SystemPrompt = _work.SystemPrompt;
        }
        DialogResult = true;
    }

    private void Reset_Click(object sender, RoutedEventArgs e)
    {
        _work = _orig is null ? new ModelConfig() : _orig.Clone();
        Load();
    }

    private void Close_Click(object sender, RoutedEventArgs e) => Close();

    private static int ParseInt(string s, int def, int min, int max)
    {
        s = s.Trim().TrimEnd('k', 'K');
        if (int.TryParse(s, out var v) && v >= min)
        {
            if (s.EndsWith("k", StringComparison.OrdinalIgnoreCase)) v *= 1024;
            return Math.Min(v, max);
        }
        return def;
    }

    private static double ParseDouble(string s, double def, double min, double max)
    {
        if (double.TryParse(s, out var v) && v >= min && v <= max) return v;
        return def;
    }
}

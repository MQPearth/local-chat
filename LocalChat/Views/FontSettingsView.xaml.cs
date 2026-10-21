using System.Windows;
using System.Windows.Controls;
using LocalChat.Services;

namespace LocalChat.Views;

/// <summary>显示设置页:滑块调整字号即时生效,持久化由设置窗口关闭时统一保存。</summary>
public partial class FontSettingsView : UserControl
{
    private bool _ready;

    public FontSettingsView()
    {
        InitializeComponent();
        UiSlider.Value = FontSettingsService.Instance.UiFontSize;
        ChatSlider.Value = FontSettingsService.Instance.ChatFontSize;
        UpdateLabels();
        _ready = true; // 之前的赋值会触发 ValueChanged,此处之后才允许应用字号
    }

    private void Slider_ValueChanged(object sender, RoutedPropertyChangedEventArgs<double> e)
    {
        if (!_ready) return;
        UpdateLabels();
        FontSettingsService.Instance.Apply(UiSlider.Value, ChatSlider.Value);
    }

    private void UpdateLabels()
    {
        UiValueText.Text = UiSlider.Value.ToString("0.#");
        ChatValueText.Text = ChatSlider.Value.ToString("0.#");
    }

    private void Reset_Click(object sender, RoutedEventArgs e)
    {
        UiSlider.Value = Models.Defaults.UiFontSize;
        ChatSlider.Value = Models.Defaults.ChatFontSize;
    }
}

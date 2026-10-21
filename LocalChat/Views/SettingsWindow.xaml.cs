using System.Windows;
using System.Windows.Controls;
using LocalChat.Models;

namespace LocalChat.Views;

/// <summary>设置窗口:左侧设置项导航,右侧对应内容;默认显示「显示设置」。</summary>
public partial class SettingsWindow : Window
{
    private readonly FontSettingsView _fontView = new();
    private readonly ProviderSettingsView _providerView;

    public SettingsWindow(AppSettings settings)
    {
        InitializeComponent();
        _providerView = new ProviderSettingsView(settings);
        NavList.SelectedIndex = 0; // 默认选中「显示设置」并触发内容切换
    }

    private void NavList_SelectionChanged(object sender, SelectionChangedEventArgs e)
    {
        if (ContentHost is null) return; // XAML 解析期间的选择变化,宿主尚未创建
        ContentHost.Content = NavList.SelectedIndex == 1 ? _providerView : (object)_fontView;
    }
}

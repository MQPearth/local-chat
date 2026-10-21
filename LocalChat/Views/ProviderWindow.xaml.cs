using System.Windows;
using System.Windows.Controls;
using System.Windows.Controls.Primitives;
using LocalChat.Models;
using LocalChat.Services;
using LocalChat.ViewModels;

namespace LocalChat.Views;

public partial class ProviderWindow : Window
{
    private readonly AppSettings _settings;
    private readonly SettingsStore _store = new();
    private readonly ProviderSettingsVM _vm;
    private bool _loadingPwd;

    public ProviderWindow(AppSettings settings)
    {
        InitializeComponent();
        _settings = settings;
        _vm = new ProviderSettingsVM(settings);
        DataContext = _vm;
    }

    private ProviderVM? ProviderOf(ModelRowVM row) =>
        _vm.Providers.FirstOrDefault(p => p.Models.Contains(row));

    // ---------- 服务商 ----------
    private void AddProvider_Click(object sender, RoutedEventArgs e) => _vm.AddProvider();

    private void ProviderMore_Click(object sender, RoutedEventArgs e)
    {
        if (sender is Button { ContextMenu: { } menu } btn)
        {
            menu.PlacementTarget = btn;
            menu.Placement = PlacementMode.Bottom;
            menu.IsOpen = true;
        }
    }

    private void DeleteProvider_Click(object sender, RoutedEventArgs e)
    {
        if (((FrameworkElement)sender).DataContext is not ProviderVM pv) return;
        var r = MessageBox.Show(this, $"确定删除服务商「{pv.Name}」及其全部模型配置吗?", "删除服务商",
            MessageBoxButton.YesNo, MessageBoxImage.Question);
        if (r != MessageBoxResult.Yes) return;
        _vm.RemoveProvider(pv);
    }

    // ---------- 模型 ----------
    private void AddModel_Click(object sender, RoutedEventArgs e)
    {
        if (((FrameworkElement)sender).DataContext is not ProviderVM pv) return;
        var dlg = new ModelDialog(pv.Data, null) { Owner = this };
        if (dlg.ShowDialog() == true && dlg.Result != null)
            pv.AddModel(dlg.Result);
    }

    private void EditModel_Click(object sender, RoutedEventArgs e)
    {
        if (((FrameworkElement)sender).DataContext is not ModelRowVM row) return;
        var pv = ProviderOf(row);
        if (pv is null) return;
        var dlg = new ModelDialog(pv.Data, row.Data) { Owner = this };
        if (dlg.ShowDialog() == true) row.Refresh();
    }

    private void DeleteModel_Click(object sender, RoutedEventArgs e)
    {
        if (((FrameworkElement)sender).DataContext is not ModelRowVM row) return;
        var pv = ProviderOf(row);
        if (pv is null) return;
        var r = MessageBox.Show(this, $"确定删除模型「{row.Id}」吗?", "删除模型",
            MessageBoxButton.YesNo, MessageBoxImage.Question);
        if (r == MessageBoxResult.Yes) pv.RemoveModel(row);
    }

    private async void TestModel_Click(object sender, RoutedEventArgs e)
    {
        if (((FrameworkElement)sender).DataContext is not ModelRowVM row) return;
        var pv = ProviderOf(row);
        if (pv is null || sender is not Button b) return;
        // 忙碌时只调透明度,不替换 Content(按钮是图标字体,普通字符会渲染成方块)
        b.IsEnabled = false;
        b.Opacity = 0.35;
        try
        {
            var msg = await LlmClient.TestAsync(pv.Data, row.Data);
            MessageBox.Show(this, msg, "测试连接", MessageBoxButton.OK,
                msg.StartsWith("✓") ? MessageBoxImage.Information : MessageBoxImage.Warning);
        }
        finally
        {
            b.IsEnabled = true;
            b.Opacity = 1;
        }
    }

    private async void FetchModels_Click(object sender, RoutedEventArgs e)
    {
        if (((FrameworkElement)sender).DataContext is not ProviderVM pv) return;
        try
        {
            var ids = await LlmClient.GetModelIdsAsync(pv.Data);
            var existing = pv.Data.Models.Select(m => m.Id).ToHashSet(StringComparer.Ordinal);
            var added = 0;
            foreach (var id in ids)
            {
                if (!existing.Add(id)) continue;
                var mc = new ModelConfig { Id = id };
                if (pv.Data.Format == ApiFormat.Ollama)
                {
                    try
                    {
                        var ctx = await LlmClient.FetchContextLengthAsync(pv.Data, id);
                        if (ctx is long c && c > 0)
                        {
                            mc.ContextWindow = (int)Math.Min(int.MaxValue, c);
                            mc.MaxOutputTokens = Math.Clamp(mc.ContextWindow / 4, 256, 8192);
                        }
                    }
                    catch { }
                }
                pv.AddModel(mc);
                added++;
            }
            MessageBox.Show(this,
                added > 0 ? $"已拉取 {added} 个新模型。" : $"未发现新模型(服务端共 {ids.Count} 个)。",
                "拉取模型", MessageBoxButton.OK, MessageBoxImage.Information);
        }
        catch (Exception ex)
        {
            MessageBox.Show(this, "拉取失败:" + ex.Message, "拉取模型",
                MessageBoxButton.OK, MessageBoxImage.Warning);
        }
    }

    // ---------- API Key 显隐 ----------
    private void Card_Loaded(object sender, RoutedEventArgs e)
    {
        if (sender is not Border { DataContext: ProviderVM pv } card) return;
        if (card.FindName("PwdBox") is PasswordBox pb)
        {
            _loadingPwd = true;
            pb.Password = pv.ApiKey;
            _loadingPwd = false;
        }
    }

    private void PwdBox_PasswordChanged(object sender, RoutedEventArgs e)
    {
        if (_loadingPwd) return;
        if (sender is PasswordBox pb && pb.DataContext is ProviderVM pv)
            pv.ApiKey = pb.Password;
    }

    private void EyeBtn_Click(object sender, RoutedEventArgs e)
    {
        if (sender is not ToggleButton eye) return;
        var grid = (Grid)eye.Parent;
        var pb = grid.Children.OfType<PasswordBox>().First();
        var tb = grid.Children.OfType<TextBox>().First();
        if (eye.IsChecked == true)
        {
            tb.Text = (tb.DataContext as ProviderVM)?.ApiKey ?? "";
            pb.Visibility = Visibility.Collapsed;
            tb.Visibility = Visibility.Visible;
        }
        else
        {
            if (tb.DataContext is ProviderVM pv && pb.Password != pv.ApiKey)
            {
                _loadingPwd = true;
                pb.Password = pv.ApiKey;
                _loadingPwd = false;
            }
            pb.Visibility = Visibility.Visible;
            tb.Visibility = Visibility.Collapsed;
        }
    }

    // ---------- 保存 ----------
    private void SaveClose_Click(object sender, RoutedEventArgs e) => Close();

    protected override void OnClosing(System.ComponentModel.CancelEventArgs e)
    {
        base.OnClosing(e);
        _store.Save(_settings);
    }
}

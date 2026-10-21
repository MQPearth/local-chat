using System.Collections.Specialized;
using System.Windows;
using System.Windows.Controls;
using System.Windows.Input;
using LocalChat.ViewModels;

namespace LocalChat.Views;

public partial class MainWindow : Window
{
    private readonly MainViewModel _vm;

    public MainWindow()
    {
        InitializeComponent();
        _vm = new MainViewModel();
        DataContext = _vm;

        // 恢复上次的窗口大小与最大化状态
        if (_vm.Settings.WindowWidth >= MinWidth) Width = _vm.Settings.WindowWidth;
        if (_vm.Settings.WindowHeight >= MinHeight) Height = _vm.Settings.WindowHeight;
        if (_vm.Settings.WindowMaximized) WindowState = WindowState.Maximized;

        _vm.Streamed += () =>
        {
            if (_pinnedToBottom) ChatScroll.ScrollToEnd();
        };
        ((INotifyCollectionChanged)_vm.Conversations).CollectionChanged +=
            (_, _) => ChatScroll.ScrollToEnd();
        Loaded += async (_, _) =>
        {
            await _vm.InitializeAsync();
            InputBox.Focus();
        };
        Closed += (_, _) =>
        {
            // 最大化时 Width/Height 仍是还原尺寸,直接记录即可
            _vm.Settings.WindowWidth = Width;
            _vm.Settings.WindowHeight = Height;
            _vm.Settings.WindowMaximized = WindowState == WindowState.Maximized;
            _vm.Save();
        };
    }

    /// <summary>视图是否贴在底部:贴底时流式输出自动跟随,用户上滚即暂停跟随。</summary>
    private bool _pinnedToBottom = true;

    private void ChatScroll_ScrollChanged(object sender, ScrollChangedEventArgs e)
    {
        if (e.VerticalChange != 0)
            _pinnedToBottom = e.ExtentHeight - e.VerticalOffset - e.ViewportHeight < 24;
    }

    private void InputBox_PreviewKeyDown(object sender, KeyEventArgs e)
    {
        if (e.Key == Key.Enter && !Keyboard.Modifiers.HasFlag(ModifierKeys.Shift))
        {
            if (_vm.SendCommand.CanExecute(null))
                _vm.SendCommand.Execute(null);
            e.Handled = true;
        }
    }

    private void SendBtn_Click(object sender, RoutedEventArgs e) => InputBox.Focus();

    private ContextMenu? _convMenu;

    private void ConvMenu_Opened(object sender, RoutedEventArgs e) => _convMenu = (ContextMenu)sender;

    private void ConvMenu_Closed(object sender, RoutedEventArgs e) => _convMenu = null;

    // 菜单打开时,点击窗口任意位置(包括不抢焦点的空白区域)都立即收起右键菜单
    private void Window_PreviewMouseDown(object sender, MouseButtonEventArgs e)
    {
        if (_convMenu is { IsOpen: true })
            _convMenu.IsOpen = false;
    }
}

using System.Windows;
using LocalChat.Models;

namespace LocalChat.Services;

/// <summary>
/// 显示字体设置:界面与聊天区两档字号,均以各自默认值为基准等比换算出各档位字号,
/// 写入应用级资源供 XAML 以 DynamicResource 引用(改值即时刷新全部窗口);
/// 聊天区 Markdown 的代码渲染元素不走路由资源,经 Changed 事件通知 ViewModel 重建。
/// </summary>
public sealed class FontSettingsService
{
    public static FontSettingsService Instance { get; } = new();

    /// <summary>界面档位换算基准,与 Defaults.UiFontSize 保持一致。</summary>
    public const double UiBase = 13.0;
    /// <summary>聊天档位换算基准,与 Defaults.ChatFontSize 保持一致。</summary>
    public const double ChatBase = 13.5;

    public double UiFontSize { get; private set; } = Defaults.UiFontSize;
    public double ChatFontSize { get; private set; } = Defaults.ChatFontSize;

    /// <summary>字号变化;聊天区消息需要按新字号重建渲染时订阅。</summary>
    public event Action? Changed;

    public void Apply(double uiSize, double chatSize)
    {
        UiFontSize = Math.Clamp(uiSize, 10, 28);
        ChatFontSize = Math.Clamp(chatSize, 10, 28);
        double ui = UiFontSize / UiBase;
        double chat = ChatFontSize / ChatBase;
        var res = Application.Current.Resources;
        res["UiFontHint"] = R(11 * ui);
        res["UiFontSub"] = R(12.5 * ui);
        res["UiFontDefault"] = R(13 * ui);
        res["UiFontTitle"] = R(14.5 * ui);
        res["UiFontHead"] = R(16 * ui);
        res["ChatFontBody"] = R(13.5 * chat);
        res["ChatFontSecondary"] = R(12.5 * chat);
        res["ChatFontMuted"] = R(12 * chat);
        res["ChatLineSecondary"] = R(19 * chat);
        Changed?.Invoke();
    }

    private static double R(double v) => Math.Round(v, 1);
}

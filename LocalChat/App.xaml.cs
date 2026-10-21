using System.IO;
using System.Windows;
using LocalChat.Services;

namespace LocalChat;

public partial class App : Application
{
    private static string CrashLogPath => Path.Combine(
        Environment.GetFolderPath(Environment.SpecialFolder.ApplicationData), "LocalChat", "crash.log");

    protected override void OnStartup(StartupEventArgs e)
    {
        base.OnStartup(e);

        // 主窗口创建前先把保存的显示字号写入全局资源
        var s = new SettingsStore().Load();
        FontSettingsService.Instance.Apply(s.UiFontSize, s.ChatFontSize);

        DispatcherUnhandledException += (_, args) =>
        {
            Log("Dispatcher: " + args.Exception);
            MessageBox.Show(args.Exception.Message, "发生错误",
                MessageBoxButton.OK, MessageBoxImage.Warning);
            args.Handled = true;
        };
        AppDomain.CurrentDomain.UnhandledException += (_, args) =>
            Log("AppDomain: " + args.ExceptionObject);
        TaskScheduler.UnobservedTaskException += (_, args) =>
        {
            Log("Task: " + args.Exception);
            args.SetObserved();
        };
    }

    private static void Log(string text)
    {
        try
        {
            Directory.CreateDirectory(Path.GetDirectoryName(CrashLogPath)!);
            File.AppendAllText(CrashLogPath, $"[{DateTime.Now:HH:mm:ss}] {text}\n\n");
        }
        catch { }
    }
}

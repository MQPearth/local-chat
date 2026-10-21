using System.IO;
using System.Text.Json;
using LocalChat.Models;

namespace LocalChat.Services;

public class SettingsStore
{
    private static string Dir => Path.Combine(
        Environment.GetFolderPath(Environment.SpecialFolder.ApplicationData), "LocalChat");

    private static string FilePath => Path.Combine(Dir, "settings.json");

    private static readonly JsonSerializerOptions Json = new() { WriteIndented = true };

    public AppSettings Load()
    {
        try
        {
            if (File.Exists(FilePath))
            {
                var s = JsonSerializer.Deserialize<AppSettings>(File.ReadAllText(FilePath));
                if (s != null) return s;
            }
        }
        catch
        {
            // 损坏的配置文件按首次启动处理
        }
        return Defaults.Create();
    }

    public void Save(AppSettings settings)
    {
        Directory.CreateDirectory(Dir);
        var tmp = FilePath + ".tmp";
        File.WriteAllText(tmp, JsonSerializer.Serialize(settings, Json));
        File.Move(tmp, FilePath, overwrite: true);
    }
}

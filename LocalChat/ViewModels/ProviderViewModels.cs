using System.Collections.ObjectModel;
using LocalChat.Models;

namespace LocalChat.ViewModels;

public class ModelRowVM : ObservableObject
{
    public ModelRowVM(ModelConfig data) => Data = data;

    public ModelConfig Data { get; }

    public string Id
    {
        get => Data.Id;
        set
        {
            Data.Id = value;
            Raise();
        }
    }

    public int ContextWindow
    {
        get => Data.ContextWindow;
        set
        {
            Data.ContextWindow = value;
            Raise();
        }
    }

    public bool Enabled
    {
        get => Data.Enabled;
        set
        {
            Data.Enabled = value;
            Raise();
        }
    }

    public void Refresh()
    {
        Raise(nameof(Id));
        Raise(nameof(ContextWindow));
        Raise(nameof(Enabled));
    }
}

public class ProviderVM : ObservableObject
{
    public ProviderVM(ProviderConfig data)
    {
        Data = data;
        foreach (var m in data.Models) Models.Add(new ModelRowVM(m));
    }

    public ProviderConfig Data { get; }
    public ObservableCollection<ModelRowVM> Models { get; } = new();

    public string Name
    {
        get => Data.Name;
        set
        {
            Data.Name = value;
            Raise();
        }
    }

    public bool Enabled
    {
        get => Data.Enabled;
        set
        {
            Data.Enabled = value;
            Raise();
        }
    }

    public string BaseUrl
    {
        get => Data.BaseUrl;
        set
        {
            Data.BaseUrl = value;
            Raise();
        }
    }

    public string ApiKey
    {
        get => Data.ApiKey;
        set
        {
            Data.ApiKey = value;
            Raise();
        }
    }

    public int FormatIndex
    {
        get => (int)Data.Format;
        set
        {
            if (value < 0) return;
            Data.Format = (ApiFormat)value;
            Raise();
        }
    }

    public ModelRowVM AddModel(ModelConfig m)
    {
        Data.Models.Add(m);
        var row = new ModelRowVM(m);
        Models.Add(row);
        return row;
    }

    public void RemoveModel(ModelRowVM row)
    {
        Data.Models.Remove(row.Data);
        Models.Remove(row);
    }
}

public class ProviderSettingsVM : ObservableObject
{
    public ProviderSettingsVM(AppSettings settings)
    {
        Settings = settings;
        foreach (var p in settings.Providers) Providers.Add(new ProviderVM(p));
    }

    public AppSettings Settings { get; }
    public ObservableCollection<ProviderVM> Providers { get; } = new();

    public ProviderVM AddProvider()
    {
        var cfg = new ProviderConfig
        {
            Name = $"服务商 {Providers.Count + 1}",
            Format = ApiFormat.OpenAI,
            BaseUrl = ""
        };
        Settings.Providers.Add(cfg);
        var vm = new ProviderVM(cfg);
        Providers.Add(vm);
        return vm;
    }

    public void RemoveProvider(ProviderVM pv)
    {
        Settings.Providers.Remove(pv.Data);
        Providers.Remove(pv);
    }
}

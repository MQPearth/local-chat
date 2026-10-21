using System.Collections.ObjectModel;
using System.IO;
using System.Windows;
using System.Windows.Input;
using LocalChat.Models;
using LocalChat.Services;
using LocalChat.Views;

namespace LocalChat.ViewModels;

public class ModelOption
{
    public ModelOption(ProviderConfig provider, ModelConfig model)
    {
        Provider = provider;
        Model = model;
    }

    public ProviderConfig Provider { get; }
    public ModelConfig Model { get; }

    public override string ToString() => $"{Provider.Name} · {Model.Id}";
}

public class ChatMessageVM : ObservableObject
{
    private readonly IncrementalMarkdownRenderer _renderer = new();
    private readonly StreamPacer _pacer;
    private readonly System.Text.StringBuilder _contentBuf = new();
    private readonly System.Text.StringBuilder _reasonBuf = new();
    private bool _streaming;
    private DateTime? _reasonStart;
    private DateTime? _reasonEnd;
    private bool _contentStarted;
    private bool _isThinkingOpen;         // 默认折叠
    private bool _thinkingTouchedByUser;  // 用户手动操作过思考块后,不再自动展开

    public ChatMessageVM(ChatMessageData data, bool streaming = false)
    {
        Data = data;
        _streaming = streaming;
        _pacer = new StreamPacer(Flush);
        _renderer.Update(Data.Content, Data.IsError); // 历史消息一次性完整渲染
    }

    /// <summary>一次节流刷新完成(滚动跟随等视图副作用挂在这里,避免每个分片都触发)。</summary>
    public event Action? Flushed;

    public ChatMessageData Data { get; }

    public bool IsStreaming => _streaming;
    public string Role => Data.Role;
    public string Content => Data.Content;
    public string Reasoning => Data.Reasoning;
    public DateTime Time => Data.Time;
    public bool IsError => Data.IsError;
    /// <summary>思考块展开状态;用户经 Expander 点击回写后,思考期间不再被自动展开。</summary>
    public bool IsThinkingOpen
    {
        get => _isThinkingOpen;
        set
        {
            _thinkingTouchedByUser = true;
            SetThinking(value);
        }
    }

    private void SetThinking(bool value)
    {
        if (_isThinkingOpen == value) return;
        _isThinkingOpen = value;
        Raise(nameof(IsThinkingOpen));
    }

    public int CompactedCount => Data.CompactedCount;

    public string CompactHeaderText =>
        Data.Role == "compact" ? $"已压缩 {Data.CompactedCount} 条历史消息,点击查看摘要" : "";

    /// <summary>正文按 Markdown 渲染出的块级元素(流式输出时增量更新,只重建变化块)。</summary>
    public ObservableCollection<System.Windows.UIElement> Blocks => _renderer.Blocks;

    /// <summary>本条回复产生的原始 HTTP 记录(含失败重试,会有多条)。</summary>
    public List<HttpExchange> HttpLog { get; } = new();

    public bool ShowWaiting =>
        _streaming && Data.Content.Length == 0 && Data.Reasoning.Length == 0;

    public string ThinkingLabel
    {
        get
        {
            if (Data.Reasoning.Length == 0) return "";
            if (!_contentStarted)
            {
                // 重启后的历史消息:展示持久化的思考耗时
                if (!_streaming)
                    return Data.ReasonSeconds > 0 ? $"已深度思考 (用时 {Data.ReasonSeconds:0.#} 秒)" : "已深度思考";
                var sec = _reasonStart is { } r ? (DateTime.Now - r).TotalSeconds : 0;
                return $"思考中… {sec:0} 秒";
            }
            var dur = _reasonStart is { } r2 && _reasonEnd is { } e2
                ? (e2 - r2).TotalSeconds
                : Data.ReasonSeconds; // 中断兜底:用已持久化的耗时
            return dur > 0 ? $"已深度思考 (用时 {dur:0.#} 秒)" : "已深度思考";
        }
    }

    public void BeginStreaming()
    {
        _streaming = true;
        Raise(nameof(IsStreaming));
        Raise(nameof(ShowWaiting));
    }

    /// <summary>字号设置变化后,清掉增量缓存并按新字号重建全部块。</summary>
    public void RefreshFont()
    {
        _renderer.Reset();
        _renderer.Update(Data.Content, Data.IsError);
    }

    public void AppendReasoning(string piece)
    {
        if (!_streaming || piece.Length == 0) return; // 流结束后到达的迟到分片直接丢弃
        if (_reasonStart is null)
        {
            _reasonStart = DateTime.Now;
            if (!_thinkingTouchedByUser) SetThinking(true); // 思考中自动展开,直播推理过程
            Raise(nameof(ShowWaiting));
        }
        _reasonBuf.Append(piece);
        _pacer.Touch();
    }

    public void AppendContent(string piece)
    {
        if (!_streaming || piece.Length == 0) return;
        if (!_contentStarted)
        {
            _contentStarted = true;
            _reasonEnd = DateTime.Now;
            if (_reasonStart is { } rs)
                Data.ReasonSeconds = (DateTime.Now - rs).TotalSeconds; // 持久化思考耗时
            SetThinking(false); // 思考结束,自动折叠思考块
            Raise(nameof(ShowWaiting));
        }
        _contentBuf.Append(piece);
        _pacer.Touch();
    }

    public void Finish(bool cancelled = false)
    {
        _pacer.Complete(); // 先落盘缓冲中的分片,收尾文本才能接在完整正文之后
        if (cancelled)
            AppendContentRaw(Data.Content.Length == 0 && Data.Reasoning.Length == 0 ? "(已停止)" : "\n\n(已停止)");
        EndStream();
    }

    public void Fail(string msg)
    {
        _pacer.Complete();
        if (Data.Content.Length == 0 && Data.Reasoning.Length == 0)
        {
            Data.IsError = true;
            Raise(nameof(IsError));
            AppendContentRaw("[请求失败] " + msg);
        }
        else
        {
            AppendContentRaw("\n\n⚠ 生成中断:" + msg);
        }
        EndStream();
    }

    private void EndStream()
    {
        // 持久化思考耗时,重启后仍可显示
        if (Data.ReasonSeconds <= 0 && _reasonStart is { } rs)
            Data.ReasonSeconds = ((_reasonEnd ?? DateTime.Now) - rs).TotalSeconds;
        _streaming = false;
        _pacer.Complete(); // 停表并做兜底刷新(Finish/Fail 已刷新过时为空操作)
        Raise(nameof(IsStreaming));
        Raise(nameof(ShowWaiting));
        Raise(nameof(ThinkingLabel));
    }

    private void AppendContentRaw(string s)
    {
        Data.Content += s;
        Raise(nameof(Content));
        _renderer.Update(Data.Content, Data.IsError);
    }

    /// <summary>把缓冲的流式分片并入数据模型并增量渲染(StreamPacer 定时/收尾调用,UI 线程)。</summary>
    private void Flush()
    {
        var hasContent = _contentBuf.Length > 0;
        if (hasContent)
        {
            Data.Content += _contentBuf.ToString();
            _contentBuf.Clear();
        }
        if (_reasonBuf.Length > 0)
        {
            Data.Reasoning += _reasonBuf.ToString();
            _reasonBuf.Clear();
            Raise(nameof(Reasoning));
        }
        if (hasContent)
        {
            Raise(nameof(Content));
            _renderer.Update(Data.Content, Data.IsError);
        }
        Raise(nameof(ShowWaiting));
        Raise(nameof(ThinkingLabel));
        Flushed?.Invoke();
    }
}

public class ConversationVM : ObservableObject
{
    public ConversationVM(ConversationData data)
    {
        Data = data;
        // 跳过中断留下的空助手消息,避免渲染空气泡
        foreach (var m in data.Messages.Where(m =>
                     !(m.Role == "assistant" && m.Content.Length == 0 && m.Reasoning.Length == 0)))
            Messages.Add(new ChatMessageVM(m));
    }

    public ConversationData Data { get; }
    public ObservableCollection<ChatMessageVM> Messages { get; } = new();

    /// <summary>汇总本对话全部 HTTP 记录(按消息顺序)。</summary>
    public List<HttpExchange> CollectHttp() =>
        Messages.SelectMany(m => m.HttpLog).ToList();

    public string Title
    {
        get => Data.Title;
        set
        {
            Data.Title = value;
            Raise();
        }
    }

    public DateTime UpdatedAt => Data.UpdatedAt;
    public string MessageCountText => Messages.Count == 0 ? "" : $"{Messages.Count} 条消息";

    /// <summary>按实际发送范围(最近 30 条非错误消息)粗略估算的上下文 token 用量。</summary>
    public int EstimatedTokens
    {
        get
        {
            var texts = Data.Messages
                .Where(m => !m.IsError && !string.IsNullOrWhiteSpace(m.Content) &&
                            (m.Role == "user" || m.Role == "assistant"))
                .Select(m => m.Content)
                .ToList();
            if (texts.Count > 30) texts = texts.Skip(texts.Count - 30).ToList();
            return TokenEstimator.Estimate(texts);
        }
    }

    public ChatMessageVM AddUser(string text)
    {
        var d = new ChatMessageData { Role = "user", Content = text, Time = DateTime.Now };
        Data.Messages.Add(d);
        var vm = new ChatMessageVM(d);
        Hook(vm);
        Messages.Add(vm);
        Raise(nameof(MessageCountText));
        Raise(nameof(EstimatedTokens));
        return vm;
    }

    public ChatMessageVM AddAssistant()
    {
        var d = new ChatMessageData { Role = "assistant", Time = DateTime.Now };
        Data.Messages.Add(d);
        var vm = new ChatMessageVM(d, streaming: true);
        Hook(vm);
        Messages.Add(vm);
        Raise(nameof(MessageCountText));
        return vm;
    }

    // 正文变化(含流式输出)时刷新上下文用量环
    private void Hook(ChatMessageVM vm)
    {
        vm.PropertyChanged += (_, e) =>
        {
            if (e.PropertyName == nameof(ChatMessageVM.Content)) Raise(nameof(EstimatedTokens));
        };
    }

    /// <summary>插入压缩标记:旧消息保留,标记只作为后续请求的上下文边界。</summary>
    public ChatMessageVM AddCompact(string summary, int count)
    {
        var d = new ChatMessageData { Role = "compact", Content = summary, Time = DateTime.Now, CompactedCount = count };
        Data.Messages.Add(d);
        var vm = new ChatMessageVM(d);
        Hook(vm);
        Messages.Add(vm);
        Raise(nameof(MessageCountText));
        Raise(nameof(EstimatedTokens));
        return vm;
    }

    /// <summary>最后一个压缩标记之后的消息条数,作为下次压缩的覆盖计数。</summary>
    public int CountAfterLastCompact()
    {
        var msgs = Data.Messages;
        int start = 0;
        for (int i = msgs.Count - 1; i >= 0; i--)
            if (msgs[i].Role == "compact") { start = i + 1; break; }
        return msgs.Count - start;
    }

    /// <summary>
    /// 按实际发送范围构建对话轮次:只取最后一个压缩标记之后的消息;
    /// 若存在标记,把摘要内容作为首条 user 消息插入。
    /// </summary>
    public List<ChatTurn> BuildTurns(int maxTurns = 30)
    {
        var msgs = Data.Messages;
        int start = 0;
        for (int i = msgs.Count - 1; i >= 0; i--)
            if (msgs[i].Role == "compact") { start = i + 1; break; }
        var turns = msgs.Skip(start)
            .Where(m => !m.IsError && !string.IsNullOrWhiteSpace(m.Content) &&
                        (m.Role == "user" || m.Role == "assistant"))
            .Select(m => new ChatTurn(m.Role, m.Content))
            .ToList();
        if (turns.Count > maxTurns) turns = turns.Skip(turns.Count - maxTurns).ToList();
        for (int i = start - 1; i >= 0; i--)
            if (msgs[i].Role == "compact")
            {
                turns.Insert(0, new ChatTurn("user", "[上下文摘要]\n" + msgs[i].Content));
                break;
            }
        return turns;
    }

    public void Refresh()
    {
        Raise(nameof(UpdatedAt));
        Raise(nameof(Title));
    }
}

public class MainViewModel : ObservableObject
{
    private readonly SettingsStore _store = new();
    private readonly System.Windows.Threading.Dispatcher _dispatcher = Application.Current.Dispatcher;
    private CancellationTokenSource? _cts;

    public AppSettings Settings { get; }

    public ObservableCollection<ConversationVM> Conversations { get; } = new();
    public ObservableCollection<ModelOption> ModelOptions { get; } = new();

    private ConversationVM? _selected;
    public ConversationVM? SelectedConversation
    {
        get => _selected;
        set
        {
            if (Set(ref _selected, value) && value != null) RestoreModelFor(value);
        }
    }

    private ModelOption? _model;
    public ModelOption? SelectedModel
    {
        get => _model;
        set
        {
            if (!Set(ref _model, value)) return;
            Settings.CurrentProviderId = value?.Provider.Id;
            Settings.CurrentModelId = value?.Model.Id;
            if (_selected != null)
            {
                _selected.Data.ProviderId = value?.Provider.Id;
                _selected.Data.ModelId = value?.Model.Id;
            }
            Save();
        }
    }

    private string _input = "";
    public string InputText
    {
        get => _input;
        set
        {
            if (Set(ref _input, value))
                CommandManager.InvalidateRequerySuggested();
        }
    }

    private bool _thinking;
    public bool ThinkingEnabled
    {
        get => _thinking;
        set
        {
            if (Set(ref _thinking, value))
            {
                Settings.ThinkingEnabled = value;
                Save();
            }
        }
    }

    private bool _streaming;
    public bool IsStreaming
    {
        get => _streaming;
        private set => Set(ref _streaming, value);
    }

    public bool HasModels => ModelOptions.Count > 0;

    public event Action? Streamed;

    public RelayCommand NewChatCommand { get; }
    public RelayCommand SendCommand { get; }
    public RelayCommand StopCommand { get; }
    public RelayCommand OpenSettingsCommand { get; }
    public RelayCommand DeleteConversationCommand { get; }
    public RelayCommand RenameConversationCommand { get; }
    public RelayCommand ExportHttpCommand { get; }
    public RelayCommand CompactContextCommand { get; }
    public RelayCommand CopyMessageCommand { get; }

    public MainViewModel()
    {
        Settings = _store.Load();
        _thinking = Settings.ThinkingEnabled;

        foreach (var c in Settings.Conversations.OrderByDescending(c => c.UpdatedAt))
            Conversations.Add(new ConversationVM(c));
        _selected = Conversations.FirstOrDefault();

        RebuildModelOptions();

        // 字号设置变化时,按新字号重建所有会话消息的 Markdown 渲染
        FontSettingsService.Instance.Changed += () =>
        {
            foreach (var c in Conversations)
                foreach (var m in c.Messages)
                    m.RefreshFont();
        };

        NewChatCommand = new RelayCommand(_ => NewChat());
        SendCommand = new RelayCommand(_ => _ = SendAsync(),
            _ => !IsStreaming && !string.IsNullOrWhiteSpace(InputText));
        StopCommand = new RelayCommand(_ => _cts?.Cancel(), _ => IsStreaming);
        OpenSettingsCommand = new RelayCommand(_ => OpenSettings());
        DeleteConversationCommand = new RelayCommand(o => DeleteConversation(o as ConversationVM));
        RenameConversationCommand = new RelayCommand(o => RenameConversation(o as ConversationVM));
        ExportHttpCommand = new RelayCommand(o => ExportHttp(o as ConversationVM));
        CompactContextCommand = new RelayCommand(_ => _ = CompactAsync(),
            _ => !IsStreaming && (_selected?.Messages.Count ?? 0) > 0);
        CopyMessageCommand = new RelayCommand(o =>
        {
            if (o is ChatMessageVM m)
            {
                var text = m.Content.Length > 0 ? m.Content : m.Reasoning;
                if (text.Length > 0)
                    try { Clipboard.SetText(text); } catch { }
            }
        });
    }

    /// <summary>启动后尝试从本地 Ollama 自动导入模型列表(仅第一次)。</summary>
    public async Task InitializeAsync()
    {
        try
        {
            var changed = false;
            foreach (var p in Settings.Providers
                         .Where(p => p.Enabled && p.Format == ApiFormat.Ollama && !p.AutoImported)
                         .ToList())
            {
                List<string> ids;
                try { ids = await LlmClient.GetModelIdsAsync(p); }
                catch { continue; }
                if (ids.Count == 0) continue;

                var existing = p.Models.ToDictionary(m => m.Id, m => m, StringComparer.Ordinal);
                var news = new List<ModelConfig>();
                foreach (var id in ids)
                {
                    if (existing.TryGetValue(id, out var keep))
                    {
                        news.Add(keep);
                        continue;
                    }
                    var mc = new ModelConfig { Id = id };
                    try
                    {
                        var ctx = await LlmClient.FetchContextLengthAsync(p, id);
                        if (ctx is long c && c > 0)
                        {
                            mc.ContextWindow = (int)Math.Min(int.MaxValue, c);
                            mc.MaxOutputTokens = Math.Clamp(mc.ContextWindow / 4, 256, 8192);
                        }
                    }
                    catch { }
                    news.Add(mc);
                }
                p.Models = news;
                p.AutoImported = true;
                changed = true;
            }
            if (changed)
            {
                Save();
                RebuildModelOptions();
            }
        }
        catch { }
    }

    public void Save() => _store.Save(Settings);

    public void NewChat()
    {
        var c = new ConversationData();
        Settings.Conversations.Add(c);
        var vm = new ConversationVM(c);
        Conversations.Insert(0, vm);
        _selected = vm; // 直接赋值避免触发模型恢复
        Raise(nameof(SelectedConversation));
        Save();
    }

    private async Task SendAsync()
    {
        if (IsStreaming) return;
        var text = InputText.Trim();
        if (text.Length == 0) return;

        var opt = SelectedModel;
        if (opt is null)
        {
            MessageBox.Show("请先点击右上角齿轮打开「模型服务设置」,添加并启用至少一个模型。",
                "未选择模型", MessageBoxButton.OK, MessageBoxImage.Information);
            return;
        }

        if (_selected is null) NewChat();
        var conv = _selected!;
        if (conv.Messages.Count == 0)
            conv.Title = text.Length > 18 ? text[..18] + "…" : text;
        conv.AddUser(text);
        InputText = "";

        var reply = conv.AddAssistant();
        reply.Flushed += () => Streamed?.Invoke(); // 滚动跟随挂在节流刷新上
        IsStreaming = true;
        _cts = new CancellationTokenSource();
        var token = _cts.Token;

        var turns = conv.BuildTurns();

        Streamed?.Invoke();
        try
        {
            await Task.Run(() => LlmClient.StreamAsync(opt.Provider, opt.Model, turns,
                opt.Model.SystemPrompt, ThinkingEnabled,
                s => Post(() => reply.AppendContent(s)),
                s => Post(() => reply.AppendReasoning(s)),
                token, reply.HttpLog), token);
            reply.Finish();
        }
        catch (OperationCanceledException)
        {
            reply.Finish(cancelled: true);
        }
        catch (Exception ex)
        {
            reply.Fail(ex.Message);
        }
        finally
        {
            _cts.Dispose();
            _cts = null;
            IsStreaming = false;
            conv.Data.UpdatedAt = DateTime.Now;
            conv.Data.ProviderId = opt.Provider.Id;
            conv.Data.ModelId = opt.Model.Id;
            conv.Refresh();
            Save();
            CommandManager.InvalidateRequerySuggested();
        }
    }

    private void Post(Action a) => _dispatcher.BeginInvoke(a);

    private void RebuildModelOptions()
    {
        var opts = Settings.Providers
            .Where(p => p.Enabled)
            .SelectMany(p => p.Models.Where(m => m.Enabled).Select(m => new ModelOption(p, m)))
            .ToList();
        ModelOptions.Clear();
        foreach (var o in opts) ModelOptions.Add(o);

        ModelOption? sel = null;
        if (_selected?.Data.ProviderId is { } pid && _selected.Data.ModelId is { } mid)
            sel = opts.FirstOrDefault(o => o.Provider.Id == pid && o.Model.Id == mid);
        sel ??= opts.FirstOrDefault(o =>
            o.Provider.Id == Settings.CurrentProviderId && o.Model.Id == Settings.CurrentModelId);
        sel ??= opts.FirstOrDefault(); // 首次启动无保存记录时,自动选中第一个可用模型

        _model = sel;
        Raise(nameof(SelectedModel));
        Raise(nameof(HasModels));
    }

    private void RestoreModelFor(ConversationVM c)
    {
        if (c.Data.ProviderId is null || c.Data.ModelId is null) return;
        var m = ModelOptions.FirstOrDefault(o =>
            o.Provider.Id == c.Data.ProviderId && o.Model.Id == c.Data.ModelId);
        if (m != null && !ReferenceEquals(m, _model))
        {
            _model = m;
            Raise(nameof(SelectedModel));
        }
    }

    /// <summary>打开设置窗口(左侧导航:显示设置/模型服务);关闭后统一落盘并刷新模型选项。</summary>
    private void OpenSettings()
    {
        var w = new SettingsWindow(Settings) { Owner = Application.Current.MainWindow };
        w.ShowDialog();
        // 显示设置页的字号即时生效在服务里,关闭窗口时同步回设置并保存
        Settings.UiFontSize = FontSettingsService.Instance.UiFontSize;
        Settings.ChatFontSize = FontSettingsService.Instance.ChatFontSize;
        Save();
        RebuildModelOptions();
    }

    public void DeleteConversation(ConversationVM? c)
    {
        if (c is null) return;
        var r = MessageBox.Show($"确定删除对话「{c.Title}」吗?", "删除对话",
            MessageBoxButton.YesNo, MessageBoxImage.Question);
        if (r != MessageBoxResult.Yes) return;
        if (IsStreaming && _selected == c) _cts?.Cancel();
        Settings.Conversations.Remove(c.Data);
        Conversations.Remove(c);
        if (_selected == c)
        {
            _selected = Conversations.FirstOrDefault();
            Raise(nameof(SelectedConversation));
        }
        Save();
    }

    public void RenameConversation(ConversationVM? c)
    {
        if (c is null) return;
        var name = InputDialog.Ask(Application.Current.MainWindow, "重命名对话", c.Title);
        if (!string.IsNullOrWhiteSpace(name) && name.Trim() != c.Title)
        {
            c.Title = name.Trim();
            Save();
        }
    }

    private void ExportHttp(ConversationVM? c)
    {
        if (c is null) return;
        var list = c.CollectHttp();
        if (list.Count == 0)
        {
            MessageBox.Show("该对话还没有 HTTP 记录,先完成一次对话再导出。",
                "导出 HTTP 数据", MessageBoxButton.OK, MessageBoxImage.Information);
            return;
        }
        var model = "";
        if (c.Data.ProviderId is { } pid && c.Data.ModelId is { } mid)
            model = $"{Settings.Providers.FirstOrDefault(p => p.Id == pid)?.Name} · {mid}";
        var dlg = new Microsoft.Win32.SaveFileDialog
        {
            Title = "导出 HTTP 数据",
            FileName = SafeFileName(c.Title) + "-http.txt",
            Filter = "文本文件 (*.txt)|*.txt|全部文件 (*.*)|*.*"
        };
        if (dlg.ShowDialog(Application.Current.MainWindow) != true) return;
        File.WriteAllText(dlg.FileName, HttpLogFormatter.Build(c.Title, model, list));
        MessageBox.Show($"已导出 {list.Count} 次 HTTP 请求到:\n{dlg.FileName}", "导出 HTTP 数据",
            MessageBoxButton.OK, MessageBoxImage.Information);
    }

    private static string SafeFileName(string s)
    {
        s = s.Trim();
        foreach (var ch in Path.GetInvalidFileNameChars()) s = s.Replace(ch, '_');
        return s.Length == 0 ? "对话" : s;
    }

    /// <summary>用当前模型把对话历史压缩成一条摘要,替换原有消息,减少上下文占用。</summary>
    private async Task CompactAsync()
    {
        if (IsStreaming) return;
        var conv = _selected;
        var opt = SelectedModel;
        if (conv is null || opt is null || conv.Messages.Count == 0) return;

        var r = MessageBox.Show(
            $"将把「{conv.Title}」的全部历史压缩为一条摘要,原消息会被替换。继续吗?",
            "压缩上下文", MessageBoxButton.YesNo, MessageBoxImage.Question);
        if (r != MessageBoxResult.Yes) return;

        var count = conv.CountAfterLastCompact();
        var turns = conv.BuildTurns(maxTurns: int.MaxValue);
        if (turns.Count == 0) return;
        // 摘要指令作为最后的用户消息发送,不走顶层 system 字段
        // (部分 Anthropic 协议网关带 system 时会返回空响应)
        turns.Add(new ChatTurn("user", CompactPrompt));

        IsStreaming = true;
        _cts = new CancellationTokenSource();
        var token = _cts.Token;
        var content = new System.Text.StringBuilder();
        var reasoning = new System.Text.StringBuilder();
        var httpLog = new List<HttpExchange>();
        try
        {
            // 先按关闭思考请求;拿不到内容再开启思考重试(部分思考型模型在禁用状态下不输出正文)
            await RequestSummary(opt, turns, token, content, reasoning, httpLog, think: false);
            var summary = ExtractSummary(content, reasoning);
            if (summary.Length == 0)
            {
                content.Clear();
                reasoning.Clear();
                await RequestSummary(opt, turns, token, content, reasoning, httpLog, think: true);
                summary = ExtractSummary(content, reasoning);
            }
            if (summary.Length == 0)
            {
                var raw = httpLog.Count > 0 ? httpLog[^1].ResponseBody.ToString().Trim() : "";
                if (raw.Length > 600) raw = raw[..600] + "…";
                throw new InvalidOperationException(string.IsNullOrWhiteSpace(raw)
                    ? "模型未返回任何内容"
                    : "模型未返回摘要,服务端原始响应:\n" + raw);
            }

            conv.AddCompact(summary, count);
            conv.Data.UpdatedAt = DateTime.Now;
            conv.Data.ProviderId = opt.Provider.Id;
            conv.Data.ModelId = opt.Model.Id;
            conv.Refresh();
            Save();
        }
        catch (OperationCanceledException)
        {
            // 用户取消:保留原历史不动
        }
        catch (Exception ex)
        {
            MessageBox.Show("压缩失败:" + ex.Message, "压缩上下文",
                MessageBoxButton.OK, MessageBoxImage.Warning);
        }
        finally
        {
            _cts.Dispose();
            _cts = null;
            IsStreaming = false;
            CommandManager.InvalidateRequerySuggested();
        }
    }

    private const string CompactPrompt =
        "你是对话摘要器。请把下面的对话历史压缩成一段简洁摘要,保留:用户的核心需求与约束、已得到的关键结论与数据、尚未解决的问题。直接输出摘要正文,不要任何评论或开场白。";

    private static async Task RequestSummary(ModelOption opt, List<ChatTurn> turns, CancellationToken token,
        System.Text.StringBuilder content, System.Text.StringBuilder reasoning,
        List<HttpExchange> httpLog, bool think)
    {
        var log = new List<HttpExchange>();
        await Task.Run(() => LlmClient.StreamAsync(opt.Provider, opt.Model, turns,
            null, think, s => content.Append(s), s => reasoning.Append(s), token, log), token);
        httpLog.AddRange(log);
    }

    private static string ExtractSummary(System.Text.StringBuilder content, System.Text.StringBuilder reasoning)
    {
        var summary = content.ToString().Trim();
        if (summary.Length == 0) summary = reasoning.ToString().Trim();
        if (summary.Contains("<think>", StringComparison.Ordinal))
            summary = System.Text.RegularExpressions.Regex
                .Replace(summary, "<think>.*?</think>", "",
                    System.Text.RegularExpressions.RegexOptions.Singleline).Trim();
        return summary;
    }
}

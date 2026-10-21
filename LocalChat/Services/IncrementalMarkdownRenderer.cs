using System.Collections.ObjectModel;
using System.Windows;

namespace LocalChat.Services;

/// <summary>
/// 增量 Markdown 渲染缓冲:重新解析后按块键值与上次对比,只重建发生变化的块,
/// 复用未变化块的 UI 元素;配合 ObservableCollection 让 ItemsControl 只重新生成
/// 变化块的容器。流式输出长回复时避免整体重建造成的界面卡死。
/// </summary>
public sealed class IncrementalMarkdownRenderer
{
    private List<string> _keys = new();

    /// <summary>渲染结果块集合,可直接作为 ItemsControl 的 ItemsSource。</summary>
    public ObservableCollection<UIElement> Blocks { get; } = new();

    /// <summary>清空块键缓存,下次 Update 时全部块按当前状态重建(字号设置变化后调用)。</summary>
    public void Reset()
    {
        _keys.Clear();
        Blocks.Clear();
    }

    public void Update(string markdown, bool error = false)
    {
        if (markdown.Length == 0)
        {
            if (_keys.Count > 0)
            {
                _keys.Clear();
                Blocks.Clear();
            }
            return;
        }

        var parsed = MarkdownParser.Parse(markdown);
        var keys = new List<string>(parsed.Count);
        foreach (var b in parsed)
            keys.Add(MarkdownParser.BlockKey(b));

        int common = 0;
        int max = Math.Min(keys.Count, _keys.Count);
        while (common < max && keys[common] == _keys[common]) common++;

        for (int i = _keys.Count - 1; i >= keys.Count; i--)
            Blocks.RemoveAt(i); // 新内容块数变少:移除多余旧块
        for (int i = common; i < keys.Count && i < _keys.Count; i++)
            Blocks[i] = MarkdownRenderer.RenderBlock(parsed[i], error); // 公共前缀之后位置重叠的块替换
        for (int i = _keys.Count; i < keys.Count; i++)
            Blocks.Add(MarkdownRenderer.RenderBlock(parsed[i], error)); // 新增块追加

        _keys = keys;
    }
}

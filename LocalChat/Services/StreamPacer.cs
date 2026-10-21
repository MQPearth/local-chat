using System.Windows.Threading;

namespace LocalChat.Services;

/// <summary>
/// 流式刷新节流器:高频到达的分片只做缓冲标记,由 DispatcherTimer 按固定间隔统一刷新,
/// 避免长回复逐片重建界面造成 UI 卡死。所有成员须在 UI 线程调用。
/// </summary>
public sealed class StreamPacer
{
    private readonly DispatcherTimer _timer;
    private readonly Action _flush;
    private bool _dirty;

    public StreamPacer(Action flush, int intervalMs = 100)
    {
        _flush = flush;
        _timer = new DispatcherTimer { Interval = TimeSpan.FromMilliseconds(intervalMs) };
        _timer.Tick += (_, _) => Fire();
    }

    /// <summary>有新分片到达(UI 线程):标记待刷新,确保定时器运行中。</summary>
    public void Touch()
    {
        _dirty = true;
        if (!_timer.IsEnabled) _timer.Start();
    }

    /// <summary>流结束(UI 线程):停止定时并立即完成最后一次刷新。</summary>
    public void Complete()
    {
        _timer.Stop();
        Fire();
    }

    private void Fire()
    {
        if (!_dirty)
        {
            _timer.Stop();
            return;
        }
        _dirty = false;
        _flush();
    }
}

using System.Globalization;
using System.Windows;
using System.Windows.Data;
using System.Windows.Media;

namespace LocalChat;

public class BoolToVisibilityConverter : IValueConverter
{
    public bool Inverse { get; set; }

    public object Convert(object? value, Type t, object? p, CultureInfo c)
    {
        bool b = value is bool v && v;
        if (Inverse || string.Equals(p as string, "inv", StringComparison.Ordinal)) b = !b;
        return b ? Visibility.Visible : Visibility.Collapsed;
    }

    public object ConvertBack(object? value, Type t, object? p, CultureInfo c) =>
        value is Visibility v && v == Visibility.Visible;
}

public class EmptyToVisibilityConverter : IValueConverter
{
    public bool Inverse { get; set; }

    public object Convert(object? value, Type t, object? p, CultureInfo c)
    {
        bool empty = value == DependencyProperty.UnsetValue || value is null ||
                     (value is string s && s.Length == 0);
        // 默认:非空显示;Inverse:为空显示
        bool show = (Inverse || string.Equals(p as string, "inv", StringComparison.Ordinal)) ? empty : !empty;
        return show ? Visibility.Visible : Visibility.Collapsed;
    }

    public object ConvertBack(object? value, Type t, object? p, CultureInfo c) => Binding.DoNothing;
}

public class CountToVisibilityConverter : IValueConverter
{
    // 计数为 0(或绑定失败)时显示元素
    public object Convert(object? value, Type t, object? p, CultureInfo c)
    {
        int count = value is int i ? i : 0;
        bool show = count <= 0;
        if (string.Equals(p as string, "inv", StringComparison.Ordinal)) show = !show;
        return show ? Visibility.Visible : Visibility.Collapsed;
    }

    public object ConvertBack(object? value, Type t, object? p, CultureInfo c) => Binding.DoNothing;
}

public class TimeTextConverter : IValueConverter
{
    public object Convert(object? value, Type t, object? p, CultureInfo c)
    {
        if (value is not DateTime d) return "";
        var now = DateTime.Now;
        if (d.Date == now.Date) return d.ToString("HH:mm");
        if (d.Year == now.Year) return d.ToString("M月d日");
        return d.ToString("yyyy/M/d");
    }

    public object ConvertBack(object? value, Type t, object? p, CultureInfo c) => Binding.DoNothing;
}

public class CtxBadgeConverter : IValueConverter
{
    public object Convert(object? value, Type t, object? p, CultureInfo c)
    {
        if (value is not int v || v <= 0) return "--";
        if (v >= 1024)
        {
            double k = v / 1024.0;
            return (Math.Abs(k - Math.Round(k)) < 0.05 ? ((int)Math.Round(k)).ToString() : k.ToString("0.#")) + "K";
        }
        return v + "";
    }

    public object ConvertBack(object? value, Type t, object? p, CultureInfo c) => Binding.DoNothing;
}

public class CornerRadiusConverter : IValueConverter
{
    public object Convert(object? value, Type t, object? p, CultureInfo culture)
    {
        if (value is string s && double.TryParse(s, out var d1)) return new CornerRadius(d1);
        if (value is double d2) return new CornerRadius(d2);
        return new CornerRadius(8d);
    }

    public object ConvertBack(object? value, Type t, object? p, CultureInfo culture) => Binding.DoNothing;
}

/// <summary>values[0]=已用 tokens,values[1]=上下文窗口;返回从正上方顺时针的用量弧线(圆心 13,13 半径 9)。</summary>
public class RingArcConverter : IMultiValueConverter
{
    public object Convert(object?[] values, Type targetType, object? parameter, CultureInfo culture)
    {
        double used = values.Length > 0 && values[0] is int u ? u : 0;
        double total = values.Length > 1 && values[1] is int t ? t : 0;
        if (total <= 0 || used <= 0) return Geometry.Empty;
        if (used >= total) return new EllipseGeometry(new Point(13, 13), 9, 9);
        var angle = Math.Clamp(used / total, 0, 1) * 2 * Math.PI;
        var x = 13 + 9 * Math.Sin(angle);
        var y = 13 - 9 * Math.Cos(angle);
        var ci = CultureInfo.InvariantCulture;
        return Geometry.Parse(string.Format(ci, "M 13,4 A 9,9 0 {0} 1 {1},{2}",
            angle > Math.PI ? 1 : 0, x.ToString("0.##", ci), y.ToString("0.##", ci)));
    }

    public object?[] ConvertBack(object? value, Type[] targetTypes, object? parameter, CultureInfo culture) =>
        Array.Empty<object?>();
}

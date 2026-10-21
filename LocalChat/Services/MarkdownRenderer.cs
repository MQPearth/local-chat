using System.Diagnostics;
using System.Windows;
using System.Windows.Controls;
using System.Windows.Documents;
using System.Windows.Media;

namespace LocalChat.Services;

/// <summary>把 Markdown 中间模型构建为 WPF 元素树(段落/标题/代码块/引用/列表/表格/分隔线)。</summary>
public static class MarkdownRenderer
{
    private static readonly Brush ErrorBrush = Hex("#D5423F");
    private static readonly Brush MutedBrush = Hex("#6F7680");
    private static readonly Brush CodeBg = Hex("#F6F8FA");
    private static readonly Brush BorderBrush = Hex("#E7EAED");
    private static readonly Brush QuoteAccent = Hex("#D0D7DE");
    private static readonly Brush LinkBrush = Hex("#0969DA");
    private static readonly Brush InlineCodeBg = Hex("#EEF0F3");

    private static Brush Hex(string s) => new SolidColorBrush((Color)ColorConverter.ConvertFromString(s));

    /// <param name="error">整条消息为错误提示时,正文以红色显示。</param>
    public static List<UIElement> Build(string markdown, bool error = false) =>
        MarkdownParser.Parse(markdown).Select(b => Block(b, error)).ToList();

    private static UIElement Block(MdBlock b, bool error) => b switch
    {
        MdCode c => CodeBlock(c),
        MdHeading h => Heading(h),
        MdRule => new Border { Height = 1, Background = BorderBrush, Margin = new Thickness(0, 9, 0, 5) },
        MdQuote q => Quote(q),
        MdItem it => Item(it),
        MdTable tb => Table(tb),
        _ => Paragraph((MdParagraph)b, error),
    };

    private static TextBlock Paragraph(MdParagraph p, bool error)
    {
        var tb = BaseText(error);
        AddInlines(tb, p.Inlines);
        tb.Margin = new Thickness(0, 4, 0, 4);
        return tb;
    }

    private static TextBlock BaseText(bool error = false) => new()
    {
        Foreground = error ? ErrorBrush : Hex("#1F2328"),
        FontSize = 13.5,
        LineHeight = 21,
        TextWrapping = TextWrapping.Wrap
    };

    private static TextBlock Heading(MdHeading h)
    {
        var tb = BaseText();
        tb.FontWeight = FontWeights.Bold;
        tb.FontSize = h.Level switch { 1 => 19.0, 2 => 17.0, 3 => 15.5, _ => 14.0 };
        tb.LineHeight = tb.FontSize * 1.45;
        tb.Margin = new Thickness(0, h.Level <= 2 ? 10 : 8, 0, 4);
        AddInlines(tb, h.Inlines);
        return tb;
    }

    private static UIElement CodeBlock(MdCode c)
    {
        var border = new Border
        {
            Background = CodeBg,
            BorderBrush = BorderBrush,
            BorderThickness = new Thickness(1),
            CornerRadius = new CornerRadius(8),
            Padding = new Thickness(11, 8, 11, 8),
            Margin = new Thickness(0, 6, 0, 6)
        };
        var sp = new StackPanel();
        if (c.Lang.Length > 0)
            sp.Children.Add(new TextBlock
            {
                Text = c.Lang,
                FontSize = 11,
                Foreground = MutedBrush,
                Margin = new Thickness(0, 0, 0, 4)
            });
        sp.Children.Add(new TextBlock
        {
            Text = c.Code,
            FontFamily = new FontFamily("Consolas"),
            FontSize = 12.5,
            LineHeight = 19,
            Foreground = Hex("#24292F"),
            TextWrapping = TextWrapping.Wrap
        });
        border.Child = sp;
        return border;
    }

    private static UIElement Quote(MdQuote q)
    {
        var border = new Border
        {
            Background = Hex("#F8F9FB"),
            BorderBrush = QuoteAccent,
            BorderThickness = new Thickness(3, 0, 0, 0),
            CornerRadius = new CornerRadius(0, 6, 6, 0),
            Padding = new Thickness(10, 6, 10, 6),
            Margin = new Thickness(0, 4, 0, 4)
        };
        var tb = BaseText();
        tb.Foreground = MutedBrush;
        tb.FontStyle = FontStyles.Italic;
        AddInlines(tb, q.Inlines);
        border.Child = tb;
        return border;
    }

    private static UIElement Item(MdItem it)
    {
        var sp = new StackPanel
        {
            Orientation = Orientation.Horizontal,
            Margin = new Thickness(6 + it.Level * 16, 2, 0, 2)
        };
        sp.Children.Add(new TextBlock
        {
            Text = it.Marker + " ",
            Foreground = MutedBrush,
            FontSize = 13.5,
            LineHeight = 21,
            VerticalAlignment = VerticalAlignment.Top
        });
        var tb = BaseText();
        AddInlines(tb, it.Inlines);
        sp.Children.Add(tb);
        return sp;
    }

    private static UIElement Table(MdTable tb)
    {
        var grid = new Grid { Margin = new Thickness(0, 6, 0, 6) };
        for (int c = 0; c < tb.Header.Count; c++)
            grid.ColumnDefinitions.Add(new ColumnDefinition { Width = new GridLength(1, GridUnitType.Star) });
        AddRow(grid, tb.Header, bold: true);
        foreach (var row in tb.Rows)
            AddRow(grid, row, bold: false);
        return new Border { BorderBrush = BorderBrush, BorderThickness = new Thickness(1, 1, 0, 0), Child = grid };
    }

    private static void AddRow(Grid grid, IReadOnlyList<IReadOnlyList<MdInline>> cells, bool bold)
    {
        grid.RowDefinitions.Add(new RowDefinition { Height = GridLength.Auto });
        for (int c = 0; c < grid.ColumnDefinitions.Count; c++)
        {
            var border = new Border
            {
                BorderBrush = BorderBrush,
                BorderThickness = new Thickness(0, 0, 1, 1),
                Background = bold ? CodeBg : Brushes.Transparent,
                Padding = new Thickness(8, 5, 8, 5)
            };
            var text = BaseText();
            text.FontSize = 12.5;
            if (bold) text.FontWeight = FontWeights.Bold;
            if (c < cells.Count) AddInlines(text, cells[c]);
            border.Child = text;
            Grid.SetColumn(border, c);
            Grid.SetRow(border, grid.RowDefinitions.Count - 1);
            grid.Children.Add(border);
        }
    }

    private static void AddInlines(TextBlock tb, IReadOnlyList<MdInline> inlines)
    {
        foreach (var mi in inlines)
            tb.Inlines.Add(ToInline(mi));
    }

    private static Inline ToInline(MdInline mi) => mi switch
    {
        MdLink link => Link(link),
        MdRun r => RunOf(r),
        _ => new Run(""),
    };

    private static Run RunOf(MdRun r)
    {
        var run = new Run(r.Text);
        if (r.Bold) run.FontWeight = FontWeights.Bold;
        if (r.Italic) run.FontStyle = FontStyles.Italic;
        if (r.Strike) run.TextDecorations = TextDecorations.Strikethrough;
        if (r.Code)
        {
            run.FontFamily = new FontFamily("Consolas");
            run.FontSize = 12.5;
            run.Background = InlineCodeBg;
        }
        return run;
    }

    private static Hyperlink Link(MdLink l)
    {
        var hl = new Hyperlink(new Run(l.Text)) { Foreground = LinkBrush };
        if (Uri.TryCreate(l.Url, UriKind.Absolute, out var uri) &&
            (uri.Scheme == Uri.UriSchemeHttp || uri.Scheme == Uri.UriSchemeHttps))
        {
            hl.NavigateUri = uri;
            hl.RequestNavigate += (_, e) =>
            {
                try { Process.Start(new ProcessStartInfo(e.Uri.AbsoluteUri) { UseShellExecute = true }); }
                catch { }
                e.Handled = true;
            };
        }
        return hl;
    }
}

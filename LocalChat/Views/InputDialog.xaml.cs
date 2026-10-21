using System.Windows;
using System.Windows.Input;

namespace LocalChat.Views;

public partial class InputDialog : Window
{
    public InputDialog() => InitializeComponent();

    public string Value => ValueBox.Text.Trim();

    public static string? Ask(Window? owner, string title, string initial)
    {
        var d = new InputDialog { Title = title, Owner = owner };
        d.ValueBox.Text = initial;
        d.ValueBox.SelectAll();
        d.Loaded += (_, _) => d.ValueBox.Focus();
        return d.ShowDialog() == true ? d.Value : null;
    }

    private void Ok_Click(object sender, RoutedEventArgs e) => DialogResult = true;

    private void Cancel_Click(object sender, RoutedEventArgs e) => DialogResult = false;

    private void ValueBox_PreviewKeyDown(object sender, KeyEventArgs e)
    {
        if (e.Key == Key.Enter)
        {
            DialogResult = true;
            e.Handled = true;
        }
        else if (e.Key == Key.Escape)
        {
            DialogResult = false;
            e.Handled = true;
        }
    }
}

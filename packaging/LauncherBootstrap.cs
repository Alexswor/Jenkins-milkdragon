using System;
using System.Diagnostics;
using System.IO;
using System.Windows.Forms;

internal static class LauncherBootstrap
{
    [STAThread]
    private static void Main()
    {
        string baseDirectory = AppDomain.CurrentDomain.BaseDirectory;
        string runtimeDirectory = Path.Combine(baseDirectory, "resources");
        string target = Path.Combine(runtimeDirectory, "runtime.exe");

        try
        {
            if (!File.Exists(target))
            {
                throw new FileNotFoundException("The application runtime launcher is missing.", target);
            }

            Process.Start(new ProcessStartInfo
            {
                FileName = target,
                WorkingDirectory = runtimeDirectory,
                UseShellExecute = true
            });
        }
        catch (Exception exception)
        {
            MessageBox.Show(
                "Unable to start Jenkins Deployment Assistant.\r\n\r\n" + exception.Message,
                "Jenkins Deployment Assistant",
                MessageBoxButtons.OK,
                MessageBoxIcon.Error);
            Environment.ExitCode = 1;
        }
    }
}

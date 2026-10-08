using System;
using System.IO;
using System.Text;

namespace Bimba.SiteAgent
{
    /// <summary>Append-only log in %ProgramData%\BIMBA Site Agent\logs (2 MB, one previous file kept).</summary>
    internal static class Log
    {
        private static readonly object Gate = new object();
        public static bool Console { get; set; }

        public static string FilePath => Path.Combine(Paths.LogDir, "agent.log");

        public static void Info(string message) => Write("INFO ", message);
        public static void Warn(string message) => Write("WARN ", message);
        public static void Error(string message) => Write("ERROR", message);

        private static void Write(string level, string message)
        {
            string line = $"{DateTime.UtcNow:yyyy-MM-dd HH:mm:ss}Z {level} {message}";
            if (Console) System.Console.WriteLine(line);
            lock (Gate)
            {
                try
                {
                    Directory.CreateDirectory(Paths.LogDir);
                    var f = new FileInfo(FilePath);
                    if (f.Exists && f.Length > 2 * 1024 * 1024)
                    {
                        string old = FilePath + ".1";
                        if (File.Exists(old)) File.Delete(old);
                        File.Move(FilePath, old);
                    }
                    File.AppendAllText(FilePath, line + Environment.NewLine, Encoding.UTF8);
                }
                catch
                {
                    // logging must never stop the agent (e.g. not elevated during "test")
                }
            }
        }
    }
}

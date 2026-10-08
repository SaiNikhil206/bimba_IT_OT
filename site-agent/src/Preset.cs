using System;
using System.Collections.Generic;
using System.IO;
using System.Text;

namespace Bimba.SiteAgent
{
    /// <summary>
    /// Settings the BIMBA Gateway stamps onto the end of the .exe when it is downloaded from the console
    /// ("Download installer"): the gateway address and the SHA-256 of its self-signed HTTPS certificate, so the
    /// person installing only enters an enrollment token or their e-mail address and password. Never a secret.
    /// Layout: ...exe bytes... "\r\n&lt;&lt;BIMBA-PRESET:" base64(JSON) "&gt;&gt;\r\n"
    /// </summary>
    internal sealed class Preset
    {
        private const string Start = "<<BIMBA-PRESET:";
        private const string End = ">>";

        public string Server { get; private set; }
        public string TrustCert { get; private set; }

        private static Preset cached;
        private static bool loaded;

        /// <summary>The preset of this .exe, or null for a plain build.</summary>
        public static Preset Current
        {
            get
            {
                if (!loaded)
                {
                    loaded = true;
                    try { cached = Read(Installer.SelfPath); } catch { cached = null; }
                }
                return cached;
            }
        }

        internal static Preset Read(string path)
        {
            using (var f = File.OpenRead(path))
            {
                int n = (int)Math.Min(f.Length, 4096);
                var buf = new byte[n];
                f.Seek(-n, SeekOrigin.End);
                int read = 0;
                while (read < n) { int r = f.Read(buf, read, n - read); if (r <= 0) break; read += r; }
                string tail = Encoding.ASCII.GetString(buf, 0, read).TrimEnd('\r', '\n');
                int a = tail.LastIndexOf(Start, StringComparison.Ordinal);
                if (a < 0 || !tail.EndsWith(End, StringComparison.Ordinal)) return null;
                string b64 = tail.Substring(a + Start.Length, tail.Length - a - Start.Length - End.Length);
                Dictionary<string, object> o = Json.Parse(Encoding.UTF8.GetString(Convert.FromBase64String(b64)));
                string server = Json.Str(o, "server");
                if (string.IsNullOrWhiteSpace(server)) return null;
                string pin = Json.Str(o, "trustCert");
                return new Preset
                {
                    Server = GatewayClient.Normalize(server),
                    TrustCert = pin != null && pin.Length == 64 ? pin.ToLowerInvariant() : null,
                };
            }
        }

        /// <summary>The preset certificate pin, only for the preset gateway address.</summary>
        public static string PinFor(string server)
        {
            var p = Current;
            if (p?.TrustCert == null || string.IsNullOrWhiteSpace(server)) return null;
            try
            {
                return string.Equals(GatewayClient.Normalize(server), p.Server, StringComparison.OrdinalIgnoreCase) ? p.TrustCert : null;
            }
            catch (ArgumentException)
            {
                return null;
            }
        }
    }
}

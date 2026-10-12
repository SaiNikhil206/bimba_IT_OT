using System;
using System.Collections.Generic;
using System.Diagnostics;
using System.Linq;
using System.Runtime.InteropServices;
using System.ServiceProcess;
using System.Text;
using System.Threading;
using System.Windows.Forms;

namespace Bimba.SiteAgent
{
    /// <summary>
    /// BIMBA-Site-Agent-Setup.exe
    ///   (no arguments)        setup wizard (asks for Setu, then an enrollment token or an organization user)
    ///   install ...           silent install for mass roll-out (see Usage)
    ///   uninstall [--quiet]   remove (also used by Apps / Programs and Features)
    ///   status                show what is installed
    ///   inventory             print the machine details that would be shared (nothing is sent)
    ///   test ...              check credentials: enroll + one heartbeat (+ client certificate) without installing anything
    ///   enroll ...            MSI step: register and write the configuration only (files + service belong to the MSI)
    ///   uninstall-notify      MSI step: tell Setu (through the service, which holds the certificate)
    ///   remove-data           MSI step: delete the configuration and logs
    ///   service               entry point of the Windows service
    /// </summary>
    internal static class Program
    {
        private const string Usage = @"BIMBA Site Agent {0}

  BIMBA-Site-Agent-Setup.exe                       setup wizard
  BIMBA-Site-Agent-Setup.exe install --server <gateway> --token <enrollment token> [options]
  BIMBA-Site-Agent-Setup.exe install --server <gateway> --email <e-mail> [--org <slug>] [--password-stdin] [options]
  BIMBA-Site-Agent-Setup.exe uninstall [--quiet]
  BIMBA-Site-Agent-Setup.exe status | inventory
  BIMBA-Site-Agent-Setup.exe test --server <gateway> (--token <t> | --email <e-mail> ...)
  For Intune / SCCM / Group Policy use BIMBA-Site-Agent.msi:
    msiexec /i BIMBA-Site-Agent.msi /qn SERVER=<gateway> TOKEN=<enrollment token> [SITE=<site>] [TRUSTCERT=<sha256>]

  options: --name <display name>  --site <site / location>  --trust-cert <sha256 of a self-signed Setu certificate>
  --server (and --trust-cert) can be left out when this .exe was downloaded from the Setu console.
  The token can also come from the BIMBA_ENROLL_TOKEN environment variable. Without --password-stdin the
  password is asked for on the console. Run install/uninstall from an elevated (administrator) prompt.
  Exit codes: 0 ok, 1 failed, 2 usage, 3 credentials refused, 4 choose --org, 5 not elevated.";

        [DllImport("kernel32.dll")]
        private static extern bool AttachConsole(int processId);

        [STAThread]
        private static int Main(string[] args)
        {
            string cmd = args.Length > 0 ? args[0].TrimStart('-', '/').ToLowerInvariant() : "";
            if (cmd == "service")
            {
                ServiceBase.Run(new AgentService());
                return 0;
            }
            if (cmd == "" || (cmd == "uninstall" && !args.Contains("--quiet")))
                return RunGui(cmd, args);

            AttachConsole(-1);
            Console.WriteLine();
            Log.Console = true;
            try
            {
                var opt = Options(args.Skip(1).ToArray());
                switch (cmd)
                {
                    case "install": return Install(opt, Mode.Install);
                    case "enroll": return Install(opt, Mode.Enroll);
                    case "test": return Install(opt, Mode.Test);
                    case "uninstall-notify":
                        if (!Installer.IsElevated) return NotElevated();
                        Installer.NotifyUninstall(s => Console.WriteLine("  " + s));
                        return 0;
                    case "remove-data":
                        if (!Installer.IsElevated) return NotElevated();
                        Installer.RemoveEdge();
                        Installer.RemoveData();
                        return 0;
                    case "install-edge":        // MSI: unpack the edge collector next to the installed exe
                        if (!Installer.IsElevated) return NotElevated();
                        Installer.ExtractEdge(s => Console.WriteLine("  " + s));
                        return 0;
                    case "uninstall":
                        if (!Installer.IsElevated) return NotElevated();
                        Installer.Uninstall(s => Console.WriteLine("  " + s));
                        Console.WriteLine("BIMBA Site Agent removed.");
                        return 0;
                    case "status": return Status();
                    case "inventory":
                        Console.WriteLine(Json.Pretty(Inventory.Collect(opt.TryGetValue("server", out var s) ? s : null)));
                        return 0;
                    default:
                        Console.WriteLine(string.Format(Usage, Inventory.Version));
                        return cmd == "help" || cmd == "?" || cmd == "h" ? 0 : 2;
                }
            }
            catch (ArgumentException e)
            {
                Console.Error.WriteLine("error: " + e.Message);
                return 2;
            }
            catch (GatewayException e)
            {
                Console.Error.WriteLine("error: " + e.Message);
                if (e.UntrustedCertSha256 != null)
                    Console.Error.WriteLine("  certificate SHA-256: " + e.UntrustedCertSha256 +
                                            "\n  if this is your gateway's certificate, add --trust-cert " + e.UntrustedCertSha256);
                if (e.Status == 409)
                    Console.Error.WriteLine("  organizations: " + string.Join(", ", e.Orgs.Select(o => $"{o.Key} ({o.Value})")) +
                                            "\n  add --org <slug>");
                return e.Status == 409 ? 4 : e.Status == 401 || e.Status == 403 ? 3 : 1;
            }
            catch (Exception e)
            {
                Console.Error.WriteLine("error: " + e.Message);
                Log.Error(e.ToString());
                return 1;
            }
        }

        private static int RunGui(string cmd, string[] args)
        {
            if (!Installer.IsElevated)
            {
                // the wizard installs a service: ask Windows for administrator rights (UAC) and start again
                try
                {
                    Process.Start(new ProcessStartInfo(Installer.SelfPath, string.Join(" ", args.Select(a => "\"" + a + "\"")))
                    {
                        UseShellExecute = true, Verb = "runas",
                    });
                }
                catch (System.ComponentModel.Win32Exception)
                {
                    // UAC prompt declined
                }
                return 0;
            }
            Application.EnableVisualStyles();
            Application.SetCompatibleTextRenderingDefault(false);
            if (cmd == "uninstall")
            {
                if (MessageBox.Show("Remove the BIMBA Site Agent from this computer?\n\nThe machine is marked as uninstalled " +
                                    "for your organization.", Paths.DisplayName, MessageBoxButtons.YesNo, MessageBoxIcon.Question) != DialogResult.Yes)
                    return 0;
                try
                {
                    Installer.Uninstall(_ => { }, quiet: false);
                    MessageBox.Show("The BIMBA Site Agent was removed.", Paths.DisplayName, MessageBoxButtons.OK, MessageBoxIcon.Information);
                    return 0;
                }
                catch (Exception e)
                {
                    MessageBox.Show("Removal failed: " + e.Message, Paths.DisplayName, MessageBoxButtons.OK, MessageBoxIcon.Error);
                    return 1;
                }
            }
            Application.Run(new SetupWizard());
            return 0;
        }

        private enum Mode { Install, Enroll, Test }

        /// <summary>
        /// Silent install; "enroll" (MSI: configuration only); "test": same enrollment, one heartbeat and, over HTTPS,
        /// the client certificate round trip, with nothing installed.
        /// </summary>
        private static int Install(Dictionary<string, string> opt, Mode mode)
        {
            bool installForReal = mode != Mode.Test;
            if (installForReal && !Installer.IsElevated) return NotElevated();
            // an installer downloaded from the Setu console already knows Setu (and its certificate)
            if (!opt.TryGetValue("server", out var server) && (server = Preset.Current?.Server) == null)
                throw new ArgumentException("--server is required");
            if (!opt.TryGetValue("trust-cert", out var pin)) pin = Preset.PinFor(server);
            var auth = new Dictionary<string, object>();
            // organization user = e-mail address (--username is still accepted for older scripts)
            if (opt.TryGetValue("email", out var user) || opt.TryGetValue("username", out user))
            {
                auth["type"] = "password";
                auth["email"] = user.Trim();
                auth["password"] = opt.ContainsKey("password-stdin") ? Console.In.ReadLine() : ReadSecret($"Password for {user}: ");
                if (opt.TryGetValue("org", out var org)) auth["org"] = org;
            }
            else
            {
                string token = opt.TryGetValue("token", out var t) ? t : Environment.GetEnvironmentVariable("BIMBA_ENROLL_TOKEN");
                if (string.IsNullOrWhiteSpace(token)) throw new ArgumentException("--token or --email is required");
                auth["type"] = "token";
                auth["token"] = token.Trim();
            }
            if (GatewayClient.IsPlainHttpToRemote(server))
                Console.WriteLine("warning: " + server + " is plain HTTP, credentials are sent unencrypted. Use Setu's HTTPS address (port 8443).");

            using (var client = new GatewayClient(server, pin))
            {
                var machine = Inventory.Collect(client.Server);
                opt.TryGetValue("name", out var name);
                opt.TryGetValue("site", out var site);
                Console.WriteLine($"Registering {machine["hostname"]} with {client.Server} ...");
                var enrolled = client.Enroll(auth, string.IsNullOrWhiteSpace(name) ? (string)machine["hostname"] : name, site, machine);
                var cfg = Installer.ConfigFrom(client, enrolled, site, pin);
                Console.WriteLine($"Registered to organization {cfg.OrgName} ({cfg.OrgSlug}) as \"{cfg.DisplayName}\", agent {cfg.AgentId}");
                if (mode == Mode.Test)
                {
                    client.Heartbeat(cfg, machine);
                    Console.WriteLine("Heartbeat accepted (agent secret).");
                    if (enrolled.TryGetValue("certificates", out var certs) && certs is bool b && b && client.Server.StartsWith("https://"))
                        TestCertificate(cfg);
                    Console.WriteLine("Test only: nothing was installed (the machine shows as offline after a few minutes).");
                    return 0;
                }
                if (mode == Mode.Enroll)
                {
                    Installer.SaveConfig(cfg);
                    Console.WriteLine("Configuration saved; the service gets its client certificate when it starts.");
                    return 0;
                }
                Installer.Install(cfg, s => Console.WriteLine("  " + s));
                Console.WriteLine("BIMBA Site Agent installed and running.");
                return 0;
            }
        }

        /// <summary>
        /// The service's certificate steps, run as the current user (its own certificate store) and cleaned up after:
        /// first certificate with the secret, heartbeat with it, secret refused, renewal, heartbeat with the renewed one.
        /// </summary>
        private static void TestCertificate(AgentConfig cfg)
        {
            using (var s = new Session(cfg, persist: false))
            {
                try
                {
                    s.UpdateCertificate();
                    Console.WriteLine($"Client certificate issued and used (mutual TLS): {s.Certificate.Subject}, valid until {s.Certificate.NotAfter:u}");
                    try
                    {
                        using (var withSecret = new GatewayClient(cfg.Server, cfg.PinnedCertSha256))
                            withSecret.Heartbeat(cfg, null);
                        Console.WriteLine("WARNING: Setu still accepts the agent secret.");
                    }
                    catch (GatewayException e) when (e.Status == 401)
                    {
                        Console.WriteLine("Agent secret refused now that the machine has a certificate (as intended).");
                    }
                    string first = s.Certificate.Thumbprint;
                    s.UpdateCertificate();
                    Console.WriteLine($"Certificate renewed and used: {first} -> {s.Certificate.Thumbprint}");
                    s.Client.Heartbeat(cfg, null);
                    Console.WriteLine("Heartbeat accepted (renewed certificate).");
                }
                finally
                {
                    AgentCertificate.Delete(s.Certificate?.Thumbprint);
                }
            }
        }

        private static int Status()
        {
            var cfg = AgentConfig.TryLoad();
            var svc = Installer.ServiceStatus();
            if (cfg == null && svc == null)
            {
                Console.WriteLine("BIMBA Site Agent is not installed.");
                return 1;
            }
            Console.WriteLine($"service:      {(svc?.ToString() ?? "not installed")}");
            if (cfg != null)
            {
                Console.WriteLine($"organization: {cfg.OrgName} ({cfg.OrgSlug})");
                Console.WriteLine($"machine:      {cfg.DisplayName}{(string.IsNullOrEmpty(cfg.Site) ? "" : " at " + cfg.Site)}");
                Console.WriteLine($"gateway:      {cfg.Server}{(cfg.PinnedCertSha256 != null ? " (pinned certificate)" : "")}");
                Console.WriteLine($"agent id:     {cfg.AgentId}");
                Console.WriteLine($"sign-in:      {(cfg.CertThumbprint != null ? $"client certificate {cfg.CertThumbprint} (valid until {cfg.CertNotAfter})" : "agent secret (the service asks for a certificate over HTTPS)")}");
                Console.WriteLine($"enrolled:     {cfg.EnrolledAt}");
            }
            else if (!Installer.IsElevated)
            {
                Console.WriteLine("configuration: run from an elevated prompt to read it");
            }
            Console.WriteLine($"log:          {Log.FilePath}");
            return 0;
        }

        private static int NotElevated()
        {
            Console.Error.WriteLine("error: run this from an elevated (Run as administrator) prompt.");
            return 5;
        }

        /// <summary>--key value pairs; flags without a value map to "".</summary>
        private static Dictionary<string, string> Options(string[] args)
        {
            var o = new Dictionary<string, string>(StringComparer.OrdinalIgnoreCase);
            for (int i = 0; i < args.Length; i++)
            {
                if (!args[i].StartsWith("--")) throw new ArgumentException("unexpected argument " + args[i]);
                string key = args[i].Substring(2);
                bool hasValue = i + 1 < args.Length && !args[i + 1].StartsWith("--");
                o[key] = hasValue ? args[++i] : "";
            }
            return o;
        }

        private static string ReadSecret(string prompt)
        {
            Console.Write(prompt);
            var sb = new StringBuilder();
            try
            {
                while (true)
                {
                    var k = Console.ReadKey(true);
                    if (k.Key == ConsoleKey.Enter) break;
                    if (k.Key == ConsoleKey.Backspace) { if (sb.Length > 0) sb.Length--; }
                    else sb.Append(k.KeyChar);
                }
            }
            catch (InvalidOperationException)
            {
                return Console.In.ReadLine();          // no interactive console: read a line
            }
            Console.WriteLine();
            return sb.ToString();
        }
    }
}

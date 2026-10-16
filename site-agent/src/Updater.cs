using System;
using System.Collections.Generic;
using System.Diagnostics;
using System.IO;
using System.Runtime.InteropServices;
using System.Security.AccessControl;
using System.Security.Cryptography;
using System.Security.Cryptography.X509Certificates;
using System.Security.Principal;
using System.ServiceProcess;
using System.Text;
using System.Text.RegularExpressions;
using System.Threading;
using Microsoft.Win32;

namespace Bimba.SiteAgent
{
    /// <summary>
    /// Automatic updates: Setu offers a newer installer in the heartbeat answer and it is run over the installed agent.
    ///
    /// The service (LocalService, no installer rights) only downloads: over its own connection (client certificate) into
    /// ProgramData\BIMBA Site Agent\update, checks size and SHA-256, writes update\ready.json and starts the updater
    /// service BIMBASiteAgentUpdate (LocalSystem, demand start; the installer lets LocalService start it, nothing more).
    /// The updater trusts nothing the service wrote: it copies the installer into its own folder (SYSTEM and
    /// Administrators only), compares its SHA-256 and version with what Setu publishes (GET
    /// /system/bimba-agent-update/release, with the Setu address and certificate pin an administrator's install stored
    /// in HKLM), refuses older versions and, when the installed agent is code-signed, installers from another publisher.
    /// Then it starts a copy of itself outside Program Files (the runner) and stops; the runner runs the installer the way
    /// the agent was installed (exe: "upgrade", msi: msiexec major upgrade), both keep the registration, and writes the
    /// result to HKLM (LastUpdate), which the service reports to Setu.
    ///
    /// "updates off" (administrator) switches updates off on this machine, whatever Setu says.
    /// </summary>
    internal sealed class Updater
    {
        public const string ServiceName = "BIMBASiteAgentUpdate";
        public const string ServiceDisplayName = "BIMBA Site Agent Update";
        private const string Key = @"SOFTWARE\BIMBA\Site Agent";
        private const string ReadyFile = "ready.json";
        private const long MaxPackage = 1L << 30;

        /// <summary>LocalService may query and start the updater service (default rights otherwise).</summary>
        public const string ServiceSddl =
            "D:(A;;CCLCSWRPWPDTLOCRRC;;;SY)(A;;CCDCLCSWRPWPDTLOCRSDRCWDWO;;;BA)(A;;CCLCSWLOCRRC;;;IU)(A;;CCLCSWLOCRRC;;;SU)(A;;CCLCSWRPLORC;;;LS)";

        /// <summary>the running service's updater (its report goes with every heartbeat)</summary>
        public static Updater Current;

        private readonly AgentConfig cfg;
        private readonly Func<X509Certificate2> certificate;
        private int busy;
        private volatile string state = "idle", target, error, at;

        public Updater(AgentConfig cfg, Func<X509Certificate2> certificate)
        {
            this.cfg = cfg;
            this.certificate = certificate;
        }

        // ================================================================== the service

        public void Start()
        {
            Current = this;
            try
            {
                if (!File.Exists(Path.Combine(Paths.UpdateDir, ReadyFile)))
                    foreach (var kind in new[] { "exe", "msi" })
                    {
                        string f = Path.Combine(Paths.UpdateDir, PackageName(kind));
                        if (File.Exists(f)) File.Delete(f);
                        if (File.Exists(f + ".part")) File.Delete(f + ".part");
                    }
            }
            catch (Exception e)
            {
                Log.Warn("update folder: " + e.Message);
            }
        }

        /// <summary>{kind, supported, why, allowed, state, version, error, at, signed}: sent with every heartbeat.</summary>
        public IDictionary<string, object> Report()
        {
            string kind = Kind();
            string why = WhyNot(kind);
            string s = state, v = target, err = error, when = at;
            if (Interlocked.CompareExchange(ref busy, 0, 0) == 0)
            {
                var last = Json.Parse(ReadValue("LastUpdate") as string ?? "");
                if (File.Exists(Path.Combine(Paths.UpdateDir, ReadyFile)))
                {
                    s = "staged";
                }
                else if (v != null && v == Json.Str(last, "version") && (Json.Str(last, "state") == "installing" || Json.Str(last, "state") == "failed"))
                {
                    s = Json.Str(last, "state");
                    err = Json.Str(last, "error");
                    when = Json.Str(last, "at");
                }
            }
            return new Dictionary<string, object>
            {
                ["kind"] = kind, ["supported"] = why == null, ["why"] = why, ["allowed"] = Allowed,
                ["state"] = s, ["version"] = v, ["error"] = s == "failed" ? err : null, ["at"] = when,
                ["signed"] = SignerThumbprint(Paths.InstalledExe) != null,
            };
        }

        /// <summary>the heartbeat answer's "update": download and stage it (once per version, in the background)</summary>
        public void Offer(IDictionary<string, object> update)
        {
            string v = Json.Str(update, "version"), kind = Json.Str(update, "kind");
            if (v == null || Compare(v, Inventory.Version) <= 0) return;
            string mine = Kind();
            if (WhyNot(mine) != null || !Allowed) return;          // the report tells Setu why
            if (mine != kind)
            {
                Fail(v, $"Setu offered the {kind} installer; this machine was installed with the {mine}.");
                return;
            }
            if (v == target && (state == "downloading" || File.Exists(Path.Combine(Paths.UpdateDir, ReadyFile)))) return;
            if (Interlocked.CompareExchange(ref busy, 1, 0) != 0) return;
            target = v;
            state = "downloading";
            error = null;
            at = DateTime.UtcNow.ToString("o");
            new Thread(() =>
            {
                try
                {
                    Download(update, mine);
                }
                catch (Exception e)
                {
                    Fail(v, "download failed: " + e.Message);
                }
                finally
                {
                    Interlocked.Exchange(ref busy, 0);
                }
            }) { IsBackground = true, Name = "update-download" }.Start();
        }

        private void Download(IDictionary<string, object> update, string kind)
        {
            string v = Json.Str(update, "version"), sha = Json.Str(update, "sha256"), path = Json.Str(update, "path");
            long size = update.TryGetValue("size", out var sz) && sz != null ? Convert.ToInt64(sz) : -1;
            if (sha == null || !Regex.IsMatch(sha, "^[0-9a-fA-F]{64}$") || path == null || !path.StartsWith("/system/bimba-agent-update/"))
                throw new InvalidOperationException("Setu's offer is incomplete");
            Log.Info($"update {Inventory.Version} -> {v}: downloading the {kind} installer ({size / 1048576} MB)");
            Directory.CreateDirectory(Paths.UpdateDir);
            string file = Path.Combine(Paths.UpdateDir, PackageName(kind)), part = file + ".part";
            if (File.Exists(part)) File.Delete(part);
            using (var client = new GatewayClient(cfg.Server, cfg.PinnedCertSha256, certificate(), TimeSpan.FromMinutes(30)))
                client.Download(cfg, path, part);
            long got = new FileInfo(part).Length;
            string hash = Sha256(part);
            if ((size >= 0 && got != size) || !string.Equals(hash, sha, StringComparison.OrdinalIgnoreCase))
            {
                File.Delete(part);
                throw new InvalidOperationException($"the download does not match Setu's installer (size {got}, SHA-256 {hash})");
            }
            if (File.Exists(file)) File.Delete(file);
            File.Move(part, file);
            var ready = new Dictionary<string, object>
            {
                ["version"] = v, ["kind"] = kind, ["sha256"] = hash.ToLowerInvariant(), ["from"] = Inventory.Version,
                ["at"] = DateTime.UtcNow.ToString("o"),
            };
            File.WriteAllText(Path.Combine(Paths.UpdateDir, ReadyFile), Json.Serialize(ready), new UTF8Encoding(false));
            state = "staged";
            at = DateTime.UtcNow.ToString("o");
            Log.Info($"update {v} downloaded and checked; starting the updater service");
            try
            {
                using (var sc = new ServiceController(ServiceName))
                    sc.Start();
            }
            catch (Exception e)
            {
                File.Delete(Path.Combine(Paths.UpdateDir, ReadyFile));
                throw new InvalidOperationException("the updater service could not be started (" + (e.InnerException?.Message ?? e.Message)
                    + "); run the 1.10 installer by hand once", e);
            }
        }

        private void Fail(string v, string why)
        {
            target = v;
            state = "failed";
            error = why;
            at = DateTime.UtcNow.ToString("o");
            Log.Warn($"update {v}: {why}");
        }

        // ================================================================== updater service (LocalSystem)

        /// <summary>checks the staged installer and hands it to the runner; the service stops right after</summary>
        public static void ApplyStaged()
        {
            string ready = Path.Combine(Paths.UpdateDir, ReadyFile);
            if (!File.Exists(ready)) return;
            Dictionary<string, object> r;
            try
            {
                var fi = new FileInfo(ready);
                if ((fi.Attributes & FileAttributes.ReparsePoint) != 0 || fi.Length > 4096)
                    throw new InvalidOperationException("not a small regular file");
                r = Json.Parse(File.ReadAllText(ready, Encoding.UTF8));
            }
            catch (Exception e)
            {
                TryDelete(ready);
                Result(null, "failed", "the update request is unreadable: " + e.Message);
                return;
            }
            TryDelete(ready);
            string version = Json.Str(r, "version"), kind = Json.Str(r, "kind"), sha = Json.Str(r, "sha256");
            string staged = kind == null ? null : Path.Combine(Paths.UpdateDir, PackageName(kind));
            string pkg = null;
            try
            {
                if (version == null || sha == null || (kind != "exe" && kind != "msi"))
                    throw new InvalidOperationException("the update request is incomplete");
                if (!Allowed) throw new InvalidOperationException("updates are switched off on this machine (updates on)");
                if (kind != Kind()) throw new InvalidOperationException($"this machine was not installed with the {kind}");
                if (Compare(version, Inventory.Version) <= 0)
                    throw new InvalidOperationException($"version {version} is not newer than the installed {Inventory.Version}");
                string server = ReadValue("UpdateServer") as string;
                if (string.IsNullOrWhiteSpace(server))
                    throw new InvalidOperationException("the updater has no Setu address: run \"updates on\" as administrator");
                Result(version, "installing", null);

                // the SYSTEM-only copy is what gets checked and installed (the service's folder could change meanwhile)
                string work = Paths.UpdateWorkDir;
                SecureWorkDir(work);
                foreach (var f in Directory.GetFiles(work))
                    TryDelete(f);
                pkg = Path.Combine(work, PackageName(kind));
                string hash = CopyAndHash(staged, pkg);
                if (!string.Equals(hash, sha, StringComparison.OrdinalIgnoreCase))
                    throw new InvalidOperationException("the installer changed after the agent checked it");
                IDictionary<string, object> mine;
                using (var client = new GatewayClient(server, ReadValue("UpdateTrustCert") as string))
                    mine = Json.Obj(Json.Obj(client.Release(), "files"), kind);
                if (mine == null || !string.Equals(Json.Str(mine, "sha256"), hash, StringComparison.OrdinalIgnoreCase))
                    throw new InvalidOperationException($"Setu does not publish this {kind} installer (SHA-256 {hash})");
                if (Json.Str(mine, "version") != version)
                    throw new InvalidOperationException($"Setu publishes version {Json.Str(mine, "version")}, not {version}");
                // a code-signed agent takes installers from the same publisher only
                string signer = SignerThumbprint(Paths.InstalledExe);
                if (signer != null)
                {
                    string other = SignerThumbprint(pkg);
                    if (other == null || !string.Equals(signer, other, StringComparison.OrdinalIgnoreCase))
                        throw new InvalidOperationException("the installer is not signed by the publisher of the installed agent");
                    uint trust = VerifySignature(pkg);
                    if (trust != 0 && trust != CertEUntrustedRoot && trust != CertEChaining)
                        throw new InvalidOperationException($"the installer's signature is not valid (0x{trust:x8})");
                }
                string runner = Path.Combine(work, "bimba-update-runner.exe");
                File.Copy(Installer.SelfPath, runner, true);
                Log.Info($"update {version} ({kind}, SHA-256 {hash}) checked; installing over {Inventory.Version}");
                Process.Start(new ProcessStartInfo(runner, $"run-update --kind {kind} --version {version} --file \"{pkg}\"")
                {
                    UseShellExecute = false, CreateNoWindow = true, WorkingDirectory = work,
                })?.Dispose();
                TryDelete(staged);
            }
            catch (Exception e)
            {
                if (staged != null) TryDelete(staged);
                TryDelete(pkg);                                  // a refused installer is not kept, not even in the SYSTEM-only folder
                Result(version, "failed", e.Message);
            }
        }

        /// <summary>
        /// The runner (a copy of the agent exe outside Program Files, SYSTEM): waits until the updater service has
        /// stopped, runs the installer, writes the result.
        /// </summary>
        public static int RunUpdate(string kind, string version, string file)
        {
            try
            {
                using (var sc = new ServiceController(ServiceName))
                    sc.WaitForStatus(ServiceControllerStatus.Stopped, TimeSpan.FromSeconds(60));
            }
            catch (Exception)
            {
                // not installed or already gone: go on
            }
            try
            {
                string exe, args;
                if (kind == "msi")
                {
                    exe = Path.Combine(Environment.SystemDirectory, "msiexec.exe");
                    args = $"/i \"{file}\" /qn /norestart /l*v \"{Path.Combine(Path.GetDirectoryName(file), "msi.log")}\"";
                }
                else
                {
                    exe = file;
                    args = "upgrade";
                }
                using (var p = Process.Start(new ProcessStartInfo(exe, args) { UseShellExecute = false, CreateNoWindow = true }))
                {
                    if (!p.WaitForExit((int)TimeSpan.FromMinutes(30).TotalMilliseconds))
                        throw new InvalidOperationException("the installer did not finish within 30 minutes");
                    if (p.ExitCode == 3010)
                    {
                        Result(version, "done", null, "Windows asks for a restart to finish replacing files.");
                        return 0;
                    }
                    if (p.ExitCode != 0)
                        throw new InvalidOperationException((kind == "msi" ? "msiexec" : "the installer") + " failed with exit code " + p.ExitCode
                            + (kind == "msi" ? " (log: " + Path.Combine(Path.GetDirectoryName(file), "msi.log") + ")" : ""));
                }
                TryDelete(file);
                Result(version, "done", null);
                return 0;
            }
            catch (Exception e)
            {
                Result(version, "failed", e.Message);
                return 1;
            }
        }

        private static void Result(string version, string state, string error, string note = null)
        {
            var j = new Dictionary<string, object>
            {
                ["version"] = version, ["state"] = state, ["error"] = error, ["note"] = note, ["from"] = Inventory.Version,
                ["at"] = DateTime.UtcNow.ToString("o"),
            };
            try
            {
                WriteValue("LastUpdate", Json.Serialize(j));
            }
            catch (Exception e)
            {
                Log.Warn("could not record the update result: " + e.Message);
            }
            if (state == "failed") Log.Warn($"update {version} failed: {error}");
            else Log.Info($"update {version}: {state}{(note == null ? "" : " (" + note + ")")}");
        }

        // ================================================================== settings (HKLM, administrators only)

        /// <summary>
        /// Setu address + certificate pin for the updater, copied from the registration by an administrator's install
        /// (overwrite = a new registration). The LocalService account can read them but not change them.
        /// </summary>
        public static void SaveTrust(AgentConfig cfg, bool overwrite)
        {
            if (cfg == null || !Installer.IsElevated) return;
            if (!overwrite && !string.IsNullOrWhiteSpace(ReadValue("UpdateServer") as string)) return;
            WriteValue("UpdateServer", cfg.Server);
            WriteValue("UpdateTrustCert", cfg.PinnedCertSha256 ?? "");
            if (ReadValue("AutoUpdate") == null) WriteValue("AutoUpdate", 1);
        }

        /// <summary>installer step: the updater service may be started by the agent; trust copied when missing</summary>
        public static void Setup()
        {
            SaveTrust(AgentConfig.TryLoad(), false);
            if (Installer.ServiceExists(ServiceName))
                Installer.Sc($"sdset {ServiceName} {ServiceSddl}");
        }

        /// <summary>"updates [on | off]"</summary>
        public static int Updates(string to)
        {
            if (to == null)
            {
                string why = WhyNot(Kind());
                Console.WriteLine((Allowed ? "on: automatic updates from Setu are installed" : "off: automatic updates from Setu are refused")
                                  + " on this machine" + (why != null ? " (but it cannot update itself: " + why + ")" : ""));
                var last = Json.Parse(ReadValue("LastUpdate") as string ?? "");
                if (last.Count > 0)
                    Console.WriteLine($"last update: {Json.Str(last, "version")} {Json.Str(last, "state")} at {Json.Str(last, "at")}"
                                      + (Json.Str(last, "error") != null ? ": " + Json.Str(last, "error") : ""));
                return 0;
            }
            if (to != "on" && to != "off") throw new ArgumentException("updates on | off");
            SaveTrust(AgentConfig.TryLoad(), false);
            WriteValue("AutoUpdate", to == "on" ? 1 : 0);
            Console.WriteLine("Automatic updates " + (to == "on" ? "allowed" : "refused") + " on this machine.");
            return 0;
        }

        /// <summary>deletes the updater's settings and folder (uninstall)</summary>
        public static void RemoveData()
        {
            foreach (var name in new[] { "UpdateServer", "UpdateTrustCert", "AutoUpdate", "LastUpdate" })
            {
                try
                {
                    using (var hklm = RegistryKey.OpenBaseKey(RegistryHive.LocalMachine, RegistryView.Registry64))
                    using (var k = hklm.OpenSubKey(Key, true))
                        k?.DeleteValue(name, false);
                }
                catch
                {
                    // already gone
                }
            }
            try
            {
                if (Directory.Exists(Paths.UpdateWorkDir)) Directory.Delete(Paths.UpdateWorkDir, true);
            }
            catch (Exception e)
            {
                Log.Warn("could not delete " + Paths.UpdateWorkDir + ": " + e.Message);
            }
        }

        public static bool Allowed => !(ReadValue("AutoUpdate") is int v && v == 0);

        // ================================================================== shared

        /// <summary>msi (Windows Installer owns it), exe (installed by the setup exe), or null (not installed)</summary>
        public static string Kind()
        {
            if (Installer.MsiProductCode != null) return "msi";
            return Installer.ExeInstalled ? "exe" : null;
        }

        /// <summary>why this machine cannot update itself, or null</summary>
        public static string WhyNot(string kind)
        {
            if (kind == null) return "the agent is not installed with the setup exe or the MSI";
            if (!Installer.ServiceExists(ServiceName)) return "the updater service is not installed: run the 1.10 installer by hand once";
            if (string.IsNullOrWhiteSpace(ReadValue("UpdateServer") as string))
                return "the updater has no Setu address yet: run \"bimba-site-agent.exe updates on\" as administrator";
            return null;
        }

        public static string PackageName(string kind) => kind == "msi" ? "BIMBA-Site-Agent.msi" : "BIMBA-Site-Agent-Setup.exe";

        private static object ReadValue(string name)
        {
            try
            {
                using (var hklm = RegistryKey.OpenBaseKey(RegistryHive.LocalMachine, RegistryView.Registry64))
                using (var k = hklm.OpenSubKey(Key))
                    return k?.GetValue(name);
            }
            catch
            {
                return null;
            }
        }

        private static void WriteValue(string name, object value)
        {
            using (var hklm = RegistryKey.OpenBaseKey(RegistryHive.LocalMachine, RegistryView.Registry64))
            using (var k = hklm.CreateSubKey(Key))
                k.SetValue(name, value, value is int ? RegistryValueKind.DWord : RegistryValueKind.String);
        }

        /// <summary>SYSTEM and Administrators only, owned by Administrators (a folder made earlier by someone else is taken over)</summary>
        private static void SecureWorkDir(string dir)
        {
            Directory.CreateDirectory(dir);
            var admins = new SecurityIdentifier(WellKnownSidType.BuiltinAdministratorsSid, null);
            var inherit = InheritanceFlags.ContainerInherit | InheritanceFlags.ObjectInherit;
            var sec = new DirectorySecurity();
            sec.SetOwner(admins);
            sec.SetAccessRuleProtection(true, false);
            sec.AddAccessRule(new FileSystemAccessRule(new SecurityIdentifier(WellKnownSidType.LocalSystemSid, null),
                FileSystemRights.FullControl, inherit, PropagationFlags.None, AccessControlType.Allow));
            sec.AddAccessRule(new FileSystemAccessRule(admins, FileSystemRights.FullControl, inherit, PropagationFlags.None, AccessControlType.Allow));
            Directory.SetAccessControl(dir, sec);
        }

        /// <summary>copies a file the service wrote (no links followed) and returns its SHA-256</summary>
        private static string CopyAndHash(string from, string to)
        {
            var fi = new FileInfo(from);
            if (!fi.Exists) throw new InvalidOperationException("the downloaded installer is missing");
            if ((fi.Attributes & FileAttributes.ReparsePoint) != 0)
                throw new InvalidOperationException("the downloaded installer is a link, not a file");
            if (fi.Length > MaxPackage) throw new InvalidOperationException("the installer is larger than 1 GB");
            using (var sha = SHA256.Create())
            using (var input = new FileStream(from, FileMode.Open, FileAccess.Read, FileShare.Read))
            using (var output = new FileStream(to, FileMode.CreateNew, FileAccess.Write))
            {
                var buf = new byte[65536];
                long total = 0;
                int n;
                while ((n = input.Read(buf, 0, buf.Length)) > 0)
                {
                    total += n;
                    if (total > MaxPackage) throw new InvalidOperationException("the installer is larger than 1 GB");
                    sha.TransformBlock(buf, 0, n, null, 0);
                    output.Write(buf, 0, n);
                }
                sha.TransformFinalBlock(buf, 0, 0);
                return Hex(sha.Hash);
            }
        }

        private static string Sha256(string file)
        {
            using (var sha = SHA256.Create())
            using (var s = File.OpenRead(file))
                return Hex(sha.ComputeHash(s));
        }

        private static string Hex(byte[] b)
        {
            var sb = new StringBuilder(b.Length * 2);
            foreach (var x in b) sb.Append(x.ToString("x2"));
            return sb.ToString();
        }

        private static void TryDelete(string file)
        {
            try
            {
                if (file != null && File.Exists(file)) File.Delete(file);
            }
            catch
            {
                // left for the next update
            }
        }

        /// <summary>"1.10.0" &gt; "1.9.2"; a pre-release ("1.10.0-rc1") is older than its release (as Setu compares)</summary>
        public static int Compare(string a, string b)
        {
            var x = Parts(a);
            var y = Parts(b);
            var nx = x[0].Length == 0 ? new string[0] : x[0].Split('.');
            var ny = y[0].Length == 0 ? new string[0] : y[0].Split('.');
            for (int i = 0; i < Math.Max(nx.Length, ny.Length); i++)
            {
                long p = i < nx.Length ? long.Parse(nx[i]) : 0, q = i < ny.Length ? long.Parse(ny[i]) : 0;
                if (p != q) return p.CompareTo(q);
            }
            if ((x[1].Length == 0) != (y[1].Length == 0)) return x[1].Length == 0 ? 1 : -1;
            return Math.Sign(string.CompareOrdinal(x[1], y[1]));
        }

        private static string[] Parts(string v)
        {
            var m = Regex.Match(v ?? "", @"^\s*v?(\d{1,9}(?:\.\d{1,9})*)(?:[~+-]?(.*))?$");
            return m.Success ? new[] { m.Groups[1].Value, m.Groups[2].Value.Trim() } : new[] { "", "" };
        }

        // ------------------------------------------------------------------ Authenticode

        private const uint CertEUntrustedRoot = 0x800B0109;
        private const uint CertEChaining = 0x800B010A;

        /// <summary>thumbprint of the file's Authenticode signer, or null when it is not signed</summary>
        public static string SignerThumbprint(string file)
        {
            try
            {
                if (!File.Exists(file)) return null;
                using (var c = new X509Certificate2(X509Certificate.CreateFromSignedFile(file)))
                    return c.Thumbprint;
            }
            catch
            {
                return null;
            }
        }

        [StructLayout(LayoutKind.Sequential, CharSet = CharSet.Unicode)]
        private struct WintrustFileInfo
        {
            public uint cbStruct;
            public string pcwszFilePath;
            public IntPtr hFile;
            public IntPtr pgKnownSubject;
        }

        [StructLayout(LayoutKind.Sequential)]
        private struct WintrustData
        {
            public uint cbStruct;
            public IntPtr pPolicyCallbackData;
            public IntPtr pSIPClientData;
            public uint dwUIChoice;
            public uint fdwRevocationChecks;
            public uint dwUnionChoice;
            public IntPtr pFile;
            public uint dwStateAction;
            public IntPtr hWVTStateData;
            public IntPtr pwszURLReference;
            public uint dwProvFlags;
            public uint dwUIContext;
            public IntPtr pSignatureSettings;
        }

        [DllImport("wintrust.dll", CharSet = CharSet.Unicode)]
        private static extern uint WinVerifyTrust(IntPtr hwnd, ref Guid action, ref WintrustData data);

        /// <summary>WinVerifyTrust result: 0 = valid and trusted; the signature's digest is checked before its chain</summary>
        private static uint VerifySignature(string file)
        {
            var info = new WintrustFileInfo { cbStruct = (uint)Marshal.SizeOf(typeof(WintrustFileInfo)), pcwszFilePath = file };
            IntPtr pInfo = Marshal.AllocHGlobal(Marshal.SizeOf(typeof(WintrustFileInfo)));
            try
            {
                Marshal.StructureToPtr(info, pInfo, false);
                var data = new WintrustData
                {
                    cbStruct = (uint)Marshal.SizeOf(typeof(WintrustData)),
                    dwUIChoice = 2,                 // WTD_UI_NONE
                    fdwRevocationChecks = 0,        // WTD_REVOKE_NONE: plants are often offline
                    dwUnionChoice = 1,              // WTD_CHOICE_FILE
                    pFile = pInfo,
                    dwProvFlags = 0x00001000,       // WTD_CACHE_ONLY_URL_RETRIEVAL
                };
                var action = new Guid("00AAC56B-CD44-11d0-8CC2-00C04FC295EE");      // WINTRUST_ACTION_GENERIC_VERIFY_V2
                return WinVerifyTrust(IntPtr.Zero, ref action, ref data);
            }
            finally
            {
                Marshal.DestroyStructure(pInfo, typeof(WintrustFileInfo));
                Marshal.FreeHGlobal(pInfo);
            }
        }
    }

    /// <summary>Windows service "BIMBASiteAgentUpdate" (LocalSystem, started by the agent): applies a staged update and stops.</summary>
    internal sealed class UpdaterService : ServiceBase
    {
        public UpdaterService()
        {
            ServiceName = Updater.ServiceName;
            CanStop = true;
        }

        protected override void OnStart(string[] args)
        {
            new Thread(() =>
            {
                try
                {
                    Updater.ApplyStaged();
                }
                catch (Exception e)
                {
                    Log.Error("updater: " + e);
                }
                finally
                {
                    ThreadPool.QueueUserWorkItem(_ => Stop());
                }
            }) { IsBackground = true, Name = "updater" }.Start();
        }
    }
}

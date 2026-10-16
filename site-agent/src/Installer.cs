using System;
using System.Collections.Generic;
using System.Diagnostics;
using System.IO;
using System.IO.Compression;
using System.Security.AccessControl;
using System.Security.Principal;
using System.ServiceProcess;
using System.Threading;
using Microsoft.Win32;

namespace Bimba.SiteAgent
{
    /// <summary>
    /// Installs / removes the agent: exe in Program Files, config in ProgramData (locked down), Windows service
    /// running as NT AUTHORITY\LocalService (least privilege: it only reads inventory and sends heartbeats),
    /// and an entry in Apps / Programs and Features.
    /// When the MSI (BIMBA-Site-Agent.msi) installed the agent, Windows Installer owns the files, the service and the
    /// Apps entry: this class then only writes the configuration, and "uninstall" runs msiexec.
    /// </summary>
    internal static class Installer
    {
        private const string UninstallKey = @"SOFTWARE\Microsoft\Windows\CurrentVersion\Uninstall\" + Paths.ServiceName;
        /// <summary>written by the MSI: ProductCode, InstalledBy = msi</summary>
        private const string MsiKey = @"SOFTWARE\BIMBA\Site Agent";

        /// <summary>product code of the installed MSI, or null when the agent was not installed with the MSI</summary>
        public static string MsiProductCode
        {
            get
            {
                try
                {
                    using (var hklm = RegistryKey.OpenBaseKey(RegistryHive.LocalMachine, RegistryView.Registry64))
                    using (var k = hklm.OpenSubKey(MsiKey))
                    {
                        var code = k?.GetValue("ProductCode") as string;
                        return string.IsNullOrWhiteSpace(code) || !File.Exists(Paths.InstalledExe) ? null : code;
                    }
                }
                catch
                {
                    return null;
                }
            }
        }

        /// <summary>installed with the setup exe (its Apps entry and the program file are there)</summary>
        public static bool ExeInstalled
        {
            get
            {
                try
                {
                    using (var hklm = RegistryKey.OpenBaseKey(RegistryHive.LocalMachine, RegistryView.Registry64))
                    using (var k = hklm.OpenSubKey(UninstallKey))
                        return k != null && File.Exists(Paths.InstalledExe);
                }
                catch
                {
                    return false;
                }
            }
        }

        public static bool ServiceExists(string name)
        {
            try
            {
                using (var sc = new ServiceController(name))
                    return sc.Status >= 0;
            }
            catch (InvalidOperationException)
            {
                return false;
            }
        }

        public static bool IsElevated =>
            new WindowsPrincipal(WindowsIdentity.GetCurrent()).IsInRole(WindowsBuiltInRole.Administrator);

        public static string SelfPath => Process.GetCurrentProcess().MainModule.FileName;

        public static ServiceControllerStatus? ServiceStatus()
        {
            try
            {
                using (var sc = new ServiceController(Paths.ServiceName))
                    return sc.Status;
            }
            catch (InvalidOperationException)
            {
                return null;                     // not installed
            }
        }

        /// <summary>Builds the stored configuration from Setu's enrollment answer.</summary>
        public static AgentConfig ConfigFrom(GatewayClient client, IDictionary<string, object> enrolled, string site, string pinnedCert)
        {
            var org = Json.Obj(enrolled, "org");
            return new AgentConfig
            {
                Server = client.Server,
                AgentId = Json.Str(enrolled, "agentId"),
                Secret = Json.Str(enrolled, "agentSecret"),
                OrgSlug = Json.Str(org, "slug"),
                OrgName = Json.Str(org, "name"),
                DisplayName = Json.Str(enrolled, "displayName"),
                Site = site ?? "",
                HeartbeatSeconds = Json.Int(enrolled, "heartbeatSeconds", 60),
                PinnedCertSha256 = pinnedCert,
                EnrolledAt = DateTime.UtcNow.ToString("o"),
            };
        }

        /// <summary>Only the configuration (the MSI's enroll step; files and service belong to Windows Installer).</summary>
        public static void SaveConfig(AgentConfig cfg)
        {
            SecureDataDir();
            cfg.Save();
        }

        /// <summary>
        /// The newer setup exe run over the installed one by the updater ("upgrade"): program files, edge collector,
        /// services; the registration (configuration, certificate, data sources, buffer) stays as it is.
        /// </summary>
        public static void Upgrade(Action<string> progress)
        {
            if (MsiProductCode != null)
                throw new InvalidOperationException("This machine was installed with the MSI: run the newer BIMBA-Site-Agent.msi instead.");
            if (AgentConfig.TryLoad() == null)
                throw new InvalidOperationException("This machine is not registered: run \"install\" with an enrollment token.");
            Install(null, progress);
        }

        /// <param name="cfg">a new registration, or null to keep the stored one (upgrade)</param>
        public static void Install(AgentConfig cfg, Action<string> progress)
        {
            if (MsiProductCode != null)
            {
                // installed with the MSI: register again = new configuration + restart the service
                if (ServiceStatus() != null) { progress("Stopping the running agent"); StopService(); }
                ExtractEdge(progress);
                progress("Saving the configuration");
                SaveConfig(cfg);
                Updater.SaveTrust(cfg, true);
                progress("Starting the service");
                using (var sc = new ServiceController(Paths.ServiceName))
                {
                    sc.Start();
                    sc.WaitForStatus(ServiceControllerStatus.Running, TimeSpan.FromSeconds(30));
                }
                return;
            }
            if (ServiceStatus() != null)
            {
                progress("Stopping the running agent");
                StopService();
            }
            if (ServiceExists(Updater.ServiceName)) StopService(Updater.ServiceName);

            progress("Copying program files");
            Directory.CreateDirectory(Paths.InstallDir);
            if (!string.Equals(Path.GetFullPath(SelfPath), Path.GetFullPath(Paths.InstalledExe), StringComparison.OrdinalIgnoreCase))
                File.Copy(SelfPath, Paths.InstalledExe, true);

            ExtractEdge(progress);

            progress("Saving the configuration");
            SecureDataDir();
            if (cfg != null)
            {
                cfg.Save();
                Updater.SaveTrust(cfg, true);
            }
            else
            {
                Updater.SaveTrust(AgentConfig.TryLoad(), false);
            }

            progress("Registering the Windows service");
            string bin = $"\"\\\"{Paths.InstalledExe}\\\" service\"";
            string common = $"binPath= {bin} start= delayed-auto obj= \"NT AUTHORITY\\LocalService\" password= \"\" DisplayName= \"{Paths.DisplayName}\"";
            Sc((ServiceStatus() == null ? "create " : "config ") + Paths.ServiceName + " " + common);
            Sc($"description {Paths.ServiceName} \"Registers this machine with its organization's BIMBA gateway, reports that it is online and sends the data of its SCADA sources.\"");
            Sc($"failure {Paths.ServiceName} reset= 86400 actions= restart/60000/restart/60000/restart/300000");

            // automatic updates: a LocalSystem service the agent may start (and do nothing else with)
            string ubin = $"\"\\\"{Paths.InstalledExe}\\\" update-service\"";
            Sc((ServiceExists(Updater.ServiceName) ? "config " : "create ") + Updater.ServiceName +
               $" binPath= {ubin} start= demand obj= LocalSystem DisplayName= \"{Updater.ServiceDisplayName}\"");
            Sc($"description {Updater.ServiceName} \"Installs BIMBA Site Agent updates that Setu offers (started by the agent, stops when done).\"");
            Sc($"sdset {Updater.ServiceName} {Updater.ServiceSddl}");

            RegisterUninstall();

            progress("Starting the service");
            using (var sc = new ServiceController(Paths.ServiceName))
            {
                sc.Start();
                sc.WaitForStatus(ServiceControllerStatus.Running, TimeSpan.FromSeconds(30));
            }
        }

        /// <summary>
        /// Tells Setu the machine is being removed. The service does it (only it can use its certificate and
        /// delete its key); an agent that still has its secret is reported directly if the service cannot.
        /// </summary>
        public static void NotifyUninstall(Action<string> progress)
        {
            var cfg = AgentConfig.TryLoad();
            if (cfg == null) return;
            progress("Telling Setu this machine is being removed");
            try { File.Delete(Paths.UninstallMarker); } catch { /* not there */ }
            bool done = false;
            if (ServiceStatus() != null)
            {
                try
                {
                    using (var sc = new ServiceController(Paths.ServiceName))
                    {
                        if (sc.Status != ServiceControllerStatus.Running)
                        {
                            sc.Start();
                            sc.WaitForStatus(ServiceControllerStatus.Running, TimeSpan.FromSeconds(30));
                        }
                        sc.ExecuteCommand(AgentService.UninstallCommand);
                    }
                    for (int i = 0; i < 40 && !(done = File.Exists(Paths.UninstallMarker)); i++) Thread.Sleep(500);
                }
                catch (Exception e)
                {
                    Log.Warn("the service could not notify Setu: " + e.Message);
                }
            }
            if (!done && cfg.SecretProtected != null)
            {
                try
                {
                    using (var client = new GatewayClient(cfg.Server, cfg.PinnedCertSha256))
                        client.Heartbeat(cfg, null, "uninstalled");
                    done = true;
                }
                catch (Exception e)
                {
                    Log.Warn("could not notify Setu: " + e.Message);
                }
            }
            if (!done) Log.Warn("Setu was not told about the removal; the machine shows as offline there until it is revoked");
        }

        /// <summary>
        /// Unpacks the edge collector (Java runtime + OPC UA / Modbus drivers, embedded in this exe as edge.zip) into
        /// Program Files\BIMBA Site Agent\edge. Returns false for a build without it (registration-only agent).
        /// </summary>
        public static bool ExtractEdge(Action<string> progress)
        {
            using (var zip = typeof(Installer).Assembly.GetManifestResourceStream("edge.zip"))
            {
                if (zip == null)
                {
                    Log.Warn("this build carries no edge collector: the machine registers but collects no data");
                    return false;
                }
                progress("Installing the edge collector (data collection)");
                EdgeProcess.KillStray();
                string target = Paths.EdgeDir, fresh = target + ".new";
                if (Directory.Exists(fresh)) Directory.Delete(fresh, true);
                using (var archive = new ZipArchive(zip, ZipArchiveMode.Read))
                    archive.ExtractToDirectory(fresh);
                if (Directory.Exists(target)) Directory.Delete(target, true);
                Directory.Move(fresh, target);
                return true;
            }
        }

        /// <summary>MSI uninstall: the edge collector files are not Windows Installer's (they were unpacked by the exe)</summary>
        public static void RemoveEdge()
        {
            try
            {
                EdgeProcess.KillStray();
                if (Directory.Exists(Paths.EdgeDir)) Directory.Delete(Paths.EdgeDir, true);
            }
            catch (Exception e)
            {
                Log.Warn("could not delete " + Paths.EdgeDir + ": " + e.Message);
            }
        }

        /// <summary>Deletes the configuration and logs (ProgramData).</summary>
        public static void RemoveData()
        {
            Updater.RemoveData();
            try
            {
                if (Directory.Exists(Paths.DataDir)) Directory.Delete(Paths.DataDir, true);
            }
            catch (Exception e)
            {
                Log.Warn("could not delete " + Paths.DataDir + ": " + e.Message);
            }
        }

        /// <summary>Tells Setu, removes the service, files, configuration and the Apps entry.</summary>
        public static void Uninstall(Action<string> progress, bool quiet = true)
        {
            var msi = MsiProductCode;
            if (msi != null)
            {
                // Windows Installer owns the files and the service; the MSI runs our notify + cleanup steps itself
                progress("Removing with Windows Installer");
                var p = Process.Start(new ProcessStartInfo("msiexec.exe", $"/x {msi}" + (quiet ? " /qn" : " /passive"))
                {
                    UseShellExecute = false, CreateNoWindow = true,
                });
                // the installed exe must not stay open while Windows Installer deletes it
                if (SelfPath.StartsWith(Paths.InstallDir, StringComparison.OrdinalIgnoreCase)) return;
                using (p)
                {
                    p.WaitForExit();
                    if (p.ExitCode != 0 && p.ExitCode != 3010)
                        throw new InvalidOperationException("msiexec /x failed with exit code " + p.ExitCode);
                }
                return;
            }
            NotifyUninstall(progress);
            if (ServiceStatus() != null)
            {
                progress("Removing the Windows service");
                StopService();
                Sc("delete " + Paths.ServiceName);
            }
            if (ServiceExists(Updater.ServiceName))
            {
                StopService(Updater.ServiceName);
                Sc("delete " + Updater.ServiceName);
            }
            progress("Removing files");
            using (var hklm = RegistryKey.OpenBaseKey(RegistryHive.LocalMachine, RegistryView.Registry64))
                hklm.DeleteSubKeyTree(UninstallKey, false);
            RemoveData();
            if (!Directory.Exists(Paths.InstallDir)) return;
            if (SelfPath.StartsWith(Paths.InstallDir, StringComparison.OrdinalIgnoreCase))
            {
                // the running exe cannot delete itself: remove the folder a few seconds after this process exits
                Process.Start(new ProcessStartInfo("cmd.exe",
                    $"/c ping 127.0.0.1 -n 4 > nul & rmdir /s /q \"{Paths.InstallDir}\"")
                {
                    CreateNoWindow = true, UseShellExecute = false, WindowStyle = ProcessWindowStyle.Hidden,
                });
            }
            else
            {
                Directory.Delete(Paths.InstallDir, true);
            }
        }

        private static void StopService(string name = Paths.ServiceName)
        {
            using (var sc = new ServiceController(name))
            {
                if (sc.Status != ServiceControllerStatus.Stopped && sc.Status != ServiceControllerStatus.StopPending)
                    sc.Stop();
                sc.WaitForStatus(ServiceControllerStatus.Stopped, TimeSpan.FromSeconds(30));
            }
        }

        /// <summary>ProgramData folder: SYSTEM + Administrators full control, the service account may modify, nobody else.</summary>
        private static void SecureDataDir()
        {
            Directory.CreateDirectory(Paths.DataDir);
            var inherit = InheritanceFlags.ContainerInherit | InheritanceFlags.ObjectInherit;
            var sec = new DirectorySecurity();
            sec.SetAccessRuleProtection(true, false);
            sec.AddAccessRule(new FileSystemAccessRule(new SecurityIdentifier(WellKnownSidType.LocalSystemSid, null),
                FileSystemRights.FullControl, inherit, PropagationFlags.None, AccessControlType.Allow));
            sec.AddAccessRule(new FileSystemAccessRule(new SecurityIdentifier(WellKnownSidType.BuiltinAdministratorsSid, null),
                FileSystemRights.FullControl, inherit, PropagationFlags.None, AccessControlType.Allow));
            sec.AddAccessRule(new FileSystemAccessRule(new SecurityIdentifier(WellKnownSidType.LocalServiceSid, null),
                FileSystemRights.Modify, inherit, PropagationFlags.None, AccessControlType.Allow));
            Directory.SetAccessControl(Paths.DataDir, sec);
        }

        private static void RegisterUninstall()
        {
            using (var hklm = RegistryKey.OpenBaseKey(RegistryHive.LocalMachine, RegistryView.Registry64))
            using (var k = hklm.CreateSubKey(UninstallKey))
            {
                k.SetValue("DisplayName", Paths.DisplayName);
                k.SetValue("DisplayVersion", Inventory.Version);
                k.SetValue("Publisher", "BIMBA");
                k.SetValue("DisplayIcon", Paths.InstalledExe);
                k.SetValue("InstallLocation", Paths.InstallDir);
                k.SetValue("UninstallString", $"\"{Paths.InstalledExe}\" uninstall");
                k.SetValue("QuietUninstallString", $"\"{Paths.InstalledExe}\" uninstall --quiet");
                k.SetValue("NoModify", 1, RegistryValueKind.DWord);
                k.SetValue("NoRepair", 1, RegistryValueKind.DWord);
                k.SetValue("EstimatedSize", (int)(new FileInfo(Paths.InstalledExe).Length / 1024), RegistryValueKind.DWord);
            }
        }

        public static void Sc(string args)
        {
            var psi = new ProcessStartInfo(Path.Combine(Environment.SystemDirectory, "sc.exe"), args)
            {
                CreateNoWindow = true, UseShellExecute = false, RedirectStandardOutput = true, RedirectStandardError = true,
            };
            using (var p = Process.Start(psi))
            {
                string output = p.StandardOutput.ReadToEnd() + p.StandardError.ReadToEnd();
                p.WaitForExit();
                if (p.ExitCode != 0)
                    throw new InvalidOperationException($"sc.exe {args.Split(' ')[0]} failed ({p.ExitCode}): {output.Trim()}");
            }
        }
    }
}
